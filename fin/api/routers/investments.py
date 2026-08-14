"""Investment positions — MPF holdings.

These are units, not cash. A contribution that left a bank account is an
ordinary transaction and reconciles like one; what lives here is the valuation
of what those contributions bought, which moves with the market and must never
be fed into the balance checks.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException

from ...investment import list_snapshots, snapshot_detail, valuation_history
from ..deps import get_conn

router = APIRouter(tags=["investments"])


@router.get("/investments")
def list_investment_snapshots(conn=Depends(get_conn)) -> dict:
    return {"snapshots": list_snapshots(conn)}


@router.get("/investments/history")
def get_investment_history(
    scheme: str | None = None,
    account_id: str | None = None,
    conn=Depends(get_conn),
) -> dict:
    return valuation_history(conn, scheme=scheme, account_id=account_id)


@router.get("/investments/{snapshot_id}")
def get_investment_snapshot(snapshot_id: str, conn=Depends(get_conn)) -> dict:
    found = snapshot_detail(conn, snapshot_id)
    if found is None:
        raise HTTPException(404, "no such snapshot")
    return found
