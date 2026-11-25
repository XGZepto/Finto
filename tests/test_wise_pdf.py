"""Wise balance statement PDFs: a row's date sits on the line beneath it."""

from __future__ import annotations

from conftest import write_pdf

from fin.ingest import ingest_file
from fin.parsers.base import ParseContext
from fin.parsers.pdf import WisePdfParser


def _statement(tmp_path, *, balance_after_topup="1,973.04"):
    return write_pdf(tmp_path / "statement_1_USD_2026-11-01_2026-11-25.pdf", [
        "Wise US Inc.",
        "USD statement",
        "1 November 2026 [GMT+08:00] - 25 November 2026 [GMT+08:00]",
        "Generated on: 25 November 2026",
        "USD on 25 November 2026 [GMT+08:00] 2,021.24 USD",
        "Description Incoming Outgoing Amount",
        "Topped up account 800.00 2,021.24",
        "24 November 2026 | Transaction: TRANSFER-1000000002",
        "Paid to AMEX EPAYMENT -751.80 1,221.24",
        "14 November 2026 | Transaction: DIRECT_DEBIT-1000000001 | Reference: 2026-11-13-0001",
        f"Topped up account 1,200.00 {balance_after_topup}",
        "11 November 2026 | Transaction: TRANSFER-1000000000",
        "Interest payment 2.37 773.04",
        "1 November 2026 | Transaction: BALANCE_INTEREST-abc-123",
        "3.40% APY from 2026-10-01",
        "Fees Summary",
    ])


def test_rows_read_oldest_first_with_wise_ids_and_chained_balances(tmp_path):
    path = _statement(tmp_path)
    ctx = ParseContext(path=path)
    parser = WisePdfParser()
    assert parser.sniff(ctx, path.read_bytes()) > 0.6
    result = parser.parse(ctx)
    assert [(str(t.txn_date), t.booked.amount, t.external_ref) for t in result.txns] == [
        ("2026-11-01", 237, "BALANCE_INTEREST-abc-123"),
        ("2026-11-11", 120000, "TRANSFER-1000000000"),
        ("2026-11-14", -75180, "DIRECT_DEBIT-1000000001"),
        ("2026-11-24", 80000, "TRANSFER-1000000002"),
    ]
    assert result.txns[2].counterparty == "AMEX EPAYMENT"
    assert {t.extra["account_hint"] for t in result.txns} == {"Wise USD"}
    kinds = {b[3]: (str(b[0]), b[1].amount) for b in result.balances if b[3] != "running"}
    assert kinds == {"opening": ("2026-10-31", 77067), "closing": ("2026-11-25", 202124)}
    assert (str(result.period_start), str(result.period_end)) == ("2026-11-01", "2026-11-25")


def test_a_misread_balance_refuses_the_whole_file(tmp_path):
    result = WisePdfParser().parse(ParseContext(path=_statement(tmp_path, balance_after_topup="1,973.00")))
    assert result.txns == []
    assert "do not chain" in result.warnings[0]


def test_an_unrelated_pdf_is_not_claimed(tmp_path):
    other = write_pdf(tmp_path / "other.pdf", ["Statement of Account", "Some Bank"])
    assert WisePdfParser().sniff(ParseContext(path=other), other.read_bytes()) == 0.0


def test_the_statement_finds_its_wise_currency_account(conn, tmp_path):
    result = ingest_file(conn, _statement(tmp_path))
    assert result["status"] == "imported", result
    rows = conn.execute(
        "SELECT account_id, COUNT(*) AS n FROM txn WHERE statement_file_id=%s GROUP BY 1",
        (result["statement_file_id"],)).fetchall()
    assert [(r["account_id"], r["n"]) for r in rows] == [("wise_usd", 4)]
    conn.rollback()
