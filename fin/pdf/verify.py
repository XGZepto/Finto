"""Proving an extraction is complete.

A PDF gives no guarantee that a parser saw every row, and a silently dropped
transaction is indistinguishable from a quiet month. The defence is that
statements are internally redundant: they print an opening balance, a closing
balance, and the rows in between, and those three things must agree.

So every extraction is checked against the issuer's own arithmetic before it is
allowed near the ledger:

    opening + sum(rows) == closing

When that holds, the extraction is provably complete for that section — not
merely plausible. When it does not, the discrepancy is reported rather than
absorbed, because the amount by which it fails is usually the transaction that
was missed.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from ..models import Money, minor_exponent
from .template import TemplateResult


@dataclass
class SectionCheck:
    section: str
    currency: str
    opening: Money | None
    closing: Money | None
    computed_closing: Money | None
    discrepancy: Money | None
    row_count: int

    @property
    def ok(self) -> bool:
        return self.discrepancy is not None and self.discrepancy.amount == 0

    @property
    def checkable(self) -> bool:
        return self.discrepancy is not None


@dataclass
class VerificationReport:
    checks: list[SectionCheck] = field(default_factory=list)
    problems: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        """True only when nothing is wrong *and* something was actually proven.

        An extraction with no verifiable section is not a pass. Treating it as
        one is how an empty parse gets mistaken for a quiet month.
        """
        if self.problems:
            return False
        return any(c.checkable for c in self.checks) and all(
            c.ok for c in self.checks if c.checkable
        )

    @property
    def verified_sections(self) -> int:
        return sum(1 for c in self.checks if c.ok)

    def summary(self) -> str:
        checkable = [c for c in self.checks if c.checkable]
        if not checkable:
            return "unverified (no balances printed)"
        bad = [c for c in checkable if not c.ok]
        if not bad:
            return f"reconciled {len(checkable)}/{len(checkable)} sections"
        worst = bad[0]
        return (
            f"reconciled {len(checkable) - len(bad)}/{len(checkable)}; "
            f"{worst.section} off by {_fmt(worst.discrepancy)}"
        )


def verify_extraction(result: TemplateResult) -> VerificationReport:
    """Reconcile each section against the balances the statement printed."""
    report = VerificationReport()

    sections: dict[str, list] = {}
    for row in result.rows:
        sections.setdefault(row.section, []).append(row)

    balances: dict[str, dict[str, Money]] = {}
    for _when, money, kind, section in result.balances:
        balances.setdefault(section, {})[kind] = money

    names = list(dict.fromkeys([*sections.keys(), *balances.keys()]))
    for name in names:
        rows = sections.get(name, [])
        marks = balances.get(name, {})
        opening = marks.get("opening")
        closing = marks.get("closing")
        currency = rows[0].currency if rows else (
            opening.currency if opening else (closing.currency if closing else "")
        )

        total = sum(r.amount.amount for r in rows)
        computed = discrepancy = None
        if opening is not None and closing is not None:
            computed = Money(amount=opening.amount + total, currency=currency)
            discrepancy = Money(
                amount=computed.amount - closing.amount, currency=currency
            )

        report.checks.append(
            SectionCheck(
                section=name,
                currency=currency,
                opening=opening,
                closing=closing,
                computed_closing=computed,
                discrepancy=discrepancy,
                row_count=len(rows),
            )
        )

    for c in report.checks:
        if c.checkable and not c.ok:
            report.problems.append(
                f"section {c.section!r}: {c.row_count} rows give a closing balance of "
                f"{_fmt(c.computed_closing)} but the statement says {_fmt(c.closing)} "
                f"(off by {_fmt(c.discrepancy)}) — a row was probably missed or "
                f"double-counted"
            )
    return report


def _fmt(m: Money | None) -> str:
    if m is None:
        return "-"
    exp = minor_exponent(m.currency)
    return f"{m.amount / (10 ** exp):,.{exp}f} {m.currency}"
