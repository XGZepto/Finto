"""PDF statement extraction via the layout-template engine.

Tables are read by column geometry, not reading order — an amount under
Withdrawal and the same amount under Deposit only differ by where they sit on
the page, so the words' coordinates are the data. The path is:

    extract  -> words with coordinates (lossless, stored as raw_record)
    template -> declarative per-issuer column layout
    verify   -> reconcile against the statement's own printed balances
    llm      -> optional fallback when no template matches or verify fails

A failed verification is a hard refusal, never a silent import of wrong money.
An unmatched PDF is refused too: templates are the only deterministic path.
"""

from __future__ import annotations

from ..enrich import extract_details
from ..installments import parse_installment_marker
from ..models import FileFormat, ParsedTxn
from ..pdf.extract import PdfTextLayerMissing, extract_document
from ..pdf.registry import select_template
from ..pdf.template import TemplateResult, apply_template
from ..pdf.verify import verify_extraction
from .base import ParseContext, ParseResult, StatementParser, register


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
        # No template: claim it anyway so import refuses with a useful warning
        # (or the LLM fallback takes over when enabled), rather than the file
        # silently matching nothing.
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

        return _to_parse_result(doc, tpl.template_id, result, warnings,
                                allow_empty=report.status == "verified")


def _to_parse_result(
    doc, template_id: str, result: TemplateResult, warnings: list[str],
    allow_empty: bool = False,
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
    for when, money, kind, _section, hint in result.balances:
        as_of = when
        if as_of is None:
            as_of = (result.period_end if kind == "closing"
                     else result.period_start or result.statement_date)
        if as_of is not None:
            balances.append((as_of, money, hint))

    return ParseResult(
        txns=txns,
        raw_rows=raw_rows,
        warnings=warnings,
        balances=balances,
        period_start=result.period_start,
        period_end=result.period_end,
        statement_date=result.statement_date,
        allow_empty=allow_empty,
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
