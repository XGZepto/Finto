"""Template-engine semantics, tested against inline templates.

Deliberately independent of the shipped issuer templates: these assert how the
engine treats signs, markers and balances, which must hold whatever any
particular issuer's file says.
"""

from __future__ import annotations

from pdf_synth import amounts, make_document, right, row

from fin.pdf.template import StatementTemplate, apply_template
from fin.pdf.verify import verify_extraction


def build(amount_spec: dict, balances: list[dict] | None = None, **section) -> StatementTemplate:
    base = {
        "template_id": "test",
        "match_all": ["LEDGER"],
        "default_currency": "HKD",
        "date": {"dayfirst": True},
        "statement_date": "Statement date (\\d{1,2} \\w+ \\d{4})",
        "sections": [
            {
                "name": "main",
                "start": "",
                "end": "END OF SECTION",
                "columns": {
                    "mode": "anchors",
                    "header": "(?-i:DATE)",
                    "anchors": {
                        "date": "(?-i:^DATE$)",
                        "description": "(?-i:^DETAILS$)",
                        "amount": "(?-i:^AMOUNT$)",
                        "balance": "(?-i:^BALANCE$)",
                    },
                },
                "description_column": "description",
                "amount": amount_spec,
                "balances": balances or [],
                **section,
            }
        ],
    }
    return StatementTemplate.from_dict(base)


HEADER = row((0, "DATE"), (10, "DETAILS"), right(60, "AMOUNT"), right(78, "BALANCE"))
PREAMBLE = "\n".join([row((0, "LEDGER")), row((0, "Statement date 8 January 2025"))])


def statement(*rows: str) -> str:
    return "\n".join([PREAMBLE, HEADER, *rows, row((0, "END OF SECTION"))])


# ---------------------------------------------------------------------------
# Sign conventions
# ---------------------------------------------------------------------------


def test_signed_mode_trusts_the_written_sign():
    doc = make_document(statement(
        row((0, "05 Jan"), (10, "INTEREST"), right(60, "+0.45")),
        row((0, "06 Jan"), (10, "PAYMENT"), right(60, "-40.00")),
    ))
    result = apply_template(doc, build({"mode": "signed", "column": "amount"}))
    assert amounts(result) == [("2025-01-05", 45), ("2025-01-06", -4000)]


def test_invert_flips_an_issuer_that_writes_a_purchase_as_positive():
    """AMEX US states what you owe, so its signs are the reverse of ours."""
    doc = make_document(statement(
        row((0, "05 Jan"), (10, "RESTAURANT"), right(60, "32.33")),
        row((0, "06 Jan"), (10, "MOBILE PAYMENT"), right(60, "-180.14")),
    ))
    result = apply_template(
        doc, build({"mode": "signed", "column": "amount", "invert": True})
    )
    assert amounts(result) == [("2025-01-05", -3233), ("2025-01-06", 18014)]


def test_cr_marker_on_the_same_row_marks_a_credit():
    doc = make_document(statement(
        row((0, "05 Jan"), (10, "PURCHASE"), right(60, "504.00")),
        row((0, "06 Jan"), (10, "REFUND"), right(60, "44.00CR")),
    ))
    result = apply_template(doc, build({"mode": "cr_marker", "column": "amount"}))
    assert amounts(result) == [("2025-01-05", -50400), ("2025-01-06", 4400)]


def test_cr_marker_on_the_following_row_marks_the_row_above():
    """AMEX prints the CR beneath the figure it applies to."""
    doc = make_document(statement(
        row((0, "05 Jan"), (10, "PURCHASE"), right(60, "504.00")),
        row((0, "06 Jan"), (10, "PAYMENT RECEIVED"), right(60, "9,178.70")),
        row(right(60, "CR")),
    ))
    result = apply_template(
        doc,
        build({"mode": "cr_marker", "column": "amount", "cr_on_following_line": True}),
    )
    assert amounts(result) == [("2025-01-05", -50400), ("2025-01-06", 917870)]


def test_a_cr_marker_applies_once_and_is_consumed():
    """A section total carries its own CR, which must not reach back.

    Left unconsumed, the total's marker flips the last real transaction a
    second time and silently reverses its sign.
    """
    doc = make_document(statement(
        row((0, "05 Jan"), (10, "REWARDS CREDIT"), right(60, "59.82")),
        row(right(60, "CR")),
        row((0, "Total of Other Transactions"), right(60, "1,451.17")),
        row(right(60, "CR")),
    ))
    tpl = build(
        {"mode": "cr_marker", "column": "amount", "cr_on_following_line": True},
        exclude=["^Total of "],
    )
    result = apply_template(doc, tpl)
    assert amounts(result) == [("2025-01-05", 5982)]


def test_debit_and_credit_columns_are_read_by_position():
    doc = make_document("\n".join([
        PREAMBLE,
        row((0, "DATE"), (10, "DETAILS"), right(55, "DEPOSIT"),
            right(70, "WITHDRAWAL"), right(85, "BALANCE")),
        row((0, "05 Jan"), (10, "SALARY"), right(55, "100.00")),
        row((0, "06 Jan"), (10, "RENT"), right(70, "100.00")),
        row((0, "END OF SECTION")),
    ]))
    tpl = build({
        "mode": "debit_credit",
        "credit_column": "deposit",
        "debit_column": "withdrawal",
        "balance_column": "balance",
    })
    tpl.sections[0].columns.anchors = {
        "date": "(?-i:^DATE$)",
        "description": "(?-i:^DETAILS$)",
        "deposit": "(?-i:^DEPOSIT$)",
        "withdrawal": "(?-i:^WITHDRAWAL$)",
        "balance": "(?-i:^BALANCE$)",
    }
    result = apply_template(doc, tpl)
    assert amounts(result) == [("2025-01-05", 10000), ("2025-01-06", -10000)]


# ---------------------------------------------------------------------------
# Balances and verification
# ---------------------------------------------------------------------------


def test_card_balances_are_negated_into_a_liability():
    """A card statement states what is owed; Finto holds a liability negative."""
    doc = make_document("\n".join([
        PREAMBLE,
        row((0, "Previous Balance"), (30, "New Balance")),
        row((0, "9,178.70"), (20, "10,629.87"), (40, "30,044.51"), (60, "28,593.34")),
        HEADER,
        row((0, "END OF SECTION")),
    ]))
    tpl = build(
        {"mode": "cr_marker", "column": "amount"},
        balances=[
            {"pattern": "Previous Balance", "kind": "opening", "scope": "document",
             "min_tokens": 4, "token_index": 0, "negate": True},
            {"pattern": "Previous Balance", "kind": "closing", "scope": "document",
             "min_tokens": 4, "token_index": 3, "negate": True},
        ],
    )
    result = apply_template(doc, tpl)
    kinds = {kind: money.amount for _when, money, kind, _sec, _hint in result.balances}
    assert kinds == {"opening": -917870, "closing": -2859334}


def test_forward_scan_survives_a_line_inserted_into_the_summary():
    """AMEX added a line between its heading and its figures partway through 2025."""
    doc = make_document("\n".join([
        PREAMBLE,
        row((0, "Previous Balance"), (30, "New Balance")),
        row((0, "(Including Installments)")),
        row((0, "9,178.70"), (20, "10,629.87"), (40, "30,044.51"), (60, "28,593.34")),
        HEADER,
        row((0, "END OF SECTION")),
    ]))
    tpl = build(
        {"mode": "cr_marker", "column": "amount"},
        balances=[
            {"pattern": "Previous Balance", "kind": "opening", "scope": "document",
             "min_tokens": 4, "token_index": 0, "negate": True},
        ],
    )
    result = apply_template(doc, tpl)
    assert result.balances[0][1].amount == -917870


def test_running_balance_pinpoints_where_a_row_was_missed():
    """Opening-to-closing says something is wrong; the running column says where."""
    doc = make_document(statement(
        row((0, "05 Jan"), (10, "ONE"), right(60, "-10.00"), right(78, "90.00")),
        row((0, "06 Jan"), (10, "TWO"), right(60, "-10.00"), right(78, "70.00")),
        row((0, "07 Jan"), (10, "THREE"), right(60, "-10.00"), right(78, "60.00")),
    ))
    result = apply_template(doc, build({"mode": "signed", "column": "amount"}))
    report = verify_extraction(result)
    assert report.status == "failed"
    assert "06 Jan" in str(report.problems) or "2025-01-06" in str(report.problems)


def test_an_extraction_with_nothing_to_check_is_not_a_pass():
    """Silence must not read as success: an empty parse looks like a quiet month."""
    doc = make_document(statement(
        row((0, "05 Jan"), (10, "PURCHASE"), right(60, "-10.00")),
    ))
    result = apply_template(doc, build({"mode": "signed", "column": "amount"}))
    report = verify_extraction(result)
    assert report.status == "unverified"
    assert report.verified_sections == 0


def test_stop_at_ends_a_section_before_a_restated_total():
    """Plan summaries restate amounts already counted as transactions."""
    doc = make_document(statement(
        row((0, "05 Jan"), (10, "INSTALMENT 02 OF 12"), right(60, "-1,093.49")),
        row((0, "PLAN SUMMARY")),
        row((0, "06 Jan"), (10, "TOTAL BALANCE"), right(60, "-12,307.57")),
    ))
    tpl = build({"mode": "signed", "column": "amount"}, stop_at=["PLAN SUMMARY"])
    result = apply_template(doc, tpl)
    assert amounts(result) == [("2025-01-05", -109349)]


def test_cr_beneath_a_fx_continuation_flips_the_row():
    """AMEX HK marks a credit card refund by ending the FX detail line under
    the charge with CR — the amount row itself carries no marker at all."""
    doc = make_document(statement(
        row((0, "14 Mar"), (10, "AMZ*PRIVATE INTERNET"), right(60, "454.68")),
        row((25, "56.94")),
        row((0, "15 Mar"), (10, "AMZ*PRIVATE INTERNET"), right(60, "445.77")),
        row((25, "56.94")),
        row((25, "UNITED STATES DOLLAR CR")),
    ))
    result = apply_template(
        doc, build({"mode": "cr_marker", "column": "amount",
                    "cr_on_following_line": True},
                   continuation="below"))
    assert [r.amount.amount for r in result.rows] == [-45468, 44577]
    # The FX details attach to the row above them, not the one below.
    assert "56.94" in result.rows[0].description
    assert "56.94" in result.rows[1].description
    assert not result.rows[1].description.startswith("56.94")


def test_cr_beneath_the_summary_marks_a_credit_balance():
    """AMEX HK drops a lone CR under the New Balance column when the account
    is in credit; the figure needs its sign flipped back to reconcile."""
    doc = make_document("\n".join([
        PREAMBLE,
        row((0, "Previous Balance New Credits New Debits New Balance")),
        row((0, "46,248.14"), (20, "51,849.99"), (40, "5,589.99"), right(60, "11.86")),
        row((58, "CR")),
        HEADER,
        row((0, "05 Jan"), (10, "PURCHASE"), right(60, "10.00")),
        row((0, "END OF SECTION")),
    ]))
    tpl = build(
        {"mode": "cr_marker", "column": "amount"},
        balances=[
            {"pattern": "Previous Balance", "kind": "closing", "scope": "document",
             "min_tokens": 4, "token_index": -1, "negate": True,
             "cr_following_line": True},
        ],
    )
    result = apply_template(doc, tpl)
    # negate turns "what you owe" into our convention; the aligned CR turns
    # it back — the account holds 11.86 in its favour.
    assert result.balances[0][1].amount == 1186


def test_cr_beneath_another_column_does_not_touch_the_figure():
    """The same lone CR must not flip the Previous Balance two columns away."""
    doc = make_document("\n".join([
        PREAMBLE,
        row((0, "Previous Balance New Credits New Debits New Balance")),
        row((0, "46,248.14"), (20, "51,849.99"), (40, "5,589.99"), right(60, "11.86")),
        row((58, "CR")),
        HEADER,
        row((0, "05 Jan"), (10, "PURCHASE"), right(60, "10.00")),
        row((0, "END OF SECTION")),
    ]))
    tpl = build(
        {"mode": "cr_marker", "column": "amount"},
        balances=[
            {"pattern": "Previous Balance", "kind": "opening", "scope": "document",
             "min_tokens": 4, "token_index": 0, "negate": True,
             "cr_following_line": True},
        ],
    )
    result = apply_template(doc, tpl)
    assert result.balances[0][1].amount == -4624814
