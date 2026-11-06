"""The ledger vocabulary and filter validation behind Ask's tools.

The model never produces a figure. It proposes filters through the agent's
tools; ``sanitise`` checks every key and value against the ledger's real
vocabulary (``build_context``) before a query runs, and the database computes
the number. An account or category the ledger does not have is reported as
dropped rather than queried, because a filter naming something that does not
exist silently returns zero rows, which reads as "you spent nothing".
"""

from __future__ import annotations

from datetime import date
from typing import Any


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
