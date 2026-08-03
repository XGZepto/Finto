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
    #: Rows whose printed running balance contradicts the arithmetic.
    running_breaks: list[str] = field(default_factory=list)

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
    def status(self) -> str:
        """One of "verified", "unverified", "failed".

        "unverified" is deliberately distinct from both. A card statement
        prints a closing balance but no opening one, so a single file cannot
        prove itself — the check that covers it compares consecutive statements
        once they are in the ledger. Reporting that as a failure would train
        the reader to ignore failures.
        """
        if self.problems:
            return "failed"
        return "verified" if any(c.checkable for c in self.checks) else "unverified"

    @property
    def ok(self) -> bool:
        """True when nothing contradicts the issuer's own figures."""
        return not self.problems

    @property
    def verified_sections(self) -> int:
        return sum(1 for c in self.checks if c.ok)

    def summary(self) -> str:
        checkable = [c for c in self.checks if c.checkable]
        if not checkable:
            marks = sum(1 for c in self.checks if c.opening or c.closing)
            if marks:
                return (
                    "unverified — the statement prints only one balance, so it "
                    "reconciles against the next statement, not itself"
                )
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

        # A statement that prints a running balance states the closing figure
        # on its last row, whether or not it also labels one at the foot.
        if closing is None:
            trailing = [r for r in rows if r.running_balance is not None]
            if trailing:
                closing = trailing[-1].running_balance

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
                running_breaks=_check_running_balance(rows, opening),
            )
        )

    for c in report.checks:
        for brk in c.running_breaks:
            report.problems.append(f"section {c.section!r}: {brk}")
        if c.checkable and not c.ok:
            report.problems.append(
                f"section {c.section!r}: {c.row_count} rows give a closing balance of "
                f"{_fmt(c.computed_closing)} but the statement says {_fmt(c.closing)} "
                f"(off by {_fmt(c.discrepancy)}) — a row was probably missed or "
                f"double-counted"
            )
    return report


def _check_running_balance(rows: list, opening: Money | None) -> list[str]:
    """Follow the statement's running balance column row by row.

    Much sharper than comparing opening to closing: when a row is missed, this
    reports the exact row where the arithmetic first stops working, which is
    where the missing transaction belongs. Rows without a printed balance are
    carried forward, because issuers print one balance for a group of same-day
    entries rather than one per entry.
    """
    printed = [r for r in rows if r.running_balance is not None]
    if len(printed) < 2:
        return []

    running = opening.amount if opening is not None else None
    breaks: list[str] = []
    for row in rows:
        if running is None:
            # No opening figure: adopt the first printed balance as the datum.
            if row.running_balance is not None:
                running = row.running_balance.amount
            continue
        running += row.amount.amount
        if row.running_balance is None:
            continue
        if running != row.running_balance.amount:
            gap = Money(
                amount=row.running_balance.amount - running,
                currency=row.amount.currency,
            )
            breaks.append(
                f"running balance breaks at {row.txn_date} "
                f"{row.description[:40]!r}: statement says {_fmt(row.running_balance)}, "
                f"the rows give {_fmt(Money(amount=running, currency=row.amount.currency))} "
                f"({_fmt(gap)} unaccounted for)"
            )
            running = row.running_balance.amount   # resync and keep checking
    return breaks[:3]


def _fmt(m: Money | None) -> str:
    if m is None:
        return "-"
    exp = minor_exponent(m.currency)
    return f"{m.amount / (10 ** exp):,.{exp}f} {m.currency}"
