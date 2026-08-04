"""Deduplication.

Four distinct duplicate sources, each needing different treatment:

1. Same file imported twice        -> caught upstream by file_sha256.
2. Overlapping statement periods   -> exact dedup_key collision.
3. Pending row later posted        -> dedup_key collides by design (the key
                                      excludes posted_date and status), but the
                                      date often shifts 1-3 days, so this also
                                      needs the fuzzy pass.
4. Same movement reported under two -> handled by the cross-account pass, which
   accounts of one provider (Wise        is scoped to a whitelist of accounts
   HKD/USD balances)                     sharing a `balance_group`.

Supplementary cards are deliberately NOT in this list: their charges post to the
parent account's statement, so they share an account_id and fall out of case 2
or 3 above. The card_id field exists for attribution and reporting, not dedup.

Auto-merge only on exact key match. Everything fuzzy goes to
duplicate_candidate for review — a wrongly-merged transaction is much harder to
notice later than a wrongly-kept one.
"""

from __future__ import annotations

from collections import defaultdict
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from difflib import SequenceMatcher

from .models import DuplicateCandidate, Txn, TxnStatus

# Tuning knobs. Deliberately conservative.
FUZZY_DATE_WINDOW_DAYS = 4
FUZZY_DESC_THRESHOLD = 0.82
AUTO_MERGE_THRESHOLD = 0.97   # fuzzy score above which we merge without asking
REVIEW_THRESHOLD = 0.70       # below this we don't even raise a candidate


@dataclass
class DedupReport:
    exact_merged: int = 0
    candidates: list[DuplicateCandidate] = None
    kept: int = 0

    def __post_init__(self):
        if self.candidates is None:
            self.candidates = []


def dedup_exact(txns: Sequence[Txn]) -> tuple[list[Txn], int]:
    """Collapse exact dedup_key collisions.

    Winner selection matters: prefer the POSTED row over a PENDING one, then
    the one with an external_ref, then the earliest imported. This way the
    surviving row is the most authoritative version of the charge.
    """
    by_key: dict[str, list[Txn]] = defaultdict(list)
    for t in txns:
        by_key[t.dedup_key].append(t)

    merged = 0
    for key, group in by_key.items():
        if len(group) < 2:
            continue
        group.sort(key=_authority_rank)
        winner = group[0]
        for loser in group[1:]:
            loser.duplicate_of_id = winner.id
            merged += 1

    survivors = [t for t in txns if t.duplicate_of_id is None]
    return survivors, merged


def _authority_rank(t: Txn) -> tuple:
    return (
        0 if t.status == TxnStatus.POSTED else 1,
        0 if t.external_ref else 1,
        0 if t.posted_date else 1,
        t.created_at,
    )


def find_fuzzy_duplicates(
    txns: Sequence[Txn],
    *,
    cross_account_pairs: set[tuple[str, str]] | None = None,
) -> list[DuplicateCandidate]:
    """Find near-duplicates that exact keying missed.

    Blocked on (currency, absolute amount) so this stays roughly linear rather
    than O(n^2) — two rows can only be duplicates if the money matches exactly.
    Amount is the one field statements never fudge.

    `cross_account_pairs` whitelists account pairs where a duplicate is
    plausible across accounts (a supplementary card and its parent). Without
    it, cross-account comparison is skipped entirely — otherwise every genuine
    transfer between your own accounts looks like a duplicate.
    """
    cross_account_pairs = cross_account_pairs or set()
    blocks: dict[tuple[str, int], list[Txn]] = defaultdict(list)
    for t in txns:
        if t.duplicate_of_id:
            continue
        blocks[(t.booked.currency, abs(t.booked.amount))].append(t)

    out: list[DuplicateCandidate] = []
    for block in blocks.values():
        if len(block) < 2:
            continue
        block.sort(key=lambda t: t.txn_date)
        for i, a in enumerate(block):
            for b in block[i + 1:]:
                delta = abs((b.txn_date - a.txn_date).days)
                if delta > FUZZY_DATE_WINDOW_DAYS:
                    break  # sorted by date; nothing further can be in window
                if (a.booked.amount < 0) != (b.booked.amount < 0):
                    continue  # opposite signs = transfer, not duplicate
                if a.account_id != b.account_id:
                    pair = tuple(sorted((a.account_id, b.account_id)))
                    if pair not in cross_account_pairs:
                        continue
                score, reasons = _score_duplicate(a, b, delta)
                if score < REVIEW_THRESHOLD:
                    continue
                keep, dupe = sorted((a, b), key=_authority_rank)
                out.append(DuplicateCandidate(
                    keep_txn_id=keep.id,
                    dupe_txn_id=dupe.id,
                    score=round(score, 4),
                    reasons=reasons,
                ))
    return out


def _score_duplicate(a: Txn, b: Txn, date_delta: int) -> tuple[float, list[str]]:
    reasons = ["amount+currency exact"]
    score = 0.55

    # Date proximity
    date_score = max(0.0, 1.0 - date_delta / (FUZZY_DATE_WINDOW_DAYS + 1))
    score += 0.15 * date_score
    reasons.append(f"date delta {date_delta}d")

    # Description similarity. Same-day same-account pairs whose norms share a
    # long common prefix (CSV "HIGHWAY BUS DOTCOM JAPAN" vs a PDF that picked
    # up trailing FX noise) are still the same charge.
    sim = SequenceMatcher(None, a.description_norm, b.description_norm).ratio()
    prefix = _common_prefix_ratio(a.description_norm, b.description_norm)
    if sim >= FUZZY_DESC_THRESHOLD or prefix >= 0.85:
        score += 0.25 * max(sim, prefix)
        reasons.append(f"description similarity {max(sim, prefix):.2f}")
    else:
        score -= 0.15
        reasons.append(f"weak description similarity {sim:.2f}")

    # Same calendar day on the same account + exact amount is almost always
    # a CSV↔PDF restatement. Boost it over the auto-merge line when the
    # descriptions at least share a prefix.
    if (date_delta == 0 and a.account_id == b.account_id
            and prefix >= 0.70):
        score += 0.12
        reasons.append("same-day same-account restatement")

    # Pending/posted pair is the classic case — boost it.
    if {a.status, b.status} == {TxnStatus.PENDING, TxnStatus.POSTED}:
        score += 0.12
        reasons.append("pending/posted pair")

    # Conflicting issuer references are strong evidence they are NOT the same.
    if a.external_ref and b.external_ref and a.external_ref != b.external_ref:
        score -= 0.45
        reasons.append("different external refs")

    if a.merchant and b.merchant and a.merchant == b.merchant:
        score += 0.05
        reasons.append("same merchant")

    return max(0.0, min(score, 1.0)), reasons


def _common_prefix_ratio(a: str, b: str) -> float:
    if not a or not b:
        return 0.0
    n = 0
    for x, y in zip(a, b):
        if x != y:
            break
        n += 1
    return n / max(len(a), len(b))


def apply_candidates(
    txns: Sequence[Txn],
    candidates: Iterable[DuplicateCandidate],
    *,
    auto_threshold: float = AUTO_MERGE_THRESHOLD,
) -> tuple[int, list[DuplicateCandidate]]:
    """Auto-merge only very high-confidence pairs; return the rest for review."""
    index = {t.id: t for t in txns}
    merged = 0
    pending: list[DuplicateCandidate] = []
    for c in candidates:
        if c.score >= auto_threshold:
            dupe = index.get(c.dupe_txn_id)
            keep = index.get(c.keep_txn_id)
            if dupe and keep and dupe.duplicate_of_id is None:
                dupe.duplicate_of_id = keep.id
                merged += 1
        else:
            pending.append(c)
    return merged, pending


def run_dedup(
    txns: Sequence[Txn],
    *,
    cross_account_pairs: set[tuple[str, str]] | None = None,
) -> DedupReport:
    survivors, exact = dedup_exact(txns)
    cands = find_fuzzy_duplicates(survivors, cross_account_pairs=cross_account_pairs)
    auto, pending = apply_candidates(survivors, cands)
    kept = sum(1 for t in txns if t.duplicate_of_id is None)
    return DedupReport(exact_merged=exact + auto, candidates=pending, kept=kept)
