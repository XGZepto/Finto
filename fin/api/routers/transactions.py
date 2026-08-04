"""Blotter: list, detail, and manual edits."""

from __future__ import annotations

from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, Query

from ... import reporting
from ..deps import get_conn
from ..schemas import LedgerFilter, TagBody, TransactionPatch

router = APIRouter(tags=["transactions"])


def filter_from_query(
    date_from: str | None = Query(None, alias="from"),
    date_to: str | None = Query(None, alias="to"),
    accounts: list[str] | None = Query(None),
    cards: list[str] | None = Query(None),
    institutions: list[str] | None = Query(None),
    categories: list[str] | None = Query(None),
    kinds: list[str] | None = Query(None),
    currency: str | None = Query(None),
    minAmount: int | None = Query(None),
    maxAmount: int | None = Query(None),
    q: str | None = Query(None),
    detail: list[str] | None = Query(None),
    tags: list[str] | None = Query(None),
    includeTransfers: bool = Query(False),
    includeDuplicates: bool = Query(False),
    uncategorisedOnly: bool = Query(False),
    installmentsOnly: bool = Query(False),
) -> LedgerFilter:
    return LedgerFilter(
        **{"from": date_from, "to": date_to}, accounts=accounts, cards=cards,
        institutions=institutions, categories=categories, kinds=kinds,
        currency=currency, minAmount=minAmount, maxAmount=maxAmount, q=q,
        detail=detail, tags=tags,
        includeTransfers=includeTransfers, includeDuplicates=includeDuplicates,
        uncategorisedOnly=uncategorisedOnly, installmentsOnly=installmentsOnly,
    )


@router.get("/transactions")
def list_transactions(
    f: LedgerFilter = Depends(filter_from_query),
    limit: int = Query(100, le=1000),
    offset: int = 0,
    sort: str = "date",
    direction: str = "desc",
    convert_to: str | None = Query(None),
    conn=Depends(get_conn),
) -> dict:
    page = reporting.transactions(
        conn, filters=f.to_query(), limit=limit, offset=offset,
        sort=sort, direction=direction)
    if convert_to and page.get("totals"):
        page["normalised"] = reporting.rollup(
            conn, page["totals"], fields=("net", "spend", "income"),
            to_currency=convert_to)
    return page


@router.get("/transactions/{txn_id}")
def get_transaction(txn_id: str, conn=Depends(get_conn)) -> dict:
    """One transaction with its provenance — the raw source row it came from.

    Provenance is what makes the ledger trustworthy: every number traces back to
    a line in a file you downloaded.
    """
    found = reporting.transaction_detail(conn, txn_id)
    if found is None:
        raise HTTPException(404, "no such transaction")
    return found


@router.patch("/transactions/{txn_id}")
def patch_transaction(txn_id: str, patch: TransactionPatch,
                      conn=Depends(get_conn)) -> dict:
    """Apply a manual correction.

    Recorded in txn_annotation with source='manual' so it always outranks a rule
    or an LLM guess, and so `DELETE FROM txn_annotation WHERE source='llm'`
    remains a complete undo of everything the model touched.
    """
    fields = patch.model_dump(exclude_none=True)
    if not fields:
        raise HTTPException(400, "nothing to update")

    row = conn.execute("SELECT id FROM txn WHERE id=?", (txn_id,)).fetchone()
    if row is None:
        raise HTTPException(404, "no such transaction")

    allowed = {"category", "subcategory", "notes", "review_state", "merchant"}
    sets = [f"{k}=?" for k in fields if k in allowed]
    params = [v for k, v in fields.items() if k in allowed]
    if sets:
        conn.execute(
            f"UPDATE txn SET {', '.join(sets)}, updated_at=? WHERE id=?",
            [*params, datetime.now().isoformat(), txn_id])

    now = datetime.now().isoformat()
    for key, value in fields.items():
        if key in ("category", "merchant", "subcategory"):
            conn.execute(
                "INSERT INTO txn_annotation (txn_id, field, value, source, "
                "confidence, created_at) VALUES (?,?,?,'manual',1.0,?) "
                "ON CONFLICT(txn_id, field) DO UPDATE SET value=excluded.value, "
                "source='manual', confidence=1.0, created_at=excluded.created_at",
                (txn_id, key, str(value), now))
    conn.commit()
    return reporting.transaction_detail(conn, txn_id)


@router.get("/tags")
def list_tags(conn=Depends(get_conn)) -> dict:
    from ... import db as dbm
    return {"tags": dbm.all_tags(conn)}


@router.post("/transactions/{txn_id}/tags")
def add_txn_tag(txn_id: str, body: TagBody, conn=Depends(get_conn)) -> dict:
    from ... import db as dbm
    if conn.execute("SELECT 1 FROM txn WHERE id=?", (txn_id,)).fetchone() is None:
        raise HTTPException(404, "no such transaction")
    if not body.tag.strip():
        raise HTTPException(400, "empty tag")
    dbm.add_tag(conn, txn_id, body.tag, source="manual")
    conn.commit()
    return reporting.transaction_detail(conn, txn_id)


@router.delete("/transactions/{txn_id}/tags/{tag}")
def delete_txn_tag(txn_id: str, tag: str, conn=Depends(get_conn)) -> dict:
    from ... import db as dbm
    dbm.remove_tag(conn, txn_id, tag)
    conn.commit()
    return reporting.transaction_detail(conn, txn_id)


@router.get("/details")
def list_detail_keys(conn=Depends(get_conn)) -> dict:
    """The structured facts extracted from statements, with counts."""
    return reporting.detail_keys(conn)


@router.get("/details/{key}")
def list_detail_values(key: str, limit: int = Query(200, le=1000),
                       conn=Depends(get_conn)) -> dict:
    return reporting.detail_values(conn, key, limit)


@router.get("/facets")
def get_facets(conn=Depends(get_conn)) -> dict:
    """Everything needed to populate the filter controls."""
    return reporting.facets(conn)
