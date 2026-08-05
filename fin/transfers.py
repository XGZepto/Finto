"""Transfer and payment linking.

The problem: money moving between accounts you own appears twice — once as an
outflow, once as an inflow. Counted naively, an HKD 20,000 move from HSBC to
Mox looks like HKD 20,000 of spending plus HKD 20,000 of income. Both are
wrong; the real economic event is zero, plus any fee.

Three shapes to handle:

  internal_transfer  HSBC -> Mox. Same currency, amounts equal, 0-2 days apart.
  cc_payment         HSBC -> AMEX. The card leg is positive (debt reduced), the
                     bank leg negative. Often 1-3 days apart, amounts equal.
  fx_conversion      Wise HKD -> Wise USD. Amounts DIFFER (different currencies)
                     and must be reconciled through the FX rate, with the
                     spread showing up as fee.

Nothing is auto-linked below a high confidence bar. Candidates land in
transfer_candidate for you to accept or reject.
"""

from __future__ import annotations

import hashlib
from collections import defaultdict
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from datetime import timedelta
from decimal import Decimal

from .models import (
    Account,
    AccountType,
    Money,
    TransferCandidate,
    TransferGroup,
    TransferKind,
    TransferLeg,
    Txn,
    TxnKind,
)

MAX_DATE_GAP_DAYS = 5
# Card payments often clear a few days after the bank debit — keep a wider
# window only when the inflow lands on a card.
MAX_CC_PAYMENT_GAP_DAYS = 10
AUTO_LINK_THRESHOLD = 0.90
REVIEW_THRESHOLD = 0.55
# FX legs rarely reconcile exactly; allow spread + fee before rejecting.
FX_TOLERANCE_PCT = Decimal("0.03")


@dataclass
class TransferContext:
    """Optional name dictionaries that lift transfer recall on real statements."""

    self_aliases: set[str] = field(default_factory=set)
    account_aliases: dict[str, str] = field(default_factory=dict)
    person_aliases: dict[str, str] = field(default_factory=dict)


@dataclass
class TransferReport:
    groups: list[TransferGroup]
    candidates: list[TransferCandidate]


def transfer_group_id(leg_txn_ids: Iterable[str]) -> str:
    """Deterministic group id, derived from the legs it contains.

    This must not be random. `reconcile` re-runs over the whole ledger every
    time, so a uuid4 here created a brand-new group for the same pair on every
    run: transfer_group and transfer_leg grew without bound while
    txn.transfer_group_id pointed only at the newest one. Nothing caught it —
    each stale group still had exactly one inflow and one outflow leg, so every
    structural invariant stayed green while `stats` over-reported transfers.

    Deriving the id from the legs makes re-running reconcile a no-op.
    """
    return hashlib.sha256("|".join(sorted(leg_txn_ids)).encode()).hexdigest()[:32]


def transfer_candidate_id(out_txn_id: str, in_txn_id: str) -> str:
    return hashlib.sha256(f"{out_txn_id}|{in_txn_id}".encode()).hexdigest()[:32]


def find_transfers(
    txns: Sequence[Txn],
    accounts: Mapping[str, Account],
    *,
    fx_lookup=None,
    context: TransferContext | None = None,
) -> TransferReport:
    """Pair outflows with inflows across accounts the user owns.

    `fx_lookup(date, base, quote) -> Decimal | None` supplies rates for
    cross-currency legs. Without it, cross-currency pairs are still proposed
    but scored lower and always sent to review.

    `context` carries self/account/person aliases loaded from accounts.yaml —
    without it the matcher still works on amounts+dates alone, but same-name
    FPS transfers between your own accounts are much easier to miss.
    """
    ctx = context or TransferContext()
    own = {aid for aid, a in accounts.items() if a.is_own_account}
    outs = [t for t in txns if t.duplicate_of_id is None
            and t.booked.amount < 0 and t.account_id in own]
    ins = [t for t in txns if t.duplicate_of_id is None
           and t.booked.amount > 0 and t.account_id in own]

    by_date: dict[object, list[Txn]] = defaultdict(list)
    for t in ins:
        by_date[t.txn_date].append(t)

    candidates: list[TransferCandidate] = []
    seen: set[tuple[str, str]] = set()

    for out in outs:
        # Widen the search window when any candidate inflow is on a card —
        # we don't know yet, so search the larger window and let scoring decide.
        max_gap = MAX_CC_PAYMENT_GAP_DAYS
        for offset in range(max_gap + 1):
            for sign in ((0,) if offset == 0 else (1, -1)):
                day = out.txn_date + timedelta(days=offset * sign)
                for inc in by_date.get(day, ()):
                    if inc.account_id == out.account_id:
                        continue
                    key = (out.id, inc.id)
                    if key in seen:
                        continue
                    seen.add(key)
                    score, reasons, amt_delta = _score_pair(
                        out, inc, accounts, fx_lookup, ctx)
                    if score < REVIEW_THRESHOLD:
                        continue
                    candidates.append(TransferCandidate(
                        id=transfer_candidate_id(out.id, inc.id),
                        out_txn_id=out.id,
                        in_txn_id=inc.id,
                        score=round(score, 4),
                        date_delta=abs((inc.txn_date - out.txn_date).days),
                        amount_delta=amt_delta,
                        reasons=reasons,
                    ))

    candidates.sort(key=lambda c: c.score, reverse=True)
    claimed: set[str] = set()
    groups: list[TransferGroup] = []
    remaining: list[TransferCandidate] = []
    index = {t.id: t for t in txns}

    for c in candidates:
        if c.out_txn_id in claimed or c.in_txn_id in claimed:
            continue
        if c.score >= AUTO_LINK_THRESHOLD:
            out, inc = index[c.out_txn_id], index[c.in_txn_id]
            g = TransferGroup(
                id=transfer_group_id([out.id, inc.id]),
                kind=_classify(out, inc, accounts),
                match_method="auto",
                confidence=c.score,
                legs=[TransferLeg(txn_id=out.id, role="out"),
                      TransferLeg(txn_id=inc.id, role="in")],
                fee=_fee_of(out, inc),
            )
            out.transfer_group_id = g.id
            inc.transfer_group_id = g.id
            out.kind = _kind_for(g.kind)
            inc.kind = out.kind
            groups.append(g)
            claimed.update({out.id, inc.id})
            c.resolution = "accepted"
        else:
            remaining.append(c)

    return TransferReport(groups=groups, candidates=remaining)


def _norm_blob(*parts: str | None) -> str:
    from .models import normalize_alias
    return normalize_alias(" ".join(p for p in parts if p))


def _score_pair(
    out: Txn, inc: Txn, accounts, fx_lookup, ctx: TransferContext
) -> tuple[float, list[str], int]:
    reasons: list[str] = []
    score = 0.0
    date_delta = abs((inc.txn_date - out.txn_date).days)

    in_acct = accounts.get(inc.account_id)
    out_acct = accounts.get(out.account_id)
    is_cc_in = bool(
        in_acct and in_acct.account_type in (AccountType.CREDIT_CARD, AccountType.CHARGE_CARD)
    )
    max_gap = MAX_CC_PAYMENT_GAP_DAYS if is_cc_in else MAX_DATE_GAP_DAYS
    if date_delta > max_gap:
        return 0.0, [], 0

    same_ccy = out.booked.currency == inc.booked.currency
    if same_ccy:
        delta = abs(abs(out.booked.amount) - abs(inc.booked.amount))
        if delta == 0:
            score += 0.62
            reasons.append("amounts match exactly")
        elif delta <= max(200, abs(out.booked.amount) // 100):
            score += 0.42
            reasons.append(f"amounts differ by {delta} minor units (fee-sized)")
        else:
            return 0.0, [], delta
    else:
        delta, ok = _fx_reconcile(out, inc, fx_lookup)
        if not ok:
            return 0.0, [], delta
        score += 0.35
        reasons.append("cross-currency legs reconcile within tolerance")

    score += 0.20 * max(0.0, 1.0 - date_delta / (max_gap + 1))
    reasons.append(f"date gap {date_delta}d")

    # Evidence that these two legs are one movement rather than two coincidences
    # of amount and date. A transfer leaves a trace — a payment marker, a name
    # linking the legs, a shared provider, an FX match. Without any, an exact
    # amount within the window is just a collision, and pairing it manufactures
    # a transfer out of, say, an Octopus top-up and a same-sized bar refund.
    evidence = 0

    text = f"{out.description_norm} {inc.description_norm}"
    has_payment_wording = any(w in text for w in (
        "TRANSFER", "FPS", "FASTER PAYMENT", "AUTOPAY", "PAYMENT RECEIVED",
        "THANK YOU", "EPAYMENT", "ACH PMT", "MOX CREDIT PAYMENT", "IFS PAYMENT"))
    if has_payment_wording:
        score += 0.12
        evidence += 1
        reasons.append("transfer/payment wording")

    # A card credit is a payment only when it says so; otherwise it is a
    # merchant refund that happens to land on a card.
    if is_cc_in and has_payment_wording:
        score += 0.12
        reasons.append("card payment")

    exchange_debit = any(w in out.description_norm for w in (
        "GOLD/EXCHANGE DEBIT", "FOREIGN EXCHANGE DEBIT", "FX DEBIT"))
    if is_cc_in and not same_ccy and exchange_debit and has_payment_wording:
        score += 0.20
        evidence += 1
        reasons.append("exchange debit funds a cross-currency card payment")

    same_group = bool(
        out_acct and in_acct and out_acct.balance_group
        and out_acct.balance_group == in_acct.balance_group
        and (out_acct.account_type is AccountType.MULTI_CURRENCY
             or in_acct.account_type is AccountType.MULTI_CURRENCY)
    )
    if same_group:
        score += 0.12
        evidence += 1
        reasons.append("same balance group (in-provider conversion)")

    parser_flagged_fx = (
        out.kind == TxnKind.FX_CONVERSION or inc.kind == TxnKind.FX_CONVERSION
    )
    if parser_flagged_fx:
        score += 0.08
        reasons.append("parser flagged FX conversion")

    if (out.external_ref and inc.external_ref
            and out.external_ref == inc.external_ref):
        score += 0.25
        evidence += 1
        reasons.append("issuer reference matches")

    out_blob = _norm_blob(out.description_raw, out.counterparty, out.description_norm)
    inc_blob = _norm_blob(inc.description_raw, inc.counterparty, inc.description_norm)

    # Cross-provider movements usually name the provider on the opposite leg:
    # "WISE PAYMENTS" on Mox, "Americanexpress Transfer" on Chase. Account
    # aliases can be ambiguous across several cards, but the institution name
    # is still strong destination evidence.
    out_family = out_acct.institution_id.split("_", 1)[0] if out_acct else ""
    in_family = in_acct.institution_id.split("_", 1)[0] if in_acct else ""
    if out_acct and in_acct and out_family != in_family:
        if _names_institution(out_blob, in_acct.institution_id):
            score += 0.30
            evidence += 1
            reasons.append("outflow names destination institution")
        elif _names_institution(inc_blob, out_acct.institution_id):
            score += 0.30
            evidence += 1
            reasons.append("inflow names source institution")

    for alias, aid in ctx.account_aliases.items():
        if not alias or len(alias) < 3:
            continue
        if aid == inc.account_id and alias in out_blob:
            score += 0.15
            evidence += 1
            reasons.append(f"outflow names destination account ({alias})")
            break
        if aid == out.account_id and alias in inc_blob:
            score += 0.10
            evidence += 1
            reasons.append(f"inflow names source account ({alias})")
            break

    if ctx.self_aliases:
        out_names_self = any(a in out_blob for a in ctx.self_aliases if len(a) >= 4)
        inc_names_self = any(a in inc_blob for a in ctx.self_aliases if len(a) >= 4)
        if out_names_self and inc_names_self:
            score += 0.08
            evidence += 1
            reasons.append("both legs name self")
        elif out_names_self or inc_names_self:
            score += 0.06
            evidence += 1
            reasons.append("one leg names self")

            if same_ccy and delta == 0 and date_delta <= 2:
                score += 0.10
                reasons.append("exact near-date self movement")
            elif not same_ccy and parser_flagged_fx and date_delta == 0:
                score += 0.21
                reasons.append("same-day self movement reconciles through FX")

    if ctx.person_aliases and any(a in out_blob for a in ctx.person_aliases if len(a) >= 4):
        score -= 0.25
        reasons.append("outflow names a known external person (P2P, not self-transfer)")

    if not evidence:
        return 0.0, [], delta

    return min(max(score, 0.0), 1.0), reasons, delta


_INSTITUTION_TOKENS = {
    "amex": ("AMEX", "AMERICANEXPRESS", "AMERICAN EXPRESS"),
    "chase": ("CHASE", "JPMORGAN"),
    "hsbc": ("HSBC",),
    "mox": ("MOX",),
    "wise": ("WISE", "TRANSFERWISE"),
}


def _names_institution(blob: str, institution_id: str) -> bool:
    family = institution_id.split("_", 1)[0].lower()
    return any(_norm_blob(token) in blob for token in _INSTITUTION_TOKENS.get(family, ()))


def _fx_reconcile(out: Txn, inc: Txn, fx_lookup) -> tuple[int, bool]:
    """Check that two cross-currency legs are plausibly the same movement."""
    if fx_lookup is None:
        return 0, False
    rate = fx_lookup(out.txn_date, out.booked.currency, inc.booked.currency)
    if not rate:
        return 0, False
    expected = abs(out.booked.to_decimal()) * Decimal(rate)
    actual = abs(inc.booked.to_decimal())
    if expected == 0:
        return 0, False
    drift = abs(expected - actual) / expected
    delta_minor = int((expected - actual) * (10 ** 2))
    return delta_minor, drift <= FX_TOLERANCE_PCT


def _classify(out: Txn, inc: Txn, accounts) -> TransferKind:
    in_acct = accounts.get(inc.account_id)
    if in_acct and in_acct.account_type in (AccountType.CREDIT_CARD, AccountType.CHARGE_CARD):
        return TransferKind.CC_PAYMENT
    if out.booked.currency != inc.booked.currency:
        return TransferKind.FX_CONVERSION
    if out.kind == TxnKind.ATM or "ATM" in out.description_norm:
        return TransferKind.ATM_WITHDRAWAL
    return TransferKind.INTERNAL_TRANSFER


def _kind_for(tk: TransferKind) -> TxnKind:
    return {
        TransferKind.CC_PAYMENT: TxnKind.CC_PAYMENT,
        TransferKind.FX_CONVERSION: TxnKind.FX_CONVERSION,
        TransferKind.ATM_WITHDRAWAL: TxnKind.ATM,
        TransferKind.INTERNAL_TRANSFER: TxnKind.TRANSFER,
    }[tk]


def _fee_of(out: Txn, inc: Txn) -> Money | None:
    if out.booked.currency != inc.booked.currency:
        return out.fx_fee or inc.fx_fee
    gap = abs(out.booked.amount) - abs(inc.booked.amount)
    if gap > 0:
        return Money(amount=gap, currency=out.booked.currency)
    return None
