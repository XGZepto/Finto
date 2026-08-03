"""Regression tests for bugs that could silently corrupt the ledger.

Each test here corresponds to a defect that was live and undetected. They are
kept together because they share a theme: every one of them failed *quietly* —
the ledger looked healthy, `check` reported no violations, and the numbers were
wrong anyway.
"""

from __future__ import annotations

from datetime import date
from pathlib import Path

import pytest

from fin import db as dbm
from fin.ingest import ingest_file, reconcile, resolve_card, unattributed_card_warnings
from fin.models import Account, Card, Institution, Money, ParsedTxn, Txn
from fin.parsers import institutions as _reg  # noqa: F401  (registers parsers)
from fin.parsers.base import ParseContext, parse_amount, select_parser
from fin.transfers import transfer_group_id

FIXTURES = Path(__file__).parent / "fixtures"


@pytest.fixture
def conn(tmp_path):
    c = dbm.connect(tmp_path / "test.db")
    dbm.init_db(c)
    dbm.upsert_institution(c, Institution(
        id="hsbc_hk", display_name="HSBC HK", country="HK"))
    dbm.upsert_institution(c, Institution(
        id="wise", display_name="Wise", country="HK"))
    dbm.upsert_institution(c, Institution(
        id="amex_us", display_name="AMEX US", country="US"))
    dbm.upsert_account(c, Account(
        id="hsbc_hk_current", institution_id="hsbc_hk", display_name="HSBC Current",
        account_type="checking", primary_currency="HKD"))
    dbm.upsert_account(c, Account(
        id="wise_hkd", institution_id="wise", display_name="Wise HKD",
        account_type="multi_currency", primary_currency="HKD",
        balance_group="wise_personal"))
    dbm.upsert_account(c, Account(
        id="amex_us_main", institution_id="amex_us", display_name="AMEX US",
        account_type="credit_card", primary_currency="USD"))
    c.commit()
    return c


# ---------------------------------------------------------------------------
# reconcile idempotency
# ---------------------------------------------------------------------------

def test_reconcile_is_idempotent(conn):
    """Re-running reconcile must converge, not accumulate transfer groups.

    The matcher used a fresh uuid4 per group, so every run created a new group
    for the same pair. txn.transfer_group_id pointed at the newest, leaving the
    older ones behind with two legs each — which meant every structural
    invariant stayed green while transfer_group grew without bound.
    """
    ingest_file(conn, FIXTURES / "hsbc_sample.csv",
                institution_id="hsbc_hk", account_id="hsbc_hk_current",
                default_currency="HKD")
    ingest_file(conn, FIXTURES / "wise_sample.csv",
                institution_id="wise", account_id="wise_hkd",
                default_currency="HKD")

    counts = []
    for _ in range(3):
        reconcile(conn)
        counts.append((
            conn.execute("SELECT COUNT(*) FROM transfer_group").fetchone()[0],
            conn.execute("SELECT COUNT(*) FROM transfer_leg").fetchone()[0],
        ))

    assert counts[0] == counts[1] == counts[2], f"groups grew across runs: {counts}"
    assert counts[0][0] >= 1, "expected the HSBC->Wise transfer to link"

    # And no transaction should ever be a leg of more than one group.
    multi = conn.execute(
        "SELECT COUNT(*) FROM (SELECT txn_id FROM transfer_leg "
        "GROUP BY txn_id HAVING COUNT(*) > 1)").fetchone()[0]
    assert multi == 0


def test_transfer_group_id_is_order_independent():
    assert transfer_group_id(["a", "b"]) == transfer_group_id(["b", "a"])
    assert transfer_group_id(["a", "b"]) != transfer_group_id(["a", "c"])


# ---------------------------------------------------------------------------
# binary files must not be claimed by CSV parsers
# ---------------------------------------------------------------------------

def test_pdf_is_not_claimed_by_a_csv_parser(tmp_path):
    """A PDF named after an institution must not match a CSV parser.

    MoxCsvParser.sniff scores 0.6 on the filename alone, which was enough to
    claim `mox-statement.pdf`, read zero rows from the binary and report success.
    """
    pdf = tmp_path / "mox-statement.pdf"
    pdf.write_bytes(b"%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n%%EOF\n")
    ctx = ParseContext(path=pdf, institution_id="mox", default_currency="HKD")
    assert select_parser(ctx) is None


def test_xlsx_is_not_claimed_by_a_csv_parser(tmp_path):
    xlsx = tmp_path / "hsbc-statement.xlsx"
    xlsx.write_bytes(b"PK\x03\x04" + b"\x00" * 64)
    ctx = ParseContext(path=xlsx, institution_id="hsbc_hk", default_currency="HKD")
    assert select_parser(ctx) is None


def test_zero_txn_import_does_not_burn_the_file_hash(conn, tmp_path):
    """An import that yields nothing must stay re-importable.

    Recording the sha256 of a file we failed to read means `file_already_imported`
    refuses it forever — so a statement you believe is in the ledger never is.
    """
    empty = tmp_path / "hsbc-empty.csv"
    empty.write_text("Date,Transaction Details,Deposit,Withdrawal,Balance\n")

    r = ingest_file(conn, empty, institution_id="hsbc_hk",
                    account_id="hsbc_hk_current", default_currency="HKD")
    assert r["status"] == "error"
    assert conn.execute("SELECT COUNT(*) FROM statement_file").fetchone()[0] == 0

    # The same path, once it actually has rows, imports normally.
    empty.write_text(
        "Date,Transaction Details,Deposit,Withdrawal,Balance\n"
        "02/01/2025,SALARY,45000.00,,88000.00\n")
    r2 = ingest_file(conn, empty, institution_id="hsbc_hk",
                     account_id="hsbc_hk_current", default_currency="HKD")
    assert r2["status"] == "imported" and r2["txns"] == 1


# ---------------------------------------------------------------------------
# card reissue
# ---------------------------------------------------------------------------

def _cards():
    return [
        Card(id="old", account_id="amex_us_main", cardholder_name="ZEPTO X",
             last4="1001", closed_on=date(2025, 6, 30)),
        Card(id="new", account_id="amex_us_main", cardholder_name="ZEPTO X",
             last4="5566", issued_on=date(2025, 7, 1), replaces_card_id="old"),
    ]


def _parsed(when: date, last4: str | None, hint: str | None = "ZEPTO X"):
    return ParsedTxn(txn_date=when, booked=Money(amount=-1000, currency="USD"),
                     description_raw="COFFEE", card_last4=last4,
                     cardholder_hint=hint)


def test_reissued_card_attributes_by_last4():
    cards = _cards()
    assert resolve_card(_parsed(date(2025, 3, 1), "1001"), "amex_us_main", cards) == "old"
    assert resolve_card(_parsed(date(2025, 8, 1), "5566"), "amex_us_main", cards) == "new"


def test_card_attribution_is_date_scoped():
    """A reissued number must not claim charges made before it existed."""
    cards = _cards()
    # March, but carrying the *new* number: the new card wasn't issued yet, so
    # last4 can't match it. The name fallback is ambiguous across both cards, so
    # this correctly declines rather than guessing.
    assert resolve_card(_parsed(date(2025, 3, 1), "5566"), "amex_us_main", cards) == "old"


def test_ambiguous_substring_name_does_not_win():
    """A short hint must not claim a longer registered name."""
    cards = [
        Card(id="primary", account_id="A", cardholder_name="JOANNA CHAN", last4="1111"),
        Card(id="supp", account_id="A", cardholder_name="JO CHAN", last4="2222"),
    ]
    exact = ParsedTxn(txn_date=date(2025, 1, 1),
                      booked=Money(amount=-100, currency="USD"),
                      description_raw="X", cardholder_hint="JO CHAN")
    assert resolve_card(exact, "A", cards) == "supp"

    # "JO" is a substring of both registered names — ambiguous, so decline.
    ambiguous = ParsedTxn(txn_date=date(2025, 1, 1),
                          booked=Money(amount=-100, currency="USD"),
                          description_raw="X", cardholder_hint="JO")
    assert resolve_card(ambiguous, "A", cards) is None


def test_unknown_last4_produces_a_warning():
    """An unregistered card number must not fail attribution silently."""
    cards = [Card(id="old", account_id="amex_us_main",
                  cardholder_name="ZEPTO X", last4="1001")]
    parsed = [_parsed(date(2025, 8, 1), "9999", hint=None)]
    txns = [Txn(account_id="amex_us_main", txn_date=date(2025, 8, 1),
                booked=Money(amount=-1000, currency="USD"),
                description_raw="COFFEE", statement_file_id="sf", card_id=None)]
    warnings = unattributed_card_warnings(parsed, txns, "amex_us_main", cards)
    assert warnings and "9999" in warnings[0]
    assert "replaces_card_id" in warnings[0]


def test_card_lineage_rolls_up_reissue_chains(conn):
    for c in (Card(id="c1", account_id="amex_us_main", cardholder_name="ZEPTO X",
                   last4="1001"),
              Card(id="c2", account_id="amex_us_main", cardholder_name="ZEPTO X",
                   last4="5566", replaces_card_id="c1"),
              Card(id="c3", account_id="amex_us_main", cardholder_name="ZEPTO X",
                   last4="7788", replaces_card_id="c2")):
        dbm.upsert_card(conn, c)
    conn.commit()
    roots = dbm.card_lineage_roots(conn)
    assert roots == {"c1": "c1", "c2": "c1", "c3": "c1"}


def test_card_lineage_survives_a_cycle(conn):
    """A config mistake must not hang the rollup."""
    dbm.upsert_card(conn, Card(id="a", account_id="amex_us_main",
                               cardholder_name="X", last4="1111"))
    dbm.upsert_card(conn, Card(id="b", account_id="amex_us_main",
                               cardholder_name="X", last4="2222",
                               replaces_card_id="a"))
    conn.execute("UPDATE card SET replaces_card_id='b' WHERE id='a'")
    conn.commit()
    roots = dbm.card_lineage_roots(conn)
    assert set(roots) == {"a", "b"}


def test_migration_adds_replaces_card_id(tmp_path):
    """An existing database must pick up columns added after it was created."""
    c = dbm.connect(tmp_path / "old.db")
    c.executescript(
        "CREATE TABLE institution (id TEXT PRIMARY KEY, display_name TEXT, "
        "country TEXT, timezone TEXT);"
        "CREATE TABLE account (id TEXT PRIMARY KEY);"
        "CREATE TABLE card (id TEXT PRIMARY KEY, account_id TEXT, "
        "cardholder_name TEXT, last4 TEXT, is_supplementary INTEGER, "
        "issued_on TEXT, closed_on TEXT);")
    c.commit()
    assert "replaces_card_id" not in {
        r["name"] for r in c.execute("PRAGMA table_info(card)")}

    applied = dbm.migrate(c)
    assert "card.replaces_card_id" in applied
    assert "replaces_card_id" in {
        r["name"] for r in c.execute("PRAGMA table_info(card)")}
    assert dbm.migrate(c) == []   # idempotent


# ---------------------------------------------------------------------------
# amount parsing
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("raw,expected", [
    ("1,234.56CR", 123456),     # credit = money in = positive
    ("1,234.56DR", -123456),    # debit  = money out = negative
    ("1,234.56", 123456),
    ("(1,234.56)", -123456),
    ("-1,234.56", -123456),
    ("+1,234.56", 123456),
])
def test_trailing_sign_markers(raw, expected):
    assert parse_amount(raw, "HKD").amount == expected


def test_credit_positive_flag_is_honoured():
    """The flag used to be dead — CR/DR overrode it unconditionally."""
    assert parse_amount("100.00CR", "HKD", credit_positive=True).amount == 10000
    assert parse_amount("100.00CR", "HKD", credit_positive=False).amount == -10000
    assert parse_amount("100.00DR", "HKD", credit_positive=True).amount == -10000
    assert parse_amount("100.00DR", "HKD", credit_positive=False).amount == 10000


# ---------------------------------------------------------------------------
# balance capture coverage
# ---------------------------------------------------------------------------

def test_generic_parser_captures_balances(conn, tmp_path):
    """`check` can only verify accounts whose parser captured a balance column."""
    f = tmp_path / "somebank.csv"
    f.write_text(
        "Date,Description,Amount,Balance\n"
        "02/01/2025,SALARY,45000.00,88000.00\n"
        "05/01/2025,GROCERIES,-500.00,87500.00\n")
    r = ingest_file(conn, f, institution_id="hsbc_hk",
                    account_id="hsbc_hk_current", default_currency="HKD")
    assert r["status"] == "imported"
    assert r["balances"] == 2, "generic parser should capture the balance column"
