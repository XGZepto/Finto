"""Finto CLI.

    python -m fin.cli init                          create the DB
    python -m fin.cli accounts load accounts.yaml   register accounts/cards
    python -m fin.cli sniff inbox/amex.csv          show detected parser + columns
    python -m fin.cli import inbox/ --account amex_us_platinum
    python -m fin.cli reconcile                     dedup + link transfers
    python -m fin.cli review duplicates             work the review queue
    python -m fin.cli review transfers
    python -m fin.cli stats
    python -m fin.cli export ledger.csv
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path

from . import db as dbm
from .ingest import ingest_file, reconcile
from .models import Account, Card, Institution
from .parsers.base import ParseContext, read_csv_rows, select_parser
from .parsers import institutions as _reg  # noqa: F401

DEFAULT_DB = "finto.db"


def cmd_init(args):
    conn = dbm.connect(args.db)
    dbm.init_db(conn)
    print(f"initialised {args.db}")


def cmd_accounts(args):
    import yaml  # lazy: only needed for this command
    conn = dbm.connect(args.db)
    data = yaml.safe_load(Path(args.file).read_text())
    for i in data.get("institutions", []):
        dbm.upsert_institution(conn, Institution(**i))
    for a in data.get("accounts", []):
        dbm.upsert_account(conn, Account(**a))
    for c in data.get("cards", []):
        dbm.upsert_card(conn, Card(**c))
    conn.commit()
    print(f"loaded {len(data.get('institutions', []))} institutions, "
          f"{len(data.get('accounts', []))} accounts, "
          f"{len(data.get('cards', []))} cards")


def cmd_sniff(args):
    """Show which parser claims a file and what its columns look like.

    Run this first on every new export — it is how you verify (and fix) the
    column mappings in parsers/institutions.py without importing anything.
    """
    path = Path(args.file)
    ctx = ParseContext(path=path, institution_id=args.institution,
                       default_currency=args.currency)
    parser = select_parser(ctx)
    print(f"file:   {path.name}")
    print(f"parser: {parser.parser_id if parser else 'NONE MATCHED'}")
    try:
        header, rows = read_csv_rows(path)
        print(f"header: {header}")
        if rows:
            print("first row:")
            for k, v in rows[0].items():
                print(f"  {k!r}: {v!r}")
    except Exception as e:
        print(f"(not readable as CSV: {e})")
    if parser:
        res = parser.parse(ctx)
        print(f"\nparsed {len(res.txns)} transactions")
        for t in res.txns[:5]:
            print(f"  {t.txn_date}  {str(t.booked):>18}  {t.description_raw[:50]}")
        if res.warnings:
            print(f"warnings: {res.warnings[:5]}")


def cmd_import(args):
    conn = dbm.connect(args.db)
    target = Path(args.path)
    files = sorted(p for p in (target.rglob("*") if target.is_dir() else [target])
                   if p.is_file() and p.suffix.lower() in
                   (".csv", ".tsv", ".txt", ".ofx", ".qfx", ".xlsx", ".pdf"))
    for f in files:
        r = ingest_file(conn, f, institution_id=args.institution,
                        account_id=args.account, default_currency=args.currency,
                        dry_run=args.dry_run)
        flag = {"imported": "OK", "skipped": "--", "dry-run": "??"}.get(r["status"], "!!")
        detail = r.get("reason") or "{} txns via {}".format(r.get("txns"), r.get("parser"))
        print(f"[{flag}] {f.name}: {detail}")
        for w in r.get("warnings", []):
            print(f"       warn: {w}")


def cmd_reconcile(args):
    conn = dbm.connect(args.db)
    print(json.dumps(reconcile(conn, use_llm=args.llm), indent=2, default=str))


def cmd_config(args):
    conn = dbm.connect(args.db)
    if args.action == "list":
        for r in conn.execute("SELECT key, value FROM setting ORDER BY key"):
            print(f"  {r['key']:<16} {r['value']}")
        return
    if args.value is None:
        row = conn.execute("SELECT value FROM setting WHERE key=?", (args.key,)).fetchone()
        print(row["value"] if row else "(unset)")
        return
    conn.execute("INSERT OR REPLACE INTO setting (key, value) VALUES (?,?)",
                 (args.key, args.value))
    conn.commit()
    print(f"{args.key} = {args.value}")


def cmd_categorize(args):
    """Categorise uncategorised transactions using the LLM layer."""
    from .llm.categorize import apply_to_ledger, promote_to_rules
    from .llm.provider import build_provider
    conn = dbm.connect(args.db)
    provider = build_provider(conn)
    if provider.name == "null" and not args.dry_run:
        print("LLM disabled or unconfigured.")
        print("  python -m fin.cli config set llm_enabled 1")
        print("  export ANTHROPIC_API_KEY=...")
        return
    print(json.dumps(apply_to_ledger(conn, provider, dry_run=args.dry_run), indent=2))
    if args.promote and not args.dry_run:
        n = promote_to_rules(conn)
        print(f"promoted {n} confident categorisations to deterministic rules")


def cmd_llm(args):
    from .llm import cache as llm_cache
    conn = dbm.connect(args.db)
    if args.action == "stats":
        print(json.dumps(llm_cache.stats(conn), indent=2))
    elif args.action == "clear":
        n = llm_cache.invalidate(conn, task=args.task)
        conn.commit()
        print(f"invalidated {n} cached decisions")
    elif args.action == "audit":
        for r in conn.execute(
                "SELECT task, input_summary, output, confidence, model, created_at "
                "FROM llm_decision ORDER BY created_at DESC LIMIT ?", (args.limit,)):
            print(f"[{r['task']}] conf={r['confidence']} {r['model']}")
            print(f"  in:  {r['input_summary'][:100]}")
            print(f"  out: {r['output'][:140]}")


def cmd_check(args):
    from .integrity import check_all, find_violations, resolve_duplicate_chains
    conn = dbm.connect(args.db)
    fixed = resolve_duplicate_chains(conn)
    if fixed:
        print(f"collapsed {fixed} duplicate chains")
    violations = find_violations(conn)
    print("structural violations:", "none" if not violations else "")
    for v in violations:
        print(f"  [{v['check']}] x{v['count']}: {v['description']}")
    print("\nbalance reconciliation:")
    checks = check_all(conn)
    if not checks:
        print("  no balance assertions recorded — parsers capture these when")
        print("  the statement has a balance column")
    for c in checks:
        if c.get("status") == "insufficient_data":
            print(f"  {c['account_id']}: {c['note']}")
        else:
            mark = "ok " if c["status"] == "ok" else "!! "
            print(f"  {mark}{c['account_id']} {c['period']}: "
                  f"expected {c['expected_delta']:,.2f} "
                  f"actual {c['actual_delta']:,.2f} "
                  f"diff {c['discrepancy']:,.2f} {c['currency']}")


def cmd_review(args):
    conn = dbm.connect(args.db)
    if args.queue == "duplicates":
        sql = """SELECT dc.*, a.description_raw AS keep_desc, a.txn_date AS keep_date,
                        a.amount_booked AS keep_amt, a.currency_booked AS keep_ccy,
                        b.description_raw AS dupe_desc, b.txn_date AS dupe_date
                 FROM duplicate_candidate dc
                 JOIN txn a ON a.id=dc.keep_txn_id JOIN txn b ON b.id=dc.dupe_txn_id
                 WHERE dc.resolution='open' ORDER BY dc.score DESC"""
    else:
        sql = """SELECT tc.*, o.description_raw AS out_desc, o.txn_date AS out_date,
                        o.amount_booked AS out_amt, o.currency_booked AS out_ccy,
                        i.description_raw AS in_desc, i.account_id AS in_acct,
                        o.account_id AS out_acct
                 FROM transfer_candidate tc
                 JOIN txn o ON o.id=tc.out_txn_id JOIN txn i ON i.id=tc.in_txn_id
                 WHERE tc.resolution='open' ORDER BY tc.score DESC"""
    rows = list(conn.execute(sql))
    if not rows:
        print("queue empty")
        return
    for r in rows[: args.limit]:
        print("-" * 72)
        print(f"score {r['score']:.2f}  reasons: {json.loads(r['reasons'])}")
        for k in r.keys():
            if k.endswith(("_desc", "_date", "_amt", "_ccy", "_acct")):
                print(f"  {k:12} {r[k]}")
        print(f"  id: {r['id']}")
    print("-" * 72)
    print(f"{len(rows)} open. Accept with: "
          f"python -m fin.cli resolve {args.queue} <id> accept")


def cmd_resolve(args):
    conn = dbm.connect(args.db)
    table = "duplicate_candidate" if args.queue == "duplicates" else "transfer_candidate"
    res = "accepted" if args.action == "accept" else "rejected"
    row = conn.execute(f"SELECT * FROM {table} WHERE id=?", (args.id,)).fetchone()
    if not row:
        sys.exit(f"no candidate {args.id}")
    conn.execute(f"UPDATE {table} SET resolution=? WHERE id=?", (res, args.id))
    if res == "accepted":
        if args.queue == "duplicates":
            conn.execute("UPDATE txn SET duplicate_of_id=? WHERE id=?",
                         (row["keep_txn_id"], row["dupe_txn_id"]))
        else:
            import uuid as _u
            from datetime import datetime as _dt
            gid = str(_u.uuid4())
            conn.execute(
                "INSERT INTO transfer_group (id, kind, match_method, confidence, "
                "is_confirmed, created_at) VALUES (?,?,?,?,?,?)",
                (gid, "internal_transfer", "manual", 1.0, 1, _dt.now().isoformat()))
            for txn_id, role in ((row["out_txn_id"], "out"), (row["in_txn_id"], "in")):
                conn.execute("INSERT INTO transfer_leg VALUES (?,?,?)", (gid, txn_id, role))
                conn.execute("UPDATE txn SET transfer_group_id=?, kind='transfer' "
                             "WHERE id=?", (gid, txn_id))
    conn.commit()
    print(f"{args.id} -> {res}")


def cmd_stats(args):
    conn = dbm.connect(args.db)
    def q(s):
        return conn.execute(s).fetchone()[0]

    live = q("SELECT COUNT(*) FROM v_ledger")
    dupes = q("SELECT COUNT(*) FROM txn WHERE duplicate_of_id IS NOT NULL")
    groups = q("SELECT COUNT(*) FROM transfer_group")
    open_dupe = q("SELECT COUNT(*) FROM duplicate_candidate WHERE resolution='open'")
    open_xfer = q("SELECT COUNT(*) FROM transfer_candidate WHERE resolution='open'")
    print(f"transactions (live):   {live}")
    print(f"suppressed duplicates: {dupes}")
    print(f"transfer groups:       {groups}")
    print(f"open dupe candidates:  {open_dupe}")
    print(f"open xfer candidates:  {open_xfer}")
    print("\nby account:")
    for r in conn.execute(
            "SELECT account_name, currency_booked, COUNT(*) n, SUM(amount_booked) tot "
            "FROM v_ledger GROUP BY account_name, currency_booked ORDER BY n DESC"):
        print(f"  {r['account_name']:<28} {r['currency_booked']}  "
              f"{r['n']:>5} txns  net {r['tot']/100:>14,.2f}")
    print("\nuncategorised:", q("SELECT COUNT(*) FROM v_ledger WHERE category IS NULL"))


def cmd_export(args):
    conn = dbm.connect(args.db)
    rows = list(conn.execute(
        "SELECT txn_date, account_name, institution_id, description_raw, merchant, "
        "amount_booked, currency_booked, amount_native, currency_native, kind, "
        "category, transfer_group_id FROM v_ledger ORDER BY txn_date"))
    with open(args.out, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(rows[0].keys() if rows else [])
        for r in rows:
            w.writerow(list(r))
    print(f"wrote {len(rows)} rows to {args.out}")


def main(argv=None):
    p = argparse.ArgumentParser(prog="finto")
    p.add_argument("--db", default=DEFAULT_DB)
    sub = p.add_subparsers(dest="cmd", required=True)

    sub.add_parser("init").set_defaults(func=cmd_init)

    a = sub.add_parser("accounts"); a.add_argument("action", choices=["load"])
    a.add_argument("file"); a.set_defaults(func=cmd_accounts)

    s = sub.add_parser("sniff"); s.add_argument("file")
    s.add_argument("--institution"); s.add_argument("--currency")
    s.set_defaults(func=cmd_sniff)

    i = sub.add_parser("import"); i.add_argument("path")
    i.add_argument("--institution"); i.add_argument("--account")
    i.add_argument("--currency"); i.add_argument("--dry-run", action="store_true")
    i.set_defaults(func=cmd_import)

    rc = sub.add_parser("reconcile")
    rc.add_argument("--llm", action="store_true",
                    help="also let the LLM adjudicate ambiguous candidates")
    rc.set_defaults(func=cmd_reconcile)

    cf = sub.add_parser("config")
    cf.add_argument("action", choices=["list", "get", "set"])
    cf.add_argument("key", nargs="?"); cf.add_argument("value", nargs="?")
    cf.set_defaults(func=cmd_config)

    cg = sub.add_parser("categorize")
    cg.add_argument("--dry-run", action="store_true",
                    help="report how many merchants would be sent, make no calls")
    cg.add_argument("--promote", action="store_true",
                    help="convert confident results into deterministic rules")
    cg.set_defaults(func=cmd_categorize)

    lm = sub.add_parser("llm")
    lm.add_argument("action", choices=["stats", "clear", "audit"])
    lm.add_argument("--task"); lm.add_argument("--limit", type=int, default=20)
    lm.set_defaults(func=cmd_llm)

    sub.add_parser("check").set_defaults(func=cmd_check)

    r = sub.add_parser("review")
    r.add_argument("queue", choices=["duplicates", "transfers"])
    r.add_argument("--limit", type=int, default=20); r.set_defaults(func=cmd_review)

    rs = sub.add_parser("resolve")
    rs.add_argument("queue", choices=["duplicates", "transfers"])
    rs.add_argument("id"); rs.add_argument("action", choices=["accept", "reject"])
    rs.set_defaults(func=cmd_resolve)

    sub.add_parser("stats").set_defaults(func=cmd_stats)

    e = sub.add_parser("export"); e.add_argument("out"); e.set_defaults(func=cmd_export)

    args = p.parse_args(argv)
    args.func(args)


if __name__ == "__main__":
    main()
