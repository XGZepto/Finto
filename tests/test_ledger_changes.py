"""Corrections Ask proposes and the user applies.

The properties under test: proposing changes nothing; applying writes the
user's own (manual) edit to exactly the rows that were proposed and are still
as they were; undo puts back what applying replaced unless the row was edited
again; and nothing here is visible to another user.
"""

from __future__ import annotations

from datetime import date

import pytest

from fin import ledger_changes as changes
from fin import reporting
from fin.llm.agent import answer_question
from fin.llm.provider import LLMProvider, LLMResponse
from fin.models import Money, Txn

CARD_FIX = {"kind": "cc_payment", "category": "transfers", "subcategory": "card_payment"}


@pytest.fixture
def ledger(conn):
    from fin.db import insert_txns
    conn.execute(
        "INSERT INTO statement_file (id, source_path, file_sha256, institution_id, "
        "account_id, file_format, parser_id, parser_version, imported_at, row_count) "
        "VALUES ('sf','x','x','amex_hk','amex_hk_main','pdf','t','1','2026-11-01',0)")
    rows = [
        Txn(account_id="amex_hk_main", txn_date=date(2026, 10, 3),
            booked=Money(amount=-2_839_194, currency="HKD"),
            description_raw="HSBC CREDIT CARDS", kind="purchase", category="fees",
            statement_file_id="sf"),
        Txn(account_id="amex_hk_main", txn_date=date(2026, 10, 20),
            booked=Money(amount=-160_216, currency="HKD"),
            description_raw="HSBC CREDIT CARDS", kind="purchase", category="fees",
            statement_file_id="sf"),
        Txn(account_id="amex_hk_main", txn_date=date(2026, 10, 9),
            booked=Money(amount=-45_000, currency="HKD"),
            description_raw="NOODLE HOUSE", kind="purchase", category="dining",
            statement_file_id="sf"),
    ]
    insert_txns(conn, rows)
    conn.commit()
    return conn


def _ids(conn, description: str) -> list[str]:
    return [r["id"] for r in conn.execute(
        "SELECT id FROM txn WHERE description_raw=%s ORDER BY txn_date", (description,)).fetchall()]


def _spend(conn) -> int:
    rows = reporting.totals(conn, filters={"spendingOnly": True, "currency": "HKD"})
    return sum(row["spend"]["amount"] for row in rows)


def _row(conn, txn_id: str) -> dict:
    return dict(conn.execute(
        "SELECT kind, category, subcategory, merchant, review_state FROM txn WHERE id=%s",
        (txn_id,)).fetchone())


def test_proposing_snapshots_rows_and_changes_nothing(ledger):
    before = _spend(ledger)
    preview = changes.propose(
        ledger, title="Mark HSBC card bill payments as card payments", values=CARD_FIX,
        filters={"q": "HSBC CREDIT CARDS"}, question="can you fix these?",
    )
    ledger.commit()
    assert preview["status"] == "proposed"
    assert preview["rows"] == 2 and preview["unchanged"] == 0
    assert preview["totals"] == [{"currency": "HKD", "amount": -2_999_410}]
    # Spending is a positive outflow; moving these out of it lowers it.
    assert preview["spending"] == [{"currency": "HKD", "change": -2_999_410}]
    sample = preview["sample"][0]
    assert sample["before"]["kind"] == "purchase" and sample["after"]["kind"] == "cc_payment"
    assert sample["after"]["subcategory"] == "card_payment"
    assert _spend(ledger) == before
    assert {_row(ledger, i)["kind"] for i in _ids(ledger, "HSBC CREDIT CARDS")} == {"purchase"}
    stored = ledger.execute(
        "SELECT applied, result->>'status' AS status FROM agent_operation WHERE id=%s",
        (preview["id"],)).fetchone()
    assert stored["applied"] == 0 and stored["status"] == "proposed"


def test_applying_is_a_manual_edit_that_moves_spending(ledger):
    before = _spend(ledger)
    preview = changes.propose(ledger, title="Card payments", values=CARD_FIX,
                              ids=_ids(ledger, "HSBC CREDIT CARDS"), add_tags=["bill"])
    applied = changes.apply(ledger, preview["id"])
    ledger.commit()
    assert applied["status"] == "applied" and applied["applied"] == 2 and applied["skipped"] == 0
    for txn_id in _ids(ledger, "HSBC CREDIT CARDS"):
        assert _row(ledger, txn_id)["kind"] == "cc_payment"
        assert _row(ledger, txn_id)["category"] == "transfers"
        sources = {r["field"]: r["source"] for r in ledger.execute(
            "SELECT field, source FROM txn_annotation WHERE txn_id=%s", (txn_id,)).fetchall()}
        assert sources == {"kind": "manual", "category": "manual", "subcategory": "manual"}
        tags = [r["tag"] for r in ledger.execute(
            "SELECT tag FROM txn_tag WHERE txn_id=%s", (txn_id,)).fetchall()]
        assert tags == ["bill"]
    assert _spend(ledger) == before - 2_999_410
    with pytest.raises(changes.ChangeError, match="applied"):
        changes.apply(ledger, preview["id"])


def test_a_row_edited_after_the_proposal_is_left_alone(ledger):
    first, second = _ids(ledger, "HSBC CREDIT CARDS")
    preview = changes.propose(ledger, title="Card payments", values=CARD_FIX, ids=[first, second])
    ledger.execute("UPDATE txn SET category='services' WHERE id=%s", (second,))
    applied = changes.apply(ledger, preview["id"])
    assert applied["applied"] == 1 and applied["skipped"] == 1
    assert _row(ledger, first)["kind"] == "cc_payment"
    assert _row(ledger, second) == {"kind": "purchase", "category": "services", "subcategory": None,
                                    "merchant": None, "review_state": "unreviewed"}


def test_undo_restores_values_and_provenance_unless_edited_again(ledger):
    first, second = _ids(ledger, "HSBC CREDIT CARDS")
    ledger.execute(
        "INSERT INTO txn_annotation (txn_id, field, value, source, confidence, created_at) "
        "VALUES (%s,'category','fees','llm',0.7,'2026-11-01')", (first,))
    preview = changes.propose(ledger, title="Card payments", values=CARD_FIX, ids=[first, second])
    changes.apply(ledger, preview["id"])
    ledger.execute("UPDATE txn SET merchant='HSBC' WHERE id=%s", (second,))
    undone = changes.undo(ledger, preview["id"])
    ledger.commit()
    assert undone["status"] == "undone" and undone["restored"] == 1 and undone["skipped"] == 1
    assert _row(ledger, first) == {"kind": "purchase", "category": "fees", "subcategory": None,
                                   "merchant": None, "review_state": "unreviewed"}
    restored = ledger.execute(
        "SELECT field, value, source FROM txn_annotation WHERE txn_id=%s", (first,)).fetchall()
    assert [dict(r) for r in restored] == [{"field": "category", "value": "fees", "source": "llm"}]
    assert _row(ledger, second)["kind"] == "cc_payment"


def test_dismissed_changes_cannot_be_applied(ledger):
    preview = changes.propose(ledger, title="Card payments", values=CARD_FIX,
                              filters={"q": "HSBC CREDIT CARDS"})
    assert changes.dismiss(ledger, preview["id"])["status"] == "dismissed"
    with pytest.raises(changes.ChangeError, match="dismissed"):
        changes.apply(ledger, preview["id"])


@pytest.mark.parametrize(("values", "message"), [
    ({"category": "made_up"}, "not in the taxonomy"),
    ({"category": "transfers", "subcategory": "lunch"}, "not in the taxonomy"),
    ({"kind": "installment_origination"}, "kind must be one of"),
    ({"subcategory": "card_payment"}, "together with its category"),
    ({"colour": "red"}, "unknown fields"),
    ({}, "nothing to change"),
])
def test_invalid_proposals_say_what_to_fix(ledger, values, message):
    with pytest.raises(changes.ChangeError, match=message):
        changes.propose(ledger, title="x", values=values, filters={"q": "HSBC"})


def test_a_proposal_needs_rows(ledger):
    with pytest.raises(changes.ChangeError, match="no rows match"):
        changes.propose(ledger, title="x", values=CARD_FIX, filters={"q": "NOTHING LIKE THIS"})
    with pytest.raises(changes.ChangeError, match="name the rows"):
        changes.propose(ledger, title="x", values=CARD_FIX)


def test_rows_already_right_are_counted_not_changed(ledger):
    noodle = _ids(ledger, "NOODLE HOUSE")
    preview = changes.propose(ledger, title="Dining", values={"category": "dining"}, ids=noodle)
    assert preview["rows"] == 0 and preview["unchanged"] == 1


def test_another_users_change_is_not_found(ledger):
    preview = changes.propose(ledger, title="Card payments", values=CARD_FIX,
                              filters={"q": "HSBC CREDIT CARDS"})
    ledger.execute("SELECT set_config('finto.user_id', 'someone-else', false)")
    with pytest.raises(changes.ChangeNotFound):
        changes.apply(ledger, preview["id"])


def test_the_spending_effect_counts_refunds_as_reporting_does(ledger):
    from fin.db import insert_txns
    insert_txns(ledger, [Txn(account_id="amex_hk_main", txn_date=date(2026, 10, 12),
                             booked=Money(amount=20_000, currency="HKD"), description_raw="SHOP REFUND",
                             kind="refund", category="shopping", statement_file_id="sf")])
    before = _spend(ledger)
    preview = changes.propose(ledger, title="Not a refund", values={"kind": "income"},
                              ids=_ids(ledger, "SHOP REFUND"))
    # A refund takes spend back; as income it no longer does, so spend rises.
    assert preview["spending"] == [{"currency": "HKD", "change": 20_000}]
    changes.apply(ledger, preview["id"])
    assert _spend(ledger) == before + 20_000


def test_restating_a_rows_category_keeps_its_subcategory(ledger):
    noodle = _ids(ledger, "NOODLE HOUSE")
    ledger.execute("UPDATE txn SET subcategory='restaurants' WHERE id=%s", (noodle[0],))
    preview = changes.propose(ledger, title="Dining", values={"category": "dining"}, ids=noodle)
    assert preview["rows"] == 0 and preview["unchanged"] == 1
    moved = changes.propose(ledger, title="Groceries", values={"category": "groceries"}, ids=noodle)
    assert moved["sample"][0]["after"]["subcategory"] is None


def test_tags_keep_the_ledgers_spelling_through_undo(ledger):
    from fin import db as dbm
    first = _ids(ledger, "HSBC CREDIT CARDS")[0]
    noodle = _ids(ledger, "NOODLE HOUSE")[0]
    dbm.add_tag(ledger, noodle, "Travel")
    preview = changes.propose(ledger, title="Tag", values={}, ids=[first], add_tags=["travel"])
    assert preview["add_tags"] == ["Travel"]
    changes.apply(ledger, preview["id"])
    tags = lambda: [r["tag"] for r in ledger.execute("SELECT tag FROM txn_tag WHERE txn_id=%s", (first,)).fetchall()]
    assert tags() == ["Travel"]
    assert changes.undo(ledger, preview["id"])["restored"] == 1
    assert tags() == []
    removal = changes.propose(ledger, title="Untag", values={}, ids=[noodle], remove_tags=["TRAVEL"])
    assert removal["rows"] == 1


def test_undo_survives_a_purged_model_decision(ledger):
    first = _ids(ledger, "HSBC CREDIT CARDS")[0]
    ledger.execute(
        "INSERT INTO llm_decision (id, task, input_hash, input_summary, output, model, prompt_version, created_at) "
        "VALUES ('d1','categorize','h','x','{}','m','v','2026-11-01')")
    ledger.execute(
        "INSERT INTO txn_annotation (txn_id, field, value, source, confidence, decision_id, created_at) "
        "VALUES (%s,'category','fees','llm',0.7,'d1','2026-11-01')", (first,))
    preview = changes.propose(ledger, title="Card payments", values=CARD_FIX, ids=[first])
    changes.apply(ledger, preview["id"])
    ledger.execute("DELETE FROM llm_decision WHERE id='d1'")
    assert changes.undo(ledger, preview["id"])["restored"] == 1
    restored = ledger.execute(
        "SELECT source, decision_id FROM txn_annotation WHERE txn_id=%s AND field='category'", (first,)).fetchone()
    assert dict(restored) == {"source": "llm", "decision_id": None}


class _FixingProvider(LLMProvider):
    """A model that is asked to fix rows: it looks them up, then proposes."""

    name = "tool-test"
    model = "tool-test-v1"

    def __init__(self, values):
        self.values = values

    def complete_json(self, system, user, *, max_tokens=2000):
        raise AssertionError("the agent uses tools")

    def complete_with_tools(self, system, user, tools, execute_tool, *, max_turns=4, max_tokens=1600):
        assert "propose_changes" in {tool["name"] for tool in tools}
        found = execute_tool("search_transactions", {"filter": {"q": "HSBC CREDIT CARDS"}})
        proposal = execute_tool("propose_changes", {
            "title": "Mark 2 HSBC card bill payments as card payments",
            "ids": [row["id"] for row in found["items"]],
            "set": self.values,
        })
        return LLMResponse(data={"answer": f"Proposed {proposal['rows']} changes."},
                           model=self.model, raw="", metadata={"usage": {}})


def test_the_agent_proposes_and_never_applies(ledger):
    before = _spend(ledger)
    result = answer_question(ledger, _FixingProvider(CARD_FIX), "can you fix the HSBC rows?")
    assert result["ok"] is True
    [proposal] = result["proposals"]
    assert proposal["rows"] == 2 and proposal["status"] == "proposed"
    assert _spend(ledger) == before
    # A one-off proposal is not served again from the answer cache.
    assert ledger.execute("SELECT COUNT(*) AS n FROM llm_decision WHERE task='query'").fetchone()["n"] == 0
    assert changes.get(ledger, proposal["id"])["title"].startswith("Mark 2 HSBC")


def test_the_agent_hears_why_a_proposal_was_refused(ledger):
    with pytest.raises(ValueError, match="not in the taxonomy"):
        answer_question(ledger, _FixingProvider({"category": "bills"}), "fix them")
