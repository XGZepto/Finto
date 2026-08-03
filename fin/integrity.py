"""Integrity checks.

The single most important thing this module does is answer: "did I actually
capture every transaction?"

Dedup can be perfect and transfer linking can be perfect while the ledger is
still wrong, because a parser silently skipped four rows it couldn't read. The
statement's own running balance is the independent check — it comes from the
bank, not from our parsing. If our transactions don't reproduce the balance
delta between two dates, something is missing.

This is why parsers should capture the balance column when one exists.
"""

from __future__ import annotations

import uuid
from datetime import datetime
from typing import Iterable

from .models import Money


def record_balance(conn, *, account_id: str, as_of, balance: Money,
                   source: str = "statement_running",
                   statement_file_id: str | None = None) -> None:
    conn.execute(
        "INSERT OR REPLACE INTO balance_assertion (id, account_id, as_of_date, "
        "balance, currency, source, statement_file_id) VALUES (?,?,?,?,?,?,?)",
        (str(uuid.uuid4()), account_id,
         as_of.isoformat() if hasattr(as_of, "isoformat") else str(as_of),
         balance.amount, balance.currency, source, statement_file_id),
    )


def check_account(conn, account_id: str) -> list[dict]:
    """Verify transactions reproduce the balance delta between assertions.

    Walks consecutive balance assertions and, for each interval, compares the
    bank's balance movement against the sum of transactions we hold. Any
    non-zero discrepancy means a dropped row, a duplicate we wrongly kept, or a
    sign error — all of which are invisible without this check.
    """
    assertions = list(conn.execute(
        "SELECT as_of_date, balance, currency FROM balance_assertion "
        "WHERE account_id=? ORDER BY as_of_date", (account_id,)))
    if len(assertions) < 2:
        return [{"account_id": account_id, "status": "insufficient_data",
                 "note": "need at least two balance assertions"}]

    out = []
    for prev, curr in zip(assertions, assertions[1:]):
        if prev["currency"] != curr["currency"]:
            continue
        expected = curr["balance"] - prev["balance"]
        row = conn.execute(
            "SELECT COALESCE(SUM(amount_booked), 0) AS total FROM txn "
            "WHERE account_id=? AND duplicate_of_id IS NULL AND status<>'void' "
            "AND currency_booked=? AND txn_date > ? AND txn_date <= ?",
            (account_id, curr["currency"], prev["as_of_date"], curr["as_of_date"])
        ).fetchone()
        actual = row["total"]
        discrepancy = actual - expected
        status = "ok" if discrepancy == 0 else "discrepancy"
        conn.execute(
            "INSERT INTO reconciliation_check (id, account_id, period_start, "
            "period_end, expected_delta, actual_delta, discrepancy, currency, "
            "status, checked_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
            (str(uuid.uuid4()), account_id, prev["as_of_date"], curr["as_of_date"],
             expected, actual, discrepancy, curr["currency"], status,
             datetime.now().isoformat()))
        out.append({
            "account_id": account_id,
            "period": f"{prev['as_of_date']} -> {curr['as_of_date']}",
            "expected_delta": expected / 100.0,
            "actual_delta": actual / 100.0,
            "discrepancy": discrepancy / 100.0,
            "currency": curr["currency"],
            "status": status,
        })
    conn.commit()
    return out


def check_all(conn) -> list[dict]:
    out = []
    for r in conn.execute("SELECT DISTINCT account_id FROM balance_assertion"):
        out.extend(check_account(conn, r["account_id"]))
    return out


# ---------------------------------------------------------------------------
# Structural invariants
# ---------------------------------------------------------------------------

def resolve_duplicate_chains(conn) -> int:
    """Collapse A->B->C duplicate chains so every pointer targets a root.

    Chains arise when reconcile runs repeatedly and a previously-suppressed row
    gets re-evaluated. Left alone, `duplicate_of_id` stops meaning "the row that
    replaced this one" and queries that join one level deep quietly miss rows.
    """
    links = {r["id"]: r["duplicate_of_id"] for r in conn.execute(
        "SELECT id, duplicate_of_id FROM txn WHERE duplicate_of_id IS NOT NULL")}
    fixed = 0
    for node in list(links):
        seen, cur = {node}, links[node]
        while cur in links and cur not in seen:
            seen.add(cur)
            cur = links[cur]
        if cur != links[node] and cur is not None:
            conn.execute("UPDATE txn SET duplicate_of_id=? WHERE id=?", (cur, node))
            fixed += 1
    conn.commit()
    return fixed


def find_violations(conn) -> list[dict]:
    """Structural problems that should never occur. Empty list is the goal."""
    problems: list[dict] = []

    checks = [
        ("self_duplicate",
         "SELECT COUNT(*) n FROM txn WHERE duplicate_of_id = id",
         "transaction marked as a duplicate of itself"),
        ("duplicate_points_to_duplicate",
         "SELECT COUNT(*) n FROM txn a JOIN txn b ON a.duplicate_of_id=b.id "
         "WHERE b.duplicate_of_id IS NOT NULL",
         "duplicate chain not collapsed — run resolve_duplicate_chains"),
        ("orphan_transfer_group",
         "SELECT COUNT(*) n FROM txn WHERE transfer_group_id IS NOT NULL AND "
         "transfer_group_id NOT IN (SELECT id FROM transfer_group)",
         "transaction references a transfer group that does not exist"),
        ("one_legged_transfer",
         "SELECT COUNT(*) n FROM (SELECT transfer_group_id FROM transfer_leg "
         "GROUP BY transfer_group_id HAVING COUNT(*) < 2)",
         "transfer group with fewer than two legs"),
        ("duplicate_in_transfer",
         "SELECT COUNT(*) n FROM txn WHERE duplicate_of_id IS NOT NULL "
         "AND transfer_group_id IS NOT NULL",
         "suppressed duplicate is still linked into a transfer"),
        ("currency_mismatch",
         "SELECT COUNT(*) n FROM txn t JOIN account a ON a.id=t.account_id "
         "WHERE t.currency_booked <> a.primary_currency AND a.account_type "
         "NOT IN ('multi_currency')",
         "transaction currency differs from a single-currency account"),
        ("transfer_not_balanced",
         "SELECT COUNT(*) n FROM (SELECT tl.transfer_group_id FROM transfer_leg tl "
         "JOIN txn t ON t.id=tl.txn_id GROUP BY tl.transfer_group_id "
         "HAVING SUM(CASE WHEN t.amount_booked < 0 THEN 1 ELSE 0 END) = 0 "
         "OR SUM(CASE WHEN t.amount_booked > 0 THEN 1 ELSE 0 END) = 0)",
         "transfer group without both an inflow and an outflow"),
    ]

    for name, sql, description in checks:
        n = conn.execute(sql).fetchone()["n"]
        if n:
            problems.append({"check": name, "count": n, "description": description})
    return problems
