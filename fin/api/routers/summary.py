"""Aggregations and positions."""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query

from ... import fx as fxm
from ... import reporting
from ..deps import get_conn
from ..schemas import LedgerFilter, SummaryRequest
from .transactions import filter_from_query

router = APIRouter(tags=["summary"])


@router.get("/summary")
def get_summary(
    group_by: str = Query("month"),
    convert_to: str | None = Query(None),
    f: LedgerFilter = Depends(filter_from_query),
    conn=Depends(get_conn),
) -> dict:
    """Aggregate the ledger along one dimension.

    Results are always grouped by currency as well as the requested dimension —
    a bucket that mixed HKD and USD would be a number with no meaning. When
    `convert_to` is given, each row gains a *companion* converted figure; the
    native amount is never replaced.
    """
    if group_by not in reporting.GROUP_BY_SQL and group_by != "tag":
        raise HTTPException(
            400, f"group_by must be one of: {sorted(reporting.GROUP_BY_SQL)}")

    rows = reporting.summary(conn, group_by=group_by, filters=f.to_query())
    headline = reporting.totals(conn, filters=f.to_query())

    payload = {
        "group_by": group_by,
        "rows": rows,
        "totals": headline,
        "dimensions": sorted([*reporting.GROUP_BY_SQL, "tag"]),
    }

    if convert_to:
        money_fields = ("net", "spend", "income")
        payload["rows"] = fxm.convert_rows(
            conn, rows, fields=money_fields, to_currency=convert_to)
        payload["totals"] = fxm.convert_rows(
            conn, headline, fields=money_fields, to_currency=convert_to)
        by_bucket = reporting.rollup(
            conn, rows, fields=money_fields, to_currency=convert_to, key="bucket")
        payload["normalised"] = {
            "to": convert_to.upper(),
            "rows": by_bucket["rows"],
            "total": reporting.rollup(
                conn, headline, fields=money_fields, to_currency=convert_to),
            "unconvertible_currencies": by_bucket["unconvertible_currencies"],
        }
    return payload


@router.post("/summary")
def post_summary(req: SummaryRequest, conn=Depends(get_conn)) -> dict:
    if req.group_by not in reporting.GROUP_BY_SQL and req.group_by != "tag":
        raise HTTPException(
            400, f"group_by must be one of: {sorted(reporting.GROUP_BY_SQL)}")
    rows = reporting.summary(conn, group_by=req.group_by,
                             filters=req.filter.to_query())
    totals = reporting.totals(conn, filters=req.filter.to_query())
    payload = {"group_by": req.group_by, "rows": rows, "totals": totals}
    if req.convert_to:
        fields = ("net", "spend", "income")
        payload["rows"] = fxm.convert_rows(conn, rows, fields=fields,
                                           to_currency=req.convert_to)
        payload["totals"] = fxm.convert_rows(conn, totals, fields=fields,
                                             to_currency=req.convert_to)
        by_bucket = reporting.rollup(conn, rows, fields=fields,
                                     to_currency=req.convert_to, key="bucket")
        payload["normalised"] = {
            "to": req.convert_to.upper(),
            "rows": by_bucket["rows"],
            "total": reporting.rollup(conn, totals, fields=fields,
                                      to_currency=req.convert_to),
            "unconvertible_currencies": by_bucket["unconvertible_currencies"],
        }
    return payload


@router.get("/coverage")
def get_coverage(conn=Depends(get_conn)) -> dict:
    """Per account, month by month, what data backs it."""
    return reporting.coverage(conn)


@router.get("/composition")
def get_composition(convert_to: str = Query(...),
                    dimension: str = Query("category"),
                    limit: int = Query(8, le=20),
                    f: LedgerFilter = Depends(filter_from_query),
                    conn=Depends(get_conn)) -> dict:
    """Spend by a dimension over time, normalised to one currency."""
    if dimension not in reporting.GROUP_BY_SQL:
        raise HTTPException(
            400, f"dimension must be one of: {sorted(reporting.GROUP_BY_SQL)}")
    return reporting.composition(
        conn, dimension=dimension, to_currency=convert_to,
        filters=f.to_query(), limit=limit)


@router.get("/flows")
def get_flows(convert_to: str = Query("USD"),
              f: LedgerFilter = Depends(filter_from_query),
              conn=Depends(get_conn)) -> dict:
    """Movement between your own accounts, and across the boundary."""
    return reporting.flows(conn, filters=f.to_query(), to_currency=convert_to)


@router.get("/positions")
def get_positions(convert_to: str | None = Query(None),
                  as_of: str | None = Query(None),
                  conn=Depends(get_conn)) -> dict:
    """Balance per (account, currency).

    Never one cross-currency total. An account that settles in two currencies
    holds two positions, and adding them together does not produce a third.
    """
    rows = reporting.positions(conn, as_of=as_of)
    payload = {
        "positions": rows,
        "declared_currencies": reporting.declared_currencies(conn),
    }
    if convert_to:
        payload["positions"] = fxm.convert_rows(
            conn, rows, fields=("balance", "inflow", "outflow"),
            to_currency=convert_to, on=as_of)
        payload["conversion"] = {
            "to": convert_to.upper(),
            "unconvertible_currencies": fxm.missing_pairs(conn, convert_to),
        }
        net_worth = reporting.rollup(
            conn, rows, fields=("balance",), to_currency=convert_to, on=as_of)
        by_type = reporting.rollup(
            conn, rows, fields=("balance",), to_currency=convert_to,
            key="account_type", on=as_of)
        payload["normalised"] = {
            "to": convert_to.upper(),
            "net_worth": net_worth["balance"],
            "by_type": by_type["rows"],
            "unconvertible_currencies": net_worth["unconvertible_currencies"],
        }
    return payload


@router.get("/stats")
def get_stats(conn=Depends(get_conn)) -> dict:
    return reporting.stats(conn)


@router.get("/fx/rates")
def get_rates(conn=Depends(get_conn)) -> dict:
    return {"pairs": fxm.available_pairs(conn)}
