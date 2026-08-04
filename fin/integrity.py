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

from .models import Money


def record_balance(conn, *, account_id: str, as_of, balance: Money,
                   kind: str = "running",
                   statement_file_id: str | None = None) -> None:
    # First write wins for a given (account, date, kind, currency). HSBC and
    # Wise export newest-first, so the first running-balance we see for a date
    # is the end-of-day figure; later same-day rows are mid-day snapshots that
    # would make check_account invent a phantom delta.
    conn.execute(
        "INSERT OR IGNORE INTO balance_assertion (id, account_id, as_of_date, "
        "balance, currency, kind, statement_file_id) VALUES (?,?,?,?,?,?,?)",
        (str(uuid.uuid4()), account_id,
         as_of.isoformat() if hasattr(as_of, "isoformat") else str(as_of),
         balance.amount, balance.currency, kind, statement_file_id),
    )


#: Rows the statement contributed, followed through dedup: where a copy of one
#: of its rows survived instead, that copy stands in for it.
_STATEMENT_ROWS = """
SELECT COALESCE(SUM(t.amount_booked), 0) AS total FROM txn t
WHERE t.duplicate_of_id IS NULL AND t.status <> 'void'
  AND t.account_id = ? AND t.currency_booked = ?
  AND (t.statement_file_id = ?
       OR EXISTS (SELECT 1 FROM txn d WHERE d.duplicate_of_id = t.id
                    AND d.statement_file_id = ?))
"""


def check_account(conn, account_id: str, *, record: bool = True) -> list[dict]:
    """Verify the transactions we hold reproduce the balances the issuer printed.

    A statement printing an opening and a closing is checked against its own
    rows, because the issuer assigns a charge to a statement by posting date —
    one dated inside the period may be billed on the next. A passbook printing
    a running balance is checked date to date.

    `record` writes the outcome to the audit trail. Callers only answering a
    question pass False, so a page load neither appends a row nor takes a write
    lock a concurrent reader deadlocks against.
    """
    name_row = conn.execute("SELECT display_name FROM account WHERE id=?",
                            (account_id,)).fetchone()
    account_name = name_row["display_name"] if name_row else account_id

    statements = _check_statements(conn, account_id)
    # Consecutive closings bracket the gaps between statements — the only
    # evidence available when the issuer prints no opening, as Mox Credit does.
    kinds = ("running",) if statements else ("running", "closing")
    out = [*statements, *_check_running(conn, account_id, kinds)]
    if not out:
        return [{"account_id": account_id, "account_name": account_name,
                 "status": "insufficient_data",
                 "note": "no opening/closing pair and fewer than two running balances"}]

    for c in out:
        c["account_id"] = account_id
        c["account_name"] = account_name
        if record:
            conn.execute(
                "INSERT INTO reconciliation_check (id, account_id, period_start, "
                "period_end, expected_delta, actual_delta, discrepancy, currency, "
                "status, checked_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                (str(uuid.uuid4()), account_id, c["period_start"], c["period_end"],
                 c["expected_delta"]["amount"], c["actual_delta"]["amount"],
                 c["discrepancy"]["amount"], c["currency"], c["status"],
                 datetime.now().isoformat()))
    if record:
        conn.commit()
    return out


def _outcome(period_start, period_end, expected, actual, currency) -> dict:
    # Minor units, and the period as two dates so a client can deep-link to the
    # rows in question.
    discrepancy = actual - expected
    return {
        "period": f"{period_start} -> {period_end}",
        "period_start": period_start,
        "period_end": period_end,
        "expected_delta": {"amount": expected, "currency": currency},
        "actual_delta": {"amount": actual, "currency": currency},
        "discrepancy": {"amount": discrepancy, "currency": currency},
        "currency": currency,
        "status": "ok" if discrepancy == 0 else "discrepancy",
    }


def _check_statements(conn, account_id: str) -> list[dict]:
    """Each statement's own opening and closing, against its own rows."""
    out = []
    for r in conn.execute(
            "SELECT o.statement_file_id AS sf, o.currency AS ccy, "
            "       o.as_of_date AS opened, c.as_of_date AS closed, "
            "       o.balance AS opening, c.balance AS closing "
            "FROM balance_assertion o JOIN balance_assertion c "
            "  ON c.statement_file_id = o.statement_file_id "
            " AND c.account_id = o.account_id "
            " AND c.currency = o.currency AND c.kind = 'closing' "
            "WHERE o.account_id = ? AND o.kind = 'opening' "
            "  AND o.statement_file_id IS NOT NULL "
            "ORDER BY c.as_of_date", (account_id,)):
        actual = conn.execute(
            _STATEMENT_ROWS, (account_id, r["ccy"], r["sf"], r["sf"])
        ).fetchone()["total"]
        out.append(_outcome(r["opened"], r["closed"],
                            r["closing"] - r["opening"], actual, r["ccy"]))
    return out


def _check_running(conn, account_id: str, kinds: tuple[str, ...]) -> list[dict]:
    """Consecutive balances, against everything dated between them."""
    assertions = list(conn.execute(
        "SELECT as_of_date, balance, currency FROM balance_assertion "
        f"WHERE account_id=? AND kind IN ({','.join('?' * len(kinds))}) "
        "ORDER BY currency, as_of_date", (account_id, *kinds)))
    out = []
    for prev, curr in zip(assertions, assertions[1:]):
        if prev["currency"] != curr["currency"]:
            continue
        actual = conn.execute(
            "SELECT COALESCE(SUM(amount_booked), 0) AS total FROM txn "
            "WHERE account_id=? AND duplicate_of_id IS NULL AND status<>'void' "
            "AND currency_booked=? AND txn_date > ? AND txn_date <= ?",
            (account_id, curr["currency"], prev["as_of_date"], curr["as_of_date"])
        ).fetchone()["total"]
        out.append(_outcome(prev["as_of_date"], curr["as_of_date"],
                            curr["balance"] - prev["balance"], actual,
                            curr["currency"]))
    return out


def check_all(conn, *, record: bool = True) -> list[dict]:
    out = []
    for r in conn.execute("SELECT DISTINCT account_id FROM balance_assertion"):
        out.extend(check_account(conn, r["account_id"], record=record))
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


def prune_orphan_transfer_groups(conn) -> int:
    """Delete automatic transfer groups no transaction points at any more.

    A group goes stale when its legs get re-matched — one leg is later suppressed
    as a duplicate, say, and the survivor pairs with something else. Groups you
    confirmed by hand are never pruned. transfer_leg cascades on delete.
    """
    cur = conn.execute(
        "DELETE FROM transfer_group WHERE is_confirmed = 0 AND id NOT IN "
        "(SELECT transfer_group_id FROM txn WHERE transfer_group_id IS NOT NULL)")
    conn.commit()
    return cur.rowcount


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
        ("stale_transfer_group",
         "SELECT COUNT(*) n FROM transfer_group WHERE is_confirmed = 0 AND id NOT IN "
         "(SELECT transfer_group_id FROM txn WHERE transfer_group_id IS NOT NULL)",
         "transfer group no transaction references — run prune_orphan_transfer_groups"),
        ("duplicate_in_transfer",
         "SELECT COUNT(*) n FROM txn WHERE duplicate_of_id IS NOT NULL "
         "AND transfer_group_id IS NOT NULL",
         "suppressed duplicate is still linked into a transfer"),
        # An account may settle in any currency listed in account_currency. When
        # nothing is declared, primary_currency is the only permitted one —
        # except for multi_currency accounts, which are multi-currency by type.
        ("currency_not_settleable",
         "SELECT COUNT(*) n FROM txn t JOIN account a ON a.id=t.account_id "
         "WHERE a.account_type <> 'multi_currency' "
         "AND t.currency_booked <> a.primary_currency "
         "AND NOT EXISTS (SELECT 1 FROM account_currency ac "
         "                WHERE ac.account_id=t.account_id "
         "                  AND ac.currency=t.currency_booked)",
         "transaction in a currency the account does not settle in"),
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
