"""Corrections Ask proposes and the user applies.

The model never writes to the ledger. When asked to fix rows it calls
``propose``, which snapshots the rows a filter (or a list of ids) matched at
that moment, the values they had and the values they would get, and returns a
preview. Nothing changes until the user applies it from the app.

Applying is the user's own edit: fields are written with ``source='manual'``
annotations, exactly as a PATCH from the blotter would, so a later LLM pass
never overrides them. A row that changed after the proposal was made is left
alone rather than overwritten with stale intent. Applying records what it
replaced, so the change can be undone while its rows are still as it left them.

Proposals live in ``agent_operation`` (action ``ledger_change``), the audit log
every agent write already goes to.
"""

from __future__ import annotations

import json
import uuid
from datetime import datetime, timezone
from typing import Any

from . import reporting
from .reporting import MOVEMENT_KINDS
from .taxonomy import canonical_key

ACTION = "ledger_change"
MAX_ROWS = 500
SAMPLE_ROWS = 8

#: What a correction may set a row's kind to. Instalment bookkeeping kinds and
#: `unknown` belong to the importers, not to a correction.
EDITABLE_KINDS: tuple[str, ...] = (
    "purchase", "refund", "fee", "interest", "reward", "cc_payment", "transfer",
    "atm", "fx_conversion", "income", "adjustment",
)
REVIEW_STATES: tuple[str, ...] = ("unreviewed", "confirmed", "flagged")
FIELDS: tuple[str, ...] = ("kind", "category", "subcategory", "merchant", "review_state")
#: Fields whose provenance is tracked in txn_annotation.
ANNOTATED: tuple[str, ...] = ("kind", "category", "subcategory", "merchant")


class ChangeError(ValueError):
    """A proposal or action the caller can correct; the message says how."""


class ChangeNotFound(Exception):
    """No change with that id belongs to the signed-in user."""


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def current_user(conn) -> str:
    row = conn.execute(
        "SELECT COALESCE(NULLIF(current_setting('finto.user_id', true), ''), 'owner') AS user_id"
    ).fetchone()
    return row["user_id"]


def counts_as_spending(kind: str | None, category: str | None) -> bool:
    """The `spendingOnly` rule from reporting, for one row."""
    return (kind or "") not in MOVEMENT_KINDS and (category or "") != "transfers"


def spend_of(amount: int, fields: dict[str, Any], refund_of_id: str | None) -> int:
    """What one row adds to spending, as `reporting.totals` counts it with `spendingOnly`.

    Outflows count as spend; a refund (a positive row tied to a purchase, or
    of kind refund) takes spend back.
    """
    if not counts_as_spending(fields.get("kind"), fields.get("category")):
        return 0
    if amount < 0:
        return -amount
    if amount > 0 and (refund_of_id or fields.get("kind") == "refund"):
        return -amount
    return 0


# --------------------------------------------------------------------------
# Proposing
# --------------------------------------------------------------------------

def normalise_set(conn, values: Any) -> dict[str, Any]:
    """The fields to set, validated; an empty string clears subcategory or merchant."""
    from .taxonomy import category_exists

    if not isinstance(values, dict):
        raise ChangeError("set must be an object")
    unknown = sorted(set(values) - set(FIELDS))
    if unknown:
        raise ChangeError(f"unknown fields in set: {', '.join(unknown)}. Use only: {', '.join(FIELDS)}")
    out: dict[str, Any] = {}
    if "kind" in values:
        kind = str(values["kind"] or "").strip()
        if kind not in EDITABLE_KINDS:
            raise ChangeError(f"kind must be one of: {', '.join(EDITABLE_KINDS)}")
        out["kind"] = kind
    if "review_state" in values:
        state = str(values["review_state"] or "").strip()
        if state not in REVIEW_STATES:
            raise ChangeError(f"review_state must be one of: {', '.join(REVIEW_STATES)}")
        out["review_state"] = state
    if "merchant" in values:
        merchant = str(values["merchant"] or "").strip()[:120]
        out["merchant"] = merchant or None
    if "category" in values:
        category = str(values["category"] or "").strip()
        if not category or not category_exists(conn, category):
            raise ChangeError(f"category {category!r} is not in the taxonomy")
        out["category"] = category
    if "subcategory" in values:
        sub = str(values["subcategory"] or "").strip() or None
        out["subcategory"] = sub
    if out.get("subcategory") and "category" not in out:
        raise ChangeError("set a subcategory together with its category")
    if out.get("subcategory") and not category_exists(conn, out["category"], out["subcategory"]):
        raise ChangeError(f"{out['category']}/{out['subcategory']} is not in the taxonomy")
    return out


def _clean_tags(conn, values: Any) -> list[str]:
    """Tags as the ledger spells them: an existing tag keeps its spelling."""
    if not values:
        return []
    if not isinstance(values, list):
        raise ChangeError("tags must be a list")
    wanted = {" ".join(str(tag).split())[:60] for tag in values if str(tag).strip()}
    known = {
        row["slug"]: row["display_name"]
        for row in conn.execute(
            "SELECT slug, display_name FROM tag_definition WHERE user_id=%s", (current_user(conn),),
        ).fetchall()
    }
    return sorted({known.get(canonical_key(tag), tag) for tag in wanted})


def _rows(conn, ids: list[str], *, lock: bool = False) -> list[dict[str, Any]]:
    """The rows' current fields; with ``lock``, held until commit so no edit lands in between."""
    if not ids:
        return []
    found = conn.execute(
        "SELECT t.id, t.txn_date, t.description_raw, t.merchant, t.kind, t.category, "
        "t.subcategory, t.review_state, t.amount_booked, t.currency_booked, t.account_id, "
        "t.refund_of_id, a.display_name AS account_name, a.user_id AS owner, "
        "finto_txn_access(t.id, 'editor') AS editable "
        "FROM txn t JOIN account a ON a.id=t.account_id WHERE t.id = ANY(%s)"
        + (" ORDER BY t.id FOR UPDATE OF t" if lock else ""),
        (ids,),
    ).fetchall()
    tags: dict[str, list[str]] = {}
    for row in conn.execute(
        "SELECT txn_id, tag FROM txn_tag WHERE txn_id = ANY(%s) ORDER BY tag", (ids,),
    ).fetchall():
        tags.setdefault(row["txn_id"], []).append(row["tag"])
    by_id = {row["id"]: {**dict(row), "tags": tags.get(row["id"], [])} for row in found}
    return [by_id[i] for i in ids if i in by_id]


def _after(row: dict[str, Any], values: dict[str, Any], add: list[str], remove: list[str]) -> dict[str, Any]:
    after = {field: values[field] if field in values else row[field] for field in FIELDS}
    # As the blotter does: moving a row to another category drops a subcategory
    # the change did not name; re-stating its own category keeps it.
    if "category" in values and "subcategory" not in values and values["category"] != row["category"]:
        after["subcategory"] = None
    gone = {canonical_key(tag) for tag in remove}
    tags = [t for t in row["tags"] if canonical_key(t) not in gone]
    present = {canonical_key(t) for t in tags}
    tags += [t for t in add if canonical_key(t) not in present]
    after["tags"] = sorted(tags)
    return after


def _before(row: dict[str, Any]) -> dict[str, Any]:
    return {**{field: row[field] for field in FIELDS}, "tags": sorted(row["tags"])}


def _target_ids(conn, ids: Any, filters: dict[str, Any] | None) -> list[str]:
    if ids:
        if not isinstance(ids, list):
            raise ChangeError("ids must be a list")
        wanted = list(dict.fromkeys(str(i) for i in ids))
        if len(wanted) > MAX_ROWS:
            raise ChangeError(f"at most {MAX_ROWS} rows per proposal")
        return wanted
    if not filters:
        raise ChangeError("name the rows: give ids from search_transactions, or a filter")
    page = reporting.transactions(conn, filters=filters, limit=MAX_ROWS + 1)
    items = page["items"]
    if len(items) > MAX_ROWS:
        raise ChangeError(f"the filter matches more than {MAX_ROWS} rows; narrow it")
    return [item["id"] for item in items]


def propose(
    conn,
    *,
    title: str,
    values: Any,
    ids: Any = None,
    filters: dict[str, Any] | None = None,
    add_tags: Any = None,
    remove_tags: Any = None,
    question: str = "",
) -> dict[str, Any]:
    """Snapshot the rows and the change, store it, and return its preview."""
    clean = normalise_set(conn, values or {})
    add, remove = _clean_tags(conn, add_tags), _clean_tags(conn, remove_tags)
    if not clean and not add and not remove:
        raise ChangeError("nothing to change: set a field or add or remove a tag")
    title = " ".join(str(title or "").split())[:120]
    if not title:
        raise ChangeError("give the change a short title")

    matched = _rows(conn, _target_ids(conn, ids, filters))
    if not matched:
        raise ChangeError("no rows match; check the ids or the filter")
    rows = [row for row in matched if row["editable"]]
    if not rows:
        raise ChangeError("these rows are shared with the user for viewing only; they cannot be changed")

    entries = []
    for row in rows:
        before, after = _before(row), _after(row, clean, add, remove)
        entries.append({
            "id": row["id"],
            "date": row["txn_date"],
            "description": row["description_raw"],
            "account_id": row["account_id"],
            "account_name": row["account_name"],
            "amount": reporting.money(row["amount_booked"], row["currency_booked"]),
            "refund_of_id": row["refund_of_id"],
            "before": before,
            "after": after,
            "changes": before != after,
        })
    record = {
        "status": "proposed",
        "title": title,
        "question": str(question or "")[:500],
        "set": clean,
        "add_tags": add,
        "remove_tags": remove,
        "rows": entries,
        "created_at": _now(),
    }
    change_id = str(uuid.uuid4())
    conn.execute(
        "INSERT INTO agent_operation (id, subject, action, user_id, applied, result, created_at) "
        "VALUES (%s,%s,%s,%s,0,%s::jsonb,%s)",
        (change_id, "ask", ACTION, current_user(conn), json.dumps(record, default=str), record["created_at"]),
    )
    return public(change_id, record)


def public(change_id: str, record: dict[str, Any]) -> dict[str, Any]:
    """What a client sees: the change, its reach, its effect, and a sample."""
    changing = [entry for entry in record["rows"] if entry["changes"]]
    totals: dict[str, int] = {}
    spending: dict[str, int] = {}
    for entry in changing:
        amount = entry["amount"]["amount"]
        currency = entry["amount"]["currency"]
        totals[currency] = totals.get(currency, 0) + amount
        refund = entry.get("refund_of_id")
        moved = spend_of(amount, entry["after"], refund) - spend_of(amount, entry["before"], refund)
        if moved:
            # Spending is a positive outflow: a negative change lowers it.
            spending[currency] = spending.get(currency, 0) + moved
    return {
        "id": change_id,
        "status": record["status"],
        "title": record["title"],
        "set": record["set"],
        "add_tags": record.get("add_tags", []),
        "remove_tags": record.get("remove_tags", []),
        "rows": len(changing),
        "unchanged": len(record["rows"]) - len(changing),
        "totals": [{"currency": c, "amount": a} for c, a in sorted(totals.items())],
        "spending": [{"currency": c, "change": a} for c, a in sorted(spending.items()) if a],
        "sample": [
            {key: entry[key] for key in ("id", "date", "description", "account_id", "account_name",
                                         "amount", "before", "after")}
            for entry in changing[:SAMPLE_ROWS]
        ],
        "applied": record.get("applied"),
        "skipped": record.get("skipped"),
        "restored": record.get("restored"),
        "created_at": record["created_at"],
        "applied_at": record.get("applied_at"),
        "undone_at": record.get("undone_at"),
        "dismissed_at": record.get("dismissed_at"),
    }


# --------------------------------------------------------------------------
# Acting on a proposal
# --------------------------------------------------------------------------

def _load(conn, change_id: str) -> dict[str, Any]:
    row = conn.execute(
        "SELECT result FROM agent_operation WHERE id=%s AND action=%s AND user_id=%s FOR UPDATE",
        (change_id, ACTION, current_user(conn)),
    ).fetchone()
    if row is None:
        raise ChangeNotFound("no such change")
    result = row["result"]
    return result if isinstance(result, dict) else json.loads(result)


def _save(conn, change_id: str, record: dict[str, Any], *, applied: bool) -> None:
    conn.execute(
        "UPDATE agent_operation SET result=%s::jsonb, applied=%s WHERE id=%s",
        (json.dumps(record, default=str), int(applied), change_id),
    )


def get(conn, change_id: str) -> dict[str, Any]:
    return public(change_id, _load(conn, change_id))


def _annotations(conn, txn_id: str) -> dict[str, Any]:
    rows = conn.execute(
        "SELECT field, value, source, confidence, decision_id, created_at "
        "FROM txn_annotation WHERE txn_id=%s AND field = ANY(%s)",
        (txn_id, list(ANNOTATED)),
    ).fetchall()
    return {row["field"]: dict(row) for row in rows}


def _write_fields(conn, txn_id: str, values: dict[str, Any], *, owner: str, result: dict[str, Any]) -> None:
    """Write fields; a named merchant joins the account owner's merchant list
    with the row's resulting category, as a blotter edit does."""
    if not values:
        return
    sets = ", ".join(f"{field}=%s" for field in values)
    conn.execute(
        f"UPDATE txn SET {sets}, updated_at=%s WHERE id=%s",
        [*values.values(), datetime.now().isoformat(), txn_id],
    )
    if values.get("merchant"):
        from .taxonomy import register_merchant
        register_merchant(conn, owner, values["merchant"], source="manual",
                          category=result.get("category"), subcategory=result.get("subcategory"))


def apply(conn, change_id: str) -> dict[str, Any]:
    """Apply a proposal as the user's own edit."""
    from . import db as dbm

    record = _load(conn, change_id)
    if record["status"] != "proposed":
        raise ChangeError(f"this change is {record['status']}")
    now = _now()
    entries = [entry for entry in record["rows"] if entry["changes"]]
    current = {row["id"]: row for row in _rows(conn, [entry["id"] for entry in entries], lock=True)}
    applied, skipped = [], []
    for entry in entries:
        row = current.get(entry["id"])
        # Changed since it was proposed, gone, or only shared for viewing:
        # leave it to the person.
        if row is None or not row["editable"] or _before(row) != entry["before"]:
            skipped.append(entry["id"])
            continue
        before, after = entry["before"], entry["after"]
        entry["annotations"] = _annotations(conn, entry["id"])
        values = {field: after[field] for field in FIELDS if after[field] != before[field]}
        _write_fields(conn, entry["id"], values, owner=row["owner"], result=after)
        for field in ANNOTATED:
            if field in values:
                conn.execute(
                    "INSERT INTO txn_annotation (txn_id, field, value, source, confidence, created_at) "
                    "VALUES (%s,%s,%s,'manual',1.0,%s) ON CONFLICT (txn_id, field) DO UPDATE SET "
                    "value=excluded.value, source='manual', confidence=1.0, decision_id=NULL, "
                    "created_at=excluded.created_at",
                    (entry["id"], field, values[field], now),
                )
        for tag in after["tags"]:
            if tag not in before["tags"]:
                dbm.add_tag(conn, entry["id"], tag, source="manual")
        for tag in before["tags"]:
            if tag not in after["tags"]:
                dbm.remove_tag(conn, entry["id"], tag)
        applied.append(entry["id"])
    record.update(status="applied", applied=len(applied), skipped=len(skipped),
                  applied_ids=applied, applied_at=now)
    _save(conn, change_id, record, applied=True)
    return public(change_id, record)


def undo(conn, change_id: str) -> dict[str, Any]:
    """Put back what an applied change replaced, on rows it still describes."""
    from . import db as dbm

    record = _load(conn, change_id)
    if record["status"] != "applied":
        raise ChangeError(f"this change is {record['status']}")
    done = set(record.get("applied_ids") or [])
    entries = [entry for entry in record["rows"] if entry["id"] in done]
    current = {row["id"]: row for row in _rows(conn, [entry["id"] for entry in entries], lock=True)}
    restored, kept = 0, 0
    for entry in entries:
        row = current.get(entry["id"])
        # Edited again since (or no longer editable): the later state wins.
        if row is None or not row["editable"] or _before(row) != entry["after"]:
            kept += 1
            continue
        before, after = entry["before"], entry["after"]
        values = {field: before[field] for field in FIELDS if after[field] != before[field]}
        _write_fields(conn, entry["id"], values, owner=row["owner"], result=before)
        previous = entry.get("annotations") or {}
        for field in ANNOTATED:
            if field not in values:
                continue
            conn.execute("DELETE FROM txn_annotation WHERE txn_id=%s AND field=%s", (entry["id"], field))
            old = previous.get(field)
            if old:
                # The model decision it cited may have been purged since; keep
                # the provenance and drop only the dangling reference.
                conn.execute(
                    "INSERT INTO txn_annotation (txn_id, field, value, source, confidence, decision_id, created_at) "
                    "VALUES (%s,%s,%s,%s,%s,(SELECT id FROM llm_decision WHERE id=%s),%s)",
                    (entry["id"], field, old["value"], old["source"], old["confidence"],
                     old["decision_id"], old["created_at"]),
                )
        for tag in after["tags"]:
            if tag not in before["tags"]:
                dbm.remove_tag(conn, entry["id"], tag)
        for tag in before["tags"]:
            if tag not in after["tags"]:
                dbm.add_tag(conn, entry["id"], tag, source="manual")
        restored += 1
    record.update(status="undone", restored=restored, skipped=kept, undone_at=_now())
    _save(conn, change_id, record, applied=False)
    return public(change_id, record)


def dismiss(conn, change_id: str) -> dict[str, Any]:
    record = _load(conn, change_id)
    if record["status"] != "proposed":
        raise ChangeError(f"this change is {record['status']}")
    record.update(status="dismissed", dismissed_at=_now())
    _save(conn, change_id, record, applied=False)
    return public(change_id, record)
