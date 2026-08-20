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
from fin.ingest import (
    ingest_file,
    reattribute_cards,
    reconcile,
    resolve_card,
    statement_content_fingerprint,
    unattributed_card_warnings,
)
from fin.models import Account, Card, Institution, Money, ParsedTxn, Txn
from fin.parsers import institutions as _reg  # noqa: F401  (registers parsers)
from fin.parsers.base import ParseContext, ParseResult, parse_amount, select_parser
from fin.parsers.pdf import _to_parse_result
from fin.pdf.extract import PdfDocument
from fin.pdf.template import ExtractedRow, TemplateResult
from fin.transfers import transfer_group_id

FIXTURES = Path(__file__).parent / "fixtures"


def test_statement_fingerprint_ignores_source_pdf_bytes():
    parsed = ParseResult(
        txns=[ParsedTxn(
            txn_date=date(2026, 8, 10),
            booked=Money(amount=-1500, currency="USD"),
            description_raw="Monthly Service Fee",
            extra={"account_hint": "Chase Total Checking"},
        )],
        raw_rows=[{"pdf_metadata": "render one"}],
        period_start=date(2026, 7, 9),
        period_end=date(2026, 8, 10),
        balances=[
            (date(2026, 8, 10), Money(amount=50103, currency="USD"),
             "Chase Total Checking", "closing"),
            (date(2026, 8, 10), Money(amount=30000, currency="USD"),
             "Chase Savings", "closing"),
        ],
    )
    rerendered = ParseResult(
        txns=parsed.txns,
        raw_rows=[{"pdf_metadata": "render two"}],
        period_start=parsed.period_start,
        period_end=parsed.period_end,
        balances=parsed.balances,
    )
    assert statement_content_fingerprint("chase_us_consolidated", parsed) == (
        statement_content_fingerprint("chase_us_consolidated", rerendered)
    )


def test_hsbc_pdf_card_number_is_normalised_to_last_four():
    row = ExtractedRow(
        txn_date=date(2026, 8, 1),
        description="TEST MERCHANT",
        amount=Money(amount=-1000, currency="HKD"),
        currency="HKD",
        section="charges",
        page_no=0,
        line_no=0,
        details={"card.number": "1234 5678 9012 3456", "card.holder": "ALEX E"},
    )
    result = _to_parse_result(
        PdfDocument(path="statement.pdf", pages=[]),
        "hsbc_hk_card",
        TemplateResult(rows=[row]),
        [],
    )
    assert result.txns[0].card_last4 == "3456"
    assert result.txns[0].cardholder_hint == "ALEX E"


@pytest.fixture
def conn(database_url):
    c = dbm.connect(database_url)
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
    yield c
    c.close()


def test_reattribute_reads_normalized_transaction_details(conn):
    dbm.upsert_card(conn, Card(
        id="hsbc_card_3456",
        account_id="hsbc_hk_current",
        cardholder_name="ALEX E",
        last4="3456",
    ))
    conn.execute(
        "INSERT INTO statement_file "
        "(id,source_path,file_sha256,institution_id,account_id,file_format,"
        "parser_id,parser_version,imported_at,row_count) "
        "VALUES ('hsbc-card-file','card.pdf','hsbc-card-hash','hsbc_hk',"
        "'hsbc_hk_current','pdf','pdf_statement','2.0',CURRENT_TIMESTAMP::text,1)"
    )
    txn = Txn(
        account_id="hsbc_hk_current",
        txn_date=date(2026, 8, 1),
        booked=Money(amount=-1000, currency="HKD"),
        description_raw="TEST MERCHANT",
        statement_file_id="hsbc-card-file",
        details={"card.number": "1234 5678 9012 3456"},
    )
    dbm.insert_txns(conn, [txn])
    conn.commit()

    assert reattribute_cards(conn, statement_file_id="hsbc-card-file") == 1
    row = conn.execute("SELECT card_id FROM txn WHERE id=%s", (txn.id,)).fetchone()
    assert row["card_id"] == "hsbc_card_3456"


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
            conn.execute("SELECT COUNT(*) AS count_value FROM transfer_group"
                         ).fetchone()["count_value"],
            conn.execute("SELECT COUNT(*) AS count_value FROM transfer_leg"
                         ).fetchone()["count_value"],
        ))

    assert counts[0] == counts[1] == counts[2], f"groups grew across runs: {counts}"
    assert counts[0][0] >= 1, "expected the HSBC->Wise transfer to link"

    # And no transaction should ever be a leg of more than one group.
    multi = conn.execute(
        "SELECT COUNT(*) AS count_value FROM (SELECT txn_id FROM transfer_leg "
        "GROUP BY txn_id HAVING COUNT(*) > 1) x").fetchone()["count_value"]
    assert multi == 0


def test_reconcile_open_transfer_queue_is_idempotent(conn):
    ingest_file(conn, FIXTURES / "hsbc_sample.csv",
                institution_id="hsbc_hk", account_id="hsbc_hk_current",
                default_currency="HKD")
    ingest_file(conn, FIXTURES / "wise_sample.csv",
                institution_id="wise", account_id="wise_hkd",
                default_currency="HKD")
    counts = []
    for _ in range(3):
        reconcile(conn)
        counts.append(conn.execute(
            "SELECT COUNT(*) AS count_value FROM transfer_candidate WHERE resolution='open'"
        ).fetchone()["count_value"])
    assert counts[0] == counts[1] == counts[2]


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
    """A failed import must stay re-importable; a provably empty one records.

    Two distinct outcomes for zero rows:
    - header-only file: there is literally nothing to miss, so it imports as
      an empty statement (allow_empty) and its hash is recorded;
    - rows present but unreadable: nothing may be recorded, because burning
      the sha256 would make `file_already_imported` refuse it forever — a
      statement you believe is in the ledger never would be.
    """
    empty = tmp_path / "hsbc-empty.csv"
    empty.write_text("Date,Transaction Details,Deposit,Withdrawal,Balance\n")

    r = ingest_file(conn, empty, institution_id="hsbc_hk",
                    account_id="hsbc_hk_current", default_currency="HKD")
    assert r["status"] == "imported" and r["txns"] == 0

    broken = tmp_path / "hsbc-broken.csv"
    broken.write_text(
        "Date,Transaction Details,Deposit,Withdrawal,Balance\n"
        "not-a-date,SALARY,45000.00,,88000.00\n")
    r2 = ingest_file(conn, broken, institution_id="hsbc_hk",
                     account_id="hsbc_hk_current", default_currency="HKD")
    assert r2["status"] == "error"
    assert conn.execute(
        "SELECT COUNT(*) AS count_value FROM statement_file "
        "WHERE source_path LIKE '%broken%'"
    ).fetchone()["count_value"] == 0

    # The same path, once it actually parses, imports normally.
    broken.write_text(
        "Date,Transaction Details,Deposit,Withdrawal,Balance\n"
        "02/01/2025,SALARY,45000.00,,88000.00\n")
    r3 = ingest_file(conn, broken, institution_id="hsbc_hk",
                     account_id="hsbc_hk_current", default_currency="HKD")
    assert r3["status"] == "imported" and r3["txns"] == 1


# ---------------------------------------------------------------------------
# card reissue
# ---------------------------------------------------------------------------

def _cards():
    return [
        Card(id="old", account_id="amex_us_main", cardholder_name="ALEX E",
             last4="1001", closed_on=date(2025, 6, 30)),
        Card(id="new", account_id="amex_us_main", cardholder_name="ALEX E",
             last4="5566", issued_on=date(2025, 7, 1), replaces_card_id="old"),
    ]


def _parsed(when: date, last4: str | None, hint: str | None = "ALEX E"):
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
                  cardholder_name="ALEX E", last4="1001")]
    parsed = [_parsed(date(2025, 8, 1), "9999", hint=None)]
    txns = [Txn(account_id="amex_us_main", txn_date=date(2025, 8, 1),
                booked=Money(amount=-1000, currency="USD"),
                description_raw="COFFEE", statement_file_id="sf", card_id=None)]
    warnings = unattributed_card_warnings(parsed, txns, "amex_us_main", cards)
    assert warnings and "9999" in warnings[0]
    assert "replaces_card_id" in warnings[0]


def test_card_lineage_rolls_up_reissue_chains(conn):
    for c in (Card(id="c1", account_id="amex_us_main", cardholder_name="ALEX E",
                   last4="1001"),
              Card(id="c2", account_id="amex_us_main", cardholder_name="ALEX E",
                   last4="5566", replaces_card_id="c1"),
              Card(id="c3", account_id="amex_us_main", cardholder_name="ALEX E",
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


def test_cjk_description_normalises_to_itself():
    """Mox FPS memos arrive in Chinese. If normalization dropped every CJK
    character the norm would be "", and — via validate_assignment — the
    Txn validator used to recurse until the interpreter gave up."""
    from fin.models import normalize_description
    assert normalize_description("阿貓的貓") == "阿貓的貓"
    assert normalize_description("PARKNSHOP 百佳 12/03") == "PARKNSHOP 百佳"
    t = Txn(account_id="mox_hkd", txn_date=date(2025, 11, 17),
            booked=Money(amount=-100, currency="HKD"),
            description_raw="阿貓的貓", statement_file_id="sf")
    assert t.description_norm == "阿貓的貓"
    assert t.dedup_key


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
