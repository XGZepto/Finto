"""Template tests built from direct reading of the real statements.

Each fixture reproduces the structure of one issuer's statement — including the
specific things that made it hard — with invented figures. The arithmetic is
chosen so the statement reconciles, which means these tests check the same
property the importer relies on: that the extracted rows account for the
difference between the opening and closing balances the issuer printed.

Every quirk asserted here was observed in a real statement. The comments say
which, because a template rule with no stated reason is one nobody will dare
change later.
"""

from __future__ import annotations

from pdf_synth import amounts, by_section, make_document, right, row

from fin.pdf.registry import builtin_templates
from fin.pdf.template import apply_template
from fin.pdf.verify import verify_extraction


def template(template_id: str):
    for t in builtin_templates():
        if t.template_id == template_id:
            return t
    raise AssertionError(f"no builtin template {template_id!r}")


def run(doc, template_id: str):
    tpl = template(template_id)
    assert tpl.matches(doc) > 0, f"{template_id} did not match the fixture"
    result = apply_template(doc, tpl)
    return result, verify_extraction(result)


# ---------------------------------------------------------------------------
# HSBC HK savings — meaning carried by column position
# ---------------------------------------------------------------------------

HSBC_SAVINGS = "\n".join([
    row((0, "MR A CUSTOMER"), (40, "Portfolio Summary")),
    row((0, "14 January 2025")),
    row((0, "HSBC One Account Transaction History")),
    row((0, "HKD Savings")),
    row((0, "Date"), (10, "Transaction Details"),
        right(55, "Deposit"), right(70, "Withdrawal"), right(85, "Balance")),
    row((0, "14 Dec"), (10, "B/F BALANCE"), right(85, "213.30")),
    # The amount sits on the continuation row, not the row carrying the date.
    row((0, "20 Dec"), (10, "OCTOPUS CARDS LTD")),
    row((10, "HC124C2009427620"), right(70, "100.00"), right(85, "113.30")),
    # One date covering two transactions, with a balance printed only at the end.
    row((0, "7 Jan"), (10, "A CUSTOMER")),
    row((10, "HC12510756657108"), right(55, "7,704.18")),
    row((10, "A C******")),
    row((10, "HC12510756658767"), right(70, "7,700.00"), right(85, "117.48")),
    # A second sub-ledger in another currency, named by a column, not a heading.
    row((0, "Foreign Currency Savings")),
    row((0, "CCY"), (6, "Date"), (14, "Transaction Details"),
        right(55, "Deposit"), right(70, "Withdrawal"), right(85, "Balance")),
    row((0, "CNY"), (6, "14 Dec"), (14, "B/F BALANCE"), right(85, "50.00")),
    row((6, "20 Dec"), (14, "DEPOSIT"), right(55, "10.00"), right(85, "60.00")),
    row((0, "Total Relationship Balance")),
])


def test_hsbc_savings_signs_come_from_column_position():
    result, report = run(make_document(HSBC_SAVINGS), "hsbc_hk_savings")
    assert amounts(result) == [
        ("2024-12-20", -10000),     # withdrawal column: money out
        ("2025-01-07", 770418),     # deposit column: money in
        ("2025-01-07", -770000),
        ("2024-12-20", 1000),       # CNY sub-ledger
    ]
    assert report.status == "verified"


def test_hsbc_savings_reconciles_each_currency_separately():
    result, report = run(make_document(HSBC_SAVINGS), "hsbc_hk_savings")
    sections = by_section(result)
    assert sections["hkd_savings"] == [-10000, 770418, -770000]
    assert sections["fx_savings:CNY"] == [1000]
    assert all(c.ok for c in report.checks if c.checkable)


def test_hsbc_savings_dates_cross_the_year_boundary():
    """December rows on a January statement belong to the previous year."""
    result, _ = run(make_document(HSBC_SAVINGS), "hsbc_hk_savings")
    assert result.rows[0].txn_date.year == 2024
    assert result.rows[1].txn_date.year == 2025


def test_dropped_row_is_caught_by_reconciliation():
    """The property that makes a PDF import trustworthy at all.

    Removing one row leaves the extraction still plausible — dates in order,
    descriptions intact — and only the arithmetic reveals it.
    """
    broken = HSBC_SAVINGS.replace(
        row((10, "HC124C2009427620"), right(70, "100.00"), right(85, "113.30")), ""
    )
    result, report = run(make_document(broken), "hsbc_hk_savings")
    assert report.status == "failed"
    assert any("100.00" in p or "unaccounted" in p for p in report.problems)


# ---------------------------------------------------------------------------
# Chase — running balance, and invisible markers colliding with real rows
# ---------------------------------------------------------------------------

CHASE = "\n".join([
    row((0, "JPMorgan Chase Bank, N.A.")),
    row((0, "December 10, 2024 through January 09, 2025")),
    row((0, "CHECKING SUMMARY")),
    row((0, "Beginning Balance"), right(60, "$1,638.52")),
    row((0, "*end*summary")),
    row((0, "*start*transaction detail")),
    row((0, "TRANSACTION DETAIL")),
    row((0, "DATE"), (8, "DESCRIPTION"), right(62, "AMOUNT"), right(78, "BALANCE")),
    row((8, "Beginning Balance"), right(78, "$1,638.52")),
    row((0, "01/06"), (8, "Zelle Payment From A Person"), right(62, "500.00"),
        right(78, "2,138.52")),
    # Chase draws a layout marker at the same height as a transaction, so the
    # two merge into one line with the characters interleaved. Excluding the
    # marker by a loose pattern takes the transaction with it.
    row((0, "*end*transac0tion"), (18, "detail8/04 ACH Pmt"), right(62, "-638.52"),
        right(78, "1,500.00")),
    row((8, "Ending Balance"), right(78, "$1,500.00")),
    row((0, "*end*transaction detail")),
    row((0, "SAVINGS SUMMARY")),
    row((0, "*start*transaction detail")),
    row((0, "TRANSACTION DETAIL")),
    row((0, "DATE"), (8, "DESCRIPTION"), right(62, "AMOUNT"), right(78, "BALANCE")),
    row((8, "Beginning Balance"), right(78, "$295.00")),
    row((0, "01/06"), (8, "Transfer To Checking"), right(62, "-95.00"), right(78, "200.00")),
    row((8, "Ending Balance"), right(78, "$200.00")),
    row((0, "*end*transaction detail")),
])


def test_chase_reads_both_accounts_from_one_statement():
    result, report = run(make_document(CHASE), "chase_us_consolidated")
    assert by_section(result) == {"checking": [50000, -63852], "savings": [-9500]}
    assert report.status == "verified"


def test_chase_transaction_survives_a_collided_layout_marker():
    """The merged row is a real transaction and must still be counted."""
    result, _ = run(make_document(CHASE), "chase_us_consolidated")
    assert -63852 in [r.amount.amount for r in result.rows]


def test_chase_dates_are_month_first():
    """01/06 is 6 January on a US statement, not 1 June."""
    result, _ = run(make_document(CHASE), "chase_us_consolidated")
    assert result.rows[0].txn_date.isoformat() == "2025-01-06"


# ---------------------------------------------------------------------------
# Mox — headings stacked over three rows, and a summary that repeats them
# ---------------------------------------------------------------------------

MOX_SUMMARY_PAGE = "\n".join([
    row((0, "Mox Bank statement")),
    row((0, "Statement period: 1 Jan 2025 - 31 Jan 2025")),
    row((0, "Statement date: 3 Feb 2025")),
    row((0, "Account Summary")),
    # These repeat the ledger headings and must not be mistaken for the tables.
    row((0, "HKD Mox Account"), right(60, "2,404.75")),
    row((0, "JPY Mox Account"), right(60, "0")),
])

MOX_ACTIVITY_PAGE = "\n".join([
    row((0, "Activities")),
    row((0, "HKD Mox Account")),
    # Three staggered header rows: no single row carries every heading.
    row((2, "Activity"), (14, "Settlement"), (50, "Corresponding")),
    row((28, "Description"), right(88, "Amount")),
    row((2, "date"), (14, "date"), (50, "currency amount")),
    row((2, "01 Jan"), (14, "01 Jan"), (28, "Opening balance"), right(88, "26,184.13")),
    row((2, "01 Jan"), (14, "01 Jan"), (28, "HKD Interest"), right(88, "+0.45")),
    row((2, "05 Jan"), (14, "05 Jan"), (28, "A PERSON"), right(88, "+5,662.11")),
    row((2, "07 Jan"), (14, "07 Jan"), (28, "Mox Credit Payment"), right(88, "-29,442.63")),
    row((2, "31 Jan"), (14, "31 Jan"), (28, "Closing balance"), right(88, "2,404.06")),
    row((0, "JPY Mox Account")),
    row((2, "Activity"), (14, "Settlement"), (50, "Corresponding")),
    row((28, "Description"), right(88, "Amount")),
    row((2, "date"), (14, "date"), (50, "currency amount")),
    row((2, "01 Jan"), (14, "01 Jan"), (28, "Opening balance"), right(88, "0")),
    row((2, "31 Jan"), (14, "31 Jan"), (28, "Closing balance"), right(88, "0")),
    row((0, "Important notice")),
])


def test_mox_reads_headings_stacked_over_several_rows():
    result, report = run(
        make_document(MOX_SUMMARY_PAGE, MOX_ACTIVITY_PAGE), "mox_bank"
    )
    assert amounts(result) == [
        ("2025-01-01", 45),
        ("2025-01-05", 566211),
        ("2025-01-07", -2944263),
    ]
    assert report.status == "verified"


def test_mox_summary_rows_are_not_mistaken_for_the_ledger():
    """"HKD Mox Account" appears first in a summary table, above the real one."""
    result, _ = run(make_document(MOX_SUMMARY_PAGE, MOX_ACTIVITY_PAGE), "mox_bank")
    assert 240475 not in [r.amount.amount for r in result.rows]


def test_mox_lowercase_heading_does_not_capture_the_amount_column():
    """The FX column is headed "currency amount"; the money column "Amount"."""
    result, _ = run(make_document(MOX_SUMMARY_PAGE, MOX_ACTIVITY_PAGE), "mox_bank")
    assert [r.amount.amount for r in result.rows] == [45, 566211, -2944263]


def test_mox_zero_decimal_currency_reads_bare_integers():
    """JPY has no minor unit, so its figures carry neither decimals nor commas."""
    result, report = run(make_document(MOX_SUMMARY_PAGE, MOX_ACTIVITY_PAGE), "mox_bank")
    jpy = [c for c in report.checks if c.section == "jpy"]
    assert jpy and jpy[0].opening is not None
    assert jpy[0].opening.currency == "JPY"


def test_mox_description_wrapped_above_the_figures_is_kept():
    """Mox wraps a description across rows with the figures on the middle one."""
    page = "\n".join([
        row((0, "Activities")),
        row((0, "HKD Mox Account")),
        row((2, "Activity"), (14, "Settlement"), (50, "Corresponding")),
        row((28, "Description"), right(88, "Amount")),
        row((2, "01 Jan"), (14, "01 Jan"), (28, "Opening balance"), right(88, "100.00")),
        row((28, "Move between own")),
        row((2, "10 Jan"), (14, "10 Jan"), right(88, "-40.00")),
        row((28, "accounts")),
        row((2, "31 Jan"), (14, "31 Jan"), (28, "Closing balance"), right(88, "60.00")),
        row((0, "Important notice")),
    ])
    result, report = run(make_document(MOX_SUMMARY_PAGE, page), "mox_bank")
    assert [r.amount.amount for r in result.rows] == [-4000]
    assert "Move between own" in result.rows[0].description
    assert report.status == "verified"
