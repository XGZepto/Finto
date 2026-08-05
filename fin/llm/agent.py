"""Read-only ledger analysis with allowlisted model tools."""

from __future__ import annotations

import json
from typing import Any

from .. import fx as fxm
from .. import reporting
from . import cache
from .provider import LLMProvider, LLMUnavailable
from .query import VALID_GROUP_BY, build_context, sanitise

PROMPT_VERSION = "agent-v1"
MAX_TOOL_CALLS = 8

SYSTEM = """You answer questions about the user's Finto ledger.

Use the ledger tools before making any factual or numerical claim. Tool results
are authoritative. Do not calculate totals from sample rows when a totals or
summary tool can calculate them. Do not mix currencies. Prefer the requested
reporting currency when supplied, and state when a currency could not be
converted. Internal transfers are excluded from spending and income unless the
question explicitly asks about transfers.

Answer the question directly in one to four sentences. Add a short list only
when it materially improves the answer. Use major-unit amounts with currency
codes. Do not describe your reasoning process, the tool protocol, or generic
financial advice. If the available data cannot answer the question, state the
missing data precisely."""


TOOLS: list[dict[str, Any]] = [
    {
        "name": "ledger_totals",
        "description": "Compute spend, income, net flow, and row counts for a ledger filter.",
        "input_schema": {
            "type": "object",
            "properties": {
                "filter": {"type": "object"},
                "reporting_currency": {"type": "string"},
            },
        },
    },
    {
        "name": "ledger_summary",
        "description": "Aggregate matching transactions by period, account, "
                       "category, merchant, or kind.",
        "input_schema": {
            "type": "object",
            "properties": {
                "filter": {"type": "object"},
                "group_by": {"type": "string", "enum": sorted(VALID_GROUP_BY)},
                "reporting_currency": {"type": "string"},
            },
            "required": ["group_by"],
        },
    },
    {
        "name": "search_transactions",
        "description": "Return matching ledger rows. Use for largest, latest, "
                       "merchant, or row-level questions.",
        "input_schema": {
            "type": "object",
            "properties": {
                "filter": {"type": "object"},
                "limit": {"type": "integer", "minimum": 1, "maximum": 25},
                "sort": {"type": "string",
                         "enum": ["date", "amount", "merchant", "account", "category"]},
                "direction": {"type": "string", "enum": ["asc", "desc"]},
            },
        },
    },
    {
        "name": "account_positions",
        "description": "Return account balances or net worth at a date, "
                       "optionally normalized to one currency.",
        "input_schema": {
            "type": "object",
            "properties": {
                "accounts": {"type": "array", "items": {"type": "string"}},
                "as_of": {"type": "string", "format": "date"},
                "reporting_currency": {"type": "string"},
            },
        },
    },
    {
        "name": "money_flows",
        "description": "Return normalized flows between owned accounts and external categories.",
        "input_schema": {
            "type": "object",
            "properties": {
                "filter": {"type": "object"},
                "reporting_currency": {"type": "string"},
            },
            "required": ["reporting_currency"],
        },
    },
]


def answer_question(
    conn,
    provider: LLMProvider,
    question: str,
    *,
    reporting_currency: str | None = None,
) -> dict[str, Any]:
    """Run a bounded read-only analysis and return its answer and audit trace."""
    context = build_context(conn)
    target = (reporting_currency or "").strip().upper() or None
    system = (
        f"{SYSTEM}\n\nLedger vocabulary and coverage:\n"
        f"{json.dumps(context, default=str, separators=(',', ':'))}"
    )
    question_prompt = question.strip()
    if target:
        question_prompt += f"\nPreferred reporting currency: {target}"

    calls: list[dict[str, Any]] = []

    def execute(name: str, arguments: dict[str, Any]) -> Any:
        if len(calls) >= MAX_TOOL_CALLS:
            raise ValueError("tool call limit reached")
        result = _execute_tool(
            conn, context, name, arguments,
            default_currency=target,
        )
        calls.append({"name": name, "input": arguments, "result": result})
        return result

    try:
        response = provider.complete_with_tools(
            system, question_prompt, TOOLS, execute,
            max_turns=4, max_tokens=1600,
        )
    except LLMUnavailable as exc:
        return {"ok": False, "error": str(exc), "question": question}

    answer = str((response.data or {}).get("answer") or response.raw).strip()
    if not answer:
        return {"ok": False, "error": "The model returned no answer.", "question": question}

    public_calls = [{"name": call["name"], "input": call["input"]} for call in calls]
    result: dict[str, Any] = {
        "ok": True,
        "question": question,
        "answer": answer,
        "tools": public_calls,
        "prompt_cache": _prompt_cache(response.metadata.get("usage", {})),
        "model": response.model,
        "cached": False,
    }
    _attach_primary_result(result, calls, context)

    cache.record(
        conn,
        task="query",
        ihash=cache.input_hash("query", {
            "question": question,
            "context": context,
            "reporting_currency": target,
        }),
        summary=question[:500],
        output={
            "answer": answer,
            "tools": public_calls,
            "prompt_cache": result["prompt_cache"],
        },
        confidence=None,
        model=response.model,
        prompt_version=PROMPT_VERSION,
    )
    conn.commit()
    return result


def _execute_tool(
    conn,
    context: dict[str, Any],
    name: str,
    arguments: dict[str, Any],
    *,
    default_currency: str | None,
) -> Any:
    if name not in {tool["name"] for tool in TOOLS}:
        raise ValueError(f"unknown tool: {name}")
    if not isinstance(arguments, dict):
        raise TypeError("tool arguments must be an object")

    target = str(arguments.get("reporting_currency") or default_currency or "").upper() or None
    filters = _filter(arguments.get("filter"), context)

    if name == "ledger_totals":
        rows = reporting.totals(conn, filters=filters)
        out: dict[str, Any] = {"native": rows}
        if target:
            out["normalised"] = reporting.rollup(
                conn, rows, fields=("net", "spend", "income"), to_currency=target,
            )
        return out

    if name == "ledger_summary":
        group_by = arguments.get("group_by")
        if group_by not in VALID_GROUP_BY:
            raise ValueError("invalid group_by")
        rows = reporting.summary(conn, group_by=group_by, filters=filters)
        if target:
            rows = fxm.convert_rows(
                conn, rows, fields=("net", "spend", "income"), to_currency=target,
            )
        return {"group_by": group_by, "rows": rows}

    if name == "search_transactions":
        limit = max(1, min(25, int(arguments.get("limit", 10))))
        sort = arguments.get("sort") if arguments.get("sort") in reporting._SORTABLE else "date"
        direction = "asc" if arguments.get("direction") == "asc" else "desc"
        return reporting.transactions(
            conn, filters=filters, limit=limit, sort=sort, direction=direction,
        )

    if name == "account_positions":
        as_of = arguments.get("as_of")
        if as_of and not sanitise({"filter": {"to": as_of}}, context)["filter"].get("to"):
            raise ValueError("invalid as_of date")
        rows = reporting.positions(conn, as_of=as_of)
        allowed = {account["id"] for account in context["accounts"]}
        requested = set(arguments.get("accounts") or []) & allowed
        if requested:
            rows = [row for row in rows if row["account_id"] in requested]
        out = {"positions": rows}
        if target:
            out["normalised"] = reporting.rollup(
                conn, rows, fields=("balance",), to_currency=target, on=as_of,
            )
        return out

    return reporting.flows(conn, filters=filters, to_currency=target or "USD")


def _filter(value: Any, context: dict[str, Any]) -> dict[str, Any]:
    proposed = value if isinstance(value, dict) else {}
    return sanitise({"filter": proposed}, context)["filter"]


def _prompt_cache(usage: dict[str, Any]) -> dict[str, int | bool]:
    read = int(usage.get("cache_read_input_tokens") or 0)
    created = int(usage.get("cache_creation_input_tokens") or 0)
    return {
        "hit": read > 0,
        "read_input_tokens": read,
        "created_input_tokens": created,
    }


def _attach_primary_result(
    payload: dict[str, Any], calls: list[dict[str, Any]], context: dict[str, Any],
) -> None:
    for call in calls:
        name, result = call["name"], call["result"]
        if name == "ledger_totals":
            payload["totals"] = result["native"]
            payload["filter"] = _filter(call["input"].get("filter"), context)
        elif name == "ledger_summary":
            payload["rows"] = result["rows"]
            payload["group_by"] = result["group_by"]
            payload["filter"] = _filter(call["input"].get("filter"), context)
        elif name == "search_transactions":
            payload["transactions"] = result
            payload["filter"] = _filter(call["input"].get("filter"), context)
        if payload.get("filter") is not None:
            break
