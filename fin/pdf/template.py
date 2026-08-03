"""Declarative statement templates.

A template describes one issuer's table layout as data: where the transaction
rows start and stop, which column holds what, how dates are written, and which
of the statement's own figures can be used to check the result. Because it is
data rather than code, a template can be hand-written, corrected by an agent, or
proposed by an LLM from a single sample page, and applied to every other
statement from that issuer deterministically.

The row model is deliberately simple, because it is the one thing every layout
observed has in common:

    a row carrying an amount emits a transaction;
    a row carrying a date sets the date for the rows that follow;
    a row carrying neither is description for the transaction being built.

That single rule covers HSBC putting the amount on a continuation line beneath
the date, AMEX putting a CR marker on the line *after* the amount it negates,
and Mox wrapping one description across three lines.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path
from typing import Any

from ..models import Money, minor_exponent
from ..parsers.base import parse_amount
from .extract import PdfDocument, TextLine, Word
from .layout import (
    BARE_INT_TOKEN,
    ColumnSet,
    columns_from_anchors,
    columns_from_fractions,
    is_money,
)


class TemplateError(ValueError):
    """A template is malformed, or does not fit the document it was applied to."""


# --------------------------------------------------------------------------
# Schema
# --------------------------------------------------------------------------


@dataclass
class ColumnSpec:
    """How to find the columns of a section."""

    mode: str = "anchors"                       # "anchors" | "fractions"
    header: str = ""                            # regex identifying the header row
    anchors: dict[str, str] = field(default_factory=dict)
    fractions: list[list] = field(default_factory=list)   # [name, x0frac, x1frac]

    def resolve(
        self, line: TextLine | None, page_width: float, required: set[str] | None = None
    ) -> ColumnSet | None:
        if self.mode == "fractions":
            return columns_from_fractions(
                [(n, float(a), float(b)) for n, a, b in self.fractions], page_width
            )
        if line is None:
            return None
        return columns_from_anchors(
            line, self.anchors, page_width=page_width, required=required
        )


@dataclass
class AmountSpec:
    """How a section's columns turn into signed minor units.

    Finto's convention is uniform: money leaving the account is negative.
    Issuers are not, so each mode records how to get from theirs to ours.
    """

    # "debit_credit"  two columns, position carries the sign (HSBC savings)
    # "signed"        one column, the written sign is authoritative (Mox, Chase)
    # "cr_marker"     one column, a CR suffix marks a credit (AMEX, HSBC cards)
    mode: str = "signed"
    column: str = "amount"
    credit_column: str = "deposit"
    debit_column: str = "withdrawal"
    balance_column: str = "balance"
    # True for card statements that print a purchase as a positive number.
    invert: bool = False
    # A CR marker may sit on the line below the amount it applies to.
    cr_on_following_line: bool = False


@dataclass
class BalanceRule:
    """A figure the issuer prints that we can reconcile against."""

    pattern: str
    kind: str = "closing"          # "opening" | "closing"
    column: str = ""               # blank: take the last money token on the line
    #: "document" searches the whole statement. Card issuers print the closing
    #: balance in a summary box above the transaction table, not inside it.
    scope: str = "section"
    #: Rows away from the matched line to read the figure from. Mox sets the
    #: amount above its own label, so the label matches at offset -1.
    line_offset: int = 0
    #: Which money token on that row to read. AMEX prints its whole reconciliation
    #: on one line — previous, credits, debits, new balance — so the opening and
    #: closing figures are the first and fourth tokens of the same row.
    token_index: int = 0
    #: Card issuers state a balance as the amount owed. Finto holds a liability
    #: as negative, so those figures need their sign reversed to be comparable.
    negate: bool = False
    #: When set, scan forward from the label for the first row carrying at
    #: least this many money tokens, instead of trusting a fixed offset. AMEX
    #: added an "(Including Installments)" line between its heading and its
    #: figures partway through 2025, which a fixed offset does not survive.
    min_tokens: int = 0
    #: How far forward to scan for that row.
    search_ahead: int = 4
    #: A standalone CR word level with the figure — AMEX HK drops it on the row
    #: beneath the summary, under the New Balance column — marks a credit
    #: balance and flips the sign `negate` would give.
    cr_following_line: bool = False


@dataclass
class SectionSpec:
    name: str
    start: str = ""
    end: str = ""
    currency: str = ""
    account_hint: str = ""
    columns: ColumnSpec = field(default_factory=ColumnSpec)
    amount: AmountSpec = field(default_factory=AmountSpec)
    #: When set, only this column is the description. Otherwise every column
    #: that is neither a date nor money is joined, which is right for layouts
    #: that spread a description across several unnamed columns.
    description_column: str = ""
    #: The issuer's posting date, kept as provenance beside the activity date.
    settlement_column: str = ""
    #: A column naming the currency of each row. HSBC files every foreign
    #: currency sub-ledger under one heading and distinguishes them by a CCY
    #: column, stated once and carried down until it changes. Rows are split
    #: per currency so each reconciles against its own balances.
    currency_column: str = ""
    exclude: list[str] = field(default_factory=list)
    balances: list[BalanceRule] = field(default_factory=list)
    # Rows matching these are structural totals, not transactions.
    stop_at: list[str] = field(default_factory=list)
    #: Where a transaction's wrapped text sits relative to its figures row:
    #: "above" (Mox wraps the description over the figures) or "below"
    #: (AMEX HK sets FX details under the transaction they belong to).
    continuation: str = "above"


@dataclass
class DateSpec:
    # "auto" handles every notation seen; narrow it only to resolve ambiguity.
    format: str = "auto"
    dayfirst: bool = True
    # Statements name a month without a year; take it from the statement date
    # and step back a year when the month runs ahead of it.
    year_from_statement: bool = True


@dataclass
class StatementTemplate:
    template_id: str
    institution_id: str = "generic"
    label: str = ""
    version: str = "1.0.0"
    #: All of these regexes must appear in the document for the template to apply.
    match_all: list[str] = field(default_factory=list)
    match_none: list[str] = field(default_factory=list)
    default_currency: str = "HKD"
    date: DateSpec = field(default_factory=DateSpec)
    statement_date: str = ""
    period: str = ""
    #: Everything above this marker is preamble. Statements repeat their section
    #: headings in a summary table near the top — Mox lists "HKD Mox Account"
    #: both as a summary row and as the real ledger heading — so sections must
    #: be located within the body, not the whole document.
    body_start: str = ""
    body_end: str = ""
    sections: list[SectionSpec] = field(default_factory=list)
    #: Arithmetic the issuer prints, used to prove nothing was dropped.
    verify: dict[str, str] = field(default_factory=dict)
    #: Provenance: hand-written, llm-derived, or agent-corrected.
    source: str = "builtin"

    # -- serialisation ----------------------------------------------------

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> StatementTemplate:
        try:
            sections = [_section_from_dict(s) for s in d.get("sections", [])]
            return cls(
                template_id=d["template_id"],
                institution_id=d.get("institution_id", "generic"),
                label=d.get("label", ""),
                version=d.get("version", "1.0.0"),
                match_all=d.get("match_all", []),
                match_none=d.get("match_none", []),
                default_currency=d.get("default_currency", "HKD"),
                date=DateSpec(**d.get("date", {})),
                statement_date=d.get("statement_date", ""),
                period=d.get("period", ""),
                body_start=d.get("body_start", ""),
                body_end=d.get("body_end", ""),
                sections=sections,
                verify=d.get("verify", {}),
                source=d.get("source", "builtin"),
            )
        except (KeyError, TypeError) as e:
            raise TemplateError(f"malformed template: {e}") from e

    @classmethod
    def load(cls, path: str | Path) -> StatementTemplate:
        return cls.from_dict(json.loads(Path(path).read_text()))

    def to_dict(self) -> dict[str, Any]:
        return {
            "template_id": self.template_id,
            "institution_id": self.institution_id,
            "label": self.label,
            "version": self.version,
            "match_all": self.match_all,
            "match_none": self.match_none,
            "default_currency": self.default_currency,
            "date": self.date.__dict__,
            "statement_date": self.statement_date,
            "period": self.period,
            "body_start": self.body_start,
            "body_end": self.body_end,
            "sections": [_section_to_dict(s) for s in self.sections],
            "verify": self.verify,
            "source": self.source,
        }

    # -- matching ---------------------------------------------------------

    def matches(self, doc: PdfDocument) -> float:
        """Confidence that this template describes `doc`."""
        text = doc.text
        if not self.match_all:
            return 0.0
        for pat in self.match_all:
            if not re.search(pat, text, re.IGNORECASE):
                return 0.0
        for pat in self.match_none:
            if re.search(pat, text, re.IGNORECASE):
                return 0.0
        # More distinguishing markers means a more specific template; prefer it
        # over a looser one that also matches.
        return min(0.6 + 0.1 * len(self.match_all), 0.98)


def _section_from_dict(s: dict) -> SectionSpec:
    return SectionSpec(
        name=s["name"],
        start=s.get("start", ""),
        end=s.get("end", ""),
        currency=s.get("currency", ""),
        account_hint=s.get("account_hint", ""),
        columns=ColumnSpec(**s.get("columns", {})),
        amount=AmountSpec(**s.get("amount", {})),
        description_column=s.get("description_column", ""),
        settlement_column=s.get("settlement_column", ""),
        currency_column=s.get("currency_column", ""),
        exclude=s.get("exclude", []),
        balances=[BalanceRule(**b) for b in s.get("balances", [])],
        stop_at=s.get("stop_at", []),
        continuation=s.get("continuation", "above"),
    )


def _section_to_dict(s: SectionSpec) -> dict:
    return {
        "name": s.name,
        "start": s.start,
        "end": s.end,
        "currency": s.currency,
        "account_hint": s.account_hint,
        "columns": s.columns.__dict__,
        "amount": s.amount.__dict__,
        "description_column": s.description_column,
        "settlement_column": s.settlement_column,
        "currency_column": s.currency_column,
        "exclude": s.exclude,
        "balances": [b.__dict__ for b in s.balances],
        "stop_at": s.stop_at,
        "continuation": s.continuation,
    }


# --------------------------------------------------------------------------
# Application
# --------------------------------------------------------------------------


@dataclass
class ExtractedRow:
    """One transaction recovered from the table, before normalisation."""

    txn_date: date
    description: str
    amount: Money
    currency: str
    section: str
    page_no: int
    line_no: int
    running_balance: Money | None = None
    raw_text: str = ""
    account_hint: str = ""
    settlement_date: date | None = None


@dataclass
class TemplateResult:
    rows: list[ExtractedRow] = field(default_factory=list)
    balances: list[tuple] = field(default_factory=list)   # (date, Money, kind, section)
    warnings: list[str] = field(default_factory=list)
    statement_date: date | None = None
    period_start: date | None = None
    period_end: date | None = None


def apply_template(
    doc: PdfDocument, tpl: StatementTemplate, *, currency_override: str | None = None
) -> TemplateResult:
    """Run a template over an extracted document."""
    result = TemplateResult()
    lines = doc.all_lines()
    text = doc.text

    lines = _trim_to_body(lines, tpl)

    result.statement_date = _find_date(text, tpl.statement_date, tpl.date.dayfirst)
    result.period_start, result.period_end = _find_period(
        text, tpl.period, tpl.date.dayfirst
    )
    anchor = result.statement_date or result.period_end

    page_width = doc.pages[0].width if doc.pages else 612.0

    for spec in tpl.sections:
        _document_balances(doc.all_lines(), spec, tpl, result, anchor,
                           currency_override or spec.currency or tpl.default_currency)

    for spec in tpl.sections:
        try:
            _apply_section(
                lines, spec, tpl, result, page_width,
                anchor=anchor,
                currency=currency_override or spec.currency or tpl.default_currency,
            )
        except TemplateError as e:
            result.warnings.append(f"section {spec.name!r}: {e}")

    if result.rows and not result.balances:
        result.warnings.append(
            "no opening or closing balance found — the import cannot be proven "
            "complete against the issuer's own figures"
        )
    return result


def _apply_section(
    lines: list[TextLine],
    spec: SectionSpec,
    tpl: StatementTemplate,
    result: TemplateResult,
    page_width: float,
    *,
    anchor: date | None,
    currency: str,
) -> None:
    start, end = _section_bounds(lines, spec)
    if start is None:
        return

    header_rx = re.compile(spec.columns.header, re.IGNORECASE) if spec.columns.header else None
    exclude_rx = [re.compile(p, re.IGNORECASE) for p in spec.exclude]
    stop_rx = [re.compile(p, re.IGNORECASE) for p in spec.stop_at]
    balance_rules = [(re.compile(b.pattern, re.IGNORECASE), b) for b in spec.balances]

    required = _required_columns(spec)
    cols = spec.columns.resolve(None, page_width) if spec.columns.mode == "fractions" else None

    current_date: date | None = None
    current_currency = currency
    pending_desc: list[str] = []
    last_row: ExtractedRow | None = None
    skip_until = -1

    for idx in range(start, end):
        if idx <= skip_until:
            continue
        line = lines[idx]
        raw = line.text.strip()
        if not raw:
            continue

        # A repeated header on a continuation page re-establishes the geometry.
        if header_rx is not None and header_rx.search(raw):
            resolved, skip_until = _resolve_header(
                lines, idx, end, spec, page_width, required
            )
            if resolved is not None:
                cols = resolved
            continue

        if any(rx.search(raw) for rx in stop_rx):
            break

        if spec.currency_column and cols is not None:
            found_ccy = _read_currency(cols.text(line, spec.currency_column))
            if found_ccy:
                current_currency = found_ccy

        matched_balance = _match_balance(
            line, raw, balance_rules, cols, current_currency, spec, result, anchor, tpl
        )
        if matched_balance:
            pending_desc.clear()
            continue

        if any(rx.search(raw) for rx in exclude_rx):
            continue

        if cols is None:
            continue

        cells = cols.cells(line)
        amount, balance = _read_amount(cells, spec.amount, current_currency)

        found_date, date_remainder = _split_date(cells.get("date", ""), tpl, anchor)

        # A CR marker on a continuation line — AMEX HK sets "UNITED STATES
        # DOLLAR CR" two rows beneath the charge it credits — negates the
        # transaction just emitted. Checked only when the line is not itself
        # a transaction row, so a dated row ending in CR is never consumed.
        if (spec.amount.cr_on_following_line and last_row is not None
                and amount is None and found_date is None
                and _CR_LINE.search(raw)):
            last_row.amount = Money(
                amount=-last_row.amount.amount, currency=last_row.amount.currency
            )
            # A marker applies once. Section totals are credits too and
            # carry their own CR, which would otherwise reach back and
            # flip the last real transaction a second time.
            last_row = None
            continue

        if found_date is not None:
            current_date = found_date
            # A date on a row that carries no amount opens a new entry, so any
            # text held over belongs to something already emitted and is
            # dropped. When the date and the amount share a row the held text
            # is this entry's own description, wrapped above it — Mox sets a
            # description over three rows with the figures on the middle one —
            # so it must survive.
            if amount is None:
                pending_desc.clear()

        if amount is None:
            desc = " ".join(p for p in (date_remainder, _describe(cells, spec)) if p)
            if desc:
                if (spec.continuation == "below" and last_row is not None
                        and found_date is None):
                    # AMEX HK puts FX details under their transaction, so
                    # trailing text belongs to the row just emitted rather
                    # than the one to come.
                    last_row.description = re.sub(
                        r"\s{2,}", " ", f"{last_row.description} {desc}").strip()
                else:
                    pending_desc.append(desc)
            continue

        if current_date is None:
            result.warnings.append(
                f"page {line.page_no + 1} line {line.line_no}: amount with no date "
                f"in scope — {raw[:60]!r}"
            )
            continue

        desc_parts = [*pending_desc, date_remainder, _describe(cells, spec)]
        description = re.sub(r"\s{2,}", " ", " ".join(p for p in desc_parts if p)).strip()
        pending_desc.clear()

        last_row = ExtractedRow(
            txn_date=current_date,
            description=description or "(no description)",
            amount=amount,
            currency=current_currency,
            section=_section_key(spec, current_currency),
            page_no=line.page_no,
            line_no=line.line_no,
            running_balance=balance,
            raw_text=raw,
            account_hint=spec.account_hint,
            settlement_date=_settlement_date(cells, spec, tpl, anchor),
        )
        result.rows.append(last_row)


#: Headings rarely occupy more than this many rows, and stopping short of a
#: data row matters more than reaching every heading.
_MAX_HEADER_ROWS = 5


def _resolve_header(
    lines: list[TextLine],
    idx: int,
    end: int,
    spec: SectionSpec,
    page_width: float,
    required: set[str],
) -> tuple[ColumnSet | None, int]:
    """Resolve columns from a header that may be stacked over several rows.

    Issuers set headings in narrow columns, so a heading wraps and its words
    land on different rows: Mox splits "Activity date" into "Activity" on one
    row and "date" two rows below, with "Description" and "Amount" on the row
    between. Anchoring to any single row therefore misses most of the columns.
    Merging the whole block before anchoring recovers all of them, and consumes
    the block so its stray words never reach a transaction's description.

    Returns the columns and the index of the last row consumed.
    """
    all_names = set(spec.columns.anchors)
    words: list = []
    last = idx
    fallback: tuple[ColumnSet | None, int] = (None, idx)

    for k in range(idx, min(idx + _MAX_HEADER_ROWS, end)):
        if k > idx and any(is_money(w.text) for w in lines[k].words):
            break                      # a data row: the header block ended
        words.extend(lines[k].words)
        last = k
        merged = TextLine(
            words=sorted(words, key=lambda w: w.x0),
            top=lines[idx].top,
            page_no=lines[idx].page_no,
            line_no=lines[idx].line_no,
        )
        # Stop at the first row that completes the header. Merging further
        # would consume the description text wrapped above the first
        # transaction, which sits directly beneath the headings and looks no
        # different from them.
        complete = spec.columns.resolve(merged, page_width, all_names)
        if complete is not None:
            return complete, k
        partial = spec.columns.resolve(merged, page_width, required)
        if partial is not None:
            fallback = (partial, k)

    return fallback if fallback[0] is not None else (None, last)


def _required_columns(spec: SectionSpec) -> set[str]:
    """Columns whose position must be known for the section to be read safely."""
    req = {"date"}
    if spec.amount.mode == "debit_credit":
        req |= {spec.amount.credit_column, spec.amount.debit_column}
    else:
        req.add(spec.amount.column)
    return req


def _trim_to_body(lines: list[TextLine], tpl: StatementTemplate) -> list[TextLine]:
    """Drop the preamble and trailing boilerplate around the transaction tables."""
    start, end = 0, len(lines)
    if tpl.body_start:
        rx = re.compile(tpl.body_start, re.IGNORECASE)
        for i, ln in enumerate(lines):
            if rx.search(ln.text):
                start = i
                break
    if tpl.body_end:
        rx = re.compile(tpl.body_end, re.IGNORECASE)
        for i in range(start, len(lines)):
            if rx.search(lines[i].text):
                end = i
                break
    return lines[start:end]


def _section_bounds(lines: list[TextLine], spec: SectionSpec) -> tuple[int | None, int]:
    if not spec.start:
        return 0, len(lines)
    start_rx = re.compile(spec.start, re.IGNORECASE)
    start = None
    for i, ln in enumerate(lines):
        if start_rx.search(ln.text):
            start = i + 1
            break
    if start is None:
        return None, 0
    if not spec.end:
        return start, len(lines)
    end_rx = re.compile(spec.end, re.IGNORECASE)
    for i in range(start, len(lines)):
        if end_rx.search(lines[i].text):
            return start, i
    return start, len(lines)


def _document_balances(
    lines: list[TextLine],
    spec: SectionSpec,
    tpl: StatementTemplate,
    result: TemplateResult,
    anchor: date | None,
    currency: str,
) -> None:
    """Read balances the issuer prints outside the transaction table."""
    for rule in spec.balances:
        if rule.scope != "document":
            continue
        rx = re.compile(rule.pattern, re.IGNORECASE)
        for i, line in enumerate(lines):
            if not rx.search(line.text):
                continue
            found = _balance_figure(lines, i, rule)
            if found is None:
                continue
            words, src = found
            if len(words) <= abs(rule.token_index):
                continue
            figure = words[rule.token_index]
            try:
                value = parse_amount(figure.text, currency)
            except ValueError:
                continue
            if rule.negate:
                value = Money(amount=-value.amount, currency=value.currency)
            if rule.cr_following_line and _cr_applies(lines, src, figure):
                value = Money(amount=-value.amount, currency=value.currency)
            result.balances.append(
                (None, value, rule.kind, _section_key(spec, currency), spec.account_hint))
            break


def _balance_figure(
    lines: list[TextLine], i: int, rule: BalanceRule
) -> tuple[list[Word], int] | None:
    """The money words of the row a balance rule points at, and that row's index."""
    if rule.min_tokens:
        for k in range(i, min(i + rule.search_ahead + 1, len(lines))):
            words = [w for w in lines[k].words if is_money(w.text)]
            if len(words) >= rule.min_tokens:
                return words, k
        return None
    target = i + rule.line_offset
    if not 0 <= target < len(lines):
        return None
    return [w for w in lines[target].words if is_money(w.text)], target


_CR_WORD = re.compile(r"^\(?CR\)?$", re.IGNORECASE)


def _cr_applies(lines: list[TextLine], src: int, figure: Word) -> bool:
    """True when a standalone CR word sits level with the figure — on its own
    row or the one beneath — in the figure's column. AMEX HK prints a credit
    New Balance as the bare figure on the summary row with CR dropped onto the
    next line, and the two are only linked by sharing an x-range."""
    for k in (src, src + 1):
        if k >= len(lines):
            break
        for w in lines[k].words:
            if _CR_WORD.match(w.text) and figure.x0 - 6 <= w.x0 <= figure.x1 + 14:
                return True
    return False


def _match_balance(
    line, raw, balance_rules, cols, currency, spec, result, anchor, tpl
) -> bool:
    for rx, rule in balance_rules:
        if rule.scope == "document":
            continue
        if not rx.search(raw):
            continue
        token = ""
        if rule.column and cols is not None:
            token = cols.text(line, rule.column).strip()
        if not token:
            money_tokens = [w.text for w in line.words if is_money(w.text)]
            token = money_tokens[-1] if money_tokens else ""
        if not token:
            continue
        try:
            value = parse_amount(token, currency)
        except ValueError:
            continue
        if rule.negate:
            value = Money(amount=-value.amount, currency=value.currency)
        when = _read_date(cols.cells(line), tpl, anchor) if cols else None
        result.balances.append(
            (when, value, rule.kind, _section_key(spec, currency), spec.account_hint))
        return True
    return False


def _section_key(spec: SectionSpec, currency: str) -> str:
    """Reconciliation happens per currency, so the key must carry one."""
    return f"{spec.name}:{currency}" if spec.currency_column else spec.name


_CCY_RE = re.compile(r"^[A-Z]{3}$")


def _read_currency(cell: str) -> str | None:
    for token in cell.split():
        if _CCY_RE.match(token):
            return token
    return None


def _describe(cells: dict[str, str], spec: SectionSpec) -> str:
    """Recover the description text from a row's cells."""
    if spec.description_column:
        return cells.get(spec.description_column, "").strip()
    skip = {
        spec.amount.column,
        spec.amount.credit_column,
        spec.amount.debit_column,
        spec.amount.balance_column,
        spec.settlement_column,
        "date",
    }
    parts = [v.strip() for k, v in cells.items() if k not in skip and v.strip()]
    return " ".join(parts)


def _settlement_date(
    cells: dict[str, str], spec: SectionSpec, tpl: StatementTemplate, anchor: date | None
) -> date | None:
    if not spec.settlement_column:
        return None
    token = cells.get(spec.settlement_column, "").strip()
    if not token:
        return None
    return parse_statement_date(token, tpl.date, anchor)


def _read_amount(
    cells: dict[str, str], spec: AmountSpec, currency: str
) -> tuple[Money | None, Money | None]:
    balance = _maybe_money(cells.get(spec.balance_column, ""), currency)

    if spec.mode == "debit_credit":
        credit = _maybe_money(cells.get(spec.credit_column, ""), currency)
        debit = _maybe_money(cells.get(spec.debit_column, ""), currency)
        if credit is not None:
            return credit, balance
        if debit is not None:
            return Money(amount=-abs(debit.amount), currency=currency), balance
        return None, balance

    token = cells.get(spec.column, "").strip()
    if not token:
        return None, balance
    money = _maybe_money(token, currency)
    if money is None:
        return None, balance

    amount = money.amount
    if spec.mode == "cr_marker":
        # Absent a marker the figure is a charge; CR flips it to a credit.
        is_credit = bool(_CR_MARKER.search(token))
        amount = abs(amount) if is_credit else -abs(amount)
        if spec.invert:
            amount = -amount
        return Money(amount=amount, currency=currency), balance

    if spec.invert:
        amount = -amount
    return Money(amount=amount, currency=currency), balance


# HSBC writes a credit as a suffix with no separator ("44.00CR"), where \b finds
# no boundary because a digit and a letter are both word characters. Requiring a
# non-letter before and after keeps the match off words like "CREDIT".
_CR_MARKER = re.compile(r"(?:^|[^A-Za-z])CR(?![A-Za-z])", re.IGNORECASE)

#: A continuation line that carries nothing but, or ends with, a standalone
#: CR marker: "CR", "(CR)", "UNITED STATES DOLLAR CR".
_CR_LINE = re.compile(r"^(?:\(?\s*CR\s*\)?|.*?[^A-Za-z]\(?CR\)?)$", re.IGNORECASE)


def _maybe_money(token: str, currency: str) -> Money | None:
    token = token.strip()
    if not token:
        return None
    # A cell can pick up a stray word; keep only the money-shaped tokens.
    candidates = [t for t in token.split() if is_money(t)]
    if not candidates and minor_exponent(currency) == 0:
        # JPY and friends have no minor unit, so a whole-yen amount is written
        # with neither a decimal part nor, below 1,000, a thousands separator.
        candidates = [t for t in token.split() if BARE_INT_TOKEN.match(t)]
    if not candidates:
        return None
    try:
        return parse_amount(candidates[-1], currency)
    except ValueError:
        return None


# --------------------------------------------------------------------------
# Dates
# --------------------------------------------------------------------------

_MONTHS = {
    m.lower(): i
    for i, m in enumerate(
        ["January", "February", "March", "April", "May", "June", "July",
         "August", "September", "October", "November", "December"], start=1)
}
_MONTHS.update({k[:3]: v for k, v in list(_MONTHS.items())})

_DATE_PATTERNS = (
    # 2025-01-31
    (re.compile(r"^(\d{4})-(\d{1,2})-(\d{1,2})$"), "ymd"),
    # 01/31/2025 or 31/01/2025
    (re.compile(r"^(\d{1,2})/(\d{1,2})/(\d{2,4})$"), "dmy_or_mdy"),
    # 01/31 — year from context
    (re.compile(r"^(\d{1,2})/(\d{1,2})$"), "md_or_dm"),
    # 05 Jan / 5 January
    (re.compile(r"^(\d{1,2})\s*([A-Za-z]{3,9})$"), "day_month"),
    # 17JAN
    (re.compile(r"^(\d{1,2})([A-Za-z]{3})$"), "day_month"),
    # December 16 / Dec 16
    (re.compile(r"^([A-Za-z]{3,9})\s+(\d{1,2})$"), "month_day"),
)


def _read_date(cells: dict[str, str], tpl: StatementTemplate, anchor: date | None) -> date | None:
    return _split_date(cells.get("date", ""), tpl, anchor)[0]


def _split_date(
    cell: str, tpl: StatementTemplate, anchor: date | None
) -> tuple[date | None, str]:
    """Take a leading date off a cell, returning it and the text that follows.

    Not every issuer gives the date a column of its own. AMEX heads a single
    column "DETAILS" and writes the date and the merchant into it, so the date
    has to be split off and the remainder kept as description rather than
    discarded.
    """
    tokens = cell.split()
    if not tokens:
        return None, ""
    for k in (1, 2, 3):
        if k > len(tokens):
            break
        when = parse_statement_date(" ".join(tokens[:k]), tpl.date, anchor)
        if when is not None:
            return when, " ".join(tokens[k:])
    return None, cell.strip()


def parse_statement_date(token: str, spec: DateSpec, anchor: date | None) -> date | None:
    """Parse a statement's date notation, inferring an absent year.

    A December row on a January statement belongs to the previous year; that is
    the only case where the inference is not simply the anchor's year.
    """
    token = token.strip().strip(".,")
    # Trailing markers like AMEX's "01/12/25*" (posting date).
    token = re.sub(r"[*†⧫]+$", "", token).strip()
    if not token:
        return None

    for rx, kind in _DATE_PATTERNS:
        m = rx.match(token)
        if not m:
            continue
        try:
            return _build_date(m, kind, spec, anchor)
        except (ValueError, KeyError):
            continue
    return None


def _build_date(m: re.Match, kind: str, spec: DateSpec, anchor: date | None) -> date | None:
    if kind == "ymd":
        return date(int(m.group(1)), int(m.group(2)), int(m.group(3)))

    if kind == "dmy_or_mdy":
        a, b, y = int(m.group(1)), int(m.group(2)), int(m.group(3))
        y += 2000 if y < 100 else 0
        day, month = (a, b) if spec.dayfirst else (b, a)
        if month > 12:                     # the convention was the other way
            day, month = month, day
        return date(y, month, day)

    if kind == "md_or_dm":
        a, b = int(m.group(1)), int(m.group(2))
        day, month = (a, b) if spec.dayfirst else (b, a)
        if month > 12:
            day, month = month, day
        return _with_inferred_year(month, day, spec, anchor)

    if kind == "day_month":
        day = int(m.group(1))
        month = _MONTHS.get(m.group(2).lower()[:3])
        if month is None:
            return None
        return _with_inferred_year(month, day, spec, anchor)

    if kind == "month_day":
        month = _MONTHS.get(m.group(1).lower()[:3])
        if month is None:
            return None
        return _with_inferred_year(month, int(m.group(2)), spec, anchor)
    return None


def _with_inferred_year(month: int, day: int, spec: DateSpec, anchor: date | None) -> date | None:
    if anchor is None:
        return None
    year = anchor.year
    # A statement covers at most a couple of months, so a month ahead of the
    # statement date is last year's, not next year's.
    if month > anchor.month + 1:
        year -= 1
    return date(year, month, day)


def _find_date(text: str, pattern: str, dayfirst: bool) -> date | None:
    if not pattern:
        return None
    m = re.search(pattern, text, re.IGNORECASE)
    if not m or not m.groups():
        return None
    return _loose_date(m.group(1), dayfirst)


def _find_period(text: str, pattern: str, dayfirst: bool) -> tuple[date | None, date | None]:
    if not pattern:
        return None, None
    m = re.search(pattern, text, re.IGNORECASE)
    if not m or len(m.groups()) < 2:
        return None, None
    return _loose_date(m.group(1), dayfirst), _loose_date(m.group(2), dayfirst)


def _loose_date(value: str, dayfirst: bool) -> date | None:
    """Parse a full date written in any of the notations issuers use."""
    v = re.sub(r"\s+", " ", value.strip().rstrip(","))
    formats = [
        "%d %B %Y", "%d %b %Y", "%B %d, %Y", "%b %d, %Y", "%B %d %Y", "%b %d %Y",
        "%Y-%m-%d", "%d%b%Y", "%d %b %y",
    ]
    formats += ["%d/%m/%Y", "%m/%d/%Y"] if dayfirst else ["%m/%d/%Y", "%d/%m/%Y"]
    formats += ["%d/%m/%y", "%m/%d/%y"] if dayfirst else ["%m/%d/%y", "%d/%m/%y"]
    from datetime import datetime

    for fmt in formats:
        try:
            return datetime.strptime(v, fmt).date()
        except ValueError:
            continue
    return None
