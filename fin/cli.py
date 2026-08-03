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
from .models import Account, Card, Institution, minor_exponent
from .parsers import institutions as _reg  # noqa: F401
from .parsers import pdf as _pdf_reg  # noqa: F401
from .parsers.base import ParseContext, read_csv_rows, select_parser

DEFAULT_DB = "finto.db"


def fmt_money(m: dict, width: int = 0) -> str:
    """Render a {amount, currency} pair. Not every currency has two decimals."""
    exp = minor_exponent(m["currency"])
    major = m["amount"] / (10 ** exp)
    return f"{major:>{width},.{exp}f} {m['currency']}"


def cmd_init(args):
    conn = dbm.connect(args.db)
    applied = dbm.init_db(conn)
    print(f"initialised {args.db}")
    for col in applied:
        print(f"  migrated: added {col}")


def cmd_accounts(args):
    import yaml  # lazy: only needed for this command

    from .models import Party
    conn = dbm.connect(args.db)
    data = yaml.safe_load(Path(args.file).read_text())
    for i in data.get("institutions", []):
        dbm.upsert_institution(conn, Institution(**i))
    for a in data.get("accounts", []):
        dbm.upsert_account(conn, Account(**a))
    for c in data.get("cards", []):
        dbm.upsert_card(conn, Card(**c))
    for p in data.get("parties", []):
        dbm.upsert_party(conn, Party(**p))
    conn.commit()
    print(f"loaded {len(data.get('institutions', []))} institutions, "
          f"{len(data.get('accounts', []))} accounts, "
          f"{len(data.get('cards', []))} cards, "
          f"{len(data.get('parties', []))} parties")


def cmd_investments(args):
    """Import an MPF / investment position snapshot (xlsx)."""
    from .investment import (
        list_snapshots,
        parse_hsbc_mpf_position_xlsx,
        save_snapshot,
        snapshot_detail,
    )
    conn = dbm.connect(args.db)
    if args.investments_cmd == "import":
        snap = parse_hsbc_mpf_position_xlsx(args.file)
        snap_id = save_snapshot(conn, snap)
        print(f"saved {snap.scheme} snapshot {snap.as_of_date} "
              f"total {snap.total_value} ({len(snap.holdings)} funds, "
              f"{len(snap.subaccounts)} sub-accounts) id={snap_id}")
    elif args.investments_cmd == "list":
        for s in list_snapshots(conn):
            print(f"  {s['as_of_date']}  {s['scheme']}  "
                  f"{fmt_money(s['total'])}  {s['id'][:8]}")
    elif args.investments_cmd == "show":
        detail = snapshot_detail(conn, args.id)
        if not detail:
            print("not found")
            return
        print(f"{detail['scheme']} @ {detail['as_of_date']}: {fmt_money(detail['total'])}")
        for s in detail["subaccounts"]:
            print(f"  {s['account_id']}  {fmt_money(s['balance'])}  "
                  f"member={s['member_no']}")
        for h in detail["holdings"]:
            print(f"  {fmt_money(h['market_value']):>18}  {h['instrument'][:60]}")


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
                  f"expected {fmt_money(c['expected_delta'])} "
                  f"actual {fmt_money(c['actual_delta'])} "
                  f"diff {fmt_money(c['discrepancy'])}")


def cmd_installments(args):
    conn = dbm.connect(args.db)
    plans = dbm.load_installment_plans(conn, active_only=args.active)
    if not plans:
        print("no instalment plans detected")
        return
    by_ccy: dict[str, int] = {}
    for p in plans:
        ccy = p["outstanding"]["currency"]
        print("-" * 78)
        print(f"{p['description']}  [{p['status']}]")
        print(f"  account {p['account_id']}   started {p['start_date']}"
              f"   term {p['term_months']}m")
        print(f"  principal    {fmt_money(p['principal'], 14)}")
        print(f"  paid         {fmt_money(p['paid'], 14)}"
              f"   ({p['paid_count']}/{p['term_months']} charges)")
        print(f"  outstanding  {fmt_money(p['outstanding'], 14)}"
              f"   ({p['remaining_count']} left)")
        if p["status"] == "active":
            by_ccy[ccy] = by_ccy.get(ccy, 0) + p["outstanding"]["amount"]
    print("-" * 78)
    # Per currency, never summed across them.
    for ccy, total in sorted(by_ccy.items()):
        print(f"total outstanding  {fmt_money({'amount': total, 'currency': ccy}, 14)}")


def cmd_positions(args):
    """Balances per account AND per settlement currency.

    Never a single cross-currency total: adding HKD to USD is a category error,
    not a balance. A normalised view belongs in the presentation layer, where it
    can be labelled as converted and dated.
    """
    from .reporting import positions
    conn = dbm.connect(args.db)
    rows = positions(conn)
    if not rows:
        print("no transactions")
        return
    print(f"{'account':<28} {'ccy':<5} {'txns':>6} {'net':>16}  basis")
    for r in rows:
        print(f"  {r['account_name']:<26} {r['currency']:<5} {r['txn_count']:>6} "
              f"{r['net']['amount']/100:>16,.2f}  {r['basis']}")
    print()
    for ccy, total in sorted(_by_currency(rows).items()):
        print(f"  net across accounts  {total/100:>16,.2f} {ccy}")


def _by_currency(rows) -> dict[str, int]:
    out: dict[str, int] = {}
    for r in rows:
        out[r["currency"]] = out.get(r["currency"], 0) + r["net"]["amount"]
    return out


def cmd_reattribute(args):
    """Re-run card resolution over transactions already imported.

    card_id is set at ingest, so registering a reissued card does not
    retroactively fix rows imported before it existed. This closes that gap.
    """
    from .ingest import reattribute_cards
    conn = dbm.connect(args.db)
    n = reattribute_cards(conn)
    conn.commit()
    print(f"re-attributed {n} transactions")


def cmd_fx(args):
    from . import fx as fxm
    conn = dbm.connect(args.db)
    if args.action == "harvest":
        n = fxm.harvest_rates(conn)
        print(f"derived {n} rate observations from transactions carrying both "
              f"a native and a booked currency")
    elif args.action == "load":
        if not args.file:
            sys.exit("fx load needs a CSV: date,base,quote,rate[,source]")
        print(f"loaded {fxm.load_rates_csv(conn, Path(args.file))} rates")
    elif args.action == "list":
        rows = fxm.available_pairs(conn)
        if not rows:
            print("no rates stored — try: python -m fin.cli fx harvest")
            return
        for r in rows:
            print(f"  {r['base']}/{r['quote']:<4} {r['observations']:>5} obs  "
                  f"{r['first_date']} .. {r['last_date']}  ({r['source']})")


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
            from datetime import datetime as _dt

            from .transfers import transfer_group_id
            # Same derivation the automatic pass uses, so confirming a pair by
            # hand and later having reconcile agree converge on one group rather
            # than creating a second one for the same legs.
            gid = transfer_group_id([row["out_txn_id"], row["in_txn_id"]])
            conn.execute(
                "INSERT INTO transfer_group (id, kind, match_method, confidence, "
                "is_confirmed, created_at) VALUES (?,?,?,?,?,?) "
                "ON CONFLICT(id) DO UPDATE SET match_method='manual', "
                "confidence=1.0, is_confirmed=1",
                (gid, "internal_transfer", "manual", 1.0, 1, _dt.now().isoformat()))
            for txn_id, role in ((row["out_txn_id"], "out"), (row["in_txn_id"], "in")):
                conn.execute(
                    "INSERT OR REPLACE INTO transfer_leg (transfer_group_id, "
                    "txn_id, role) VALUES (?,?,?)", (gid, txn_id, role))
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
    # Per-card spend, rolled up over reissue chains: a card replaced mid-year is
    # one card here, not two.
    roots = dbm.card_lineage_roots(conn)
    names = {r["id"]: r["cardholder_name"]
             for r in conn.execute("SELECT id, cardholder_name FROM card")}
    per_card: dict[tuple, list] = {}
    for r in conn.execute(
            "SELECT card_id, currency_booked, COUNT(*) n, SUM(amount_booked) tot "
            "FROM v_ledger WHERE card_id IS NOT NULL "
            "GROUP BY card_id, currency_booked"):
        key = (roots.get(r["card_id"], r["card_id"]), r["currency_booked"])
        agg = per_card.setdefault(key, [0, 0])
        agg[0] += r["n"]
        agg[1] += r["tot"]
    if per_card:
        print("\nby card (reissues rolled up):")
        for (root, ccy), (n, tot) in sorted(per_card.items()):
            label = f"{names.get(root, root)} ({root})"
            print(f"  {label:<40} {ccy}  {n:>5} txns  net {tot/100:>14,.2f}")

    unattributed = q("SELECT COUNT(*) FROM v_ledger WHERE card_id IS NULL "
                     "AND account_id IN (SELECT DISTINCT account_id FROM card)")
    if unattributed:
        print(f"\n!! {unattributed} txns on card accounts have no card attributed")
        print("   (a reissued card? register it with replaces_card_id)")

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

    a = sub.add_parser("accounts")
    a.add_argument("action", choices=["load"])
    a.add_argument("file")
    a.set_defaults(func=cmd_accounts)

    s = sub.add_parser("sniff")
    s.add_argument("file")
    s.add_argument("--institution")
    s.add_argument("--currency")
    s.set_defaults(func=cmd_sniff)

    i = sub.add_parser("import")
    i.add_argument("path")
    i.add_argument("--institution")
    i.add_argument("--account")
    i.add_argument("--currency")
    i.add_argument("--dry-run", action="store_true")
    i.set_defaults(func=cmd_import)

    rc = sub.add_parser("reconcile")
    rc.add_argument("--llm", action="store_true",
                    help="also let the LLM adjudicate ambiguous candidates")
    rc.set_defaults(func=cmd_reconcile)

    cf = sub.add_parser("config")
    cf.add_argument("action", choices=["list", "get", "set"])
    cf.add_argument("key", nargs="?")
    cf.add_argument("value", nargs="?")
    cf.set_defaults(func=cmd_config)

    cg = sub.add_parser("categorize")
    cg.add_argument("--dry-run", action="store_true",
                    help="report how many merchants would be sent, make no calls")
    cg.add_argument("--promote", action="store_true",
                    help="convert confident results into deterministic rules")
    cg.set_defaults(func=cmd_categorize)

    lm = sub.add_parser("llm")
    lm.add_argument("action", choices=["stats", "clear", "audit"])
    lm.add_argument("--task")
    lm.add_argument("--limit", type=int, default=20)
    lm.set_defaults(func=cmd_llm)

    sub.add_parser("check").set_defaults(func=cmd_check)

    ip = sub.add_parser("installments")
    ip.add_argument("--active", action="store_true", help="only active plans")
    ip.set_defaults(func=cmd_installments)

    sub.add_parser("positions").set_defaults(func=cmd_positions)

    fx = sub.add_parser("fx")
    fx.add_argument("action", choices=["harvest", "load", "list"])
    fx.add_argument("file", nargs="?")
    fx.set_defaults(func=cmd_fx)
    sub.add_parser("reattribute").set_defaults(func=cmd_reattribute)

    inv = sub.add_parser("investments")
    inv_sub = inv.add_subparsers(dest="investments_cmd", required=True)
    inv_imp = inv_sub.add_parser("import", help="import HSBC MPF position xlsx")
    inv_imp.add_argument("file")
    inv_sub.add_parser("list")
    inv_show = inv_sub.add_parser("show")
    inv_show.add_argument("id")
    inv.set_defaults(func=cmd_investments)

    r = sub.add_parser("review")
    r.add_argument("queue", choices=["duplicates", "transfers"])
    r.add_argument("--limit", type=int, default=20)
    r.set_defaults(func=cmd_review)

    rs = sub.add_parser("resolve")
    rs.add_argument("queue", choices=["duplicates", "transfers"])
    rs.add_argument("id")
    rs.add_argument("action", choices=["accept", "reject"])
    rs.set_defaults(func=cmd_resolve)

    sub.add_parser("stats").set_defaults(func=cmd_stats)

    e = sub.add_parser("export")
    e.add_argument("out")
    e.set_defaults(func=cmd_export)

    args = p.parse_args(argv)
    args.func(args)


if __name__ == "__main__":
    main()
