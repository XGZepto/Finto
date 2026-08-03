"""Accounts, cards and settings."""

from __future__ import annotations

from fastapi import APIRouter, Depends

from ... import db as dbm
from ..deps import get_conn

router = APIRouter(tags=["accounts"])


@router.get("/accounts")
def list_accounts(conn=Depends(get_conn)) -> dict:
    accounts = dbm.load_accounts(conn)
    return {"accounts": [{
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
    } for a in accounts.values()]}


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
    conn.execute("INSERT OR REPLACE INTO setting (key, value) VALUES (?,?)",
                 (key, value))
    conn.commit()
    return {"key": key, "value": value}
