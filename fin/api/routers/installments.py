"""Instalment plans and outstanding liability."""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException

from ... import db as dbm
from ..deps import get_conn

router = APIRouter(tags=["installments"])


@router.get("/installments")
def list_plans(active_only: bool = False, conn=Depends(get_conn)) -> dict:
    """Plans with progress and what is still owed.

    Outstanding totals are grouped by currency. Summing a HKD plan and a USD
    plan into one liability figure would be meaningless.
    """
    plans = dbm.load_installment_plans(conn, active_only=active_only)

    outstanding: dict[str, int] = {}
    monthly: dict[str, int] = {}
    for p in plans:
        if p["status"] != "active":
            continue
        ccy = p["outstanding"]["currency"]
        outstanding[ccy] = outstanding.get(ccy, 0) + p["outstanding"]["amount"]
        monthly[ccy] = monthly.get(ccy, 0) + p["per_installment"]["amount"]

    return {
        "plans": plans,
        "outstanding_by_currency": [
            {"currency": c, "amount": a} for c, a in sorted(outstanding.items())],
        # Remaining instalments are known future outflows — the only genuinely
        # predictable part of a spending forecast.
        "committed_monthly_by_currency": [
            {"currency": c, "amount": a} for c, a in sorted(monthly.items())],
    }


@router.get("/installments/{plan_id}")
def get_plan(plan_id: str, conn=Depends(get_conn)) -> dict:
    plans = dbm.load_installment_plans(conn)
    plan = next((p for p in plans if p["id"] == plan_id), None)
    if plan is None:
        raise HTTPException(404, "no such plan")
    plan["charges"] = [dict(r) for r in conn.execute(
        "SELECT id, txn_date, description_raw, amount_booked, currency_booked, "
        "       installment_seq, "
        "       CASE WHEN installment_seq IS NULL THEN TRUE ELSE FALSE END AS is_settlement "
        "FROM txn WHERE installment_plan_id=%s AND duplicate_of_id IS NULL "
        "ORDER BY txn_date, installment_seq NULLS LAST", (plan_id,))]
    return plan
