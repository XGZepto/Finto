"""Refund → purchase linking.

A refund is not income. Counted naively, returning a HKD 3,000 jacket looks
exactly like being paid HKD 3,000, and the month it lands in shows inflated
income while the month of the purchase shows inflated spending. Neither number
is real: the economic event is that the purchase partly or wholly did not
happen.

Linking the refund to its purchase lets reporting net them against the *original*
category and, optionally, the original period — so "clothing" reflects what you
actually kept.

This is a different problem from deduplication despite looking similar. A
duplicate is the same transaction seen twice and one copy must be suppressed. A
refund is a second, genuine transaction with the opposite sign, and both rows
stay in the ledger — the balance assertions depend on it, because both really
did move money.

Matching is conservative in the same way as everywhere else here: a refund with
no confident purchase is simply left unlinked, which is honest. A refund linked
to the wrong purchase silently misattributes both categories.
"""

from __future__ import annotations

from collections import defaultdict
from collections.abc import Sequence
from dataclasses import dataclass, field
from difflib import SequenceMatcher

from .models import Txn, TxnKind

# Refunds usually post within a couple of months of the purchase. Beyond that a
# same-amount credit is more likely to be something else entirely.
MAX_LOOKBACK_DAYS = 180
MIN_DESC_SIMILARITY = 0.55
AUTO_LINK_THRESHOLD = 0.80

_REFUND_WORDS = ("REFUND", "RETURN", "CREDIT VOUCHER", "REVERSAL", "CHARGEBACK",
                 "退款", "MERCHANDISE CREDIT")


@dataclass
class RefundReport:
    links: dict[str, str] = field(default_factory=dict)   # refund id -> purchase id
    unmatched: list[str] = field(default_factory=list)     # refund ids


def looks_like_refund(t: Txn) -> bool:
    """An inflow on a spending account that reads as a reversal.

    Inflows on cards are the interesting case: a card only receives money from a
    payment (handled by transfer linking) or a refund. Bank accounts receive
    money for many reasons, so those need the wording to say so.
    """
    if t.booked.amount <= 0:
        return False
    if t.kind in (TxnKind.CC_PAYMENT, TxnKind.TRANSFER, TxnKind.FX_CONVERSION):
        return False
    if t.kind == TxnKind.REFUND:
        return True
    return any(w in t.description_norm.upper() for w in _REFUND_WORDS)


def find_refunds(txns: Sequence[Txn]) -> RefundReport:
    """Match refunds to the purchases they reverse.

    Blocked by account so a refund can only reverse a purchase on the same
    account, which is always true in practice and keeps this near-linear.
    """
    report = RefundReport()

    live = [t for t in txns if t.duplicate_of_id is None
            and t.transfer_group_id is None]
    purchases: dict[str, list[Txn]] = defaultdict(list)
    for t in live:
        if t.booked.amount < 0:
            purchases[t.account_id].append(t)
    for group in purchases.values():
        group.sort(key=lambda t: t.txn_date)

    claimed: set[str] = set()
    refunds = [t for t in live if looks_like_refund(t)]
    refunds.sort(key=lambda t: t.txn_date)

    for refund in refunds:
        best, best_score = None, 0.0
        for purchase in purchases.get(refund.account_id, ()):
            if purchase.id in claimed:
                continue
            if purchase.booked.currency != refund.booked.currency:
                continue
            # A purchase can only be reversed after it happened, and refunds
            # never exceed the original charge.
            if purchase.txn_date > refund.txn_date:
                break   # sorted by date; nothing later can qualify
            if (refund.txn_date - purchase.txn_date).days > MAX_LOOKBACK_DAYS:
                continue
            if abs(refund.booked.amount) > abs(purchase.booked.amount):
                continue
            score = _score(refund, purchase)
            if score > best_score:
                best, best_score = purchase, score

        if best is not None and best_score >= AUTO_LINK_THRESHOLD:
            report.links[refund.id] = best.id
            claimed.add(best.id)
        else:
            report.unmatched.append(refund.id)

    return report


def _score(refund: Txn, purchase: Txn) -> float:
    score = 0.0

    # Same merchant text is the core signal — a refund almost always carries the
    # original merchant's name.
    sim = SequenceMatcher(
        None, _subject(refund), _subject(purchase)).ratio()
    if sim < MIN_DESC_SIMILARITY:
        return 0.0
    score += 0.55 * sim

    if refund.merchant and purchase.merchant and refund.merchant == purchase.merchant:
        score += 0.15

    # A full reversal is more certain than a partial one.
    if abs(refund.booked.amount) == abs(purchase.booked.amount):
        score += 0.25
    else:
        score += 0.08

    # Closer in time is better, but a slow refund is still a refund.
    gap = (refund.txn_date - purchase.txn_date).days
    score += 0.10 * max(0.0, 1.0 - gap / MAX_LOOKBACK_DAYS)

    return min(score, 1.0)


def _subject(t: Txn) -> str:
    """Merchant-ish text with refund wording removed, so the two sides compare."""
    s = (t.merchant or t.description_norm).upper()
    for w in _REFUND_WORDS:
        s = s.replace(w, " ")
    return " ".join(s.split())


def apply_refund_links(txns: Sequence[Txn], report: RefundReport) -> int:
    """Write the links onto the transactions and tag them as refunds."""
    index = {t.id: t for t in txns}
    applied = 0
    for refund_id, purchase_id in report.links.items():
        refund = index.get(refund_id)
        if refund is None:
            continue
        refund.refund_of_id = purchase_id
        refund.kind = TxnKind.REFUND
        # Inherit the purchase's category: a returned jacket belongs to
        # clothing, not to whatever a refund would otherwise classify as.
        purchase = index.get(purchase_id)
        if purchase is not None and purchase.category and not refund.category:
            refund.category = purchase.category
            refund.subcategory = purchase.subcategory
        applied += 1
    return applied
