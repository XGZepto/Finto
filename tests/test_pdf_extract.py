"""Tests for the layout primitives templates are built on.

These cover the specific behaviours that were wrong in the line-oriented parser
this replaced, and that real statements depend on.
"""

from __future__ import annotations

from datetime import date

import pytest
from pdf_synth import make_document, make_page, right, row

from fin.pdf.extract import PdfDocument, TextLine, Word
from fin.pdf.layout import (
    columns_from_anchors,
    columns_from_fractions,
    detect_money_columns,
    is_money,
)
from fin.pdf.template import DateSpec, parse_statement_date

# ---------------------------------------------------------------------------
# Line grouping
# ---------------------------------------------------------------------------


def test_words_group_into_rows_by_vertical_position():
    page = make_page("ALPHA        BETA\nGAMMA        DELTA")
    assert [ln.text for ln in page.lines] == ["ALPHA BETA", "GAMMA DELTA"]


def test_reading_order_does_not_determine_row_membership():
    """A PDF may emit words in any order; position decides which row they join.

    Chase interleaves marketing copy with the balance summary in the content
    stream, so trusting emission order scrambles the table.
    """
    words = [
        Word("RIGHT", 300, 340, 50, 60),
        Word("SECOND", 0, 40, 80, 90),
        Word("LEFT", 0, 40, 50, 60),
    ]
    page = make_page("")
    page.lines = []
    from fin.pdf.extract import _group_into_lines

    lines = _group_into_lines(
        [
            {"text": w.text, "x0": w.x0, "x1": w.x1, "top": w.top, "bottom": w.bottom}
            for w in words
        ],
        0,
    )
    assert [ln.text for ln in lines] == ["LEFT RIGHT", "SECOND"]


def test_layout_text_preserves_horizontal_gaps():
    line = TextLine(
        words=[Word("A", 0, 6, 0, 10), Word("B", 300, 306, 0, 10)],
        top=0, page_no=0, line_no=0,
    )
    rendered = line.layout_text()
    assert rendered.startswith("A")
    assert rendered.rstrip().endswith("B")
    assert rendered.index("B") > 100


# ---------------------------------------------------------------------------
# Money recognition
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "token",
    ["1,234.56", "(1,234.56)", "1,234.56CR", "-1,234.56", "$1,500.00", "0.03", "12,307.57"],
)
def test_money_tokens_recognised(token):
    assert is_money(token)


@pytest.mark.parametrize(
    "token",
    ["HC12510756657108", "20DEC", "2025", "0.0985", "AT243510019000010008255", "of"],
)
def test_non_money_tokens_rejected(token):
    """Reference numbers and rates share a shape with money and must not match."""
    assert not is_money(token)


def test_reward_points_are_indistinguishable_from_money():
    """A documented limitation, and why sections must be bounded by markers.

    AMEX prints a rewards points balance with a thousands separator, which is
    exactly the shape of an amount. No token-level rule can separate them, so
    templates stop the section before the rewards table instead.
    """
    assert is_money("83,473")


# ---------------------------------------------------------------------------
# Columns
# ---------------------------------------------------------------------------


def _header(text: str) -> TextLine:
    return make_page(text).lines[0]


def test_columns_split_midway_between_headings():
    cols = columns_from_anchors(
        _header(row((0, "DATE"), (20, "DESCRIPTION"), (60, "AMOUNT"))),
        {"date": "DATE", "description": "DESCRIPTION", "amount": "AMOUNT"},
        page_width=612.0,
    )
    assert cols is not None
    date_col, desc_col, amount_col = cols.columns
    assert date_col.x0 == 0.0
    assert amount_col.x1 == 612.0
    assert date_col.x1 == desc_col.x0
    assert desc_col.x1 == amount_col.x0


def test_position_alone_decides_deposit_versus_withdrawal():
    """The behaviour the previous parser could not express.

    Two identical figures mean opposite things depending on the column they sit
    in, and flattened text cannot tell them apart.
    """
    header = _header(
        row((0, "Date"), (10, "Transaction Details"), right(60, "Deposit"),
            right(74, "Withdrawal"), right(88, "Balance"))
    )
    cols = columns_from_anchors(
        header,
        {"date": "Date", "description": "Transaction", "deposit": "Deposit",
         "withdrawal": "Withdrawal", "balance": "Balance"},
        page_width=612.0,
    )
    assert cols is not None

    deposit_row = make_page(row((0, "7 Jan"), (10, "SALARY"), right(60, "100.00"))).lines[0]
    withdrawal_row = make_page(row((0, "8 Jan"), (10, "RENT"), right(74, "100.00"))).lines[0]

    assert cols.text(deposit_row, "deposit") == "100.00"
    assert cols.text(deposit_row, "withdrawal") == ""
    assert cols.text(withdrawal_row, "withdrawal") == "100.00"
    assert cols.text(withdrawal_row, "deposit") == ""


def test_missing_optional_heading_is_tolerated():
    """Issuers stack headings across rows, so some land on a different line."""
    cols = columns_from_anchors(
        _header(row((0, "DATE"), (40, "AMOUNT"))),
        {"date": "DATE", "fx": "FOREIGN", "amount": "AMOUNT"},
        page_width=612.0,
        required={"date", "amount"},
    )
    assert cols is not None
    assert [c.name for c in cols.columns] == ["date", "amount"]


def test_missing_required_heading_refuses_to_guess():
    """Guessing where the money column sits would mis-sign transactions."""
    cols = columns_from_anchors(
        _header(row((0, "DATE"), (40, "DESCRIPTION"))),
        {"date": "DATE", "description": "DESCRIPTION", "amount": "AMOUNT"},
        page_width=612.0,
        required={"date", "amount"},
    )
    assert cols is None


def test_case_sensitive_anchor_distinguishes_repeated_words():
    """A lowercase heading in one column collides with the real one elsewhere.

    Mox writes "Amount" over the money column and "currency amount" over the FX
    column, so a case-insensitive anchor binds to whichever comes first.
    """
    header = _header(row((0, "Activity"), (30, "currency amount"), (60, "Amount")))
    cols = columns_from_anchors(
        header, {"date": "Activity", "amount": "(?-i:^Amount)"}, page_width=612.0
    )
    assert cols is not None
    amount_col = cols.get("amount")
    assert amount_col.x0 > 30 * 6.0


def test_fraction_columns_scale_to_page_width():
    cols = columns_from_fractions([("date", 0.0, 0.2), ("amount", 0.8, 1.0)], 600.0)
    assert cols.get("date").x1 == 120.0
    assert cols.get("amount").x0 == 480.0


def test_money_columns_detected_from_right_alignment():
    page = make_page(
        "\n".join(
            row((0, f"0{i} Jan"), (10, "MERCHANT"), right(60, "1,000.00"), right(80, "9,999.99"))
            for i in range(1, 5)
        )
    )
    found = detect_money_columns(page.lines)
    assert len(found) == 2


# ---------------------------------------------------------------------------
# Dates
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "token,anchor,expected",
    [
        ("2025-01-31", None, date(2025, 1, 31)),
        ("05 Jan", date(2025, 1, 14), date(2025, 1, 5)),
        ("17JAN", date(2025, 1, 18), date(2025, 1, 17)),
        ("December 16", date(2025, 1, 8), date(2024, 12, 16)),
        ("January 8", date(2025, 1, 8), date(2025, 1, 8)),
    ],
)
def test_dates_without_a_year_take_it_from_the_statement(token, anchor, expected):
    """A December row on a January statement belongs to the previous year."""
    assert parse_statement_date(token, DateSpec(dayfirst=True), anchor) == expected


def test_regional_ordering_is_explicit():
    us = DateSpec(dayfirst=False)
    hk = DateSpec(dayfirst=True)
    assert parse_statement_date("03/08/2026", us, None) == date(2026, 3, 8)
    assert parse_statement_date("03/08/2026", hk, None) == date(2026, 8, 3)


def test_posting_date_marker_is_stripped():
    """AMEX marks a posting date with a trailing asterisk."""
    assert parse_statement_date("01/12/25*", DateSpec(dayfirst=False), None) == date(2025, 1, 12)


def test_ambiguous_ordering_falls_back_when_impossible():
    """31/01 cannot be month-first, whatever the configured convention."""
    assert parse_statement_date("31/01/2025", DateSpec(dayfirst=False), None) == date(2025, 1, 31)


# ---------------------------------------------------------------------------
# Round-tripping an extraction
# ---------------------------------------------------------------------------


def test_extraction_survives_serialisation():
    """Stored extractions are how a statement is re-parsed without the file."""
    doc = make_document(row((0, "14 Dec"), (20, "B/F BALANCE"), right(80, "213.30")))
    restored = PdfDocument.from_json(doc.to_json())
    assert restored.text == doc.text
    assert restored.pages[0].lines[0].words[0].x0 == doc.pages[0].lines[0].words[0].x0
