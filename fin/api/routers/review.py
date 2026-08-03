"""Review queues: duplicates, transfers, instalment plans.

Nothing ambiguous is ever auto-merged, so these queues are where the judgement
calls land. A wrongly-merged transaction is far harder to notice six months
later than a wrongly-kept one, which is why the bar for automatic action is high
and this list exists at all.
"""

from __future__ import annotations

import json
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException

from ..deps import get_conn
from ..schemas import ResolveRequest

router = APIRouter(tags=["review"])


@router.get("/review/duplicates")
def duplicates(limit: int = 100, conn=Depends(get_conn)) -> dict:
    rows = [dict(r) for r in conn.execute(
        """SELECT dc.id, dc.score, dc.reasons, dc.resolution,
                  a.id AS keep_id, a.description_raw AS keep_desc,
                  a.txn_date AS keep_date, a.amount_booked AS keep_amount,
                  a.currency_booked AS keep_currency, a.account_id AS keep_account,
                  b.id AS dupe_id, b.description_raw AS dupe_desc,
                  b.txn_date AS dupe_date, b.amount_booked AS dupe_amount,
                  b.currency_booked AS dupe_currency, b.account_id AS dupe_account
           FROM duplicate_candidate dc
           JOIN txn a ON a.id = dc.keep_txn_id
           JOIN txn b ON b.id = dc.dupe_txn_id
           WHERE dc.resolution = 'open'
           ORDER BY dc.score DESC LIMIT ?""", (limit,))]
    for r in rows:
        r["reasons"] = json.loads(r["reasons"])
    return {"items": rows, "total": len(rows)}


@router.get("/review/transfers")
def transfers(limit: int = 100, conn=Depends(get_conn)) -> dict:
    rows = [dict(r) for r in conn.execute(
        """SELECT tc.id, tc.score, tc.reasons, tc.date_delta, tc.amount_delta,
                  o.id AS out_id, o.description_raw AS out_desc,
                  o.txn_date AS out_date, o.amount_booked AS out_amount,
                  o.currency_booked AS out_currency, o.account_id AS out_account,
                  i.id AS in_id, i.description_raw AS in_desc,
                  i.txn_date AS in_date, i.amount_booked AS in_amount,
                  i.currency_booked AS in_currency, i.account_id AS in_account
           FROM transfer_candidate tc
           JOIN txn o ON o.id = tc.out_txn_id
           JOIN txn i ON i.id = tc.in_txn_id
           WHERE tc.resolution = 'open'
           ORDER BY tc.score DESC LIMIT ?""", (limit,))]
    for r in rows:
        r["reasons"] = json.loads(r["reasons"])
    return {"items": rows, "total": len(rows)}


@router.get("/review/installments")
def installment_candidates(limit: int = 100, conn=Depends(get_conn)) -> dict:
    rows = []
    for r in conn.execute(
            "SELECT * FROM installment_candidate WHERE resolution='open' "
            "ORDER BY score DESC LIMIT ?", (limit,)):
        item = dict(r)
        item["reasons"] = json.loads(r["reasons"])
        item["txn_ids"] = json.loads(r["txn_ids"])
        ids = item["txn_ids"]
        placeholders = ",".join("?" * len(ids))
        item["transactions"] = [dict(t) for t in conn.execute(
            f"SELECT id, txn_date, description_raw, amount_booked, currency_booked "
            f"FROM txn WHERE id IN ({placeholders})", ids)]
        rows.append(item)
    return {"items": rows, "total": len(rows)}


@router.post("/review/duplicates/{candidate_id}")
def resolve_duplicate(candidate_id: str, req: ResolveRequest,
                      conn=Depends(get_conn)) -> dict:
    row = conn.execute("SELECT * FROM duplicate_candidate WHERE id=?",
                       (candidate_id,)).fetchone()
    if row is None:
        raise HTTPException(404, "no such candidate")

    resolution = "accepted" if req.action == "accept" else "rejected"
    conn.execute("UPDATE duplicate_candidate SET resolution=? WHERE id=?",
                 (resolution, candidate_id))
    if resolution == "accepted":
        conn.execute("UPDATE txn SET duplicate_of_id=? WHERE id=?",
                     (row["keep_txn_id"], row["dupe_txn_id"]))
    conn.commit()
    return {"id": candidate_id, "resolution": resolution}


@router.post("/review/transfers/{candidate_id}")
def resolve_transfer(candidate_id: str, req: ResolveRequest,
                     conn=Depends(get_conn)) -> dict:
    from ...transfers import transfer_group_id

    row = conn.execute("SELECT * FROM transfer_candidate WHERE id=?",
                       (candidate_id,)).fetchone()
    if row is None:
        raise HTTPException(404, "no such candidate")

    resolution = "accepted" if req.action == "accept" else "rejected"
    conn.execute("UPDATE transfer_candidate SET resolution=? WHERE id=?",
                 (resolution, candidate_id))

    if resolution == "accepted":
        # Same derivation the automatic pass uses, so a hand-confirmed pair and
        # a later automatic match converge on one group instead of two.
        gid = transfer_group_id([row["out_txn_id"], row["in_txn_id"]])
        conn.execute(
            "INSERT INTO transfer_group (id, kind, match_method, confidence, "
            "is_confirmed, created_at) VALUES (?,?,?,?,?,?) "
            "ON CONFLICT(id) DO UPDATE SET match_method='manual', confidence=1.0, "
            "is_confirmed=1",
            (gid, "internal_transfer", "manual", 1.0, 1, datetime.now().isoformat()))
        for txn_id, role in ((row["out_txn_id"], "out"), (row["in_txn_id"], "in")):
            conn.execute(
                "INSERT OR REPLACE INTO transfer_leg (transfer_group_id, txn_id, "
                "role) VALUES (?,?,?)", (gid, txn_id, role))
            conn.execute("UPDATE txn SET transfer_group_id=?, kind='transfer' "
                         "WHERE id=?", (gid, txn_id))
    conn.commit()
    return {"id": candidate_id, "resolution": resolution}


@router.post("/review/installments/{candidate_id}")
def resolve_installment(candidate_id: str, req: ResolveRequest,
                        conn=Depends(get_conn)) -> dict:
    row = conn.execute("SELECT * FROM installment_candidate WHERE id=?",
                       (candidate_id,)).fetchone()
    if row is None:
        raise HTTPException(404, "no such candidate")

    resolution = "accepted" if req.action == "accept" else "rejected"
    conn.execute("UPDATE installment_candidate SET resolution=? WHERE id=?",
                 (resolution, candidate_id))

    if resolution == "accepted":
        txn_ids = json.loads(row["txn_ids"])
        first = conn.execute(
            "SELECT * FROM txn WHERE id=? ", (txn_ids[0],)).fetchone()
        per = abs(first["amount_booked"])
        conn.execute(
            "INSERT INTO installment_plan (id, account_id, card_id, merchant, "
            "description, principal, currency, term_months, start_date, status, "
            "match_method, confidence, is_confirmed, created_at) "
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
            "ON CONFLICT(id) DO UPDATE SET is_confirmed=1, match_method='manual'",
            (row["id"], row["account_id"], first["card_id"], first["merchant"],
             row["description"], -(per * row["term_months"]),
             first["currency_booked"], row["term_months"], first["txn_date"],
             "active", "manual", 1.0, 1, datetime.now().isoformat()))
        from ...installments import parse_installment_marker
        for txn_id in txn_ids:
            t = conn.execute("SELECT description_raw FROM txn WHERE id=?",
                             (txn_id,)).fetchone()
            marker = parse_installment_marker(t["description_raw"]) if t else None
            conn.execute(
                "UPDATE txn SET installment_plan_id=?, installment_seq=?, "
                "kind='installment' WHERE id=?",
                (row["id"], marker[0] if marker else None, txn_id))
    conn.commit()
    return {"id": candidate_id, "resolution": resolution}
