"""Accounts, cards and settings."""

from __future__ import annotations

import re
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field

from ... import db as dbm
from ...models import Account, Card
from ..deps import get_conn

router = APIRouter(tags=["accounts"])


def _serialize_account(a: Account) -> dict:
    return {
        "id": a.id,
        "institution_id": a.institution_id,
        "display_name": a.display_name,
        "account_type": a.account_type.value,
        "primary_currency": a.primary_currency,
        # What the account may hold. A UI should show a zero position for a
        # declared currency with no activity rather than omitting it.
        "settlement_currencies": a.settlement_currencies,
        "balance_group": a.balance_group,
        "masked_number": a.masked_number,
        "opened_on": str(a.opened_on) if a.opened_on else None,
        "closed_on": str(a.closed_on) if a.closed_on else None,
        "watch_statements": a.watch_statements,
    }


@router.get("/accounts")
def list_accounts(conn=Depends(get_conn)) -> dict:
    accounts = dbm.load_accounts(conn)
    return {"accounts": [_serialize_account(a) for a in accounts.values()]}


class AccountCreate(BaseModel):
    id: str
    institution_id: str
    display_name: str
    account_type: str
    primary_currency: str
    masked_number: str | None = None
    aliases: list[str] = Field(default_factory=list)
    balance_group: str | None = None
    watch_statements: bool = True
    cardholder_name: str | None = None
    last4: str | None = None


@router.post("/accounts", status_code=201)
def create_account(body: AccountCreate, request: Request) -> dict:
    """Add an account for the signed-in user.

    Row security cannot see an account until it has an access grant, so this
    uses the maintenance connection and stamps the caller as owner.
    """
    if not re.fullmatch(r"[a-z][a-z0-9_]{1,63}", body.id):
        raise HTTPException(422, "account id must be a lowercase slug")
    user_id = request.state.user_id
    conn = dbm.connect()
    try:
        if conn.execute(
            "SELECT 1 FROM institution WHERE id=%s", (body.institution_id,)
        ).fetchone() is None:
            raise HTTPException(422, "unknown institution")
        if conn.execute("SELECT 1 FROM account WHERE id=%s", (body.id,)).fetchone():
            raise HTTPException(409, "account already exists")
        account = Account(
            id=body.id,
            institution_id=body.institution_id,
            display_name=body.display_name,
            account_type=body.account_type,
            primary_currency=body.primary_currency,
            masked_number=body.masked_number,
            aliases=body.aliases,
            balance_group=body.balance_group,
            watch_statements=body.watch_statements,
        )
        dbm.upsert_account(conn, account, user_id=user_id)
        if body.last4 or body.cardholder_name:
            dbm.upsert_card(conn, Card(
                id=f"{body.id}_primary",
                account_id=body.id,
                cardholder_name=body.cardholder_name or "",
                last4=body.last4,
            ))
        conn.commit()
        created = dbm.load_accounts(conn).get(body.id)
    except HTTPException:
        conn.rollback()
        raise
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()
    if created is None:
        raise HTTPException(500, "account was not saved")
    return _serialize_account(created)


class AccountPatch(BaseModel):
    closed_on: date | None = None
    watch_statements: bool | None = None


@router.patch("/accounts/{account_id}")
def patch_account(account_id: str, patch: AccountPatch, conn=Depends(get_conn)) -> dict:
    """Mark an account closed or stop expecting monthly statements."""
    fields = patch.model_dump(exclude_unset=True)
    if not fields:
        raise HTTPException(400, "nothing to update")
    row = conn.execute("SELECT id FROM account WHERE id=%s", (account_id,)).fetchone()
    if row is None:
        raise HTTPException(404, "account not found")
    sets = [f"{k}=%s" for k in fields]
    params = [
        int(v) if k == "watch_statements" else (str(v) if v is not None else None)
        for k, v in fields.items()
    ]
    conn.execute(
        f"UPDATE account SET {', '.join(sets)} WHERE id=%s",
        [*params, account_id],
    )
    conn.commit()
    updated = dbm.load_accounts(conn).get(account_id)
    if updated is None:
        raise HTTPException(404, "account not found")
    return _serialize_account(updated)


@router.get("/cards")
def list_cards(conn=Depends(get_conn)) -> dict:
    """Cards, with reissue chains resolved.

    `lineage_root` groups a card with the ones it replaced, so per-card history
    survives renumbering.
    """
    roots = dbm.card_lineage_roots(conn)
    cards = dbm.load_cards(conn)
    return {"cards": [{
        "id": c.id,
        "account_id": c.account_id,
        "cardholder_name": c.cardholder_name,
        "last4": c.last4,
        "is_supplementary": c.is_supplementary,
        "issued_on": str(c.issued_on) if c.issued_on else None,
        "closed_on": str(c.closed_on) if c.closed_on else None,
        "replaces_card_id": c.replaces_card_id,
        "lineage_root": roots.get(c.id, c.id),
    } for c in cards]}


@router.get("/statement-freshness")
def statement_freshness(conn=Depends(get_conn)) -> dict:
    """Newest imported statement and whether another cycle is now expected."""
    today = datetime.now(ZoneInfo("Asia/Hong_Kong")).date()
    rows = conn.execute(
        r"""WITH statement_coverage AS (
               SELECT sf.account_id, sf.id AS statement_id, sf.row_count,
                      COALESCE(sf.statement_date, sf.period_end,
                               substring(sf.source_path from 'to_(20[0-9]{2}-[0-9]{2}-[0-9]{2})'),
                               substring(sf.source_path from
                                         '_(20[0-9]{2}-[0-9]{2}-[0-9]{2})\.[^.]+$'),
                               (SELECT MAX(t.txn_date)::text FROM txn t
                                 WHERE t.statement_file_id=sf.id),
                               CASE WHEN sf.row_count=0
                                    THEN substr(sf.imported_at, 1, 10) END) AS covered_on
                 FROM statement_file sf
                WHERE sf.account_id IS NOT NULL
               UNION ALL
               SELECT ba.account_id, sf.id AS statement_id, sf.row_count,
                      COALESCE(sf.statement_date, sf.period_end,
                               substring(sf.source_path from 'to_(20[0-9]{2}-[0-9]{2}-[0-9]{2})'),
                               substring(sf.source_path from
                                         '_(20[0-9]{2}-[0-9]{2}-[0-9]{2})\.[^.]+$'),
                               ba.as_of_date) AS covered_on
                 FROM balance_assertion ba
                 JOIN statement_file sf ON sf.id=ba.statement_file_id
           )
           SELECT a.id AS account_id, a.display_name, a.closed_on, a.account_type,
                  COALESCE(a.watch_statements, 1) AS watch_statements,
                  latest.covered_on AS statement_date,
                  latest.row_count AS statement_row_count,
                  (SELECT MAX(t.txn_date) FROM txn t
                    WHERE t.account_id=a.id AND t.duplicate_of_id IS NULL) AS latest_activity
             FROM account a
             LEFT JOIN LATERAL (
                  SELECT sc.covered_on, sc.row_count
                    FROM statement_coverage sc
                   WHERE sc.account_id=a.id AND sc.covered_on IS NOT NULL
                   ORDER BY sc.covered_on DESC, sc.statement_id DESC
                   LIMIT 1
             ) latest ON TRUE
            ORDER BY a.display_name"""
    ).fetchall()
    accounts = []
    for row in rows:
        statement_date = row["statement_date"]
        if isinstance(statement_date, str):
            statement_date = date.fromisoformat(statement_date[:10])
        expected_on = statement_date + timedelta(days=31) if statement_date else None
        closed = bool(row["closed_on"] and date.fromisoformat(str(row["closed_on"])[:10]) <= today)
        watched = bool(row["watch_statements"]) and row["account_type"] != "investment"
        stale = bool(
            not closed and watched and expected_on and today > expected_on + timedelta(days=7)
        )
        if closed:
            status = "closed"
        elif not watched:
            status = "ignored"
        elif stale:
            status = "stale"
        elif statement_date:
            status = "current"
        else:
            status = "unknown"
        accounts.append({
            "account_id": row["account_id"],
            "display_name": row["display_name"],
            "statement_date": str(statement_date) if statement_date else None,
            "latest_activity": str(row["latest_activity"]) if row["latest_activity"] else None,
            "expected_on": str(expected_on) if expected_on else None,
            "status": status,
            "statement_empty": row["statement_row_count"] == 0,
            "days_overdue": max(0, (today - expected_on).days) if stale else 0,
        })
    return {
        "as_of": str(today),
        "stale_count": sum(row["status"] == "stale" for row in accounts),
        "accounts": accounts,
    }


@router.get("/institutions")
def list_institutions(conn=Depends(get_conn)) -> dict:
    return {"institutions": [dict(r) for r in conn.execute(
        "SELECT * FROM institution ORDER BY display_name")]}


@router.get("/settings")
def get_settings(conn=Depends(get_conn)) -> dict:
    return {r["key"]: r["value"] for r in conn.execute(
        "SELECT key, value FROM setting ORDER BY key")}


@router.put("/settings/{key}")
def set_setting(key: str, value: str, conn=Depends(get_conn)) -> dict:
    conn.execute(
        "INSERT INTO setting (key, value) VALUES (%s,%s) "
        "ON CONFLICT (key) DO UPDATE SET value=EXCLUDED.value", (key, value))
    conn.commit()
    return {"key": key, "value": value}
