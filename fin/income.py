"""Regular income detection.

Salary and other recurring credits are already labelled `TxnKind.INCOME` by
the parsers when the description says so. This module finds the rest: a
credit that lands on roughly the same day each month for the same amount
is income even when the bank called it "QUBE R & T HK LTD" with no SALARY
marker on every row.

Detection is deliberately conservative. Three or more occurrences, gaps of
25–35 days, amounts within 2% of each other — then we stamp the kind and a
`income_stream` detail so the frontend can group them. Nothing is invented
as a forecast; we only label what is already in the ledger.
"""

from __future__ import annotations

from collections import defaultdict
from collections.abc import Sequence
from dataclasses import dataclass
from statistics import median

from .models import Txn, TxnKind

MIN_OCCURRENCES = 3
MIN_GAP_DAYS = 25
MAX_GAP_DAYS = 35
AMOUNT_TOLERANCE = 0.02


@dataclass
class IncomeStream:
    key: str
    txn_ids: list[str]
    median_amount: int
    median_gap_days: float
    currency: str


def detect_regular_income(
    txns: Sequence[Txn], *, income_accounts: set[str] | None = None
) -> list[IncomeStream]:
    """Group recurring same-account credits into income streams.

    `income_accounts` limits the search to accounts income can actually arrive
    in. Nobody is paid into a credit card: a recurring credit there is a
    statement benefit — AMEX refunds a Walmart+ subscription every month — and
    counting those as earnings inflates income by whatever the card rebates.
    """
    credits = [
        t for t in txns
        if t.duplicate_of_id is None
        and t.booked.amount > 0
        and t.transfer_group_id is None
        and t.refund_of_id is None
        and (income_accounts is None or t.account_id in income_accounts)
    ]
    buckets: dict[tuple[str, str, str], list[Txn]] = defaultdict(list)
    for t in credits:
        # Bucket on account + currency + a short description stem so "SALARY
        # 22MAY" and "SALARY 22JUN" land together.
        stem = _stem(t.description_norm or t.description_raw)
        if not stem:
            continue
        buckets[(t.account_id, t.booked.currency, stem)].append(t)

    streams: list[IncomeStream] = []
    for (account_id, ccy, stem), group in buckets.items():
        group.sort(key=lambda t: t.txn_date)
        stream = _as_stream(account_id, ccy, stem, group)
        if stream is not None:
            streams.append(stream)
    return streams


def apply_income_labels(txns: Sequence[Txn], streams: Sequence[IncomeStream]) -> int:
    """Stamp matching credits as INCOME and tag them with the stream key."""
    by_id = {t.id: t for t in txns}
    labelled = 0
    for stream in streams:
        for tid in stream.txn_ids:
            t = by_id.get(tid)
            if t is None:
                continue
            if t.kind in (TxnKind.UNKNOWN, TxnKind.INCOME, TxnKind.INTEREST,
                          TxnKind.REWARD):
                t.kind = TxnKind.INCOME
            t.details = {**(t.details or {}), "income_stream": stream.key}
            if not t.category:
                t.category = "income"
            labelled += 1
    return labelled


def _stem(desc: str) -> str:
    # Drop trailing day/month tokens and collapse whitespace.
    import re
    s = re.sub(r"\b\d{1,2}[A-Z]{3}\d{0,2}\b", " ", desc.upper())
    s = re.sub(r"\b\d{1,2}[-/]\d{1,2}([-/]\d{2,4})?\b", " ", s)
    s = re.sub(r"\s+", " ", s).strip()
    # Keep the first ~4 tokens — enough to identify an employer, short
    # enough that a trailing reference number doesn't split the bucket.
    parts = s.split()
    return " ".join(parts[:4]) if parts else ""


def _as_stream(
    account_id: str, ccy: str, stem: str, group: list[Txn]
) -> IncomeStream | None:
    if len(group) < MIN_OCCURRENCES:
        return None
    # Amounts must cluster: reject if the spread exceeds the tolerance
    # around the median.
    amounts = [t.booked.amount for t in group]
    med_amt = int(median(amounts))
    if med_amt <= 0:
        return None
    tight = [
        t for t in group
        if abs(t.booked.amount - med_amt) / med_amt <= AMOUNT_TOLERANCE
    ]
    if len(tight) < MIN_OCCURRENCES:
        return None
    tight.sort(key=lambda t: t.txn_date)
    gaps = [
        (tight[i].txn_date - tight[i - 1].txn_date).days
        for i in range(1, len(tight))
    ]
    monthly = [g for g in gaps if MIN_GAP_DAYS <= g <= MAX_GAP_DAYS]
    if len(monthly) < MIN_OCCURRENCES - 1:
        return None
    return IncomeStream(
        key=f"{account_id}:{ccy}:{stem}",
        txn_ids=[t.id for t in tight],
        median_amount=med_amt,
        median_gap_days=float(median(monthly)),
        currency=ccy,
    )
