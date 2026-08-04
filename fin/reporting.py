"""Queries behind the CLI and the API.

Everything here returns plain dicts, so the CLI renders them as text and the API
serialises them as JSON without either owning the SQL.

Two rules run through the whole module:

**Money leaves as integer minor units plus a currency code.** Never a float,
never a pre-formatted string. The schema is built on integer minor units to
avoid float error; emitting `1234.56` hands that error to whatever consumes it.

**Positions are per currency, never summed across currencies.** A balance is
only meaningful in a currency the account actually settles in. Adding HKD to USD
does not produce a balance, it produces a number with no referent. A normalised
"everything in USD" view is a *presentation* choice — it needs a rate, a date,
and a label saying so — and belongs in the client, not here.
"""

from __future__ import annotations

from collections.abc import Sequence
from typing import Any

# Aggregation dimensions the summary endpoint understands. Mapping to SQL here
# rather than accepting caller-supplied column names keeps this injection-proof.
GROUP_BY_SQL: dict[str, str] = {
    "month": "substr(t.txn_date, 1, 7)",
    "quarter": "substr(t.txn_date, 1, 4) || '-Q' || "
               "((CAST(substr(t.txn_date, 6, 2) AS INTEGER) + 2) / 3)",
    "year": "substr(t.txn_date, 1, 4)",
    "day": "t.txn_date",
    "category": "COALESCE(t.category, '(uncategorised)')",
    "subcategory": "COALESCE(t.subcategory, '(none)')",
    "merchant": "COALESCE(t.merchant, t.description_norm)",
    "account": "t.account_id",
    "institution": "a.institution_id",
    "card": "COALESCE(t.card_id, '(unattributed)')",
    # Supplementary spend is separable here: primary vs (supp) labelled by name.
    "cardholder": (
        "CASE WHEN c.id IS NULL THEN '(unattributed)' "
        "WHEN c.is_supplementary = 1 THEN c.cardholder_name || ' (supp)' "
        "ELSE c.cardholder_name END"
    ),
    "kind": "t.kind",
    "currency": "t.currency_booked",
}


def rollup(conn, rows, *, fields, to_currency, key=None, on=None):
    """Collapse per-currency rows into one figure per money field, in `to_currency`.

    Every aggregation groups by currency, because HKD and USD cannot be added
    natively. This sums them once converted, so each bucket — or the whole set,
    when `key` is None — carries a single normalised number instead of leaving
    the reader to total the currencies. Rows in a currency with no rate are
    named in `unconvertible` rather than dropped in silence.
    """
    from .fx import convert
    from .models import Money

    to = to_currency.upper()
    groups: dict[Any, dict[str, int]] = {}
    unconvertible: set[str] = set()
    for r in rows:
        bucket = r.get(key) if key else None
        acc = groups.setdefault(bucket, {f: 0 for f in fields})
        for f in fields:
            m = r.get(f)
            if not isinstance(m, dict):
                continue
            c = convert(conn, Money(amount=m["amount"], currency=m["currency"]),
                        to, on, nearest=True)
            if c.ok:
                acc[f] += c.amount
            elif m["amount"]:
                unconvertible.add(m["currency"])

    out = [{**({key: b} if key else {}), **{f: money(v, to) for f, v in vals.items()}}
           for b, vals in groups.items()]
    result = {"unconvertible_currencies": sorted(unconvertible)}
    if key:
        result["rows"] = out
    else:
        result.update(out[0] if out else {f: money(0, to) for f in fields})
    return result


def money(amount: int | None, currency: str) -> dict[str, Any]:
    return {"amount": int(amount or 0), "currency": currency}


# ---------------------------------------------------------------------------
# Filtering
# ---------------------------------------------------------------------------

def build_where(f: dict[str, Any] | None) -> tuple[str, list[Any]]:
    """Translate a LedgerFilter dict into SQL. Every value is bound, never inlined.

    Transfers are excluded by default: money moved between your own accounts is
    not spending, and counting it as such is the specific error transfers.py
    exists to prevent. The caller has to ask for them explicitly.
    """
    f = f or {}
    clauses = ["t.duplicate_of_id IS NULL", "t.status <> 'void'"]
    params: list[Any] = []

    def in_clause(column: str, values: Sequence[str]) -> None:
        clauses.append(f"{column} IN ({','.join('?' * len(values))})")
        params.extend(values)

    if f.get("from"):
        clauses.append("t.txn_date >= ?")
        params.append(str(f["from"]))
    if f.get("to"):
        clauses.append("t.txn_date <= ?")
        params.append(str(f["to"]))
    if f.get("accounts"):
        in_clause("t.account_id", f["accounts"])
    if f.get("cards"):
        in_clause("t.card_id", f["cards"])
    if f.get("institutions"):
        in_clause("a.institution_id", f["institutions"])
    if f.get("categories"):
        in_clause("COALESCE(t.category, '(uncategorised)')", f["categories"])
    if f.get("kinds"):
        in_clause("t.kind", f["kinds"])
    if f.get("currency"):
        clauses.append("t.currency_booked = ?")
        params.append(f["currency"])
    if f.get("minAmount") is not None:
        clauses.append("t.amount_booked >= ?")
        params.append(int(f["minAmount"]))
    if f.get("maxAmount") is not None:
        clauses.append("t.amount_booked <= ?")
        params.append(int(f["maxAmount"]))
    if f.get("uncategorisedOnly"):
        clauses.append("t.category IS NULL")
    if f.get("installmentsOnly"):
        clauses.append("t.installment_plan_id IS NOT NULL")
    if not f.get("includeTransfers"):
        clauses.append("t.transfer_group_id IS NULL")
    # Exact detail lookup: "travel.passenger_name=YIXIANG ZHOU". This is what
    # txn_detail exists for — a JSON blob cannot answer "every flight booked
    # for this passenger", and `q` only does substrings across every key.
    for tag in f.get("tags") or []:
        clauses.append(
            "EXISTS (SELECT 1 FROM txn_tag tg WHERE tg.txn_id = t.id AND tg.tag = ?)")
        params.append(tag)

    for pair in f.get("detail") or []:
        key, _, value = str(pair).partition("=")
        clauses.append(
            "EXISTS (SELECT 1 FROM txn_detail d WHERE d.txn_id = t.id "
            "        AND d.key = ?" + (" AND d.value = ?" if value else "") + ")")
        params.append(key)
        if value:
            params.append(value)

    for term in str(f.get("q") or "").split():
        clauses.append(
            "(t.description_raw LIKE ? OR t.description_norm LIKE ? "
            " OR COALESCE(t.merchant,'') LIKE ? OR COALESCE(t.counterparty,'') LIKE ? "
            " OR EXISTS (SELECT 1 FROM txn_detail d "
            "            WHERE d.txn_id = t.id AND d.value LIKE ?))")
        params.extend([f"%{term}%"] * 5)

    return " AND ".join(clauses), params


def detail_keys(conn) -> dict:
    """Which structured facts the ledger holds, and how common each is.

    Lets a client build a facet list without knowing in advance what the
    parsers found — the set grows as templates learn to read more.
    """
    return {"keys": [dict(r) for r in conn.execute(
        "SELECT key, COUNT(*) AS facts, COUNT(DISTINCT txn_id) AS transactions "
        "FROM txn_detail GROUP BY key ORDER BY transactions DESC, key")]}


def detail_values(conn, key: str, limit: int = 200) -> dict:
    return {"key": key, "values": [dict(r) for r in conn.execute(
        "SELECT d.value, COUNT(*) AS transactions FROM txn_detail d "
        "JOIN txn t ON t.id = d.txn_id "
        "WHERE d.key = ? AND t.duplicate_of_id IS NULL AND t.status <> 'void' "
        "GROUP BY d.value ORDER BY transactions DESC, d.value LIMIT ?",
        (key, int(limit)))]}


def composition(conn, *, dimension: str = "category", to_currency: str,
                filters: dict | None = None, limit: int = 8) -> dict:
    """Spend by `dimension` per month, normalised to one currency.

    Ranking spend needs a single unit, so this only exists converted. The top
    `limit` buckets over the whole span are tracked by name; the rest fold into
    "other", because a stacked chart with forty bands says nothing.
    """
    expr = GROUP_BY_SQL.get(dimension)
    if expr is None:
        raise ValueError(f"unknown dimension: {dimension}")

    where, params = build_where(filters)
    rows = conn.execute(f"""
        SELECT substr(t.txn_date, 1, 7) AS month, {expr} AS bucket,
               t.currency_booked AS currency,
               SUM(CASE WHEN t.amount_booked < 0 THEN -t.amount_booked ELSE 0 END) AS spend
        FROM txn t JOIN account a ON a.id = t.account_id
        LEFT JOIN card c ON c.id = t.card_id
        WHERE {where}
        GROUP BY 1, 2, 3
    """, params).fetchall()

    # Convert each (bucket, month) spend into the target currency, dropping what
    # has no rate rather than guessing one.
    from .fx import convert
    from .models import Money
    per_bucket: dict[str, int] = {}
    grid: dict[tuple[str, str], int] = {}
    months: set[str] = set()
    unconvertible: set[str] = set()
    for r in rows:
        c = convert(conn, Money(amount=r["spend"], currency=r["currency"]),
                    to_currency, f"{r['month']}-15", nearest=True)
        if not c.ok:
            unconvertible.add(r["currency"])
            continue
        months.add(r["month"])
        grid[(r["bucket"], r["month"])] = grid.get((r["bucket"], r["month"]), 0) + c.amount
        per_bucket[r["bucket"]] = per_bucket.get(r["bucket"], 0) + c.amount

    top = [b for b, _ in sorted(per_bucket.items(), key=lambda kv: -kv[1])[:limit]]
    keep = set(top)
    ordered_months = sorted(months)
    series = {b: {m: 0 for m in ordered_months} for b in [*top, "other"]}
    for (bucket, month), amount in grid.items():
        series[bucket if bucket in keep else "other"][month] += amount

    buckets = [*top] + (["other"] if any(series["other"].values()) else [])
    return {
        "dimension": dimension,
        "currency": to_currency.upper(),
        "months": ordered_months,
        "series": [{
            "bucket": b,
            "total": sum(series[b].values()),
            "values": [series[b][m] for m in ordered_months],
        } for b in buckets],
        "unconvertible_currencies": sorted(unconvertible),
    }


def coverage(conn) -> dict:
    """Per account, month by month, what data backs it.

    All sources are the issuer's own; the distinction is whether a month is
    backed by a statement that printed a balance — so capture is proven — or
    only by an export or loose rows, which carry no balance to check against.
    A month with neither, inside the account's life, is a hole to go fill.
    """
    months = [r["m"] for r in conn.execute(
        "SELECT DISTINCT substr(txn_date, 1, 7) AS m FROM txn "
        "WHERE duplicate_of_id IS NULL AND status <> 'void' ORDER BY m")]
    if not months:
        return {"months": [], "accounts": []}

    # A PDF statement covers every month its period touches.
    stmt: dict[str, set[str]] = {}
    for r in conn.execute(
            "SELECT account_id, period_start, period_end, statement_date "
            "FROM statement_file WHERE file_format = 'pdf' AND account_id IS NOT NULL"):
        covered = stmt.setdefault(r["account_id"], set())
        if r["period_start"] and r["period_end"]:
            covered |= {m for m in months
                        if r["period_start"][:7] <= m <= r["period_end"][:7]}
        elif r["statement_date"]:
            covered.add(r["statement_date"][:7])

    active: dict[str, set[str]] = {}
    for r in conn.execute(
            "SELECT account_id, substr(txn_date, 1, 7) AS m, COUNT(*) AS n FROM txn "
            "WHERE duplicate_of_id IS NULL AND status <> 'void' GROUP BY 1, 2"):
        active.setdefault(r["account_id"], set()).add(r["m"])

    names = {r["id"]: r["display_name"]
             for r in conn.execute("SELECT id, display_name FROM account")}
    out = []
    for account_id in sorted(set(stmt) | set(active)):
        covered, seen = stmt.get(account_id, set()), active.get(account_id, set())
        # Before an account's first activity it did not exist, which is not a
        # gap. Statement coverage can still precede activity — an opening
        # balance the statement carries — so the account starts at the earlier
        # of the two.
        start = min([*seen, *covered], default=months[-1])
        cells = [
            "pre" if m < start
            else "statement" if m in covered
            else "export" if m in seen
            else "none"
            for m in months
        ]
        out.append({
            "account_id": account_id,
            "account_name": names.get(account_id, account_id),
            "cells": cells,
            "statement_months": sum(c == "statement" for c in cells),
            "export_months": sum(c == "export" for c in cells),
            "gap_months": sum(c == "none" for c in cells),
        })
    return {"months": months, "accounts": out}


def flows(conn, *, filters: dict | None = None) -> dict:
    """Where money moved: between your own accounts, and across the boundary.

    A transfer between accounts you own is the same money in a different place,
    so it is counted apart from what actually entered or left your control.
    """
    where, params = build_where({**(filters or {}), "includeTransfers": True})

    internal = conn.execute(f"""
        SELECT src.account_id AS from_account, dst.account_id AS to_account,
               src.currency_booked AS currency, COUNT(*) AS moves,
               SUM(-src.amount_booked) AS amount_minor
        FROM transfer_leg lo
        JOIN transfer_leg li ON li.transfer_group_id = lo.transfer_group_id
                            AND li.role = 'in'
        JOIN txn src ON src.id = lo.txn_id
        JOIN txn dst ON dst.id = li.txn_id
        WHERE lo.role = 'out' AND src.account_id <> dst.account_id
          AND src.id IN (SELECT t.id FROM txn t JOIN account a ON a.id = t.account_id
                         WHERE {where})
        GROUP BY 1, 2, 3
        ORDER BY amount_minor DESC
    """, params).fetchall()

    external = conn.execute(f"""
        SELECT t.currency_booked AS currency,
               SUM(CASE WHEN t.amount_booked > 0 THEN t.amount_booked ELSE 0 END) AS in_minor,
               SUM(CASE WHEN t.amount_booked < 0 THEN -t.amount_booked ELSE 0 END) AS out_minor,
               COUNT(*) AS moves
        FROM txn t JOIN account a ON a.id = t.account_id
        WHERE {where} AND t.transfer_group_id IS NULL
        GROUP BY 1 ORDER BY 1
    """, params).fetchall()

    return {
        "internal": [{
            "from_account": r["from_account"], "to_account": r["to_account"],
            "moves": r["moves"],
            "amount": money(r["amount_minor"], r["currency"]),
        } for r in internal],
        "external": [{
            "currency": r["currency"], "moves": r["moves"],
            "in": money(r["in_minor"], r["currency"]),
            "out": money(-r["out_minor"], r["currency"]),
            "net": money(r["in_minor"] - r["out_minor"], r["currency"]),
        } for r in external],
    }


# ---------------------------------------------------------------------------
# Positions
# ---------------------------------------------------------------------------

def positions(conn, *, as_of: str | None = None) -> list[dict]:
    """Balance per (account, currency).

    Prefers the statement's own closing balance when one is available for that
    currency — that figure comes from the bank and is authoritative. Falls back
    to summed movements, and says which basis was used, because the two answer
    different questions: a movement sum has no opening balance in it.
    """
    rows = []
    sql = """
        SELECT t.account_id, a.display_name AS account_name, a.institution_id,
               a.account_type, t.currency_booked AS currency,
               COUNT(*) AS txn_count,
               SUM(t.amount_booked) AS net_minor,
               SUM(CASE WHEN t.amount_booked < 0 THEN t.amount_booked ELSE 0 END) AS out_minor,
               SUM(CASE WHEN t.amount_booked > 0 THEN t.amount_booked ELSE 0 END) AS in_minor,
               MIN(t.txn_date) AS first_date, MAX(t.txn_date) AS last_date
        FROM txn t JOIN account a ON a.id = t.account_id
        WHERE t.duplicate_of_id IS NULL AND t.status <> 'void'
        {as_of}
        GROUP BY t.account_id, t.currency_booked
        ORDER BY a.display_name, t.currency_booked
    """.format(as_of="AND t.txn_date <= ?" if as_of else "")
    params = (as_of,) if as_of else ()

    for r in conn.execute(sql, params):
        assertion = conn.execute(
            "SELECT balance, as_of_date FROM balance_assertion "
            "WHERE account_id=? AND currency=? " +
            ("AND as_of_date <= ? " if as_of else "") +
            # A statement with no printed period dates its opening on the
            # statement day too, so the closing has to win that tie.
            "ORDER BY as_of_date DESC, "
            "CASE kind WHEN 'closing' THEN 0 WHEN 'running' THEN 1 ELSE 2 END "
            "LIMIT 1",
            (r["account_id"], r["currency"]) + ((as_of,) if as_of else ()),
        ).fetchone()

        if assertion is not None:
            balance, basis, basis_date = assertion["balance"], "statement", assertion["as_of_date"]
        else:
            balance, basis, basis_date = r["net_minor"], "movements", r["last_date"]

        rows.append({
            "account_id": r["account_id"],
            "account_name": r["account_name"],
            "institution_id": r["institution_id"],
            "account_type": r["account_type"],
            "currency": r["currency"],
            "txn_count": r["txn_count"],
            "balance": money(balance, r["currency"]),
            "net": money(r["net_minor"], r["currency"]),
            "inflow": money(r["in_minor"], r["currency"]),
            "outflow": money(r["out_minor"], r["currency"]),
            "basis": basis,
            "basis_date": basis_date,
            "first_date": r["first_date"],
            "last_date": r["last_date"],
        })
    return rows


def declared_currencies(conn) -> dict[str, list[str]]:
    """Settlement currencies per account, so a UI can show a zero position."""
    out: dict[str, list[str]] = {}
    for r in conn.execute(
            "SELECT account_id, currency FROM account_currency "
            "ORDER BY is_primary DESC, currency"):
        out.setdefault(r["account_id"], []).append(r["currency"])
    return out


# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------

def summary(conn, *, group_by: str = "month", filters: dict | None = None) -> list[dict]:
    """Aggregate the ledger along one dimension.

    Always grouped by currency as well as the requested dimension. A "total
    spend" that mixes currencies would be meaningless, so the shape of the
    result makes mixing impossible rather than merely discouraged.
    """
    if group_by != "tag" and group_by not in GROUP_BY_SQL:
        raise ValueError(f"unknown group_by: {group_by}")
    where, params = build_where(filters)

    # A tag fans out: a transaction under two tags counts under each. Every
    # other dimension is one value per row, so it groups on an expression.
    if group_by == "tag":
        bucket = "COALESCE(tg.tag, '(untagged)')"
        source = ("FROM txn t JOIN account a ON a.id = t.account_id "
                  "LEFT JOIN card c ON c.id = t.card_id "
                  "LEFT JOIN txn_tag tg ON tg.txn_id = t.id")
    else:
        bucket = GROUP_BY_SQL[group_by]
        source = ("FROM txn t JOIN account a ON a.id = t.account_id "
                  "LEFT JOIN card c ON c.id = t.card_id")

    sql = f"""
        SELECT {bucket} AS bucket, t.currency_booked AS currency,
               COUNT(*) AS txn_count,
               SUM(t.amount_booked) AS net_minor,
               SUM(CASE WHEN t.amount_booked < 0 THEN -t.amount_booked ELSE 0 END) AS spend_minor,
               SUM(CASE WHEN t.amount_booked > 0 THEN t.amount_booked ELSE 0 END) AS income_minor
        {source}
        WHERE {where}
        GROUP BY bucket, t.currency_booked
        ORDER BY bucket, t.currency_booked
    """
    return [{
        "bucket": r["bucket"],
        "currency": r["currency"],
        "txn_count": r["txn_count"],
        "net": money(r["net_minor"], r["currency"]),
        "spend": money(r["spend_minor"], r["currency"]),
        "income": money(r["income_minor"], r["currency"]),
    } for r in conn.execute(sql, params)]


def totals(conn, *, filters: dict | None = None) -> list[dict]:
    """Headline figures, one row per currency."""
    where, params = build_where(filters)
    sql = f"""
        SELECT t.currency_booked AS currency, COUNT(*) AS txn_count,
               SUM(t.amount_booked) AS net_minor,
               SUM(CASE WHEN t.amount_booked < 0 THEN -t.amount_booked ELSE 0 END) AS spend_minor,
               SUM(CASE WHEN t.amount_booked > 0 THEN t.amount_booked ELSE 0 END) AS income_minor,
               SUM(CASE WHEN t.category IS NULL THEN 1 ELSE 0 END) AS uncategorised
        FROM txn t JOIN account a ON a.id = t.account_id
        WHERE {where}
        GROUP BY t.currency_booked ORDER BY t.currency_booked
    """
    return [{
        "currency": r["currency"],
        "txn_count": r["txn_count"],
        "net": money(r["net_minor"], r["currency"]),
        "spend": money(r["spend_minor"], r["currency"]),
        "income": money(r["income_minor"], r["currency"]),
        "uncategorised": r["uncategorised"],
    } for r in conn.execute(sql, params)]


# ---------------------------------------------------------------------------
# Transactions
# ---------------------------------------------------------------------------

_SORTABLE = {
    "date": "t.txn_date", "amount": "t.amount_booked",
    "merchant": "COALESCE(t.merchant, t.description_norm)",
    "account": "t.account_id", "category": "t.category",
}


def transactions(conn, *, filters: dict | None = None, limit: int = 100,
                 offset: int = 0, sort: str = "date",
                 direction: str = "desc") -> dict:
    where, params = build_where(filters)
    order = _SORTABLE.get(sort, _SORTABLE["date"])
    dirn = "ASC" if str(direction).lower() == "asc" else "DESC"

    total = conn.execute(
        f"SELECT COUNT(*) n FROM txn t JOIN account a ON a.id=t.account_id "
        f"WHERE {where}", params).fetchone()["n"]

    sql = f"""
        SELECT t.*, a.display_name AS account_name, a.institution_id,
               c.cardholder_name, c.last4
        FROM txn t
        JOIN account a ON a.id = t.account_id
        LEFT JOIN card c ON c.id = t.card_id
        WHERE {where}
        ORDER BY {order} {dirn}, t.id
        LIMIT ? OFFSET ?
    """
    rows = list(conn.execute(sql, [*params, int(limit), int(offset)]))
    ids = [r["id"] for r in rows]

    details: dict[str, dict[str, str]] = {}
    if ids:
        q = ",".join("?" * len(ids))
        for d in conn.execute(
                f"SELECT txn_id, key, value FROM txn_detail WHERE txn_id IN ({q})",
                ids):
            details.setdefault(d["txn_id"], {})[d["key"]] = d["value"]

    from . import db as _dbm
    tags = _dbm.load_tags(conn, ids)

    return {
        "total": total,
        "limit": limit,
        "offset": offset,
        "items": [_txn_dict(r, details.get(r["id"], {}), tags.get(r["id"], []))
                  for r in rows],
        # The filtered set, not the page: the question a filter asks is what
        # the matching rows come to.
        "totals": totals(conn, filters=filters),
    }


def _txn_dict(r, details: dict[str, str], tags: list[str] | None = None) -> dict:
    return {
        "id": r["id"],
        "date": r["txn_date"],
        "posted_date": r["posted_date"],
        "account_id": r["account_id"],
        "account_name": r["account_name"],
        "institution_id": r["institution_id"],
        "card_id": r["card_id"],
        "cardholder_name": r["cardholder_name"],
        "card_last4": r["last4"],
        "description": r["description_raw"],
        "merchant": r["merchant"],
        "counterparty": r["counterparty"],
        "booked": money(r["amount_booked"], r["currency_booked"]),
        # What the merchant charged, and the rate the issuer applied to get to
        # the booked amount. Both are printed on the statement; neither is
        # derived here, so a client can show the original charge exactly.
        "native": (money(r["amount_native"], r["currency_native"])
                   if r["amount_native"] is not None else None),
        "fx_rate": r["fx_rate"],
        "fx_fee": (money(r["fx_fee_booked"], r["currency_booked"])
                   if r["fx_fee_booked"] is not None else None),
        "kind": r["kind"],
        "category": r["category"],
        "subcategory": r["subcategory"],
        "status": r["status"],
        "transfer_group_id": r["transfer_group_id"],
        "installment_plan_id": r["installment_plan_id"],
        "installment_seq": r["installment_seq"],
        "refund_of_id": r["refund_of_id"],
        "external_ref": r["external_ref"],
        "review_state": r["review_state"],
        "notes": r["notes"],
        "details": details,
        "tags": tags or [],
    }


def transaction_detail(conn, txn_id: str) -> dict | None:
    """One transaction plus its provenance — the raw source row it came from."""
    r = conn.execute(
        "SELECT t.*, a.display_name AS account_name, a.institution_id, "
        "       c.cardholder_name, c.last4 "
        "FROM txn t JOIN account a ON a.id=t.account_id "
        "LEFT JOIN card c ON c.id=t.card_id WHERE t.id=?", (txn_id,)).fetchone()
    if r is None:
        return None

    details = {d["key"]: d["value"] for d in conn.execute(
        "SELECT key, value FROM txn_detail WHERE txn_id=?", (txn_id,))}
    from . import db as _dbm
    out = _txn_dict(r, details, _dbm.load_tags(conn, [txn_id]).get(txn_id, []))

    raw = conn.execute(
        "SELECT rr.payload, sf.source_path, sf.parser_id, sf.imported_at "
        "FROM raw_record rr JOIN statement_file sf ON sf.id = rr.statement_file_id "
        "WHERE rr.id = ?", (r["raw_record_id"],)).fetchone() if r["raw_record_id"] else None
    if raw:
        import json as _json
        out["provenance"] = {
            "source_path": raw["source_path"],
            "parser_id": raw["parser_id"],
            "imported_at": raw["imported_at"],
            "raw_row": _json.loads(raw["payload"]),
        }

    # The other leg(s) of a transfer, so the UI can show what this pairs with.
    if r["transfer_group_id"]:
        out["transfer_legs"] = [dict(x) for x in conn.execute(
            "SELECT tl.role, t.id, t.description_raw, t.amount_booked, "
            "       t.currency_booked, t.account_id, t.txn_date "
            "FROM transfer_leg tl JOIN txn t ON t.id = tl.txn_id "
            "WHERE tl.transfer_group_id = ?", (r["transfer_group_id"],))]
    return out


# ---------------------------------------------------------------------------
# Facets — populate filter controls
# ---------------------------------------------------------------------------

def facets(conn) -> dict:
    def col(sql: str) -> list:
        return [r[0] for r in conn.execute(sql) if r[0] is not None]

    return {
        "accounts": [dict(r) for r in conn.execute(
            "SELECT id, display_name, institution_id, account_type, primary_currency "
            "FROM account ORDER BY display_name")],
        "cards": [dict(r) for r in conn.execute(
            "SELECT id, account_id, cardholder_name, last4, replaces_card_id "
            "FROM card ORDER BY cardholder_name")],
        "institutions": [dict(r) for r in conn.execute(
            "SELECT id, display_name, country FROM institution ORDER BY display_name")],
        "categories": col("SELECT DISTINCT category FROM txn "
                          "WHERE category IS NOT NULL ORDER BY category"),
        "kinds": col("SELECT DISTINCT kind FROM txn ORDER BY kind"),
        "currencies": col("SELECT DISTINCT currency_booked FROM txn "
                          "ORDER BY currency_booked"),
        "detail_keys": col("SELECT DISTINCT key FROM txn_detail ORDER BY key"),
        "date_range": dict(conn.execute(
            "SELECT MIN(txn_date) AS min_date, MAX(txn_date) AS max_date "
            "FROM txn").fetchone() or {}),
    }


def stats(conn) -> dict:
    def one(sql: str):
        return conn.execute(sql).fetchone()[0]

    return {
        "transactions": one("SELECT COUNT(*) FROM v_ledger"),
        "suppressed_duplicates":
            one("SELECT COUNT(*) FROM txn WHERE duplicate_of_id IS NOT NULL"),
        "transfer_groups": one("SELECT COUNT(*) FROM transfer_group"),
        "installment_plans": one("SELECT COUNT(*) FROM installment_plan"),
        "open_duplicate_candidates":
            one("SELECT COUNT(*) FROM duplicate_candidate WHERE resolution='open'"),
        "open_transfer_candidates":
            one("SELECT COUNT(*) FROM transfer_candidate WHERE resolution='open'"),
        "open_installment_candidates":
            one("SELECT COUNT(*) FROM installment_candidate WHERE resolution='open'"),
        "uncategorised": one("SELECT COUNT(*) FROM v_ledger WHERE category IS NULL"),
        "unattributed_card_txns": one(
            "SELECT COUNT(*) FROM v_ledger WHERE card_id IS NULL AND account_id IN "
            "(SELECT DISTINCT account_id FROM card)"),
        "positions": positions(conn),
    }
