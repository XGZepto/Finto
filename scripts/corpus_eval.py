#!/usr/bin/env python3
"""Full-corpus ingest evaluation.

Imports every supported file under ~/Documents/Finto-Data into a scratch
database, runs reconcile + check, and reports per-account ingestion and
balance coverage. This is the "are we at a perfect data layer?" gate.

Usage:
    python scripts/corpus_eval.py [--root ~/Documents/Finto-Data] [--db /tmp/finto-eval.db]
"""
from __future__ import annotations

import argparse
import sys
import tempfile
from pathlib import Path

import yaml

from fin import db as dbm
from fin.ingest import ingest_file, reconcile
from fin.models import Account, Card, Institution, Party

ROOT_DEFAULT = Path.home() / "Documents" / "Finto-Data"
ACCOUNTS_YAML = Path("accounts.example.yaml")

#: account_for return value for consolidated multi-account statements: import
#: with no fixed account and let each row's account_hint route it.
ROUTED = ""


def account_for(path: Path) -> str | None:
    """Map a corpus path to its account id. CSVs name the account in the
    filename; single-account PDFs need it too. Consolidated files (Chase,
    HSBC One, Mox bank) return ROUTED — their rows carry account hints and
    the alias registry splits them."""
    name = path.name.lower()
    parts = [p.lower() for p in path.parts]

    # Consolidated multi-account statements — route by account_hint.
    if "chase" in parts and "consolidated" in name:
        return ROUTED
    if "hsbc_one" in name and name.endswith(".pdf"):
        return ROUTED
    if "mox_hk_bank" in name and name.endswith(".pdf"):
        return ROUTED

    mapping = [
        ("amex_hk_essential", "amex_hk_essential"),
        ("amex_hk_explorer", "amex_hk_explorer"),
        ("amex_hk_platinum", "amex_hk_platinum"),
        ("amex_us_marriott", "amex_us_marriott"),
        ("amex_us_platinum", "amex_us_platinum"),
        ("amex_us_high_yield_savings", "amex_us_savings"),
        ("hsbc_hk_everymile", "hsbc_hk_everymile"),
        ("hsbc_hk_pulse_dual_currency", "hsbc_hk_pulse_hkd"),
        ("hsbc_hk_pulse_0071_hkd", "hsbc_hk_pulse_hkd"),
        ("hsbc_hk_pulse_2217_rmb", "hsbc_hk_pulse_cny"),
        ("hsbc_hk_pulse_cny", "hsbc_hk_pulse_cny"),
        ("hsbc_hk_pulse_hkd", "hsbc_hk_pulse_hkd"),
        ("hsbc_hk_hsbc_one_hkd_savings", "hsbc_hk_savings_hkd"),
        ("hsbc_hk_hsbc_one_rmb_savings", "hsbc_hk_savings_cny"),
        ("hsbc_hk_savings_cny", "hsbc_hk_savings_cny"),
        ("hsbc_hk_savings_hkd", "hsbc_hk_savings_hkd"),
        ("mox_hk_credit", "mox_credit"),
        ("mox_hk_bank", "mox_hkd"),
        ("mox_jpy", "mox_jpy"),
    ]
    for needle, account_id in mapping:
        if needle in name:
            return account_id
    if "mox_bank" in parts:
        return "mox_hkd"
    if "chase" in parts and "checking" in name:
        return "chase_checking"
    if "chase" in parts and "savings" in name:
        return "chase_savings"
    if "wise" in parts:
        for ccy in ("eur", "gbp", "hkd", "jpy", "krw", "nzd", "usd"):
            if f"wise_{ccy}" in name or f"_{ccy}_" in name:
                return f"wise_{ccy}"
    return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", type=Path, default=ROOT_DEFAULT)
    ap.add_argument("--db", type=Path, default=None)
    args = ap.parse_args()

    root = args.root.expanduser()
    if not root.is_dir():
        print(f"corpus root not found: {root}", file=sys.stderr)
        return 1

    db = args.db or Path(tempfile.mkstemp(suffix=".db")[1])
    db.unlink(missing_ok=True)

    conn = dbm.connect(db)
    dbm.init_db(conn)

    # Load account map
    data = yaml.safe_load(ACCOUNTS_YAML.read_text())
    for i in data.get("institutions", []):
        dbm.upsert_institution(conn, Institution(**i))
    for a in data.get("accounts", []):
        dbm.upsert_account(conn, Account(**a))
    for c in data.get("cards", []):
        dbm.upsert_card(conn, Card(**c))
    for p in data.get("parties", []):
        dbm.upsert_party(conn, Party(**p))
    conn.commit()

    counts = {"imported": 0, "skipped": 0, "error": 0}
    errors = []

    for path in sorted(root.rglob("*")):
        if path.suffix.lower() not in (".csv", ".pdf", ".xlsx", ".xls"):
            continue
        acct = account_for(path)
        if acct is None:
            counts["skipped"] += 1
            print(f"  SKIP (no account map) {path.name}")
            continue
        try:
            r = ingest_file(conn, path, account_id=acct or None)
        except Exception as e:
            counts["error"] += 1
            errors.append(path.name)
            print(f"  ERROR {path.name}: {type(e).__name__}: {e}")
            continue
        if r["status"] == "error":
            counts["error"] += 1
            errors.append(path.name)
            print(f"  ERROR {path.name}: {r['reason'][:100]}")
            for w in r.get("warnings", [])[:3]:
                print(f"         warn: {w[:100]}")
        elif r.get("txns", 0) == 0:
            counts["skipped"] += 1
        else:
            counts["imported"] += 1

    # MPF position snapshot
    mpf_xlsx = root / "HSBC_HK" / "MPF_Investment" / "Positions" / \
        "HSBC_MPF_Position_2026-07-31.xlsx"
    if mpf_xlsx.exists():
        from fin.investment import parse_hsbc_mpf_position_xlsx, save_snapshot
        snap = parse_hsbc_mpf_position_xlsx(mpf_xlsx)
        save_snapshot(conn, snap)
        conn.commit()

    reconcile(conn)

    # Per-account report
    rows = conn.execute("""
        SELECT a.id, a.display_name, a.primary_currency,
               COUNT(t.id) AS txn_count,
               MIN(t.txn_date) AS first_txn,
               MAX(t.txn_date) AS last_txn
        FROM account a
        LEFT JOIN txn t ON t.account_id = a.id AND t.duplicate_of_id IS NULL
        GROUP BY a.id ORDER BY a.id
    """).fetchall()

    checks = conn.execute("""
        SELECT a.id,
               COUNT(DISTINCT ba.as_of_date) AS balance_dates,
               SUM(CASE WHEN rc.status = 'discrepancy' THEN 1 ELSE 0 END) AS discrepancies,
               SUM(CASE WHEN rc.status = 'ok' THEN 1 ELSE 0 END) AS ok_checks
        FROM account a
        LEFT JOIN balance_assertion ba ON ba.account_id = a.id
        LEFT JOIN reconciliation_check rc ON rc.account_id = a.id
        GROUP BY a.id
    """).fetchall()

    unlinked = conn.execute(
        "SELECT COUNT(*) AS n FROM transfer_candidate WHERE resolution = 'open'"
    ).fetchone()["n"]

    supp = conn.execute("""
        SELECT c.cardholder_name, COUNT(t.id) AS n
        FROM txn t JOIN card c ON c.id = t.card_id
        WHERE c.is_supplementary = 1 AND t.duplicate_of_id IS NULL
        GROUP BY c.cardholder_name
    """).fetchall()

    mpf = conn.execute("""
        SELECT s.as_of_date, s.total_value, COUNT(h.id) AS holdings
        FROM investment_snapshot s
        LEFT JOIN investment_holding h ON h.snapshot_id = s.id
        GROUP BY s.id ORDER BY s.as_of_date DESC
    """).fetchall()

    check_map = {r["id"]: r for r in checks}

    print("\n" + "=" * 70)
    print(f"CORPUS INGEST EVALUATION — {root}")
    print("=" * 70)
    print(f"Files: {counts['imported']} imported, {counts['skipped']} empty, "
          f"{counts['error']} errors")
    print(f"Unlinked transfer candidates: {unlinked}")
    print()

    total_txns = 0
    for r in rows:
        c = check_map.get(r["id"])
        balance_info = (f"{c['balance_dates']} dates, "
                        f"{c['ok_checks'] or 0} ok, "
                        f"{c['discrepancies'] or 0} discrepancies"
                        if c else "no balance data")
        total_txns += r["txn_count"] or 0
        print(f"{r['id']:28} {r['primary_currency']:4} "
              f"{r['txn_count'] or 0:6} txns  "
              f"{r['first_txn'] or '—'} → {r['last_txn'] or '—'}  "
              f"[{balance_info}]")

    print(f"\nTotal transactions: {total_txns}")
    if supp:
        print("Supplementary card spend:")
        for s in supp:
            print(f"  {s['cardholder_name']}: {s['n']} txns")
    if mpf:
        print("MPF snapshots:")
        for m in mpf:
            print(f"  {m['as_of_date']}: total={m['total_value']} "
                  f"({m['holdings']} holdings)")

    conn.close()
    print(f"\nDatabase: {db}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
