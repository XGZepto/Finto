"""PDF statement extraction.

Several issuers — Mox in particular — give you a PDF and nothing else. A PDF has
no columns, only text positioned on a page, so extraction is line-oriented: pull
the text layer, find the lines that look like transactions, and read date /
description / amount out of each.

This is inherently less certain than CSV, and the design reflects that:

* **Only the text layer is read.** A scanned statement has no text layer and is
  rejected outright rather than silently importing nothing. OCR is a different
  problem with different failure modes.
* **Every line that looks like a transaction but doesn't parse becomes a
  warning**, never a silent skip. On a PDF, a dropped row is exactly the error
  the balance-assertion check exists to catch — but only if you notice.
* **The closing balance is captured when present**, so `check` can verify the
  extraction against the issuer's own figure. For PDFs this matters more than
  anywhere else.

`pypdf` is an optional dependency: `pip install finto[pdf]`.
"""

from __future__ import annotations

import re
from datetime import date
from decimal import Decimal

from ..enrich import extract_details
from ..installments import parse_installment_marker
from ..models import FileFormat, Money, ParsedTxn
from .base import ParseContext, ParseResult, StatementParser, parse_amount, parse_date, register


def extract_text(path) -> list[str]:
    """Return the PDF's text as lines. Empty when there is no text layer."""
    try:
        from pypdf import PdfReader
    except ImportError as e:  # pragma: no cover - depends on optional extra
        raise RuntimeError(
            "PDF support needs pypdf — install with: pip install 'finto[pdf]'") from e

    reader = PdfReader(str(path))
    lines: list[str] = []
    for page in reader.pages:
        text = page.extract_text() or ""
        lines.extend(ln.rstrip() for ln in text.splitlines())
    return [ln for ln in lines if ln.strip()]


# A money token: 1,234.56 / (1,234.56) / 1,234.56CR / -1,234.56
_MONEY = r"\(?-?(?:\d{1,3}(?:,\d{3})*|\d+)\.\d{2}\)?(?:\s?(?:CR|DR))?"
_MONEY_RE = re.compile(_MONEY, re.I)

# Dates as statements write them, anchored at the start of a transaction line.
_DATE_PATTERNS = (
    r"\d{1,2}[/-]\d{1,2}[/-]\d{2,4}",
    r"\d{1,2}\s+[A-Za-z]{3,9}\s+\d{2,4}",
    r"\d{1,2}\s?[A-Za-z]{3}",              # "05 Jan" — year comes from context
    r"\d{4}-\d{2}-\d{2}",
)
_LINE_RE = re.compile(
    r"^\s*(?P<date>" + "|".join(_DATE_PATTERNS) + r")\s+(?P<rest>.+)$")

# Note the character classes exclude newlines throughout: a label and its value
# are always on one line, and `\D` would happily match across a line break and
# pair a label with a number from somewhere else entirely. The generous upper
# bounds are because PDFs pad columns with wide runs of spaces.
_GAP = r"[^\d\r\n]{0,80}"
_STATEMENT_PERIOD = re.compile(
    r"(?:statement period|period|from)[^\d\r\n]{0,12}"
    r"(\d{1,2}[/\-\s][A-Za-z0-9]{2,9}[/\-\s]\d{2,4})[^\d\r\n]{1,10}"
    r"(\d{1,2}[/\-\s][A-Za-z0-9]{2,9}[/\-\s]\d{2,4})", re.I)
_CLOSING_BALANCE = re.compile(
    r"(?:closing|ending|statement|new)\s+balance" + _GAP + r"(" + _MONEY + r")", re.I)
_OPENING_BALANCE = re.compile(
    r"(?:opening|previous|beginning|prior)\s+balance" + _GAP + r"(" + _MONEY + r")", re.I)

# Lines that carry money but are not transactions.
_NOISE_LINE = re.compile(
    r"\b(total|subtotal|balance|minimum payment|credit limit|available|"
    r"interest rate|apr|page \d|statement date|due date|payment due)\b", re.I)


@register
class PdfStatementParser(StatementParser):
    """Generic line-oriented PDF statement parser.

    CONFIDENCE: medium. Layout varies by issuer far more than CSV does. Always
    run `sniff` on a new PDF before importing, and check the resulting balance
    reconciliation — for PDFs that check is the difference between a trustworthy
    import and a plausible-looking one.
    """

    parser_id = "pdf_statement"
    version = "0.1.0"
    institution_id = "generic"
    file_format = FileFormat.PDF

    def sniff(self, ctx: ParseContext, sample: bytes) -> float:
        if not sample.startswith(b"%PDF"):
            return 0.0
        try:
            lines = extract_text(ctx.path)
        except Exception:
            return 0.0
        if not lines:
            return 0.0
        # Confidence tracks how much of the document parses as transactions.
        hits = sum(1 for ln in lines if _looks_like_txn(ln))
        if hits >= 3:
            return min(0.55 + 0.05 * hits, 0.9)
        return 0.0

    def parse(self, ctx: ParseContext) -> ParseResult:
        lines = extract_text(ctx.path)
        if not lines:
            return ParseResult(
                txns=[], raw_rows=[], account_id=ctx.account_id,
                warnings=["PDF has no text layer — it is probably a scan. "
                          "Export a CSV from the issuer, or OCR the file first."])

        ccy = ctx.default_currency or _guess_currency(lines) or "HKD"
        year = _guess_year(lines)

        txns: list[ParsedTxn] = []
        raw_rows: list[dict] = []
        warnings: list[str] = []
        balances: list[tuple] = []

        for i, line in enumerate(lines):
            if not _looks_like_txn(line):
                continue
            parsed = _parse_line(line, ccy, year, i)
            if parsed is None:
                # Looked like a transaction but didn't parse. On a PDF this is
                # exactly how rows go missing, so it must be visible.
                warnings.append(f"line {i}: could not parse {line[:70]!r}")
                continue
            txns.append(parsed)
            raw_rows.append({"line_no": i, "text": line})

        period_start, period_end = _period(lines)

        # The issuer's own closing balance is the check on this extraction.
        closing = _CLOSING_BALANCE.search("\n".join(lines))
        if closing and period_end:
            try:
                balances.append((period_end, parse_amount(closing.group(1), ccy)))
            except ValueError:
                pass
        opening = _OPENING_BALANCE.search("\n".join(lines))
        if opening and period_start:
            try:
                balances.append((period_start, parse_amount(opening.group(1), ccy)))
            except ValueError:
                pass

        if txns and not balances:
            warnings.append(
                "no opening/closing balance found — `check` cannot verify that "
                "every transaction was extracted from this PDF")

        return ParseResult(
            txns=txns, raw_rows=raw_rows, warnings=warnings,
            account_id=ctx.account_id, balances=balances,
            period_start=period_start, period_end=period_end)


def _looks_like_txn(line: str) -> bool:
    if not _LINE_RE.match(line):
        return False
    if _NOISE_LINE.search(line):
        return False
    return bool(_MONEY_RE.search(line))


def _parse_line(line: str, ccy: str, year: int | None, line_no: int) -> ParsedTxn | None:
    m = _LINE_RE.match(line)
    if not m:
        return None

    try:
        when = _parse_date_token(m.group("date"), year)
    except ValueError:
        return None

    rest = m.group("rest")
    amounts = _MONEY_RE.findall(rest)
    if not amounts:
        return None

    # The last money token on the line is usually the running balance, and the
    # one before it the transaction amount. With a single token, that token is
    # the amount.
    if len(amounts) >= 2:
        amount_token, balance_token = amounts[-2], amounts[-1]
    else:
        amount_token, balance_token = amounts[-1], None

    try:
        booked = parse_amount(amount_token, ccy)
    except ValueError:
        return None

    description = _MONEY_RE.sub(" ", rest).strip(" .-")
    description = re.sub(r"\s{2,}", " ", description)
    if not description:
        description = "(no description)"

    return ParsedTxn(
        txn_date=when,
        booked=booked,
        description_raw=description,
        installment_hint=parse_installment_marker(description),
        details=extract_details(description=description),
        line_no=line_no,
        extra={"pdf_line": line, "balance_token": balance_token},
    )


def _parse_date_token(token: str, year: int | None) -> date:
    token = token.strip()
    # "05 Jan" with no year — take it from the statement period.
    if re.fullmatch(r"\d{1,2}\s?[A-Za-z]{3}", token):
        if year is None:
            raise ValueError("no year context")
        return parse_date(f"{token} {year}", dayfirst=True)
    return parse_date(token, dayfirst=True)


def _period(lines: list[str]) -> tuple[date | None, date | None]:
    m = _STATEMENT_PERIOD.search("\n".join(lines[:40]))
    if not m:
        return None, None
    try:
        return (parse_date(m.group(1), dayfirst=True),
                parse_date(m.group(2), dayfirst=True))
    except ValueError:
        return None, None


def _guess_year(lines: list[str]) -> int | None:
    start, end = _period(lines)
    if start:
        return start.year
    m = re.search(r"\b(20\d{2})\b", "\n".join(lines[:40]))
    return int(m.group(1)) if m else None


def _guess_currency(lines: list[str]) -> str | None:
    head = "\n".join(lines[:40]).upper()
    for code in ("HKD", "USD", "GBP", "EUR", "SGD", "JPY", "AUD", "CNY"):
        if re.search(rf"\b{code}\b", head):
            return code
    return None
