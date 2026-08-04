"""End-to-end tests over synthetic fixtures.

Every fixture below is invented. No real account data.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal
from pathlib import Path

import pytest

from fin import db as dbm
from fin.dedup import dedup_exact, find_fuzzy_duplicates
from fin.ingest import ingest_file, reconcile
from fin.models import (
    Account,
    AccountType,
    Institution,
    Money,
    Txn,
    TxnStatus,
    normalize_description,
)
from fin.parsers.base import ParseContext, parse_amount, parse_date, select_parser
from fin.transfers import find_transfers

FIXTURES = Path(__file__).parent / "fixtures"


# ---------------------------------------------------------------------------
# Money
# ---------------------------------------------------------------------------

def test_money_roundtrip_two_decimals():
    m = Money.from_decimal("1234.56", "HKD")
    assert m.amount == 123456
    assert m.to_decimal() == Decimal("1234.56")


def test_money_zero_decimal_currency():
    m = Money.from_decimal("1200", "JPY")
    assert m.amount == 1200          # JPY has no minor unit
    assert m.to_decimal() == Decimal("1200")


def test_money_three_decimal_currency():
    assert Money.from_decimal("1.234", "KWD").amount == 1234


# ---------------------------------------------------------------------------
# Amount / date parsing — the notations statements actually use
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("raw,expected", [
    ("1,234.56", 123456),
    ("(1,234.56)", -123456),
    ("-1,234.56", -123456),
    ("1234.56CR", 123456),
    ("1234.56DR", -123456),
    ("HKD1,234.56", 123456),
])
def test_parse_amount_notations(raw, expected):
    assert parse_amount(raw, "HKD").amount == expected


def test_parse_date_regional_ordering():
    # The same string means different days depending on the issuer.
    assert parse_date("03/08/2026", dayfirst=True) == date(2026, 8, 3)
    assert parse_date("03/08/2026", dayfirst=False) == date(2026, 3, 8)


# ---------------------------------------------------------------------------
# Description normalisation
# ---------------------------------------------------------------------------

def test_normalize_strips_reference_noise():
    a = normalize_description("STARBUCKS HK  REF ABC123456  03/08")
    b = normalize_description("Starbucks HK  REF ZZZ999888  05/08")
    assert a == b == "STARBUCKS HK"


# ---------------------------------------------------------------------------
# Dedup
# ---------------------------------------------------------------------------

def _txn(**kw) -> Txn:
    base = dict(
        account_id="acct1",
        txn_date=date(2026, 3, 1),
        booked=Money(amount=-5000, currency="HKD"),
        description_raw="CAFE ABC",
        statement_file_id="sf1",
    )
    base.update(kw)
    return Txn(**base)


def test_exact_dedup_collapses_a_charge_restated_by_two_statements():
    a, b = _txn(statement_file_id="jan"), _txn(statement_file_id="feb")
    assert a.dedup_key == b.dedup_key
    survivors, merged = dedup_exact([a, b])
    assert merged == 1
    assert len(survivors) == 1


def test_two_identical_charges_in_one_statement_are_two_charges():
    """Indistinguishable by key; the source lists each movement once."""
    a, b = _txn(statement_file_id="jan"), _txn(statement_file_id="jan")
    assert a.dedup_key == b.dedup_key
    survivors, merged = dedup_exact([a, b])
    assert merged == 0
    assert len(survivors) == 2


def test_posted_row_wins_over_pending():
    pending = _txn(status=TxnStatus.PENDING, statement_file_id="export1")
    posted = _txn(status=TxnStatus.POSTED, statement_file_id="export2")
    _, merged = dedup_exact([pending, posted])
    assert merged == 1
    assert pending.duplicate_of_id == posted.id
    assert posted.duplicate_of_id is None


def test_same_charge_collides_when_only_one_source_carries_the_reference():
    """The statement prints an issuer reference; the CSV export omits it."""
    from_pdf = _txn(external_ref="HC12552952988759", statement_file_id="stmt")
    from_csv = _txn(statement_file_id="export")
    assert from_pdf.dedup_key == from_csv.dedup_key
    survivors, merged = dedup_exact([from_csv, from_pdf])
    assert merged == 1
    # The copy carrying the issuer's reference is the one worth keeping.
    assert survivors[0].external_ref == "HC12552952988759"


def test_different_references_still_separate_when_anything_else_differs():
    a = _txn(external_ref="TW-1", booked=Money(amount=-100, currency="HKD"),
             statement_file_id="a")
    b = _txn(external_ref="TW-2", booked=Money(amount=-200, currency="HKD"),
             statement_file_id="b")
    assert a.dedup_key != b.dedup_key
    _, merged = dedup_exact([a, b])
    assert merged == 0


def test_fuzzy_catches_date_shifted_duplicate():
    a = _txn(txn_date=date(2026, 3, 1), status=TxnStatus.PENDING,
             description_raw="CAFE ABC KOWLOON")
    b = _txn(txn_date=date(2026, 3, 3), status=TxnStatus.POSTED,
             description_raw="CAFE ABC KOWLOON")
    cands = find_fuzzy_duplicates([a, b])
    assert len(cands) == 1
    assert cands[0].score > 0.7


def test_opposite_signs_are_never_duplicates():
    out = _txn(booked=Money(amount=-5000, currency="HKD"))
    inc = _txn(booked=Money(amount=5000, currency="HKD"), account_id="acct2")
    assert find_fuzzy_duplicates([out, inc]) == []


def test_cross_account_requires_whitelist():
    a = _txn(account_id="acct1", description_raw="CAFE ABC")
    b = _txn(account_id="acct2", description_raw="CAFE ABC")
    assert find_fuzzy_duplicates([a, b]) == []
    pairs = {tuple(sorted(("acct1", "acct2")))}
    assert len(find_fuzzy_duplicates([a, b], cross_account_pairs=pairs)) == 1


# ---------------------------------------------------------------------------
# Transfers
# ---------------------------------------------------------------------------

def _accounts() -> dict[str, Account]:
    return {
        "hsbc": Account(id="hsbc", institution_id="hsbc_hk", display_name="HSBC",
                        account_type=AccountType.CHECKING, primary_currency="HKD"),
        "mox": Account(id="mox", institution_id="mox", display_name="Mox",
                       account_type=AccountType.CHECKING, primary_currency="HKD"),
        "amex": Account(id="amex", institution_id="amex_hk", display_name="AMEX",
                        account_type=AccountType.CREDIT_CARD, primary_currency="HKD"),
    }


def test_internal_transfer_is_linked():
    out = _txn(account_id="hsbc", booked=Money(amount=-2000000, currency="HKD"),
               txn_date=date(2026, 4, 1), description_raw="TRANSFER TO MOX")
    inc = _txn(account_id="mox", booked=Money(amount=2000000, currency="HKD"),
               txn_date=date(2026, 4, 2), description_raw="FPS TRANSFER IN")
    rep = find_transfers([out, inc], _accounts())
    assert len(rep.groups) == 1
    assert out.transfer_group_id == inc.transfer_group_id is not None


def test_cc_payment_classified_correctly():
    from fin.models import TransferKind
    out = _txn(account_id="hsbc", booked=Money(amount=-500000, currency="HKD"),
               txn_date=date(2026, 4, 10), description_raw="AUTOPAY AMEX")
    inc = _txn(account_id="amex", booked=Money(amount=500000, currency="HKD"),
               txn_date=date(2026, 4, 11), description_raw="PAYMENT RECEIVED THANK YOU")
    rep = find_transfers([out, inc], _accounts())
    assert len(rep.groups) == 1
    assert rep.groups[0].kind == TransferKind.CC_PAYMENT


def test_transfer_fee_is_captured():
    out = _txn(account_id="hsbc", booked=Money(amount=-1000000, currency="HKD"),
               txn_date=date(2026, 4, 1), description_raw="TRANSFER OUT")
    inc = _txn(account_id="mox", booked=Money(amount=999500, currency="HKD"),
               txn_date=date(2026, 4, 1), description_raw="TRANSFER IN")
    rep = find_transfers([out, inc], _accounts())
    assert rep.groups or rep.candidates   # matched somewhere
    if rep.groups:
        assert rep.groups[0].fee.amount == 500


def test_unrelated_amounts_do_not_match():
    out = _txn(account_id="hsbc", booked=Money(amount=-12345, currency="HKD"),
               txn_date=date(2026, 4, 1), description_raw="GROCERY")
    inc = _txn(account_id="mox", booked=Money(amount=98765, currency="HKD"),
               txn_date=date(2026, 4, 1), description_raw="SALARY")
    rep = find_transfers([out, inc], _accounts())
    assert not rep.groups and not rep.candidates


def test_a_txn_is_only_one_leg():
    """Two identical inflows must not both claim the same outflow."""
    out = _txn(account_id="hsbc", booked=Money(amount=-100000, currency="HKD"),
               txn_date=date(2026, 4, 1), description_raw="TRANSFER OUT")
    in1 = _txn(account_id="mox", booked=Money(amount=100000, currency="HKD"),
               txn_date=date(2026, 4, 1), description_raw="TRANSFER IN")
    in2 = _txn(account_id="amex", booked=Money(amount=100000, currency="HKD"),
               txn_date=date(2026, 4, 1), description_raw="TRANSFER IN")
    find_transfers([out, in1, in2], _accounts())
    claimed = [t for t in (out, in1, in2) if t.transfer_group_id]
    assert len(claimed) <= 2


# ---------------------------------------------------------------------------
# Parser selection + full ingest
# ---------------------------------------------------------------------------

def test_wise_parser_wins_on_wise_file():
    ctx = ParseContext(path=FIXTURES / "wise_sample.csv")
    p = select_parser(ctx)
    assert p is not None and p.parser_id == "wise_csv"


def test_amex_parser_flips_sign():
    ctx = ParseContext(path=FIXTURES / "amex_us_sample.csv",
                       institution_id="amex_us", default_currency="USD")
    p = select_parser(ctx)
    assert p.parser_id == "amex_csv"
    res = p.parse(ctx)
    purchases = [t for t in res.txns if "STARBUCKS" in t.description_raw.upper()]
    assert purchases and purchases[0].booked.amount < 0   # outflow is negative


def test_full_pipeline(tmp_path):
    db = tmp_path / "t.db"
    conn = dbm.connect(db)
    dbm.init_db(conn)

    dbm.upsert_institution(conn, Institution(id="wise", display_name="Wise", country="HK"))
    dbm.upsert_institution(conn, Institution(id="hsbc_hk", display_name="HSBC", country="HK"))
    dbm.upsert_account(conn, Account(id="wise_hkd", institution_id="wise",
                                     display_name="Wise HKD",
                                     account_type=AccountType.MULTI_CURRENCY,
                                     primary_currency="HKD"))
    dbm.upsert_account(conn, Account(id="hsbc_current", institution_id="hsbc_hk",
                                     display_name="HSBC Current",
                                     account_type=AccountType.CHECKING,
                                     primary_currency="HKD"))
    conn.commit()

    r1 = ingest_file(conn, FIXTURES / "wise_sample.csv",
                     institution_id="wise", account_id="wise_hkd")
    assert r1["status"] == "imported" and r1["txns"] > 0

    # Re-importing the identical file must be a no-op.
    r2 = ingest_file(conn, FIXTURES / "wise_sample.csv",
                     institution_id="wise", account_id="wise_hkd")
    assert r2["status"] == "skipped"

    r3 = ingest_file(conn, FIXTURES / "hsbc_sample.csv",
                     institution_id="hsbc_hk", account_id="hsbc_current")
    assert r3["status"] == "imported"

    summary = reconcile(conn)
    assert summary["transactions"] > 0
    # The fixtures contain one HSBC->Wise transfer pair.
    assert summary["transfers_linked"] + summary["transfer_candidates"] >= 1


def test_hsbc_split_debit_credit_columns():
    ctx = ParseContext(path=FIXTURES / "hsbc_sample.csv",
                       institution_id="hsbc_hk", default_currency="HKD")
    p = select_parser(ctx)
    assert p.parser_id == "hsbc_hk_csv"
    res = p.parse(ctx)
    assert any(t.booked.amount > 0 for t in res.txns)   # deposits positive
    assert any(t.booked.amount < 0 for t in res.txns)   # withdrawals negative
