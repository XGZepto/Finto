"""Regular income cadence detection."""

from __future__ import annotations

from datetime import date, timedelta

from fin.income import apply_income_labels, detect_regular_income
from fin.models import Money, Txn, TxnKind


def _credit(day: date, amount: int, desc: str, account: str = "hsbc") -> Txn:
    return Txn(
        account_id=account,
        txn_date=day,
        booked=Money(amount=amount, currency="HKD"),
        description_raw=desc,
        statement_file_id="sf",
    )


def test_monthly_salary_is_detected_as_an_income_stream():
    start = date(2025, 1, 22)
    txns = [
        _credit(start + timedelta(days=30 * i), 199_812_50,
                f"QUBE R & T HK LTD SALARY {22 + i}MAY")
        for i in range(4)
    ]
    # jitter one amount by <2%
    txns[2] = _credit(txns[2].txn_date, 199_500_00, txns[2].description_raw)

    streams = detect_regular_income(txns)
    assert len(streams) == 1
    assert streams[0].median_amount == 199_812_50 or streams[0].median_amount == 199_500_00
    labelled = apply_income_labels(txns, streams)
    assert labelled == 4
    assert all(t.kind == TxnKind.INCOME for t in txns)
    assert all(t.details.get("income_stream") for t in txns)


def test_irregular_credits_are_not_streams():
    start = date(2025, 1, 1)
    txns = [
        _credit(start, 10_000_00, "FRIEND FPS"),
        _credit(start + timedelta(days=3), 10_000_00, "FRIEND FPS"),
        _credit(start + timedelta(days=40), 10_000_00, "FRIEND FPS"),
    ]
    assert detect_regular_income(txns) == []


def test_transfer_linked_credits_are_ignored():
    start = date(2025, 1, 22)
    txns = [
        _credit(start + timedelta(days=30 * i), 20_000_00, "FROM MOX")
        for i in range(4)
    ]
    for t in txns:
        t.transfer_group_id = "tg"
    assert detect_regular_income(txns) == []
