"""Seed an isolated database schema with invented transactions for UI work.

Design work needs a populated ledger — empty panels hide every layout problem
worth finding. This builds ~1,270 transactions over 18 months across six
accounts, with recurring subscriptions, salary and rent, a handful of refunds,
and ~50 uncategorised rows so the triage queue is not empty.

Nothing here is real. Every merchant, amount and cardholder is invented.

    initdb -D /tmp/pg --no-locale --encoding=UTF8
    pg_ctl -D /tmp/pg -o "-p 55432" -l /tmp/pg.log start
    export DATABASE_URL='postgresql://127.0.0.1:55432/postgres'
    .venv/bin/python scripts/seed_demo.py

See https://github.com/XGZepto/Finto/wiki/Mobile-Experience for the visual QA setup.
"""
from __future__ import annotations

import argparse
import hashlib
import os
import random
from datetime import datetime, timedelta, timezone
from decimal import Decimal

from psycopg import sql

from fin import db as dbm
from fin.auth import create_user, grant_account
from fin.models import (
    Account,
    Card,
    FileFormat,
    FxRate,
    Institution,
    Money,
    StatementFile,
    Txn,
    TxnKind,
)

DEMO_SCHEMA = "finto_demo"

INSTITUTIONS = [
    Institution(id="hsbc_hk", display_name="HSBC HK", country="HK"),
    Institution(id="wise", display_name="Wise", country="HK"),
    Institution(id="amex_hk", display_name="AMEX HK", country="HK"),
    Institution(id="mox", display_name="Mox", country="HK"),
]

ACCOUNTS = [
    Account(id="hsbc_hk_current", institution_id="hsbc_hk", display_name="HSBC Current",
            account_type="checking", primary_currency="HKD"),
    Account(id="hsbc_hk_savings", institution_id="hsbc_hk", display_name="HSBC Savings",
            account_type="savings", primary_currency="HKD"),
    Account(id="wise_hkd", institution_id="wise", display_name="Wise HKD",
            account_type="multi_currency", primary_currency="HKD",
            balance_group="wise_personal"),
    Account(id="wise_usd", institution_id="wise", display_name="Wise USD",
            account_type="multi_currency", primary_currency="USD",
            balance_group="wise_personal"),
    Account(id="amex_hk_main", institution_id="amex_hk", display_name="AMEX Platinum",
            account_type="credit_card", primary_currency="HKD"),
    Account(id="mox_main", institution_id="mox", display_name="Mox",
            account_type="checking", primary_currency="HKD"),
]

# merchant, category, subcategory, typical amount range, currency
MERCHANTS = [
    ("Park N Shop", "groceries", "supermarket", (85, 480), "HKD"),
    ("Wellcome", "groceries", "supermarket", (60, 320), "HKD"),
    ("City Super", "groceries", "supermarket", (180, 900), "HKD"),
    ("Starbucks", "dining", "coffee", (38, 78), "HKD"),
    ("% Arabica", "dining", "coffee", (45, 95), "HKD"),
    ("Yardbird", "dining", "restaurant", (320, 980), "HKD"),
    ("Ho Lee Fook", "dining", "restaurant", (420, 1200), "HKD"),
    ("Deliveroo", "dining", "delivery", (95, 340), "HKD"),
    ("MTR", "transport", "rail", (12, 58), "HKD"),
    ("Uber", "transport", "rideshare", (48, 260), "HKD"),
    ("Cathay Pacific", "travel", "flights", (2400, 9800), "HKD"),
    ("Agoda", "travel", "hotel", (900, 4200), "HKD"),
    ("Netflix", "subscriptions", "streaming", (78, 78), "HKD"),
    ("Spotify", "subscriptions", "streaming", (58, 58), "HKD"),
    ("iCloud", "subscriptions", "software", (23, 23), "HKD"),
    ("Adobe", "subscriptions", "software", (168, 168), "HKD"),
    ("GitHub", "subscriptions", "software", (4, 4), "USD"),
    ("Anthropic", "subscriptions", "software", (20, 20), "USD"),
    ("CLP Power", "utilities", "electricity", (280, 720), "HKD"),
    ("Towngas", "utilities", "gas", (90, 240), "HKD"),
    ("HKBN", "utilities", "internet", (218, 218), "HKD"),
    ("Watsons", "health", "pharmacy", (45, 260), "HKD"),
    ("Pure Fitness", "health", "gym", (880, 880), "HKD"),
    ("Uniqlo", "shopping", "clothing", (180, 890), "HKD"),
    ("Muji", "shopping", "home", (120, 640), "HKD"),
    ("Apple Store", "shopping", "electronics", (800, 12800), "HKD"),
    ("Eslite", "shopping", "books", (90, 380), "HKD"),
    ("Amazon", "shopping", "general", (35, 240), "USD"),
]

SUBSCRIPTIONS = {"Netflix", "Spotify", "iCloud", "Adobe", "GitHub", "Anthropic",
                 "HKBN", "Pure Fitness"}
CARD_MERCHANTS = {m[0] for m in MERCHANTS} - {"CLP Power", "Towngas"}


def demo_write_blocked(method: str, path: str, user_id: str) -> bool:
    """Keep the published viewer credential read-only across non-ledger tables."""
    if os.environ.get("FINTO_DEMO_READ_ONLY") != "1" or user_id != "demo":
        return False
    if method in {"GET", "HEAD", "OPTIONS"}:
        return False
    return path not in {"/api/query", "/api/summary"}


def _prepare_schema(conn, *, reset: bool, schema: str | None) -> None:
    if not reset:
        return
    if os.environ.get("FINTO_DEMO_SEED") != "1" or schema != DEMO_SCHEMA:
        raise RuntimeError("demo reset requires FINTO_DEMO_SEED=1 and schema finto_demo")
    conn.execute(sql.SQL("DROP SCHEMA IF EXISTS {} CASCADE").format(sql.Identifier(schema)))
    conn.execute(sql.SQL("CREATE SCHEMA {}").format(sql.Identifier(schema)))
    conn.execute("SELECT set_config('search_path', %s, false)", (schema,))


def seed_demo(
    *, reset: bool = False, schema: str | None = None, conn=None, version: str | None = None,
) -> int:
    """Create a repeatable invented ledger and return its transaction count."""
    random.seed(11)
    owns_connection = conn is None
    conn = conn or dbm.connect()
    _prepare_schema(conn, reset=reset, schema=schema)
    dbm.init_db(conn)

    for inst in INSTITUTIONS:
        dbm.upsert_institution(conn, inst)
    for acct in ACCOUNTS:
        dbm.upsert_account(conn, acct)

    demo_password = os.environ.get("FINTO_DEMO_PASSWORD")
    if demo_password:
        create_user(
            conn,
            user_id="demo",
            username=os.environ.get("FINTO_DEMO_USERNAME", "demo"),
            email=os.environ.get("FINTO_DEMO_EMAIL", "demo@finto.app"),
            password=demo_password,
        )
        for acct in ACCOUNTS:
            grant_account(conn, account_id=acct.id, user_id="demo", role="viewer")
    dbm.upsert_card(conn, Card(id="amex_primary", account_id="amex_hk_main",
                               cardholder_name="ALEX E", last4="3007"))
    dbm.upsert_card(conn, Card(id="amex_supp", account_id="amex_hk_main",
                               cardholder_name="JORDAN E", last4="3015",
                               is_supplementary=True))

    today = datetime.now(timezone.utc).date()
    start = today - timedelta(days=545)

    for days in range(0, 560, 7):
        d = start + timedelta(days=days)
        dbm.upsert_fx_rate(conn, FxRate(rate_date=d, base="USD", quote="HKD",
                                        rate=Decimal("7.81"), source="seed"))
        dbm.upsert_fx_rate(conn, FxRate(rate_date=d, base="HKD", quote="USD",
                                        rate=Decimal("0.128"), source="seed"))

    sf = StatementFile(
        source_path="seed/invented-statements.csv",
        file_sha256=hashlib.sha256(b"finto-design-seed").hexdigest(),
        institution_id="hsbc_hk", account_id=None, file_format=FileFormat.CSV,
        parser_id="seed", parser_version="1", period_start=start, period_end=today,
        row_count=0,
    )
    dbm.insert_statement_file(conn, sf)

    txns: list[Txn] = []

    def add(account_id, d, amount, currency, desc, merchant, category, sub,
            kind=TxnKind.PURCHASE, card_id=None, native=None, fx_rate=None):
        txns.append(Txn(
            account_id=account_id, card_id=card_id, txn_date=d,
            booked=Money(amount=round(amount * 100), currency=currency),
            native=native, fx_rate=fx_rate,
            description_raw=desc, merchant=merchant, category=category,
            subcategory=sub, kind=kind, statement_file_id=sf.id,
            details={"reference": f"REF{random.randint(100000, 999999)}"},
        ))

    # Salary, monthly.
    d = start.replace(day=1)
    while d < today:
        add("hsbc_hk_current", d, 68000, "HKD", "SALARY CREDIT — NORTHWIND LTD",
            "Northwind Ltd", "income", "salary", TxnKind.INCOME)
        # Rent out.
        add("hsbc_hk_current", d + timedelta(days=1), -19500, "HKD",
            "RENT TRANSFER", "Landlord", "housing", "rent")
        # Card payment (a transfer pair).
        add("hsbc_hk_current", d + timedelta(days=17), -random.uniform(9000, 21000),
            "HKD", "AMEX AUTOPAY", "American Express", None, None, TxnKind.CC_PAYMENT)
        # Savings sweep.
        add("hsbc_hk_current", d + timedelta(days=2), -8000, "HKD",
            "STANDING INSTRUCTION SAVINGS", None, None, None, TxnKind.TRANSFER)
        add("hsbc_hk_savings", d + timedelta(days=2), 8000, "HKD",
            "STANDING INSTRUCTION SAVINGS", None, None, None, TxnKind.TRANSFER)
        # Subscriptions, same day each month.
        for name in SUBSCRIPTIONS:
            m = next(x for x in MERCHANTS if x[0] == name)
            amt = m[3][0]
            ccy = m[4]
            acct = "amex_hk_main" if name in CARD_MERCHANTS else "hsbc_hk_current"
            native = None
            fx = None
            booked = -amt
            bccy = ccy
            if ccy == "USD" and acct == "amex_hk_main":
                native = Money(amount=round(-amt * 100), currency="USD")
                fx = Decimal("7.81")
                booked = -amt * 7.81
                bccy = "HKD"
            stable_day = int(hashlib.sha256(name.encode()).hexdigest()[:8], 16) % 26
            add(acct, d + timedelta(days=stable_day + 1), booked, bccy,
                f"{name.upper()} SUBSCRIPTION", name, m[1], m[2],
                TxnKind.PURCHASE, "amex_primary" if acct == "amex_hk_main" else None,
                native, fx)
        d = (d + timedelta(days=32)).replace(day=1)

    # Everyday spending.
    day = start
    while day < today:
        for _ in range(random.randint(0, 5)):
            name, cat, sub, (lo, hi), ccy = random.choice(MERCHANTS)
            if name in SUBSCRIPTIONS:
                continue
            amt = random.uniform(lo, hi)
            card = None
            acct = "amex_hk_main"
            native = None
            fx = None
            booked = -amt
            bccy = ccy
            if ccy == "USD":
                native = Money(amount=round(-amt * 100), currency="USD")
                fx = Decimal("7.81")
                booked = -amt * 7.81
                bccy = "HKD"
            if name in ("MTR", "Wellcome"):
                acct = "mox_main"
            else:
                card = "amex_supp" if random.random() < 0.22 else "amex_primary"
            add(acct, day, booked, bccy, f"{name.upper()} HONG KONG", name, cat, sub,
                TxnKind.PURCHASE, card, native, fx)
        day += timedelta(days=1)

    # A few refunds and some uncategorised rows to triage.
    for _ in range(9):
        day = today - timedelta(days=random.randint(1, 60))
        add("amex_hk_main", day, random.uniform(120, 900), "HKD",
            "REFUND — RETURNED ITEM", "Uniqlo", "shopping", "clothing",
            TxnKind.REFUND, "amex_primary")
    for i in range(14):
        day = today - timedelta(days=random.randint(0, 21))
        add("amex_hk_main", day, -random.uniform(45, 620), "HKD",
            f"SQ *VENDOR {1000 + i}", None, None, None, TxnKind.PURCHASE,
            "amex_primary")

    sf.row_count = len(txns)
    inserted = dbm.insert_txns(conn, txns)
    dbm.insert_txn_details(conn, txns)
    # The demo represents a ledger already in use: most rule-classified rows
    # have been checked, while the invented mystery merchants remain a queue.
    conn.execute("UPDATE txn SET review_state='confirmed' WHERE category IS NOT NULL")

    from fin.llm import cache as llm_cache
    from fin.llm.categorize import PROMPT_VERSION
    for row in conn.execute(
            "SELECT DISTINCT description_norm FROM txn WHERE category IS NULL "):
        description = row["description_norm"]
        llm_cache.record(
            conn,
            task="categorize",
            ihash=llm_cache.input_hash("categorize", description),
            summary=description,
            output={"category": "shopping", "subcategory": "general",
                    "merchant": "Square Vendor", "tags": [], "confidence": 0.82},
            confidence=0.82,
            model="seed",
            prompt_version=PROMPT_VERSION,
        )
    if version:
        conn.execute(
            "INSERT INTO setting (key,value) VALUES ('demo_seed_version', %s) "
            "ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value",
            (version,),
        )
    conn.commit()
    if owns_connection:
        conn.close()
    print(f"seeded {inserted} transactions across {len(ACCOUNTS)} accounts")
    return inserted


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reset", action="store_true", help="replace the guarded demo schema")
    parser.add_argument("--schema", help="schema used by the guarded reset")
    args = parser.parse_args()
    seed_demo(reset=args.reset, schema=args.schema)


if __name__ == "__main__":
    main()
