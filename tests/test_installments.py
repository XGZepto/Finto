"""Instalment plan detection, refund linking, and metadata extraction."""

from __future__ import annotations

from datetime import date

import pytest

from fin import db as dbm
from fin.enrich import extract_details, is_travel
from fin.ingest import ingest_file, reconcile
from fin.installments import (
    find_installments,
    find_origination_pairs,
    parse_installment_marker,
    plan_subject,
)
from fin.models import Money, Txn, TxnKind
from fin.refunds import apply_refund_links, find_refunds, looks_like_refund


def _txn(desc, amount, when, *, account="amex_hk_main", ccy="HKD",
         merchant=None, kind=TxnKind.UNKNOWN):
    return Txn(account_id=account, txn_date=when,
               booked=Money(amount=amount, currency=ccy),
               description_raw=desc, merchant=merchant, kind=kind,
               statement_file_id="sf")


# ---------------------------------------------------------------------------
# Marker parsing
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("desc,expected", [
    ("INSTALMENT 03/12 BEST BUY TST", (3, 12)),
    ("INSTALLMENT 3 OF 12 BEST BUY", (3, 12)),
    ("MTHLY INSTAL 01/24 SONY STORE", (1, 24)),
    ("BEST BUY TST INSTALMENT 12/12", (12, 12)),
    ("分期 03/12 蘋果專門店", (3, 12)),
    ("INST 7-18 FURNITURE CO", (7, 18)),
])
def test_marker_is_parsed(desc, expected):
    assert parse_installment_marker(desc) == expected


@pytest.mark.parametrize("desc", [
    "PARKNSHOP SUPERMARKET 03/01",   # an embedded date, not an instalment
    "STARBUCKS STORE 1234",
    "PAYMENT RECEIVED THANK YOU",
    "",
])
def test_non_installments_are_not_matched(desc):
    assert parse_installment_marker(desc) is None


def test_marker_must_be_read_before_normalisation():
    """normalize_description strips dd/mm as noise and would erase the marker."""
    from fin.models import normalize_description
    raw = "INSTALMENT 03/12 BEST BUY TST"
    assert parse_installment_marker(raw) == (3, 12)
    assert parse_installment_marker(normalize_description(raw)) is None


def test_plan_subject_collapses_sequence():
    a = plan_subject("INSTALMENT 03/12 BEST BUY TST")
    b = plan_subject("INSTALMENT 04/12 BEST BUY TST")
    assert a == b == "BEST BUY TST"


# ---------------------------------------------------------------------------
# Plan detection
# ---------------------------------------------------------------------------

def test_monthly_charges_group_into_a_plan():
    txns = [_txn(f"INSTALMENT {i:02d}/12 BEST BUY TST", -100000,
                 date(2025, i, 15)) for i in range(1, 7)]
    report = find_installments(txns)

    assert len(report.plans) == 1
    plan = report.plans[0]
    assert plan.term_months == 12
    # Principal is the whole commitment, not what has been paid so far.
    assert plan.principal.amount == -1200000
    assert plan.start_date == date(2025, 1, 15)
    assert plan.status.value == "active"
    assert len(report.assignments) == 6


def test_completed_plan_is_marked_completed():
    txns = [_txn(f"INSTALMENT {i:02d}/6 SONY STORE", -50000, date(2025, i, 10))
            for i in range(1, 7)]
    plan = find_installments(txns).plans[0]
    assert plan.status.value == "completed"


def test_partial_plan_backdates_its_start():
    """Importing from month 4 still dates the plan to when it actually began."""
    txns = [_txn(f"INSTALMENT {i:02d}/12 SOFA CO", -80000, date(2025, i, 20))
            for i in range(4, 8)]
    plan = find_installments(txns).plans[0]
    assert plan.start_date == date(2025, 1, 20)


def test_irregular_spacing_goes_to_review_not_auto_created():
    txns = [
        _txn("INSTALMENT 01/12 ODD CO", -100000, date(2025, 1, 15)),
        _txn("INSTALMENT 02/12 ODD CO", -100000, date(2025, 1, 18)),  # 3 days later
        _txn("INSTALMENT 03/12 ODD CO", -100000, date(2025, 1, 21)),
    ]
    report = find_installments(txns)
    assert report.plans == []
    assert len(report.candidates) == 1


def test_repeated_sequence_is_never_auto_created():
    """Two copies of instalment 1 means the grouping is ambiguous."""
    txns = [
        _txn("INSTALMENT 01/12 DUP CO", -100000, date(2025, 1, 15)),
        _txn("INSTALMENT 01/12 DUP CO", -100000, date(2025, 2, 15)),
        _txn("INSTALMENT 02/12 DUP CO", -100000, date(2025, 3, 15)),
    ]
    assert find_installments(txns).plans == []


def test_unmarked_monthly_charges_are_never_grouped():
    """A recurring subscription is not an instalment plan."""
    txns = [_txn("NETFLIX SUBSCRIPTION", -9800, date(2025, i, 5))
            for i in range(1, 7)]
    report = find_installments(txns)
    assert report.plans == [] and report.candidates == []


def test_plan_ids_are_deterministic():
    def build():
        return find_installments(
            [_txn(f"INSTALMENT {i:02d}/12 BEST BUY", -100000, date(2025, i, 15))
             for i in range(1, 5)]).plans[0].id
    assert build() == build()


# ---------------------------------------------------------------------------
# Shape (b): gross charge then reversal
# ---------------------------------------------------------------------------

def test_origination_and_reversal_are_paired():
    txns = [
        _txn("BEST BUY TST INSTALMENT PLAN", -1200000, date(2025, 1, 15)),
        _txn("INSTALMENT PLAN CREDIT BEST BUY TST", 1100000, date(2025, 1, 15)),
        _txn("INSTALMENT 01/12 BEST BUY TST", -100000, date(2025, 1, 15)),
    ]
    pairs = find_origination_pairs(txns)
    assert len(pairs) == 1
    charge, credit = pairs[0]
    assert charge.booked.amount == -1200000
    assert credit.booked.amount == 1100000


def test_credit_larger_than_charge_is_not_an_origination():
    txns = [
        _txn("SOMETHING INSTALMENT", -100000, date(2025, 1, 15)),
        _txn("INSTALMENT PLAN CREDIT SOMETHING", 500000, date(2025, 1, 15)),
    ]
    assert find_origination_pairs(txns) == []


# ---------------------------------------------------------------------------
# Refunds
# ---------------------------------------------------------------------------

def test_refund_links_to_its_purchase():
    purchase = _txn("UNIQLO HK CAUSEWAY BAY", -49900, date(2025, 3, 1),
                    merchant="UNIQLO")
    refund = _txn("REFUND UNIQLO HK CAUSEWAY BAY", 49900, date(2025, 3, 20),
                  merchant="UNIQLO")
    report = find_refunds([purchase, refund])
    assert report.links == {refund.id: purchase.id}

    apply_refund_links([purchase, refund], report)
    assert refund.refund_of_id == purchase.id
    assert refund.kind == TxnKind.REFUND


def test_refund_inherits_the_purchase_category():
    purchase = _txn("UNIQLO HK", -49900, date(2025, 3, 1), merchant="UNIQLO")
    purchase.category = "shopping"
    purchase.subcategory = "clothing"
    refund = _txn("REFUND UNIQLO HK", 49900, date(2025, 3, 20), merchant="UNIQLO")
    report = find_refunds([purchase, refund])
    apply_refund_links([purchase, refund], report)
    assert refund.category == "shopping"
    assert refund.subcategory == "clothing"


def test_refund_never_precedes_its_purchase():
    purchase = _txn("UNIQLO HK", -49900, date(2025, 4, 1), merchant="UNIQLO")
    refund = _txn("REFUND UNIQLO HK", 49900, date(2025, 3, 1), merchant="UNIQLO")
    assert find_refunds([purchase, refund]).links == {}


def test_refund_larger_than_purchase_is_rejected():
    purchase = _txn("UNIQLO HK", -10000, date(2025, 3, 1), merchant="UNIQLO")
    refund = _txn("REFUND UNIQLO HK", 99900, date(2025, 3, 20), merchant="UNIQLO")
    assert find_refunds([purchase, refund]).links == {}


def test_card_payment_is_not_treated_as_a_refund():
    payment = _txn("PAYMENT RECEIVED THANK YOU", 500000, date(2025, 3, 20),
                   kind=TxnKind.CC_PAYMENT)
    assert not looks_like_refund(payment)


def test_partial_refund_is_matched_but_scored_lower():
    purchase = _txn("SOME SHOP LTD", -100000, date(2025, 3, 1), merchant="SOME SHOP")
    refund = _txn("REFUND SOME SHOP LTD", 30000, date(2025, 3, 10),
                  merchant="SOME SHOP")
    assert find_refunds([purchase, refund]).links == {refund.id: purchase.id}


# ---------------------------------------------------------------------------
# Metadata extraction
# ---------------------------------------------------------------------------

def test_amex_flight_details_are_extracted():
    blob = (
        "XXXXXXXXXXX1001\n"
        "PASSENGER NAME  CHAN/MEI LING\n"
        "TICKET NUMBER 160 1234567890\n"
        "CARRIER: CX\n"
        "JFK/HKG\n"
        "DEPARTURE DATE: 15/03/2025\n"
    )
    d = extract_details(extended=blob, description="CATHAY PACIFIC AIRWAYS")
    assert d["travel.passenger_name"] == "CHAN/MEI LING"
    assert d["travel.ticket_number"] == "1601234567890"
    assert d["travel.carrier"] == "CX"
    assert d["travel.departure_date"] == "15/03/2025"
    assert is_travel(d)
    # The bare routing line carries no label, so it is kept verbatim rather
    # than parsed into an origin and a destination. Reading AAA/BBB as a route
    # turns "opt out" into a flight, which is worse than not knowing.
    assert "JFK/HKG" in d.values()
    assert "travel.origin" not in d


def test_merchant_columns_are_captured():
    d = extract_details(columns={
        "Address": "1 QUEENS ROAD", "City/State": "CENTRAL",
        "Country": "HONG KONG", "Reference": "AB1234",
    })
    assert d["merchant.address"] == "1 QUEENS ROAD"
    assert d["merchant.city"] == "CENTRAL"
    assert d["merchant.country"] == "HONG KONG"
    assert d["issuer.reference"] == "AB1234"


def test_unrecognised_detail_lines_are_kept_not_dropped():
    d = extract_details(extended="SOME UNUSUAL ISSUER LINE\nANOTHER ONE")
    assert any(k.startswith("raw.line_") for k in d)


def test_non_travel_rows_do_not_invent_a_passenger():
    """A slash in a merchant name is not a surname/forename pair."""
    d = extract_details(extended="AMZN/MKTP US\nORDER 123-456", description="AMAZON")
    assert "travel.passenger_name" not in d


def test_details_survive_ingest(conn, tmp_path):
    """ParsedTxn.extra used to be dropped entirely by to_txn."""
    f = tmp_path / "amex_travel.csv"
    f.write_text(
        "Date,Description,Card Member,Account #,Amount,Extended Details,Category\n"
        "03/02/2025,CATHAY PACIFIC,ZEPTO X,-11001,980.00,"
        '"PASSENGER NAME  CHAN/MEI LING TICKET NUMBER 160 1234567890 '
        'CARRIER: CX HKG/LHR",Travel\n')
    r = ingest_file(conn, f, institution_id="amex_us",
                    account_id="amex_us_main", default_currency="USD")
    assert r["status"] == "imported"

    details = dict(conn.execute(
        "SELECT key, value FROM txn_detail").fetchall())
    assert details["travel.passenger_name"] == "CHAN/MEI LING"
    assert details["travel.carrier"] == "CX"
    assert "HKG/LHR" in details.values()


def test_details_are_searchable(conn, tmp_path):
    from fin.reporting import transactions
    f = tmp_path / "amex_travel.csv"
    f.write_text(
        "Date,Description,Card Member,Account #,Amount,Extended Details,Category\n"
        "03/02/2025,CATHAY PACIFIC,ZEPTO X,-11001,980.00,"
        '"PASSENGER NAME  CHAN/MEI LING CARRIER: CX",Travel\n')
    ingest_file(conn, f, institution_id="amex_us", account_id="amex_us_main",
                default_currency="USD")
    found = transactions(conn, filters={"q": "CHAN/MEI LING"})
    assert found["total"] == 1


# ---------------------------------------------------------------------------
# Through the full pipeline
# ---------------------------------------------------------------------------

def test_installments_survive_reconcile(conn, tmp_path):
    rows = ["Date,Description,Card Member,Account #,Amount,Extended Details,Category"]
    for i in range(1, 7):
        rows.append(f"{i:02d}/15/2025,INSTALMENT {i:02d}/12 BEST BUY TST,"
                    f"ZEPTO X,-11001,100.00,,Merchandise")
    f = tmp_path / "amex_plan.csv"
    f.write_text("\n".join(rows) + "\n")

    ingest_file(conn, f, institution_id="amex_us", account_id="amex_us_main",
                default_currency="USD")
    summary = reconcile(conn)
    assert summary["installment_plans"] == 1

    plans = dbm.load_installment_plans(conn)
    assert len(plans) == 1
    p = plans[0]
    assert p["term_months"] == 12
    assert p["paid_count"] == 6
    assert p["remaining_count"] == 6
    assert p["outstanding"]["amount"] == -60000     # 6 x USD 100.00 still owed
    assert p["principal"]["amount"] == -120000


def test_reconcile_is_idempotent_with_installments(conn, tmp_path):
    rows = ["Date,Description,Card Member,Account #,Amount,Extended Details,Category"]
    for i in range(1, 5):
        rows.append(f"{i:02d}/15/2025,INSTALMENT {i:02d}/12 BEST BUY TST,"
                    f"ZEPTO X,-11001,100.00,,Merchandise")
    f = tmp_path / "amex_plan.csv"
    f.write_text("\n".join(rows) + "\n")
    ingest_file(conn, f, institution_id="amex_us", account_id="amex_us_main",
                default_currency="USD")

    counts = []
    for _ in range(3):
        reconcile(conn)
        counts.append(conn.execute(
            "SELECT COUNT(*) FROM installment_plan").fetchone()[0])
    assert counts == [1, 1, 1]
