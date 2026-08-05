"""Natural language → ledger filter.

The model does **not** produce the number. It produces a *query*, and the
database produces the number. That separation is the whole design:

* **Nothing arbitrary executes.** The output is a filter object validated
  against a fixed schema, not SQL. There is no path from a question to an
  unconstrained statement against your financial data.
* **The answer is inspectable.** The filter comes back with the result, so a UI
  renders it as editable chips — "dining, Apr–Jun, excluding transfers". A
  misreading is visible and correctable, rather than arriving as a confident
  wrong number.
* **It is reproducible.** Same filter, same answer, forever. Results are cached
  in `llm_decision`, so a model update cannot silently move your totals.
* **It degrades honestly.** A question the DSL cannot express returns the
  closest filter it can, flagged as partial — never a different question
  answered as though it were the one asked.
"""

from __future__ import annotations

import json
from datetime import date
from typing import Any

from .cache import cached_decision
from .provider import LLMProvider, LLMUnavailable

PROMPT_VERSION = "query-v1"

VALID_KEYS = {
    "from", "to", "accounts", "cards", "institutions", "categories", "kinds",
    "currency", "minAmount", "maxAmount", "q", "includeTransfers",
    "includeDuplicates", "uncategorisedOnly", "installmentsOnly",
}
VALID_GROUP_BY = {
    "month", "quarter", "year", "day", "category", "subcategory", "merchant",
    "account", "institution", "card", "kind", "currency",
}
VALID_KINDS = {
    "purchase", "refund", "fee", "interest", "reward", "cc_payment", "transfer",
    "atm", "fx_conversion", "income", "adjustment", "installment",
    "installment_origination", "unknown",
}

SYSTEM = """You translate questions about a personal finance ledger into a JSON \
query. You never compute or estimate figures yourself — the database does that.

Return ONLY a JSON object:

{
  "filter": {
    "from": "YYYY-MM-DD",           // optional, inclusive
    "to": "YYYY-MM-DD",             // optional, inclusive
    "accounts": ["account_id"],     // optional, from the provided list only
    "categories": ["name"],         // optional, from the provided list only
    "kinds": ["purchase"],          // optional
    "currency": "HKD",              // optional
    "minAmount": -100000,           // optional, INTEGER MINOR UNITS, signed
    "maxAmount": -1,                // optional
    "q": "text",                    // optional free-text search
    "includeTransfers": false,      // default false
    "uncategorisedOnly": false,
    "installmentsOnly": false
  },
  "group_by": "month",              // one of the listed dimensions, or null
  "intent": "aggregate" | "list",
  "confidence": 0.0-1.0,
  "explanation": "one short sentence describing what you searched for",
  "unsupported": "set ONLY if the question cannot be expressed; say what is missing"
}

Rules:
- Amounts are INTEGER MINOR UNITS. HKD 100.00 is 10000. Outflows are NEGATIVE.
- "spending", "spent", "expenses" mean outflows: leave includeTransfers false.
- Transfers between the user's own accounts are NOT spending. Only set
  includeTransfers true if the question is explicitly about moving money.
- Only use account ids and category names from the provided lists. If the user
  names something not in the lists, put it in "q" instead of inventing an id.
- If the question needs a comparison, ranking across periods, or arithmetic the
  filter cannot express, still return your best filter AND set "unsupported".
- Prefer a wider filter over a wrong one. Never guess a date range that was not
  implied."""


def build_context(conn) -> dict[str, Any]:
    """The vocabulary the model is allowed to use."""
    def col(sql: str) -> list[str]:
        return [r["item_value"] for r in conn.execute(sql) if r["item_value"]]

    return {
        "today": date.today().isoformat(),
        "accounts": [
            {"id": r["id"], "name": r["display_name"],
             "type": r["account_type"], "currency": r["primary_currency"]}
            for r in conn.execute(
                "SELECT id, display_name, account_type, primary_currency "
                "FROM account ORDER BY display_name")],
        "categories": col("SELECT DISTINCT category AS item_value FROM txn "
                          "WHERE category IS NOT NULL ORDER BY category"),
        "currencies": col("SELECT DISTINCT currency_booked AS item_value FROM txn "
                          "ORDER BY currency_booked"),
        "kinds": sorted(VALID_KINDS),
        "group_by_options": sorted(VALID_GROUP_BY),
        "date_range": dict(conn.execute(
            "SELECT MIN(txn_date) AS earliest, MAX(txn_date) AS latest "
            "FROM txn").fetchone() or {}),
    }


def translate(conn, provider: LLMProvider, question: str) -> dict[str, Any]:
    """Turn a question into a validated filter. Never raises on bad model output."""
    context = build_context(conn)
    user = (f"Ledger context:\n{json.dumps(context, indent=2)}\n\n"
            f"Question: {question}")

    def call() -> dict:
        response = provider.complete_json(SYSTEM, user, max_tokens=900)
        return response.data if isinstance(response.data, dict) else {}

    try:
        raw, from_cache = cached_decision(
            conn, task="query", input_summary=question[:400],
            prompt_version=PROMPT_VERSION, model=getattr(provider, "model", provider.name),
            compute=call)
    except LLMUnavailable as e:
        return {"ok": False, "error": str(e), "filter": {}, "group_by": None}

    return {**sanitise(raw, context), "cached": from_cache}


def sanitise(raw: dict, context: dict) -> dict[str, Any]:
    """Discard anything the model invented.

    Model output is untrusted input. An account id or category that is not in
    the ledger is dropped rather than queried, because a filter naming a
    non-existent account silently returns zero rows — which reads as "you spent
    nothing" instead of "that account does not exist".
    """
    raw = raw or {}
    proposed = raw.get("filter") or {}
    if not isinstance(proposed, dict):
        proposed = {}

    valid_accounts = {a["id"] for a in context["accounts"]}
    valid_categories = set(context["categories"])
    clean: dict[str, Any] = {}
    dropped: list[str] = []

    for key, value in proposed.items():
        if key not in VALID_KEYS:
            dropped.append(key)
            continue
        if key == "accounts":
            kept = [v for v in _as_list(value) if v in valid_accounts]
            if len(kept) != len(_as_list(value)):
                dropped.append("accounts (unknown ids)")
            if kept:
                clean[key] = kept
        elif key == "categories":
            kept = [v for v in _as_list(value) if v in valid_categories]
            if len(kept) != len(_as_list(value)):
                dropped.append("categories (unknown names)")
            if kept:
                clean[key] = kept
        elif key == "kinds":
            kept = [v for v in _as_list(value) if v in VALID_KINDS]
            if kept:
                clean[key] = kept
        elif key in ("from", "to"):
            if _is_date(value):
                clean[key] = value
            else:
                dropped.append(key)
        elif key in ("minAmount", "maxAmount"):
            if isinstance(value, int):
                clean[key] = value
            elif isinstance(value, float) and value.is_integer():
                clean[key] = int(value)
            else:
                dropped.append(f"{key} (must be integer minor units)")
        elif key in ("includeTransfers", "includeDuplicates", "uncategorisedOnly",
                     "installmentsOnly"):
            clean[key] = bool(value)
        elif key in ("currency", "q"):
            if isinstance(value, str) and value.strip():
                clean[key] = value.strip()
        else:
            clean[key] = value

    group_by = raw.get("group_by")
    if group_by not in VALID_GROUP_BY:
        group_by = None

    return {
        "ok": True,
        "filter": clean,
        "group_by": group_by,
        "intent": raw.get("intent") if raw.get("intent") in ("aggregate", "list")
                  else ("aggregate" if group_by else "list"),
        "confidence": _confidence(raw.get("confidence")),
        "explanation": str(raw.get("explanation") or "")[:400],
        "unsupported": str(raw.get("unsupported"))[:400] if raw.get("unsupported") else None,
        "dropped_fields": dropped,
    }


def _as_list(v) -> list:
    if v is None:
        return []
    return v if isinstance(v, list) else [v]


def _is_date(v) -> bool:
    if not isinstance(v, str):
        return False
    try:
        date.fromisoformat(v)
        return True
    except ValueError:
        return False


def _confidence(v) -> float:
    try:
        return max(0.0, min(1.0, float(v)))
    except (TypeError, ValueError):
        return 0.0
