"""PDF statement extraction via the layout-template engine.

The old line-oriented path flattened tables into reading order and destroyed
exactly the information that gives a number its meaning — an amount under
Withdrawal and the same amount under Deposit arrived identical. That is why it
extracted nothing from most of the real corpus.

The path now is:

    extract  -> words with coordinates (lossless, stored as raw_record)
    template -> declarative per-issuer column layout
    verify   -> reconcile against the statement's own printed balances
    llm      -> optional fallback when no template matches or verify fails

A failed verification is a hard warning, never a silent import of wrong money.
"""

from __future__ import annotations

import re
from datetime import date

from ..enrich import extract_details
from ..installments import parse_installment_marker
from ..models import FileFormat, ParsedTxn
from ..pdf.extract import PdfTextLayerMissing, extract_document
from ..pdf.registry import select_template
from ..pdf.template import TemplateResult, apply_template
from ..pdf.verify import verify_extraction
from .base import ParseContext, ParseResult, StatementParser, parse_amount, parse_date, register


@register
class PdfStatementParser(StatementParser):
    """Layout-aware PDF parser driven by declarative issuer templates.

    CONFIDENCE: high when a template matches and verification passes; medium
    when unverified (no printed balances); refused when verification fails
    unless the LLM fallback produces a verified extraction.
    """

    parser_id = "pdf_statement"
    version = "0.2.0"
    institution_id = "generic"
    file_format = FileFormat.PDF

    def sniff(self, ctx: ParseContext, sample: bytes) -> float:
        if not sample.startswith(b"%PDF"):
            return 0.0
        try:
            doc = extract_document(ctx.path)
        except PdfTextLayerMissing:
            return 0.0
        except Exception:
            return 0.0
        if not doc.pages or not doc.text.strip():
            return 0.0
        tpl, score = select_template(doc)
        if tpl is not None:
            return min(0.55 + 0.4 * score, 0.95)
        # No template: still a parseable text PDF. The generic line parser may
        # recover a simple statement; a template beats it whenever one matches.
        return 0.60

    def parse(self, ctx: ParseContext) -> ParseResult:
        try:
            doc = extract_document(ctx.path)
        except PdfTextLayerMissing:
            return ParseResult(
                txns=[], raw_rows=[], account_id=ctx.account_id,
                warnings=["PDF has no text layer — it is probably a scan. "
                          "Export a CSV from the issuer, or OCR the file first."])
        except Exception as e:
            return ParseResult(
                txns=[], raw_rows=[], account_id=ctx.account_id,
                warnings=[f"PDF extraction failed: {e}"])

        tpl, score = select_template(doc)

        if tpl is None:
            llm = _try_llm_extract(doc, ctx)
            if llm is not None and llm.txns:
                return llm
            legacy = _legacy_line_parse(doc, ctx)
            if legacy.txns:
                legacy.warnings.insert(
                    0,
                    f"no PDF template matched (best {score:.2f}); "
                    "used the generic line parser — prefer a template",
                )
                return legacy
            return ParseResult(
                txns=[], raw_rows=[{"extraction": doc.to_json()}],
                account_id=ctx.account_id,
                warnings=[
                    f"no PDF template matched (best score {score:.2f}). "
                    "Add a template under fin/pdf/templates/, or enable the LLM "
                    "fallback (`finto config set llm_enabled 1`)."
                ])

        result = apply_template(doc, tpl, currency_override=ctx.default_currency)
        report = verify_extraction(result)
        warnings = list(result.warnings)
        warnings.append(
            f"template={tpl.template_id} match={score:.2f} "
            f"verify={report.status} ({report.summary()})"
        )

        if report.status == "failed":
            llm = _try_llm_extract(doc, ctx, failed=result)
            if llm is not None and llm.txns:
                return llm
            warnings.append(
                "EXTRACTION CONTRADICTS THE STATEMENT — rows were probably "
                "missed or double-counted. Importing anyway would corrupt the "
                "ledger; refusing. Fix the template or enable LLM fallback."
            )
            return ParseResult(
                txns=[],
                raw_rows=[{
                    "extraction": doc.to_json(),
                    "template_id": tpl.template_id,
                    "verify": report.status,
                    "problems": report.problems,
                }],
                account_id=ctx.account_id,
                warnings=warnings,
                period_start=result.period_start,
                period_end=result.period_end,
                statement_date=result.statement_date,
            )

        return _to_parse_result(doc, tpl.template_id, result, warnings)


def _to_parse_result(
    doc, template_id: str, result: TemplateResult, warnings: list[str]
) -> ParseResult:
    txns: list[ParsedTxn] = []
    raw_rows: list[dict] = [
        {"extraction": doc.to_json(), "template_id": template_id},
    ]
    for i, row in enumerate(result.rows):
        txns.append(ParsedTxn(
            txn_date=row.txn_date,
            posted_date=row.settlement_date,
            booked=row.amount,
            description_raw=row.description,
            installment_hint=parse_installment_marker(row.description),
            details=extract_details(description=row.description),
            line_no=i,
            extra={
                "pdf_section": row.section,
                "pdf_page": row.page_no,
                "pdf_line": row.raw_text,
                "account_hint": row.account_hint,
                "running_balance": (
                    {"amount": row.running_balance.amount,
                     "currency": row.running_balance.currency}
                    if row.running_balance else None
                ),
            },
        ))
        raw_rows.append({
            "line_no": i,
            "section": row.section,
            "text": row.raw_text,
            "amount": row.amount.amount,
            "currency": row.amount.currency,
        })

    balances: list[tuple] = []
    for when, money, kind, _section in result.balances:
        as_of = when
        if as_of is None:
            as_of = (result.period_end if kind == "closing"
                     else result.period_start or result.statement_date)
        if as_of is not None:
            balances.append((as_of, money))

    return ParseResult(
        txns=txns,
        raw_rows=raw_rows,
        warnings=warnings,
        balances=balances,
        period_start=result.period_start,
        period_end=result.period_end,
        statement_date=result.statement_date,
    )


def _try_llm_extract(doc, ctx: ParseContext, failed: TemplateResult | None = None):
    """Optional LLM pass. Returns None when the layer is off or unavailable."""
    try:
        from ..pdf.llm_extract import extract_with_llm
    except ImportError:
        return None
    try:
        return extract_with_llm(doc, ctx, prior=failed)
    except Exception as e:
        return ParseResult(
            txns=[], raw_rows=[], account_id=ctx.account_id,
            warnings=[f"LLM PDF fallback unavailable: {e}"])


# ---------------------------------------------------------------------------
# Legacy line parser — only for unmatched simple statements / fixtures
# ---------------------------------------------------------------------------

_MONEY = r"\(?-?(?:\d{1,3}(?:,\d{3})*|\d+)\.\d{2}\)?(?:\s?(?:CR|DR))?"
_MONEY_RE = re.compile(_MONEY, re.I)
_DATE_PATTERNS = (
    r"\d{1,2}[/-]\d{1,2}[/-]\d{2,4}",
    r"\d{1,2}\s+[A-Za-z]{3,9}\s+\d{2,4}",
    r"\d{1,2}\s?[A-Za-z]{3}",
    r"\d{4}-\d{2}-\d{2}",
)
_LINE_RE = re.compile(
    r"^\s*(?P<date>" + "|".join(_DATE_PATTERNS) + r")\s+(?P<rest>.+)$")
_GAP = r"[^\d\r\n]{0,80}"
_STATEMENT_PERIOD = re.compile(
    r"(?:statement period|period|from)[^\d\r\n]{0,12}"
    r"(\d{1,2}[/\-\s][A-Za-z0-9]{2,9}[/\-\s]\d{2,4})[^\d\r\n]{1,10}"
    r"(\d{1,2}[/\-\s][A-Za-z0-9]{2,9}[/\-\s]\d{2,4})", re.I)
_CLOSING_BALANCE = re.compile(
    r"(?:closing|ending|statement|new)\s+balance" + _GAP + r"(" + _MONEY + r")", re.I)
_OPENING_BALANCE = re.compile(
    r"(?:opening|previous|beginning|prior)\s+balance" + _GAP + r"(" + _MONEY + r")", re.I)
_NOISE_LINE = re.compile(
    r"\b(total|subtotal|balance|minimum payment|credit limit|available|"
    r"interest rate|apr|page \d|statement date|due date|payment due)\b", re.I)


def _legacy_line_parse(doc, ctx: ParseContext) -> ParseResult:
    lines = [ln.text for ln in doc.all_lines()]
    if not lines:
        return ParseResult(txns=[], raw_rows=[], account_id=ctx.account_id,
                           warnings=["PDF has no text layer — it is probably a scan."])

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
            warnings.append(f"line {i}: could not parse {line[:70]!r}")
            continue
        txns.append(parsed)
        raw_rows.append({"line_no": i, "text": line})

    period_start, period_end = _period(lines)
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
    if len(amounts) >= 2:
        amount_token, balance_token = amounts[-2], amounts[-1]
    else:
        amount_token, balance_token = amounts[-1], None
    try:
        booked = parse_amount(amount_token, ccy)
    except ValueError:
        return None
    description = _MONEY_RE.sub(" ", rest).strip(" .-")
    description = re.sub(r"\s{2,}", " ", description) or "(no description)"
    return ParsedTxn(
        txn_date=when, booked=booked, description_raw=description,
        installment_hint=parse_installment_marker(description),
        details=extract_details(description=description),
        line_no=line_no,
        extra={"pdf_line": line, "balance_token": balance_token},
    )


def _parse_date_token(token: str, year: int | None) -> date:
    token = token.strip()
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
    start, _end = _period(lines)
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
