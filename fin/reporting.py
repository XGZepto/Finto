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

from datetime import date
from typing import Any, Iterable, Sequence

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
    "kind": "t.kind",
    "currency": "t.currency_booked",
}


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
    if f.get("q"):
        clauses.append(
            "(t.description_raw LIKE ? OR t.description_norm LIKE ? "
            " OR COALESCE(t.merchant,'') LIKE ? OR COALESCE(t.counterparty,'') LIKE ? "
            " OR EXISTS (SELECT 1 FROM txn_detail d "
            "            WHERE d.txn_id = t.id AND d.value LIKE ?))")
        like = f"%{f['q']}%"
        params.extend([like] * 5)

    return " AND ".join(clauses), params


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
            "ORDER BY as_of_date DESC LIMIT 1",
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
    if group_by not in GROUP_BY_SQL:
        raise ValueError(f"unknown group_by: {group_by}")
    expr = GROUP_BY_SQL[group_by]
    where, params = build_where(filters)

    sql = f"""
        SELECT {expr} AS bucket, t.currency_booked AS currency,
               COUNT(*) AS txn_count,
               SUM(t.amount_booked) AS net_minor,
               SUM(CASE WHEN t.amount_booked < 0 THEN -t.amount_booked ELSE 0 END) AS spend_minor,
               SUM(CASE WHEN t.amount_booked > 0 THEN t.amount_booked ELSE 0 END) AS income_minor
        FROM txn t JOIN account a ON a.id = t.account_id
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

    return {
        "total": total,
        "limit": limit,
        "offset": offset,
        "items": [_txn_dict(r, details.get(r["id"], {})) for r in rows],
    }


def _txn_dict(r, details: dict[str, str]) -> dict:
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
        "native": (money(r["amount_native"], r["currency_native"])
                   if r["amount_native"] is not None else None),
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
    out = _txn_dict(r, details)

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
