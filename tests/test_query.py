"""Natural language query.

The property under test: the model produces a *query*, never a figure, and
anything it invents is discarded rather than executed.
"""

from __future__ import annotations

from datetime import date
from types import SimpleNamespace

import pytest

from fin.llm.agent import _execute_tool, answer_question
from fin.llm.provider import (
    AnthropicProvider,
    EchoProvider,
    LLMProvider,
    LLMResponse,
    build_provider,
)
from fin.llm.query import build_context, sanitise, translate
from fin.models import Money, Txn


@pytest.fixture
def seeded(conn):
    from fin.db import insert_txns
    conn.execute(
        "INSERT INTO statement_file (id, source_path, file_sha256, institution_id, "
        "account_id, file_format, parser_id, parser_version, imported_at, row_count) "
        "VALUES ('sf','x','x','amex_us','amex_us_main','csv','t','1','2025-01-01',0)")
    rows = [
        Txn(account_id="amex_us_main", txn_date=date(2025, 4, 3),
            booked=Money(amount=-4500, currency="USD"),
            description_raw="SUSHI PLACE", category="dining",
            statement_file_id="sf"),
        Txn(account_id="amex_us_main", txn_date=date(2025, 5, 9),
            booked=Money(amount=-8900, currency="USD"),
            description_raw="RAMEN BAR", category="dining",
            statement_file_id="sf"),
        Txn(account_id="amex_us_main", txn_date=date(2025, 8, 1),
            booked=Money(amount=-12000, currency="USD"),
            description_raw="HARDWARE STORE", category="home",
            statement_file_id="sf"),
    ]
    insert_txns(conn, rows)
    conn.commit()
    return conn


def _provider(answer: dict) -> EchoProvider:
    return EchoProvider(canned={"Question": answer})


# ---------------------------------------------------------------------------
# Translation
# ---------------------------------------------------------------------------

def test_question_becomes_a_filter(seeded):
    provider = _provider({
        "filter": {"from": "2025-04-01", "to": "2025-06-30",
                   "categories": ["dining"], "includeTransfers": False},
        "group_by": "month", "intent": "aggregate", "confidence": 0.9,
        "explanation": "dining spend, April to June",
    })
    plan = translate(seeded, provider, "how much on dining last quarter?")
    assert plan["ok"]
    assert plan["filter"]["categories"] == ["dining"]
    assert plan["filter"]["from"] == "2025-04-01"
    assert plan["group_by"] == "month"
    assert plan["intent"] == "aggregate"


def test_invented_account_is_dropped(seeded):
    """A filter naming a non-existent account would silently return zero rows.

    That reads as "you spent nothing" rather than "no such account", so the id
    is discarded instead of queried.
    """
    plan = translate(seeded, _provider({
        "filter": {"accounts": ["barclays_offshore"]}, "confidence": 0.5,
    }), "spending at Barclays")
    assert "accounts" not in plan["filter"]
    assert any("accounts" in d for d in plan["dropped_fields"])


def test_invented_category_is_dropped(seeded):
    plan = translate(seeded, _provider({
        "filter": {"categories": ["cryptocurrency"]}, "confidence": 0.4,
    }), "crypto spend")
    assert "categories" not in plan["filter"]


def test_real_account_survives(seeded):
    plan = translate(seeded, _provider({
        "filter": {"accounts": ["amex_us_main"]}, "confidence": 0.9,
    }), "amex spending")
    assert plan["filter"]["accounts"] == ["amex_us_main"]


def test_unknown_filter_keys_are_stripped(seeded):
    plan = translate(seeded, _provider({
        "filter": {"categories": ["dining"], "sql": "DROP TABLE txn",
                   "limit": 999999},
    }), "dining")
    assert set(plan["filter"]) <= {"categories"}
    assert "sql" in plan["dropped_fields"]


def test_invalid_group_by_becomes_none(seeded):
    plan = translate(seeded, _provider({
        "filter": {}, "group_by": "; DELETE FROM txn",
    }), "everything")
    assert plan["group_by"] is None


def test_non_integer_amount_is_rejected(seeded):
    """Amounts are integer minor units; a decimal means the model misunderstood."""
    plan = translate(seeded, _provider({
        "filter": {"minAmount": -123.45},
    }), "big purchases")
    assert "minAmount" not in plan["filter"]


def test_malformed_date_is_rejected(seeded):
    plan = translate(seeded, _provider({
        "filter": {"from": "last April"},
    }), "since April")
    assert "from" not in plan["filter"]


def test_unsupported_question_is_flagged_not_faked(seeded):
    plan = translate(seeded, _provider({
        "filter": {"categories": ["dining"]},
        "unsupported": "cannot compare two periods in one query",
        "confidence": 0.3,
    }), "did I spend more on dining this year than last?")
    assert plan["unsupported"]
    assert plan["filter"]["categories"] == ["dining"]


def test_garbage_output_does_not_crash(seeded):
    assert sanitise(None, build_context(seeded))["ok"] is True
    assert sanitise({"filter": "not a dict"}, build_context(seeded))["filter"] == {}


# ---------------------------------------------------------------------------
# Caching — reproducibility, not just cost
# ---------------------------------------------------------------------------

def test_identical_questions_reuse_the_cached_plan(seeded):
    provider = _provider({"filter": {"categories": ["dining"]}, "confidence": 0.9})
    first = translate(seeded, provider, "dining spend")
    second = translate(seeded, provider, "dining spend")
    assert first["cached"] is False
    assert second["cached"] is True
    # One model call, not two.
    assert len(provider.calls) == 1
    assert first["filter"] == second["filter"]


def test_cached_answer_survives_a_model_change(seeded):
    """A ledger whose numbers move because a model was updated is not a ledger."""
    first = translate(seeded, _provider({"filter": {"categories": ["dining"]}}),
                      "dining spend")
    # A different model returning something else must not change the answer.
    second = translate(seeded, _provider({"filter": {"categories": ["home"]}}),
                       "dining spend")
    assert second["filter"] == first["filter"]
    assert second["cached"] is True


def test_clearing_the_cache_allows_recomputation(seeded):
    from fin.llm.cache import invalidate
    translate(seeded, _provider({"filter": {"categories": ["dining"]}}), "q")
    invalidate(seeded, task="query")
    seeded.commit()
    again = translate(seeded, _provider({"filter": {"categories": ["home"]}}), "q")
    assert again["cached"] is False
    assert again["filter"]["categories"] == ["home"]


# ---------------------------------------------------------------------------
# The database, not the model, produces the number
# ---------------------------------------------------------------------------

def test_figures_come_from_sql_not_the_model(seeded):
    """Even when the model asserts a total, the reported figure is computed."""
    from fin.reporting import totals
    plan = translate(seeded, _provider({
        "filter": {"categories": ["dining"]},
        "total": 999999999,          # a fabricated figure the model volunteered
        "confidence": 0.9,
    }), "dining spend")
    assert "total" not in plan["filter"]

    computed = totals(seeded, filters=plan["filter"])
    usd = next(t for t in computed if t["currency"] == "USD")
    assert usd["spend"]["amount"] == 13400      # 45.00 + 89.00, from the ledger


def test_context_only_exposes_real_vocabulary(seeded):
    ctx = build_context(seeded)
    assert {a["id"] for a in ctx["accounts"]} >= {"amex_us_main"}
    assert "dining" in ctx["categories"]
    assert ctx["date_range"]["earliest"] == "2025-04-03"


# ---------------------------------------------------------------------------
# Read-only analysis agent
# ---------------------------------------------------------------------------

class _ToolProvider(LLMProvider):
    name = "tool-test"
    model = "tool-test-v1"

    def __init__(self):
        self.calls = 0

    def complete_json(self, system, user, *, max_tokens=2000):
        raise AssertionError("the analysis agent must use the tool interface")

    def complete_with_tools(
        self, system, user, tools, execute_tool, *, max_turns=4, max_tokens=1600,
    ):
        self.calls += 1
        result = execute_tool("ledger_totals", {
            "filter": {"categories": ["dining"]},
            "reporting_currency": "USD",
        })
        amount = result["normalised"]["spend"]["amount"]
        return LLMResponse(
            data={"answer": f"Dining spend was USD {amount / 100:.2f}."},
            model=self.model,
            raw="",
            metadata={"usage": {"cache_read_input_tokens": 800}},
        )


def test_agent_answer_uses_allowlisted_sql_tool(seeded):
    provider = _ToolProvider()
    result = answer_question(
        seeded, provider, "How much did I spend on dining?",
        reporting_currency="USD",
    )
    assert result["ok"] is True
    assert result["answer"] == "Dining spend was USD 134.00."
    assert result["tools"] == [{
        "name": "ledger_totals",
        "input": {
            "filter": {"categories": ["dining"]},
            "reporting_currency": "USD",
        },
    }]
    assert result["filter"] == {"categories": ["dining"]}
    assert result["prompt_cache"]["hit"] is True
    audit = seeded.execute(
        "SELECT output,prompt_version FROM llm_decision WHERE task='query'"
    ).fetchone()
    assert "Dining spend was USD 134.00" in audit["output"]
    assert audit["prompt_version"] == "agent-v2"

    repeated = answer_question(
        seeded, provider, "How much did I spend on dining?",
        reporting_currency="USD",
    )
    assert repeated["answer"] == result["answer"]
    assert repeated["totals"] == result["totals"]
    assert repeated["cached"] is True
    assert provider.calls == 1

    seeded.execute(
        "UPDATE txn SET amount_booked=amount_booked-100, "
        "updated_at='2099-01-01T00:00:00' "
        "WHERE id=(SELECT id FROM txn WHERE category='dining' LIMIT 1)"
    )
    seeded.commit()
    refreshed = answer_question(
        seeded, provider, "How much did I spend on dining?",
        reporting_currency="USD",
    )
    assert refreshed["cached"] is False
    assert provider.calls == 2


def test_agent_tool_rejects_unknown_name(seeded):
    with pytest.raises(ValueError, match="unknown tool"):
        _execute_tool(
            seeded, build_context(seeded), "run_sql", {"sql": "select 1"},
            default_currency="USD",
        )


class _FakeMessages:
    def __init__(self, responses):
        self.responses = list(responses)
        self.requests = []

    def create(self, **kwargs):
        self.requests.append(kwargs)
        return self.responses.pop(0)


def _message(content, *, stop_reason="end_turn", read=0, created=0):
    return SimpleNamespace(
        content=content,
        stop_reason=stop_reason,
        usage=SimpleNamespace(
            input_tokens=100,
            output_tokens=20,
            cache_creation_input_tokens=created,
            cache_read_input_tokens=read,
        ),
    )


def _anthropic_with(responses):
    provider = AnthropicProvider.__new__(AnthropicProvider)
    provider.model = "claude-test"
    provider._client = SimpleNamespace(messages=_FakeMessages(responses))
    return provider


def test_json_calls_cache_the_system_prefix():
    provider = _anthropic_with([
        _message([SimpleNamespace(type="text", text='{"ok":true}')], created=600),
    ])
    response = provider.complete_json("stable system", "changing question")
    request = provider._client.messages.requests[0]
    assert request["system"][-1]["cache_control"] == {"type": "ephemeral"}
    assert response.metadata["usage"]["cache_creation_input_tokens"] == 600


def test_claude_5_calls_omit_deprecated_temperature():
    provider = _anthropic_with([
        _message([SimpleNamespace(type="text", text='{"ok":true}')]),
    ])
    provider.model = "claude-sonnet-5"
    provider.complete_json("stable system", "question")
    assert "temperature" not in provider._client.messages.requests[0]


def test_tool_loop_caches_tools_and_system_and_reports_hits():
    tool_use = SimpleNamespace(
        type="tool_use", id="tool-1", name="ledger_totals", input={"filter": {}},
    )
    provider = _anthropic_with([
        _message([tool_use], stop_reason="tool_use", created=700),
        _message([SimpleNamespace(type="text", text="Net flow was USD 12.00.")], read=700),
    ])
    executed = []
    tools = [{
        "name": "ledger_totals",
        "description": "Totals",
        "input_schema": {"type": "object"},
    }]
    response = provider.complete_with_tools(
        "stable system", "question", tools,
        lambda name, value: executed.append((name, value)) or {"net": 1200},
    )
    first = provider._client.messages.requests[0]
    assert first["tools"][-1]["cache_control"] == {"type": "ephemeral"}
    assert first["system"][-1]["cache_control"] == {"type": "ephemeral"}
    assert executed == [("ledger_totals", {"filter": {}})]
    assert response.data["answer"] == "Net flow was USD 12.00."
    assert response.metadata["usage"]["cache_read_input_tokens"] == 700


def test_deployment_can_enable_analysis_without_changing_database(seeded, monkeypatch):
    monkeypatch.setenv("FINTO_LLM_ENABLED", "1")
    monkeypatch.setenv("FINTO_LLM_AGENT_MODEL", "claude-sonnet-5")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test-key")
    provider = build_provider(seeded, purpose="analysis")
    assert provider.name == "anthropic"
    assert provider.model == "claude-sonnet-5"
