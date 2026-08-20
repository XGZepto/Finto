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
    "card": "COALESCE(t.card_id, '(primary)')",
    # Supplementary spend is separable here: primary vs (supp) labelled by name.
    "cardholder": (
        "CASE WHEN c.id IS NULL OR c.is_supplementary = 0 THEN 'You' "
        "ELSE c.cardholder_name || ' (supp)' END"
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
        clauses.append(f"{column} IN ({','.join(['%s'] * len(values))})")
        params.extend(values)

    if f.get("from"):
        clauses.append("t.txn_date >= %s")
        params.append(str(f["from"]))
    if f.get("to"):
        clauses.append("t.txn_date <= %s")
        params.append(str(f["to"]))
    if f.get("months"):
        months = [str(month) for month in f["months"]]
        if any(len(month) != 7 or month[4] != "-" or not month.replace("-", "").isdigit()
               for month in months):
            raise ValueError("months must use YYYY-MM")
        in_clause("substr(t.txn_date, 1, 7)", months)
    if f.get("accounts"):
        in_clause("t.account_id", f["accounts"])
    if f.get("cards"):
        in_clause("t.card_id", f["cards"])
    if f.get("cardholders"):
        requested = list(f["cardholders"])
        primary = "You" in requested
        named = [name.removesuffix(" (supp)") for name in requested if name != "You"]
        parts: list[str] = []
        if primary:
            parts.append(
                "(t.card_id IS NULL OR t.card_id IN "
                "(SELECT id FROM card WHERE is_supplementary=0))"
            )
        if named:
            ph = ",".join(["%s"] * len(named))
            parts.append(
                f"t.card_id IN (SELECT id FROM card WHERE cardholder_name IN ({ph}))"
            )
            params.extend(named)
        clauses.append("(" + " OR ".join(parts) + ")")
    if f.get("institutions"):
        in_clause("a.institution_id", f["institutions"])
    if f.get("categories"):
        in_clause("COALESCE(t.category, '(uncategorised)')", f["categories"])
    if f.get("kinds"):
        in_clause("t.kind", f["kinds"])
    if f.get("currency"):
        clauses.append("t.currency_booked = %s")
        params.append(f["currency"])
    if f.get("minAmount") is not None:
        clauses.append("t.amount_booked >= %s")
        params.append(int(f["minAmount"]))
    if f.get("maxAmount") is not None:
        clauses.append("t.amount_booked <= %s")
        params.append(int(f["maxAmount"]))
    if f.get("uncategorisedOnly"):
        clauses.append("t.category IS NULL")
    if f.get("installmentsOnly"):
        clauses.append("t.installment_plan_id IS NOT NULL")
    if not f.get("includeTransfers"):
        scoped_accounts = list(f.get("accounts") or [])
        if f.get("boundaryTransfers") and scoped_accounts:
            # A linked transfer only disappears when both sides are inside the
            # reporting scope. If its peer is outside, this leg crossed the
            # scope boundary and belongs in the scoped cash-flow result.
            placeholders = ",".join(["%s"] * len(scoped_accounts))
            clauses.append(
                "(t.transfer_group_id IS NULL OR NOT EXISTS ("
                "SELECT 1 FROM transfer_leg boundary_leg "
                "JOIN txn boundary_peer ON boundary_peer.id=boundary_leg.txn_id "
                "WHERE boundary_leg.transfer_group_id=t.transfer_group_id "
                "AND boundary_peer.id<>t.id "
                f"AND boundary_peer.account_id IN ({placeholders})))"
            )
            params.extend(scoped_accounts)
        else:
            clauses.append("t.transfer_group_id IS NULL")
    # Exact detail lookup: "travel.passenger_name=ALEX EXAMPLE". This is what
    # txn_detail exists for — a JSON blob cannot answer "every flight booked
    # for this passenger", and `q` only does substrings across every key.
    for tag in f.get("tags") or []:
        clauses.append(
            "EXISTS (SELECT 1 FROM txn_tag tg WHERE tg.txn_id = t.id AND tg.tag = %s)")
        params.append(tag)

    for pair in f.get("detail") or []:
        key, _, value = str(pair).partition("=")
        clauses.append(
            "EXISTS (SELECT 1 FROM txn_detail d WHERE d.txn_id = t.id "
            "        AND d.key = %s" + (" AND d.value = %s" if value else "") + ")")
        params.append(key)
        if value:
            params.append(value)

    for term in str(f.get("q") or "").split():
        clauses.append("t.search_text ILIKE %s")
        params.append(f"%{term}%")

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
        "WHERE d.key = %s AND t.duplicate_of_id IS NULL AND t.status <> 'void' "
        "GROUP BY d.value ORDER BY transactions DESC, d.value LIMIT %s",
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


def flows(conn, *, filters: dict | None = None, to_currency: str = "USD") -> dict:
    """Where money moved: between your own accounts, and across the boundary.

    A transfer between accounts you own is the same money in a different place,
    so it is counted apart from what actually entered or left your control.
    """
    target = to_currency.upper()
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

    external_accounts = conn.execute(f"""
        SELECT t.account_id, t.currency_booked AS currency,
               SUM(CASE WHEN t.amount_booked > 0 THEN t.amount_booked ELSE 0 END) AS in_minor,
               SUM(CASE WHEN t.amount_booked < 0 THEN -t.amount_booked ELSE 0 END) AS out_minor,
               COUNT(*) AS moves
        FROM txn t JOIN account a ON a.id = t.account_id
        WHERE {where} AND t.transfer_group_id IS NULL
        GROUP BY 1, 2 ORDER BY 2, GREATEST(
          SUM(CASE WHEN t.amount_booked > 0 THEN t.amount_booked ELSE 0 END),
          SUM(CASE WHEN t.amount_booked < 0 THEN -t.amount_booked ELSE 0 END)
        ) DESC
    """, params).fetchall()

    # Charts compare accounts on one scale. Convert monthly aggregates (rather
    # than a whole-history lump) so each period uses a contemporaneous rate.
    monthly_accounts = conn.execute(f"""
        SELECT t.account_id, substr(t.txn_date, 1, 7) AS month,
               t.currency_booked AS currency,
               SUM(CASE WHEN t.amount_booked > 0 THEN t.amount_booked ELSE 0 END) AS in_minor,
               SUM(CASE WHEN t.amount_booked < 0 THEN -t.amount_booked ELSE 0 END) AS out_minor,
               COUNT(*) AS moves
        FROM txn t JOIN account a ON a.id = t.account_id
        WHERE {where} AND t.transfer_group_id IS NULL
        GROUP BY 1, 2, 3
    """, params).fetchall()
    monthly_internal = conn.execute(f"""
        SELECT src.account_id AS from_account, dst.account_id AS to_account,
               substr(src.txn_date, 1, 7) AS month,
               src.currency_booked AS currency, COUNT(*) AS moves,
               SUM(-src.amount_booked) AS amount_minor
        FROM transfer_leg lo
        JOIN transfer_leg li ON li.transfer_group_id=lo.transfer_group_id
                            AND li.role='in'
        JOIN txn src ON src.id=lo.txn_id
        JOIN txn dst ON dst.id=li.txn_id
        WHERE lo.role='out' AND src.account_id<>dst.account_id
          AND src.id IN (SELECT t.id FROM txn t JOIN account a ON a.id=t.account_id
                         WHERE {where})
        GROUP BY 1, 2, 3, 4
    """, params).fetchall()
    monthly_external_nodes = conn.execute(f"""
        SELECT t.account_id, substr(t.txn_date, 1, 7) AS month,
               t.currency_booked AS currency,
               COALESCE(t.category, t.kind, '(uncategorised)') AS bucket,
               SUM(CASE WHEN t.amount_booked > 0 THEN t.amount_booked ELSE 0 END) AS in_minor,
               SUM(CASE WHEN t.amount_booked < 0 THEN -t.amount_booked ELSE 0 END) AS out_minor,
               COUNT(*) AS moves
        FROM txn t JOIN account a ON a.id=t.account_id
        WHERE {where} AND t.transfer_group_id IS NULL
        GROUP BY 1, 2, 3, 4
    """, params).fetchall()
    from decimal import Decimal

    from .fx import convert
    from .models import Money, minor_exponent
    normalised: dict[str, dict] = {}
    normalised_internal: dict[tuple[str, str], dict] = {}
    normalised_external_nodes: dict[tuple[str, str], dict] = {}
    unconvertible: set[str] = set()
    rates: dict[tuple[str, str], Any] = {}
    for r in monthly_accounts:
        key = (r["currency"], r["month"])
        rate = rates.get(key)
        if rate is None:
            rate = convert(
                conn, Money(amount=0, currency=r["currency"]), target,
                f"{r['month']}-15", nearest=True)
            rates[key] = rate
        if not rate.ok or rate.rate is None:
            unconvertible.add(r["currency"])
            continue
        scale = Decimal(10) ** (
            minor_exponent(target) - minor_exponent(r["currency"])
        )
        incoming = int((Decimal(r["in_minor"]) * rate.rate * scale).quantize(Decimal(1)))
        outgoing = int((Decimal(r["out_minor"]) * rate.rate * scale).quantize(Decimal(1)))
        item = normalised.setdefault(
            r["account_id"], {"in": 0, "out": 0, "moves": 0})
        item["in"] += incoming
        item["out"] += outgoing
        item["moves"] += r["moves"]

    for r in monthly_internal:
        key = (r["currency"], r["month"])
        rate = rates.get(key)
        if rate is None:
            rate = convert(
                conn, Money(amount=0, currency=r["currency"]), target,
                f"{r['month']}-15", nearest=True)
            rates[key] = rate
        if not rate.ok or rate.rate is None:
            unconvertible.add(r["currency"])
            continue
        scale = Decimal(10) ** (
            minor_exponent(target) - minor_exponent(r["currency"])
        )
        amount = int((Decimal(r["amount_minor"]) * rate.rate * scale).quantize(Decimal(1)))
        pair = normalised_internal.setdefault(
            (r["from_account"], r["to_account"]), {"amount": 0, "moves": 0})
        pair["amount"] += amount
        pair["moves"] += r["moves"]

    for r in monthly_external_nodes:
        key = (r["currency"], r["month"])
        rate = rates.get(key)
        if rate is None:
            rate = convert(
                conn, Money(amount=0, currency=r["currency"]), target,
                f"{r['month']}-15", nearest=True)
            rates[key] = rate
        if not rate.ok or rate.rate is None:
            unconvertible.add(r["currency"])
            continue
        scale = Decimal(10) ** (
            minor_exponent(target) - minor_exponent(r["currency"])
        )
        incoming = int((Decimal(r["in_minor"]) * rate.rate * scale).quantize(Decimal(1)))
        outgoing = int((Decimal(r["out_minor"]) * rate.rate * scale).quantize(Decimal(1)))
        node = normalised_external_nodes.setdefault(
            (r["account_id"], r["bucket"]), {"in": 0, "out": 0, "moves": 0})
        node["in"] += incoming
        node["out"] += outgoing
        node["moves"] += r["moves"]

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
        "external_accounts": [{
            "account_id": r["account_id"], "currency": r["currency"],
            "moves": r["moves"],
            "in": money(r["in_minor"], r["currency"]),
            "out": money(-r["out_minor"], r["currency"]),
            "net": money(r["in_minor"] - r["out_minor"], r["currency"]),
        } for r in external_accounts],
        "normalised": {
            "currency": target,
            "unconvertible_currencies": sorted(unconvertible),
            "external_accounts": [{
                "account_id": account_id, "currency": target,
                "moves": item["moves"],
                "in": money(item["in"], target),
                "out": money(-item["out"], target),
                "net": money(item["in"] - item["out"], target),
            } for account_id, item in normalised.items()],
            "internal": [{
                "from_account": pair[0], "to_account": pair[1],
                "moves": item["moves"], "amount": money(item["amount"], target),
            } for pair, item in normalised_internal.items()],
            "external_nodes": [{
                "account_id": pair[0], "bucket": pair[1], "moves": item["moves"],
                "in": money(item["in"], target),
                "out": money(-item["out"], target),
            } for pair, item in normalised_external_nodes.items()],
        },
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
        GROUP BY t.account_id, t.currency_booked,
                 a.display_name, a.institution_id, a.account_type
        ORDER BY a.display_name, t.currency_booked
    """.format(as_of="AND t.txn_date <= %s" if as_of else "")
    params = (as_of,) if as_of else ()

    for r in conn.execute(sql, params):
        assertion = conn.execute(
            "SELECT balance, as_of_date FROM balance_assertion "
            "WHERE account_id=%s AND currency=%s " +
            ("AND as_of_date <= %s " if as_of else "") +
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

    # Investment values are point-in-time valuations, not the sum of cash
    # contributions. The latest snapshot for each scheme therefore replaces the
    # balance of its subaccounts while leaving their transaction-derived flow
    # fields intact.
    snapshot_sql = """
        WITH ranked AS (
            SELECT id, as_of_date,
                   ROW_NUMBER() OVER (
                       PARTITION BY scheme ORDER BY as_of_date DESC, created_at DESC
                   ) AS snapshot_rank
            FROM investment_snapshot
            {as_of}
        )
        SELECT b.account_id, a.display_name AS account_name, a.institution_id,
               a.account_type, b.currency, b.balance, s.as_of_date
        FROM ranked s
        JOIN investment_subaccount_balance b ON b.snapshot_id=s.id
        JOIN account a ON a.id=b.account_id
        WHERE s.snapshot_rank=1
    """.format(as_of="WHERE as_of_date <= %s" if as_of else "")
    existing = {(r["account_id"], r["currency"]): r for r in rows}
    for valuation in conn.execute(snapshot_sql, (as_of,) if as_of else ()):
        key = (valuation["account_id"], valuation["currency"])
        movement = existing.get(key)
        zero = money(0, valuation["currency"])
        existing[key] = {
            "account_id": valuation["account_id"],
            "account_name": valuation["account_name"],
            "institution_id": valuation["institution_id"],
            "account_type": valuation["account_type"],
            "currency": valuation["currency"],
            "txn_count": movement["txn_count"] if movement else 0,
            "balance": money(valuation["balance"], valuation["currency"]),
            "net": movement["net"] if movement else zero,
            "inflow": movement["inflow"] if movement else zero,
            "outflow": movement["outflow"] if movement else zero,
            "basis": "investment_snapshot",
            "basis_date": valuation["as_of_date"],
            "first_date": movement["first_date"] if movement else None,
            "last_date": movement["last_date"] if movement else None,
        }
    return sorted(existing.values(), key=lambda r: (r["account_name"], r["currency"]))


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
    where, params = build_where({**(filters or {}), "boundaryTransfers": True})

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
               SUM(CASE
                   WHEN t.amount_booked > 0 AND (t.refund_of_id IS NOT NULL OR t.kind='refund')
                     THEN -t.amount_booked
                   WHEN t.amount_booked < 0 THEN -t.amount_booked ELSE 0 END) AS spend_minor,
               SUM(CASE WHEN t.amount_booked > 0
                              AND t.refund_of_id IS NULL AND t.kind<>'refund'
                        THEN t.amount_booked ELSE 0 END) AS income_minor
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
    where, params = build_where({**(filters or {}), "boundaryTransfers": True})
    sql = f"""
        SELECT t.currency_booked AS currency, COUNT(*) AS txn_count,
               SUM(t.amount_booked) AS net_minor,
               SUM(CASE
                   WHEN t.amount_booked > 0 AND (t.refund_of_id IS NOT NULL OR t.kind='refund')
                     THEN -t.amount_booked
                   WHEN t.amount_booked < 0 THEN -t.amount_booked ELSE 0 END) AS spend_minor,
               SUM(CASE WHEN t.amount_booked > 0
                              AND t.refund_of_id IS NULL AND t.kind<>'refund'
                        THEN t.amount_booked ELSE 0 END) AS income_minor,
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

    # Later pages only need the next rows. Recomputing filter aggregates and
    # attaching details to OFFSET-skipped rows is what made mobile infinite
    # scroll look stuck.
    aggregates = int(offset) == 0
    review = review_totals(conn, filters=filters) if aggregates else None
    total = review["total"] if review is not None else None

    sql = f"""
        SELECT t.*, a.display_name AS account_name, a.institution_id,
               a.primary_currency AS account_currency,
               c.cardholder_name, c.last4,
               COALESCE(detail_rows.details, '{{}}'::jsonb) AS page_details,
               COALESCE(tag_rows.tags, '[]'::jsonb) AS page_tags
        FROM (
          SELECT t.id
          FROM txn t
          JOIN account a ON a.id = t.account_id
          WHERE {where}
          ORDER BY {order} {dirn}, t.id
          LIMIT %s OFFSET %s
        ) page
        JOIN txn t ON t.id = page.id
        JOIN account a ON a.id = t.account_id
        LEFT JOIN card c ON c.id = t.card_id
        LEFT JOIN LATERAL (
          SELECT jsonb_object_agg(d.key, d.value) AS details
          FROM txn_detail d
          WHERE d.txn_id=t.id AND d.key NOT LIKE 'raw.%%'
        ) detail_rows ON TRUE
        LEFT JOIN LATERAL (
          SELECT jsonb_agg(tg.tag ORDER BY tg.tag) AS tags
          FROM txn_tag tg WHERE tg.txn_id=t.id
        ) tag_rows ON TRUE
        ORDER BY {order} {dirn}, t.id
    """
    rows = list(conn.execute(sql, [*params, int(limit), int(offset)]))
    payload = {
        "total": total,
        "limit": limit,
        "offset": offset,
        "items": [
            _txn_dict(r, r["page_details"] or {}, r["page_tags"] or [])
            for r in rows
        ],
    }
    if aggregates:
        payload["totals"] = totals(conn, filters=filters)
        payload["review"] = review
    return payload


def review_totals(conn, *, filters: dict | None = None) -> dict:
    """Review-state denominator for the same rows the ledger is showing."""
    where, params = build_where(filters)
    row = conn.execute(
        f"""SELECT COUNT(*) AS total,
                   COUNT(*) FILTER (WHERE t.review_state='unreviewed') AS unreviewed,
                   COUNT(*) FILTER (WHERE t.review_state='confirmed') AS confirmed,
                   COUNT(*) FILTER (WHERE t.review_state='flagged') AS flagged
              FROM txn t JOIN account a ON a.id=t.account_id
             WHERE {where}""",
        params,
    ).fetchone()
    return {key: int(row[key] or 0) for key in ("total", "unreviewed", "confirmed", "flagged")}


def _txn_dict(r, details: dict[str, str], tags: list[str] | None = None) -> dict:
    return {
        "id": r["id"],
        "date": r["txn_date"],
        "posted_date": r["posted_date"],
        "account_id": r["account_id"],
        "account_name": r["account_name"],
        "account_currency": r["account_currency"],
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
        "       a.primary_currency AS account_currency, "
        "       c.cardholder_name, c.last4 "
        "FROM txn t JOIN account a ON a.id=t.account_id "
        "LEFT JOIN card c ON c.id=t.card_id WHERE t.id=%s", (txn_id,)).fetchone()
    if r is None:
        return None

    details = {d["key"]: d["value"] for d in conn.execute(
        "SELECT key, value FROM txn_detail WHERE txn_id=%s", (txn_id,))}
    from . import db as _dbm
    out = _txn_dict(r, details, _dbm.load_tags(conn, [txn_id]).get(txn_id, []))

    raw = conn.execute(
        "SELECT rr.payload, sf.source_path, sf.parser_id, sf.imported_at "
        "FROM raw_record rr JOIN statement_file sf ON sf.id = rr.statement_file_id "
        "WHERE rr.id = %s", (r["raw_record_id"],)).fetchone() if r["raw_record_id"] else None
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
            "WHERE tl.transfer_group_id = %s", (r["transfer_group_id"],))]
    related = []
    if r["refund_of_id"]:
        row = conn.execute(
            "SELECT 'purchase' AS relation, id, description_raw, amount_booked, "
            "currency_booked, account_id, txn_date FROM txn WHERE id=%s",
            (r["refund_of_id"],),
        ).fetchone()
        if row:
            related.append(dict(row))
    related.extend(dict(x) for x in conn.execute(
        "SELECT 'refund' AS relation, id, description_raw, amount_booked, "
        "currency_booked, account_id, txn_date FROM txn WHERE refund_of_id=%s",
        (txn_id,),
    ))
    if r["duplicate_of_id"]:
        row = conn.execute(
            "SELECT 'canonical' AS relation, id, description_raw, amount_booked, "
            "currency_booked, account_id, txn_date FROM txn WHERE id=%s",
            (r["duplicate_of_id"],),
        ).fetchone()
        if row:
            related.append(dict(row))
    related.extend(dict(x) for x in conn.execute(
        "SELECT 'duplicate' AS relation, id, description_raw, amount_booked, "
        "currency_booked, account_id, txn_date FROM txn WHERE duplicate_of_id=%s",
        (txn_id,),
    ))
    if related:
        out["related_transactions"] = related
    return out


# ---------------------------------------------------------------------------
# Facets — populate filter controls
# ---------------------------------------------------------------------------

def facets(conn) -> dict:
    row = conn.execute("""
        SELECT
          COALESCE((
            SELECT jsonb_agg(to_jsonb(items) ORDER BY items.display_name)
            FROM (
              SELECT id,display_name,institution_id,account_type,primary_currency
              FROM account
            ) items
          ), '[]'::jsonb) AS accounts,
          COALESCE((
            SELECT jsonb_agg(to_jsonb(items) ORDER BY items.cardholder_name)
            FROM (
              SELECT id,account_id,cardholder_name,last4,replaces_card_id
              FROM card
            ) items
          ), '[]'::jsonb) AS cards,
          COALESCE((
            SELECT jsonb_agg(to_jsonb(items) ORDER BY items.display_name)
            FROM (SELECT id,display_name,country FROM institution) items
          ), '[]'::jsonb) AS institutions,
          COALESCE((
            SELECT jsonb_agg(item_value ORDER BY item_value)
            FROM (
              SELECT DISTINCT category AS item_value
              FROM category_definition WHERE active=1
            ) items
          ), '[]'::jsonb) AS categories,
          COALESCE((
            SELECT jsonb_agg(item_value ORDER BY item_value)
            FROM (
              SELECT DISTINCT currency AS item_value FROM account_currency
            ) items
          ), '[]'::jsonb) AS currencies,
          COALESCE((
            SELECT jsonb_agg(item_value ORDER BY item_value)
            FROM (SELECT key AS item_value FROM detail_key_catalog) items
          ), '[]'::jsonb) AS detail_keys,
          (
            SELECT jsonb_build_object(
              'min_date', MIN(txn_date),
              'max_date', MAX(txn_date)
            )
            FROM txn
          ) AS date_range
    """).fetchone()
    return {
        "accounts": row["accounts"],
        "cards": row["cards"],
        "institutions": row["institutions"],
        "categories": row["categories"],
        "kinds": [
            "purchase", "refund", "fee", "interest", "reward",
            "cc_payment", "transfer", "atm", "fx_conversion",
            "income", "adjustment", "installment",
            "installment_origination", "unknown",
        ],
        "currencies": row["currencies"],
        "detail_keys": row["detail_keys"],
        "date_range": row["date_range"],
    }


def stats(conn) -> dict:
    def one(sql: str):
        return conn.execute(sql).fetchone()["count_value"]

    return {
        "transactions": one("SELECT COUNT(*) AS count_value FROM v_ledger"),
        "suppressed_duplicates":
            one("SELECT COUNT(*) AS count_value FROM txn WHERE duplicate_of_id IS NOT NULL"),
        "transfer_groups": one("SELECT COUNT(*) AS count_value FROM transfer_group"),
        "installment_plans": one("SELECT COUNT(*) AS count_value FROM installment_plan"),
        "open_duplicate_candidates":
            one("SELECT COUNT(*) AS count_value FROM duplicate_candidate "
                "WHERE resolution='open'"),
        "open_transfer_candidates":
            one("SELECT COUNT(*) AS count_value FROM transfer_candidate "
                "WHERE resolution='open'"),
        "open_installment_candidates":
            one("SELECT COUNT(*) AS count_value FROM installment_candidate "
                "WHERE resolution='open'"),
        "uncategorised": one(
            "SELECT COUNT(*) AS count_value FROM v_ledger WHERE category IS NULL"),
        "positions": positions(conn),
    }
