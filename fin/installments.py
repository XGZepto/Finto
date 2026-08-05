"""Instalment plan detection and transaction linkage.

Supports periodic instalment rows and initial charge-plus-reversal statements.
Ledger transactions remain cash basis; plan totals and future schedule entries
are projections stored separately from transaction rows.
"""

from __future__ import annotations

import hashlib
import re
from collections import defaultdict
from collections.abc import Sequence
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal

from .models import (
    InstallmentCandidate,
    InstallmentPlan,
    Money,
    PlanStatus,
    Txn,
    TxnKind,
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
        r"\b(\d{1,2})(?:ST|ND|RD|TH)?\s+OF\s+(\d{1,2})\s+INSTAL?LMENTS?\b",
        re.IGNORECASE),
    re.compile(
        r"\b(?:INSTAL?LMENT|INSTAL|INST)\b\D{0,12}?(\d{1,2})\s*(?:/|OF|-)\s*(\d{1,2})\b",
        re.IGNORECASE),
    re.compile(
        r"\b(\d{1,2})\s*(?:/|OF|-)\s*(\d{1,2})\b\D{0,12}?\b(?:INSTAL?LMENT|INSTAL|INST)\b",
        re.IGNORECASE),
    re.compile(r"分期\D{0,6}?(\d{1,2})\s*/\s*(\d{1,2})"),
    re.compile(r"\b(?:INSTAL?LMENT|INSTAL)\b\D{0,8}?(\d{1,2})\s*期\D{0,4}?共?\s*(\d{1,2})",  # noqa: E501
        re.IGNORECASE),
)

_PLAN_WORDS = re.compile(
    r"INSTAL?LMENT|INSTAL\b|分期|MTHLY INSTAL|MONTHLY PLAN|SPLIT PURCHASE", re.IGNORECASE
)


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
    # txn id -> (plan id, sequence). An early-settlement charge belongs to the
    # plan but has no monthly sequence number.
    assignments: dict[str, tuple[str, int | None]] = field(default_factory=dict)


def find_installments(txns: Sequence[Txn]) -> InstallmentReport:
    """Group instalment charges into plans.

    Grouping key is (account, subject, currency, term). Explicit sequence
    markers are stronger evidence than equal amounts: loan principal can vary
    month to month while interest or exchange rates move.
    """
    report = InstallmentReport()

    groups: dict[tuple, list[tuple[int, Txn]]] = defaultdict(list)
    for t in txns:
        if t.duplicate_of_id is not None:
            continue
        if _is_installment_fee(t):
            continue
        marker = _marker_for(t)
        if marker is None:
            continue
        seq, term = marker
        key = (t.account_id, plan_subject(t.description_raw),
               t.booked.currency, term)
        groups[key].append((seq, t))

    for (account_id, subject, currency, term), members in groups.items():
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
            amounts = [abs(t.booked.amount) for t in ordered]
            declared_principal = next((
                _detail_minor(t, "installment.principal", currency) for t in ordered
                if (t.details or {}).get("installment.principal")
            ), None)
            principal = declared_principal or round(sum(amounts) / len(amounts)) * term
            apr = next((
                Decimal(t.details["installment.apr"]) for t in ordered
                if (t.details or {}).get("installment.apr")
            ), None)
            plan = InstallmentPlan(
                id=pid,
                account_id=account_id,
                card_id=first.card_id,
                merchant=first.merchant or subject or None,
                description=subject or first.description_raw,
                # Principal is the whole commitment, not what has been paid.
                principal=Money(amount=-principal, currency=currency),
                term_months=term,
                start_date=_inferred_start(ordered, sorted(by_seq)),
                confidence=round(score, 4),
                apr=apr,
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

    _find_early_settlements(txns, report)
    _find_unnumbered_completed_plans(txns, report)
    return report


_EARLY_SETTLEMENT = re.compile(
    r"UNPAID AMOUNT FROM CANCELLED PLAN|EARLY (?:REPAYMENT|SETTLEMENT)|"
    r"PLAN (?:CANCELLED|CANCELED|SETTLEMENT)", re.IGNORECASE,
)


def _find_early_settlements(txns: Sequence[Txn], report: InstallmentReport) -> None:
    """Close a plan when the issuer bills its remaining principal at once.

    AMEX writes ``UNPAID AMOUNT FROM CANCELLED PLAN`` rather than another
    numbered instalment. It is an actual charge in the ledger, but not month
    11 or 12; linking it without a sequence preserves that distinction and
    prevents projected future charges after an early payoff.
    """
    by_id = {t.id: t for t in txns}
    used = set(report.assignments)
    for plan in report.plans:
        if plan.status is PlanStatus.COMPLETED:
            continue
        members = [
            by_id[txn_id] for txn_id, (pid, seq) in report.assignments.items()
            if pid == plan.id and seq is not None
        ]
        if not members:
            continue
        paid_principal = sum(abs(t.booked.amount) for t in members)
        expected = max(0, abs(plan.principal.amount) - paid_principal)
        if expected == 0:
            continue
        latest = max(t.txn_date for t in members)
        candidates = [
            t for t in txns
            if t.id not in used
            and t.duplicate_of_id is None
            and t.account_id == plan.account_id
            and t.booked.currency == plan.principal.currency
            and t.booked.amount < 0
            and 0 <= (t.txn_date - latest).days <= 62
            and _EARLY_SETTLEMENT.search(t.description_raw)
        ]
        if not candidates:
            continue
        settlement = min(candidates, key=lambda t: abs(abs(t.booked.amount) - expected))
        tolerance = max(5_000, expected * 3 // 100)
        if abs(abs(settlement.booked.amount) - expected) > tolerance:
            continue
        plan.status = PlanStatus.COMPLETED
        plan.match_method = "rule"
        plan.confidence = 1.0
        plan.notes = "Settled early by issuer cancellation charge"
        settlement.details = settlement.details or {}
        settlement.details["installment.settlement"] = "early"
        report.assignments[settlement.id] = (plan.id, None)
        used.add(settlement.id)


def _detail_minor(t: Txn, key: str, currency: str) -> int:
    from .parsers.base import parse_amount
    return abs(parse_amount(t.details[key], currency).amount)


def _marker_for(t: Txn) -> tuple[int, int] | None:
    details = t.details or {}
    try:
        if details.get("installment.sequence") and details.get("installment.term"):
            return int(details["installment.sequence"]), int(details["installment.term"])
    except ValueError:
        pass
    return parse_installment_marker(
        "\n".join([t.description_raw, *details.values()])
    )


def _is_installment_fee(t: Txn) -> bool:
    return "HANDLING FEE" in " ".join((t.details or {}).values()).upper()


def _find_unnumbered_completed_plans(
    txns: Sequence[Txn], report: InstallmentReport
) -> None:
    """Recover explicit but unnumbered plans once their cadence has ended.

    Some issuers only print ``(Statement) Instalment``. Three or more monthly,
    near-identical charges plus a later account history prove a completed plan;
    ordinary subscriptions are excluded because the wording itself must name
    an instalment.
    """
    assigned = set(report.assignments)
    latest: dict[str, date] = {}
    groups: dict[tuple[str, str, int], list[Txn]] = defaultdict(list)
    credits: dict[tuple[str, str], list[Txn]] = defaultdict(list)
    for t in txns:
        if t.duplicate_of_id is not None:
            continue
        latest[t.account_id] = max(latest.get(t.account_id, t.txn_date), t.txn_date)
        if not looks_like_plan(t.description_raw) or _marker_for(t) is not None:
            continue
        detail_text = " ".join((t.details or {}).values()).upper()
        if "HANDLING FEE" in detail_text:
            continue
        if t.booked.amount > 0:
            credits[(t.account_id, t.booked.currency)].append(t)
        elif t.id not in assigned:
            # One-cent final adjustments stay in one plan.
            groups[(t.account_id, t.booked.currency,
                    abs(t.booked.amount) // 10)].append(t)

    for (account_id, currency, _bucket), members in groups.items():
        members.sort(key=lambda t: t.txn_date)
        if len(members) < 3:
            continue
        gaps = [(b.txn_date - a.txn_date).days for a, b in zip(members, members[1:])]
        amounts = [abs(t.booked.amount) for t in members]
        if (not all(MIN_GAP_DAYS <= gap <= MAX_GAP_DAYS for gap in gaps)
                or max(amounts) - min(amounts) > 5):
            continue
        if (latest.get(account_id, members[-1].txn_date) - members[-1].txn_date).days \
                <= MAX_GAP_DAYS:
            continue

        term = len(members)
        subject = plan_subject(max(members, key=lambda t: len(t.description_raw)).description_raw)
        pid = plan_id_for(account_id, subject, term)
        total = sum(amounts)
        origin = next((c for c in sorted(
            credits.get((account_id, currency), []), key=lambda t: t.txn_date, reverse=True)
            if 0 <= (members[0].txn_date - c.txn_date).days <= 60
            and total * 80 // 100 <= c.booked.amount <= total), None)
        principal = origin.booked.amount if origin else round(sum(amounts) / term) * term
        fee_total = total - principal
        first = members[0]
        report.plans.append(InstallmentPlan(
            id=pid, account_id=account_id, card_id=first.card_id,
            merchant=first.merchant or subject or None,
            description=subject or first.description_raw,
            principal=Money(amount=-principal, currency=currency),
            term_months=term, start_date=first.txn_date,
            fee_total=(Money(amount=fee_total, currency=currency)
                       if fee_total > 0 else None),
            confidence=0.94, status=PlanStatus.COMPLETED,
            match_method="rule",
        ))
        for seq, t in enumerate(members, 1):
            report.assignments[t.id] = (pid, seq)
        if origin:
            origin.kind = TxnKind.INSTALLMENT_ORIGINATION
            origin.details["installment.originated_plan"] = pid


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
