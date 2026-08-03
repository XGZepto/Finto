"""PDF statement extraction."""

from __future__ import annotations

from datetime import date

import pytest
from conftest import write_pdf

from fin.ingest import ingest_file
from fin.parsers.base import ParseContext, select_parser
from fin.parsers.pdf import PdfStatementParser

STATEMENT = [
    "MOX BANK LIMITED",
    "Account Statement",
    "Statement Period: 01/01/2025 to 31/01/2025",
    "Account Number: ****4455   Currency: HKD",
    "",
    "Opening Balance                              50,000.00",
    "",
    "Date        Description                    Amount      Balance",
    "02/01/2025  SALARY CREDIT ACME LTD       45,000.00   95,000.00",
    "05/01/2025  PARKNSHOP SUPERMARKET          -845.20   94,154.80",
    "08/01/2025  FPS TRANSFER TO WISE        -20,000.00   74,154.80",
    "15/01/2025  INSTALMENT 03/12 SONY STORE  -1,000.00   73,154.80",
    "22/01/2025  MTR OCTOPUS RELOAD             -500.00   72,654.80",
    "",
    "Closing Balance                              72,654.80",
    "Minimum Payment Due                             0.00",
    "Page 1 of 1",
]


@pytest.fixture
def statement_pdf(tmp_path):
    return write_pdf(tmp_path / "mox-statement.pdf", STATEMENT)


def test_pdf_parser_is_selected(statement_pdf):
    ctx = ParseContext(path=statement_pdf, institution_id="mox",
                       default_currency="HKD")
    parser = select_parser(ctx)
    assert parser is not None
    assert parser.parser_id == "pdf_statement"


def test_transactions_are_extracted(statement_pdf):
    ctx = ParseContext(path=statement_pdf, institution_id="mox",
                       default_currency="HKD")
    result = PdfStatementParser().parse(ctx)

    assert len(result.txns) == 5

    salary = next(t for t in result.txns if "SALARY" in t.description_raw)
    assert salary.txn_date == date(2025, 1, 2)
    assert salary.booked.amount == 4500000        # +45,000.00 HKD

    shop = next(t for t in result.txns if "PARKNSHOP" in t.description_raw)
    assert shop.booked.amount == -84520
    # The trailing running balance must not be mistaken for the amount.
    assert all("94,154" not in t.description_raw for t in result.txns)


def test_running_balance_is_not_parsed_as_the_amount(statement_pdf):
    ctx = ParseContext(path=statement_pdf, default_currency="HKD")
    result = PdfStatementParser().parse(ctx)
    transfer = next(t for t in result.txns if "FPS TRANSFER" in t.description_raw)
    assert transfer.booked.amount == -2000000


def test_period_and_balances_are_captured(statement_pdf):
    ctx = ParseContext(path=statement_pdf, default_currency="HKD")
    result = PdfStatementParser().parse(ctx)
    assert result.period_start == date(2025, 1, 1)
    assert result.period_end == date(2025, 1, 31)
    # Opening and closing balances let `check` verify the extraction.
    amounts = sorted(b[1].amount for b in result.balances)
    assert amounts == [5000000, 7265480]


def test_installment_marker_survives_pdf_extraction(statement_pdf):
    ctx = ParseContext(path=statement_pdf, default_currency="HKD")
    result = PdfStatementParser().parse(ctx)
    plan = next(t for t in result.txns if "SONY" in t.description_raw)
    assert plan.installment_hint == (3, 12)


def test_summary_lines_are_not_imported_as_transactions(statement_pdf):
    ctx = ParseContext(path=statement_pdf, default_currency="HKD")
    result = PdfStatementParser().parse(ctx)
    text = " ".join(t.description_raw for t in result.txns)
    for noise in ("Opening Balance", "Closing Balance", "Minimum Payment", "Page 1"):
        assert noise not in text


def test_scanned_pdf_is_rejected_with_an_explanation(tmp_path):
    """A PDF with no text layer must not import as an empty success."""
    blank = write_pdf(tmp_path / "scan.pdf", [])
    ctx = ParseContext(path=blank, default_currency="HKD")
    result = PdfStatementParser().parse(ctx)
    assert result.txns == []
    assert any("no text layer" in w for w in result.warnings)


def test_pdf_imports_end_to_end(conn, statement_pdf):
    r = ingest_file(conn, statement_pdf, institution_id="mox",
                    account_id="mox_main", default_currency="HKD")
    assert r["status"] == "imported"
    assert r["txns"] == 5
    assert r["balances"] == 2

    rows = conn.execute(
        "SELECT COUNT(*) FROM txn WHERE account_id='mox_main'").fetchone()[0]
    assert rows == 5


def test_pdf_balance_check_verifies_the_extraction(conn, statement_pdf):
    """The issuer's own balances confirm we pulled every row out of the PDF."""
    from fin.integrity import check_all
    ingest_file(conn, statement_pdf, institution_id="mox",
                account_id="mox_main", default_currency="HKD")
    checks = [c for c in check_all(conn) if c.get("status") != "insufficient_data"]
    assert checks, "expected a balance reconciliation for the PDF import"
    assert all(c["status"] == "ok" for c in checks), checks


def test_dropped_pdf_row_is_caught_by_the_balance_check(conn, tmp_path):
    """Remove a transaction line; the balances must stop reconciling."""
    from fin.integrity import check_all
    broken = [ln for ln in STATEMENT if "PARKNSHOP" not in ln]
    pdf = write_pdf(tmp_path / "mox-missing-row.pdf", broken)
    ingest_file(conn, pdf, institution_id="mox", account_id="mox_main",
                default_currency="HKD")
    checks = [c for c in check_all(conn) if c.get("status") != "insufficient_data"]
    assert any(c["status"] == "discrepancy" for c in checks)
    bad = next(c for c in checks if c["status"] == "discrepancy")
    assert abs(bad["discrepancy"]["amount"]) == 84520
    assert bad["discrepancy"]["currency"] == "HKD"
