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

import re
from datetime import timedelta

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
    display_name = "PDF statement template"
    institution_ids = ("generic",)
    extensions = (".pdf",)

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
        tpl, score = select_template(doc, ctx.connection)
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

        tpl, score = select_template(doc, ctx.connection)

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
    document_plans = _document_installment_facts(doc)
    raw_rows: list[dict] = [
        {"extraction": doc.to_json(), "template_id": template_id},
    ]
    for i, row in enumerate(result.rows):
        # Facts the template named win over anything inferred from free text.
        details = extract_details(
            extended="\n".join(row.detail_lines), description=row.description)
        details.update(row.details)
        for subject, facts in document_plans.items():
            if subject in row.description.upper():
                for key, value in facts.items():
                    details.setdefault(key, value)
        marker_text = "\n".join([row.description, *row.detail_lines])
        txns.append(ParsedTxn(
            txn_date=row.txn_date,
            posted_date=row.settlement_date,
            booked=row.amount,
            native=row.foreign,
            fx_rate=row.fx_rate,
            external_ref=details.pop("issuer.reference", None),
            card_last4=details.pop("card.last4", None),
            cardholder_hint=details.pop("card.holder", None),
            description_raw=row.description,
            installment_hint=parse_installment_marker(marker_text),
            details=details,
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
        as_of = when or result.period_end or result.statement_date
        if kind == "opening" and result.period_start is not None:
            # An opening balance is the position *before* the period's first
            # day, which is the close of the day before. Card issuers who print
            # no period at all keep the statement's own date: an opening is
            # matched to its closing through the statement they were printed
            # on, so the date labels it rather than locating it.
            as_of = result.period_start - timedelta(days=1)
        if as_of is not None:
            balances.append((as_of, money, hint, kind))

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


def _document_installment_facts(doc) -> dict[str, dict[str, str]]:
    """Plan metadata printed in statement summaries outside transaction tables."""
    lines = [" ".join(line.split()) for line in doc.layout.splitlines()]
    found: dict[str, dict[str, str]] = {}
    for i, line in enumerate(lines):
        m = re.match(
            r"(.+?) FOR \$?([\d,]+\.\d{2}).*?(\d{1,2}) of (\d{1,2})$",
            line, re.IGNORECASE,
        )
        if not m:
            continue
        subject, principal, seq, term = m.groups()
        facts = {
            "installment.principal": principal.replace(",", ""),
            "installment.sequence": seq,
            "installment.term": term,
        }
        for nearby in lines[i + 1:i + 4]:
            apr = re.search(r"APR\s+([\d.]+)%", nearby, re.IGNORECASE)
            if apr:
                facts["installment.apr"] = apr.group(1)
        found[subject.upper()] = facts
    return found


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
