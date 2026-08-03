"""Installment plan detection and linking.

A card instalment plan turns one purchase into N monthly charges. Naively this
is N unrelated purchases: monthly spend looks artificially smooth, the original
purchase decision is untraceable, and the question that actually matters — *how
much do I still owe?* — has no answer at all.

Two statement shapes:

  (a) amortised only   Each statement shows one "INSTALMENT 03/12" charge.
  (b) charge+reversal  Month 1 books the full amount, credits back all but the
                       first instalment, then charges instalment 1.

Transactions stay cash basis. `integrity.check_account` proves we captured every
row by reproducing the bank's own running balance, so a synthetic accrual row
would break the one check that catches dropped transactions. The economic view —
one HKD 12,000 event in January rather than twelve of HKD 1,000 — is a
projection built from `installment_plan`, never a stored transaction.

Detection is deliberately conservative. A wrongly-grouped plan misstates your
liabilities, which is harder to notice than a plan that failed to group.
"""

from __future__ import annotations

import hashlib
import re
from collections import defaultdict
from collections.abc import Sequence
from dataclasses import dataclass, field
from datetime import date

from .models import (
    InstallmentCandidate,
    InstallmentPlan,
    Money,
    PlanStatus,
    Txn,
)

# Monthly charges land roughly a month apart; issuers vary by a few days and
# month lengths differ.
MIN_GAP_DAYS = 24
MAX_GAP_DAYS = 38
AUTO_CREATE_THRESHOLD = 0.90
REVIEW_THRESHOLD = 0.55


# ---------------------------------------------------------------------------
# Parsing the sequence marker
# ---------------------------------------------------------------------------
# Ordered most-specific first: "3 OF 12" must not be matched by the bare "03/12"
# pattern, and the bare pattern must require an instalment word nearby so it
# does not eat ordinary embedded dates.

_PATTERNS = (
    re.compile(
        r"\b(?:INSTAL?LMENT|INSTAL|INST)\b\D{0,12}?(\d{1,2})\s*(?:/|OF|-)\s*(\d{1,2})\b",
        re.I),
    re.compile(
        r"\b(\d{1,2})\s*(?:/|OF|-)\s*(\d{1,2})\b\D{0,12}?\b(?:INSTAL?LMENT|INSTAL|INST)\b",
        re.I),
    re.compile(r"分期\D{0,6}?(\d{1,2})\s*/\s*(\d{1,2})"),
    re.compile(r"\b(?:INSTAL?LMENT|INSTAL)\b\D{0,8}?(\d{1,2})\s*期\D{0,4}?共?\s*(\d{1,2})", re.I),
)

_PLAN_WORDS = re.compile(r"INSTAL?LMENT|INSTAL\b|分期|MTHLY INSTAL|MONTHLY PLAN", re.I)


def parse_installment_marker(description: str) -> tuple[int, int] | None:
    """Extract (sequence, term) from a description, or None.

    Must run against the RAW description. `normalize_description` strips
    `\\d{2}/\\d{2}` as statement noise, which erases "03/12" before anything
    downstream can read it.
    """
    if not description:
        return None
    for pat in _PATTERNS:
        m = pat.search(description)
        if not m:
            continue
        seq, term = int(m.group(1)), int(m.group(2))
        if 1 <= seq <= term and 2 <= term <= 60:
            return seq, term
    return None


def looks_like_plan(description: str) -> bool:
    return bool(description and _PLAN_WORDS.search(description))


def plan_subject(description: str) -> str:
    """Strip the instalment marker so the remaining text identifies the purchase.

    "INSTALMENT 03/12 BEST BUY TST" and "INSTALMENT 04/12 BEST BUY TST" must
    reduce to the same subject or they will never group.
    """
    s = description.upper()
    s = re.sub(r"\b(?:INSTAL?LMENT|INSTAL|INST)\b", " ", s)
    s = re.sub(r"\b\d{1,2}\s*(?:/|OF|-)\s*\d{1,2}\b", " ", s)
    s = re.sub(r"分期|\d{1,2}\s*期", " ", s)
    s = re.sub(r"[^A-Z0-9 &./-]", " ", s)
    return re.sub(r"\s+", " ", s).strip()


def plan_id_for(account_id: str, subject: str, term: int) -> str:
    """Deterministic plan id, so re-running detection converges."""
    basis = f"{account_id}|{subject}|{term}"
    return hashlib.sha256(basis.encode()).hexdigest()[:32]


# ---------------------------------------------------------------------------
# Linking
# ---------------------------------------------------------------------------

@dataclass
class InstallmentReport:
    plans: list[InstallmentPlan] = field(default_factory=list)
    candidates: list[InstallmentCandidate] = field(default_factory=list)
    # txn id -> (plan id, sequence)
    assignments: dict[str, tuple[str, int]] = field(default_factory=dict)


def find_installments(txns: Sequence[Txn]) -> InstallmentReport:
    """Group instalment charges into plans.

    Grouping key is (account, subject, |amount|, currency, term): every charge in
    a plan is the same amount from the same merchant on the same account. Rows
    without a parseable sequence marker are never grouped — guessing that two
    similar monthly charges form a plan would invent a liability.
    """
    report = InstallmentReport()

    groups: dict[tuple, list[tuple[int, Txn]]] = defaultdict(list)
    for t in txns:
        if t.duplicate_of_id is not None:
            continue
        marker = parse_installment_marker(t.description_raw)
        if marker is None:
            continue
        seq, term = marker
        key = (t.account_id, plan_subject(t.description_raw),
               abs(t.booked.amount), t.booked.currency, term)
        groups[key].append((seq, t))

    for (account_id, subject, _amount, currency, term), members in groups.items():
        members.sort(key=lambda st: (st[0], st[1].txn_date))

        # One charge per sequence number. A repeat means the same statement got
        # ingested under two accounts, or the subject is too coarse — either way
        # it is not safe to auto-create.
        by_seq: dict[int, Txn] = {}
        duplicate_seq = False
        for seq, t in members:
            if seq in by_seq:
                duplicate_seq = True
            by_seq.setdefault(seq, t)

        ordered = [by_seq[s] for s in sorted(by_seq)]
        score, reasons = _score_plan(ordered, sorted(by_seq), term, duplicate_seq)

        if score < REVIEW_THRESHOLD:
            continue

        pid = plan_id_for(account_id, subject, term)
        if score >= AUTO_CREATE_THRESHOLD:
            first = ordered[0]
            per_charge = abs(first.booked.amount)
            plan = InstallmentPlan(
                id=pid,
                account_id=account_id,
                card_id=first.card_id,
                merchant=first.merchant or subject or None,
                description=subject or first.description_raw,
                # Principal is the whole commitment, not what has been paid.
                principal=Money(amount=-(per_charge * term), currency=currency),
                term_months=term,
                start_date=_inferred_start(ordered, sorted(by_seq)),
                confidence=round(score, 4),
                status=(PlanStatus.COMPLETED if len(by_seq) >= term
                        else PlanStatus.ACTIVE),
            )
            report.plans.append(plan)
            for seq, t in sorted(by_seq.items()):
                report.assignments[t.id] = (pid, seq)
        else:
            report.candidates.append(InstallmentCandidate(
                id=pid,
                account_id=account_id,
                description=subject or ordered[0].description_raw,
                txn_ids=[t.id for t in ordered],
                term_months=term,
                score=round(score, 4),
                reasons=reasons,
            ))

    return report


def _score_plan(ordered: list[Txn], seqs: list[int], term: int,
                duplicate_seq: bool) -> tuple[float, list[str]]:
    reasons = [f"explicit instalment marker n/{term}"]
    score = 0.62

    if duplicate_seq:
        return 0.0, ["repeated sequence number — ambiguous grouping"]

    # Consecutive run starting at 1 is the signature of a real plan. A partial
    # plan is normal: statements only cover the months you have imported.
    expected = list(range(seqs[0], seqs[0] + len(seqs)))
    if seqs == expected:
        score += 0.18
        reasons.append("sequence numbers are consecutive")
    else:
        score -= 0.20
        reasons.append(f"sequence gaps: {seqs}")

    if seqs[0] == 1:
        score += 0.06
        reasons.append("starts at instalment 1")

    # Monthly spacing.
    gaps = [(b.txn_date - a.txn_date).days for a, b in zip(ordered, ordered[1:])]
    if not gaps:
        score -= 0.10
        reasons.append("single charge — cannot confirm monthly cadence")
    elif all(MIN_GAP_DAYS <= g <= MAX_GAP_DAYS for g in gaps):
        score += 0.20
        reasons.append("charges are one month apart")
    else:
        score -= 0.25
        reasons.append(f"irregular spacing: {gaps} days")

    if len(ordered) >= 3:
        score += 0.05
        reasons.append(f"{len(ordered)} charges observed")

    return max(0.0, min(score, 1.0)), reasons


def _inferred_start(ordered: list[Txn], seqs: list[int]) -> date:
    """Start date of the plan, back-dated when instalment 1 wasn't imported."""
    first_txn, first_seq = ordered[0], seqs[0]
    if first_seq == 1:
        return first_txn.txn_date
    month_offset = first_seq - 1
    year, month = first_txn.txn_date.year, first_txn.txn_date.month - month_offset
    while month <= 0:
        month += 12
        year -= 1
    day = min(first_txn.txn_date.day, 28)
    return date(year, month, day)


# ---------------------------------------------------------------------------
# Shape (b): full charge booked then reversed
# ---------------------------------------------------------------------------

def find_origination_pairs(txns: Sequence[Txn]) -> list[tuple[Txn, Txn]]:
    """Pair a gross instalment charge with the credit that reverses it.

    Some issuers book the whole purchase, then credit back everything except the
    first instalment. Left alone that is a large phantom outflow followed by a
    large phantom inflow. Paired, they cancel — which is what actually happened.
    """
    by_account: dict[tuple[str, date], list[Txn]] = defaultdict(list)
    for t in txns:
        if t.duplicate_of_id is None:
            by_account[(t.account_id, t.txn_date)].append(t)

    pairs: list[tuple[Txn, Txn]] = []
    claimed: set[str] = set()
    for same_day in by_account.values():
        charges = [t for t in same_day if t.booked.amount < 0]
        credits = [t for t in same_day if t.booked.amount > 0]
        if not charges or not credits:
            continue
        for credit in credits:
            if not looks_like_plan(credit.description_raw):
                continue
            for charge in charges:
                if charge.id in claimed or credit.id in claimed:
                    continue
                if charge.booked.currency != credit.booked.currency:
                    continue
                # The credit backs out part of the charge, never more than it.
                if abs(credit.booked.amount) >= abs(charge.booked.amount):
                    continue
                if _subjects_agree(charge, credit):
                    pairs.append((charge, credit))
                    claimed.update({charge.id, credit.id})
                    break
    return pairs


# Words that appear in plan bookkeeping lines rather than naming the merchant.
_PLAN_NOISE = {"PLAN", "CREDIT", "DEBIT", "REVERSAL", "ADJUSTMENT", "AMOUNT",
               "MONTHLY", "MTHLY", "TOTAL", "PURCHASE", "CONVERSION"}


def _subjects_agree(a: Txn, b: Txn) -> bool:
    """Do two descriptions name the same purchase?

    Substring comparison is not enough: "BEST BUY TST INSTALMENT PLAN" and
    "INSTALMENT PLAN CREDIT BEST BUY TST" describe the same purchase but neither
    contains the other. Compare the merchant-ish tokens instead, after dropping
    the bookkeeping words both sides share.
    """
    ta, tb = _subject_tokens(a), _subject_tokens(b)
    if not ta or not tb:
        return False
    overlap = ta & tb
    return len(overlap) >= min(len(ta), len(tb))


def _subject_tokens(t: Txn) -> set[str]:
    return {w for w in plan_subject(t.description_raw).split()
            if w not in _PLAN_NOISE and len(w) > 1}


# ---------------------------------------------------------------------------
# Projection
# ---------------------------------------------------------------------------

def outstanding(plan: InstallmentPlan, paid_count: int) -> Money:
    """What is still owed on a plan after `paid_count` instalments."""
    per = abs(plan.principal.amount) // plan.term_months
    remaining = max(0, plan.term_months - paid_count)
    return Money(amount=-(per * remaining), currency=plan.principal.currency)
