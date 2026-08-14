"""Investment / MPF position ingest.

Cash contributions that leave a bank account remain ordinary `txn` rows and can
link as transfers into an investment account. This module handles the unit
ledger: fund holdings and valuations that do not move cash and must never be
fed into `integrity.check_account`.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from datetime import date, datetime
from decimal import Decimal
from pathlib import Path

from .models import Money


@dataclass
class SubaccountBalance:
    account_id: str
    member_no: str | None
    balance: Money
    allocation: Decimal | None = None


@dataclass
class FundHolding:
    instrument: str
    units: Decimal | None
    unit_price: Decimal | None
    market_value: Money
    allocation: Decimal | None = None


@dataclass
class InvestmentSnapshot:
    as_of_date: date
    scheme: str
    currency: str
    total_value: Money
    source: str
    subaccounts: list[SubaccountBalance] = field(default_factory=list)
    holdings: list[FundHolding] = field(default_factory=list)
    notes: str | None = None
    statement_file_id: str | None = None


# Map HSBC MPF role labels -> stable account ids (must match accounts.yaml).
HSBC_MPF_ROLE_ACCOUNTS = {
    "regular employee": "hsbc_mpf_regular",
    "personal account holder": "hsbc_mpf_personal",
    "tax deductible voluntary contribution account holder": "hsbc_mpf_tdvc",
}


def parse_hsbc_mpf_position_xlsx(path: str | Path) -> InvestmentSnapshot:
    """Read the HSBC MPF position workbook captured from Personal Internet Banking."""
    try:
        import openpyxl
    except ImportError as e:
        raise RuntimeError(
            "MPF xlsx support needs openpyxl — install with: pip install 'finto[xlsx]'"
        ) from e

    path = Path(path)
    wb = openpyxl.load_workbook(path, data_only=True)
    summary = wb["Summary"]
    funds = wb["Fund Positions"]

    total = None
    as_of = None
    currency = "HKD"
    for row in summary.iter_rows(values_only=True):
        label = (row[0] or "")
        if isinstance(label, str) and label.strip().lower() == "reported total balance":
            total = Decimal(str(row[1]))
        if isinstance(label, str) and label.strip().lower() == "position date":
            as_of = row[1]
        if isinstance(label, str) and label.strip().lower() == "currency":
            currency = str(row[1]).strip().upper()

    if total is None or as_of is None:
        raise ValueError(f"{path.name}: missing total balance or position date")
    if isinstance(as_of, datetime):
        as_of = as_of.date()
    elif isinstance(as_of, str):
        as_of = date.fromisoformat(as_of[:10])

    subaccounts: list[SubaccountBalance] = []
    for row in summary.iter_rows(values_only=True):
        role = (row[0] or "")
        if not isinstance(role, str):
            continue
        key = role.strip().lower()
        account_id = HSBC_MPF_ROLE_ACCOUNTS.get(key)
        if not account_id:
            continue
        member_no = str(row[1]).strip() if row[1] is not None else None
        bal = Money.from_decimal(Decimal(str(row[2])), currency)
        alloc = Decimal(str(row[3])) if row[3] is not None else None
        subaccounts.append(SubaccountBalance(
            account_id=account_id, member_no=member_no, balance=bal, allocation=alloc
        ))

    holdings: list[FundHolding] = []
    for row in funds.iter_rows(values_only=True):
        name = row[0]
        if not isinstance(name, str) or name.strip() in ("", "Constituent fund", "Total"):
            continue
        if "HSBC MPF" in name or name.startswith("Aggregate") or name.startswith("Reported"):
            continue
        units = Decimal(str(row[1])) if row[1] is not None else None
        price = Decimal(str(row[2])) if row[2] is not None else None
        value = Money.from_decimal(Decimal(str(row[3])), currency)
        alloc = Decimal(str(row[6])) if len(row) > 6 and row[6] is not None else None
        holdings.append(FundHolding(
            instrument=name.strip(), units=units, unit_price=price,
            market_value=value, allocation=alloc,
        ))

    return InvestmentSnapshot(
        as_of_date=as_of,
        scheme="hsbc_mpf",
        currency=currency,
        total_value=Money.from_decimal(total, currency),
        source="xlsx",
        subaccounts=subaccounts,
        holdings=holdings,
        notes=f"imported from {path.name}",
    )


def save_snapshot(conn, snap: InvestmentSnapshot) -> str:
    """Persist a snapshot, replacing any previous one for the same scheme+date+source."""
    snap_id = str(uuid.uuid4())
    conn.execute(
        "DELETE FROM investment_snapshot WHERE scheme=%s AND as_of_date=%s AND source=%s",
        (snap.scheme, snap.as_of_date.isoformat(), snap.source),
    )
    conn.execute(
        "INSERT INTO investment_snapshot (id, as_of_date, scheme, currency, total_value, "
        "source, statement_file_id, notes, created_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)",
        (snap_id, snap.as_of_date.isoformat(), snap.scheme, snap.currency,
         snap.total_value.amount, snap.source, snap.statement_file_id, snap.notes,
         datetime.now().isoformat()),
    )
    for s in snap.subaccounts:
        conn.execute(
            "INSERT INTO investment_subaccount_balance "
            "(snapshot_id, account_id, member_no, balance, currency, allocation) "
            "VALUES (%s,%s,%s,%s,%s,%s)",
            (snap_id, s.account_id, s.member_no, s.balance.amount, s.balance.currency,
             str(s.allocation) if s.allocation is not None else None),
        )
    for h in snap.holdings:
        conn.execute(
            "INSERT INTO investment_holding "
            "(id, snapshot_id, instrument, units, unit_price, market_value, currency, allocation) "
            "VALUES (%s,%s,%s,%s,%s,%s,%s,%s)",
            (str(uuid.uuid4()), snap_id, h.instrument,
             str(h.units) if h.units is not None else None,
             str(h.unit_price) if h.unit_price is not None else None,
             h.market_value.amount, h.market_value.currency,
             str(h.allocation) if h.allocation is not None else None),
        )
    conn.commit()
    return snap_id


def list_snapshots(conn) -> list[dict]:
    rows = conn.execute(
        "SELECT id, as_of_date, scheme, currency, total_value, source, notes "
        "FROM investment_snapshot ORDER BY as_of_date DESC"
    )
    return [
        {
            "id": r["id"],
            "as_of_date": r["as_of_date"],
            "scheme": r["scheme"],
            "total": {"amount": r["total_value"], "currency": r["currency"]},
            "source": r["source"],
            "notes": r["notes"],
        }
        for r in rows
    ]


def valuation_history(conn, *, scheme: str | None = None,
                      account_id: str | None = None) -> dict:
    """Dated scheme or member-account values, ordered for charting."""
    clauses: list[str] = []
    params: list[str] = []
    if scheme:
        clauses.append("s.scheme=%s")
        params.append(scheme)
    if account_id:
        clauses.append("b.account_id=%s")
        params.append(account_id)
        value_sql = "b.balance"
        currency_sql = "b.currency"
        join_sql = (
            "JOIN investment_subaccount_balance b ON b.snapshot_id=s.id"
        )
    else:
        value_sql = "s.total_value"
        currency_sql = "s.currency"
        join_sql = ""
    where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
    rows = conn.execute(
        f"SELECT s.as_of_date,s.scheme,{value_sql} AS value,{currency_sql} AS currency "
        f"FROM investment_snapshot s {join_sql} {where} "
        "ORDER BY s.as_of_date ASC",
        params,
    )
    return {
        "scheme": scheme,
        "account_id": account_id,
        "points": [
            {
                "as_of_date": row["as_of_date"],
                "value": {"amount": row["value"], "currency": row["currency"]},
            }
            for row in rows
        ],
    }


def snapshot_detail(conn, snapshot_id: str) -> dict | None:
    r = conn.execute(
        "SELECT * FROM investment_snapshot WHERE id=%s", (snapshot_id,)
    ).fetchone()
    if not r:
        return None
    subs = [
        {
            "account_id": s["account_id"],
            "member_no": s["member_no"],
            "balance": {"amount": s["balance"], "currency": s["currency"]},
            "allocation": s["allocation"],
        }
        for s in conn.execute(
            "SELECT * FROM investment_subaccount_balance WHERE snapshot_id=%s",
            (snapshot_id,),
        )
    ]
    holdings = [
        {
            "instrument": h["instrument"],
            "units": h["units"],
            "unit_price": h["unit_price"],
            "market_value": {"amount": h["market_value"], "currency": h["currency"]},
            "allocation": h["allocation"],
        }
        for h in conn.execute(
            "SELECT * FROM investment_holding WHERE snapshot_id=%s ORDER BY market_value DESC",
            (snapshot_id,),
        )
    ]
    return {
        "id": r["id"],
        "as_of_date": r["as_of_date"],
        "scheme": r["scheme"],
        "total": {"amount": r["total_value"], "currency": r["currency"]},
        "source": r["source"],
        "notes": r["notes"],
        "subaccounts": subs,
        "holdings": holdings,
    }
