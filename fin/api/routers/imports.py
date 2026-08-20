"""Statement upload: preview, then commit.

The README has always insisted you `sniff` a new export before importing it,
because the column mappings for several institutions are informed guesses. A
drag-and-drop UI should make that the default path rather than an optional
discipline — so upload stages the file and returns a preview, and nothing
reaches the ledger until the preview is confirmed.

That preview is where a wrong column mapping becomes visible. A dd/mm vs mm/dd
error is glaring when you see the first five parsed dates, and nearly invisible
six months later.
"""

from __future__ import annotations

import hashlib
import hmac
import os
import tempfile
from collections.abc import Iterator
from contextlib import contextmanager
from datetime import date, timedelta
from pathlib import Path

from fastapi import APIRouter, Depends, File, Form, HTTPException, Request, UploadFile

from ...ingest import ingest_file, reattribute_cards, reconcile
from ...parsers.base import (
    ParseContext,
    all_parsers,
    read_csv_rows,
    select_parser,
    supported_extensions,
)
from ...pdf.registry import available_templates
from ..deps import get_conn, write_conn

router = APIRouter(tags=["imports"])

MAX_UPLOAD_BYTES = 64 * 1024 * 1024


@router.get("/imports/capabilities")
def import_capabilities(conn=Depends(get_conn)) -> dict:
    """Formats registered by code and active database templates."""
    institutions = {
        row["id"]: row["display_name"]
        for row in conn.execute("SELECT id, display_name FROM institution")
    }
    formats = []
    for parser in all_parsers():
        if parser.file_format.value == "pdf":
            continue
        institution_ids = parser.institution_ids or (parser.institution_id,)
        formats.append({
            "id": parser.parser_id,
            "label": parser.display_name or parser.parser_id,
            "file_format": parser.file_format.value,
            "extensions": list(parser.extensions),
            "version": parser.version,
            "source": "bundled",
            "institution_ids": list(institution_ids),
            "institutions": [institutions.get(value, _humanize(value))
                             for value in institution_ids if value != "generic"],
            "generic": parser.institution_id == "generic",
            "verified": False,
        })
    for template in available_templates(conn):
        formats.append({
            "id": template.template_id,
            "label": template.label or _humanize(template.template_id),
            "file_format": "pdf",
            "extensions": [".pdf"],
            "version": template.version,
            "source": template.source,
            "institution_ids": [template.institution_id],
            "institutions": [institutions.get(
                template.institution_id, _humanize(template.institution_id))],
            "generic": False,
            "verified": bool(template.verify or any(section.balances
                                                      for section in template.sections)),
        })
    formats.sort(key=lambda item: (item["file_format"], item["label"].lower()))
    repository = os.environ.get(
        "FINTO_REPOSITORY_URL", "https://github.com/XGZepto/Finto"
    ).rstrip("/")
    return {
        "extensions": list(supported_extensions()),
        "formats": formats,
        "contribution": {
            "guide": f"{repository}/wiki/Statement-Formats",
            "request": f"{repository}/issues/new?labels=statement-format",
        },
    }


async def _uploaded_file(file: UploadFile) -> tuple[str, bytes, str]:
    name = Path(file.filename or "upload").name
    allowed_suffixes = set(supported_extensions())
    if Path(name).suffix.lower() not in allowed_suffixes:
        raise HTTPException(
            400, f"unsupported file type — expected one of "
                 f"{sorted(allowed_suffixes)}")
    data = await file.read(MAX_UPLOAD_BYTES + 1)
    if len(data) > MAX_UPLOAD_BYTES:
        raise HTTPException(413, "file too large")
    return name, data, hashlib.sha256(data).hexdigest()


@contextmanager
def _request_file(name: str, data: bytes) -> Iterator[Path]:
    """Expose upload bytes as a path only for the current request."""
    suffix = Path(name).suffix.lower()
    with tempfile.NamedTemporaryFile(prefix="finto-", suffix=suffix, delete=False) as out:
        out.write(data)
        path = Path(out.name)
    try:
        yield path
    finally:
        path.unlink(missing_ok=True)


def _preview(
    conn, path: Path, *, name: str, size: int, digest: str,
    institution_id: str | None, account_id: str | None, currency: str | None,
) -> dict:
    ctx = ParseContext(path=path, institution_id=institution_id,
                       account_id=account_id, default_currency=currency,
                       connection=conn)
    parser = select_parser(ctx)
    preview: dict = {
        "filename": name,
        "size_bytes": size,
        "sha256": digest,
        "parser": parser.parser_id if parser else None,
        "parser_version": parser.version if parser else None,
        "institution_id": institution_id,
        "account_id": account_id,
        "currency": currency,
    }
    if parser is None:
        preview["error"] = (
            "No parser recognised this file."
            + (" PDFs need a text layer — a scanned statement cannot be read."
               if Path(name).suffix.lower() == ".pdf" else "")
        )
        return preview
    try:
        header, rows = read_csv_rows(path)
        preview["header"] = header
        preview["first_row"] = rows[0] if rows else None
    except Exception:
        preview["header"] = None
    result = parser.parse(ctx)
    preview.update({
        "txn_count": len(result.txns),
        "balance_count": len(result.balances),
        "period_start": str(result.period_start) if result.period_start else None,
        "period_end": str(result.period_end) if result.period_end else None,
        "warnings": result.warnings[:20],
        "sample": [{
            "date": str(t.txn_date),
            "description": t.description_raw[:80],
            "amount": t.booked.amount,
            "currency": t.booked.currency,
            "installment": list(t.installment_hint) if t.installment_hint else None,
            "details": t.details,
        } for t in result.txns[:10]],
    })
    if not result.txns and not result.allow_empty:
        preview["error"] = (
            "Parsed zero transactions. The file is not being recorded, so you "
            "can re-import once this is resolved.")
    return preview


@router.post("/imports/preview")
@router.post("/imports/stage", include_in_schema=False)
async def preview_upload(
    file: UploadFile = File(...),
    institution_id: str | None = Form(None),
    account_id: str | None = Form(None),
    currency: str | None = Form(None),
    conn=Depends(get_conn),
) -> dict:
    """Parse an upload without retaining bytes or changing the ledger."""
    name, data, digest = await _uploaded_file(file)
    with _request_file(name, data) as path:
        return _preview(conn, path, name=name, size=len(data), digest=digest,
                        institution_id=institution_id, account_id=account_id,
                        currency=currency)


def _confirm(
    conn, path: Path, *, expected_sha256: str, actual_sha256: str,
    source_name: str, institution_id: str | None,
    account_id: str | None, currency: str | None,
) -> dict:
    if not expected_sha256 or not hmac.compare_digest(
            expected_sha256.lower(), actual_sha256.lower()):
        raise HTTPException(409, "upload does not match the preview SHA-256")
    result = ingest_file(conn, path, institution_id=institution_id,
                         account_id=account_id, default_currency=currency,
                         source_name=source_name)
    if result["status"] == "error":
        raise HTTPException(422, result["reason"])
    summary = None
    if result["status"] == "imported":
        start = result.get("period_start")
        end = result.get("period_end")
        if start and end:
            summary = reconcile(
                conn,
                from_date=date.fromisoformat(start) - timedelta(days=45),
                to_date=date.fromisoformat(end) + timedelta(days=45),
            )
        else:
            summary = reconcile(conn)
    return {"import": result, "reconcile": summary}


@router.post("/imports/confirm")
async def confirm_import(
    request: Request,
    file: UploadFile = File(...),
    expected_sha256: str = Form(...),
    institution_id: str | None = Form(None),
    account_id: str | None = Form(None),
    currency: str | None = Form(None),
) -> dict:
    """Re-upload verified bytes, import, and synchronously return the result."""
    name, data, digest = await _uploaded_file(file)
    with _request_file(name, data) as path, write_conn(request.state.user_id) as conn:
        return _confirm(conn, path, expected_sha256=expected_sha256,
                        actual_sha256=digest, institution_id=institution_id,
                        source_name=name, account_id=account_id, currency=currency)


def _agent_owner(request: Request, scope: str) -> str:
    # Imported lazily to avoid a module cycle while the app registers routers.
    from ..app import _api_key_owner
    return _api_key_owner(request, scope)[0]


@router.post("/agent/imports/preview")
async def agent_preview_upload(
    request: Request,
    file: UploadFile = File(...),
    institution_id: str | None = Form(None),
    account_id: str | None = Form(None),
    currency: str | None = Form(None),
) -> dict:
    user_id = _agent_owner(request, "imports:write")
    name, data, digest = await _uploaded_file(file)
    with _request_file(name, data) as path, write_conn(user_id) as conn:
        return _preview(conn, path, name=name, size=len(data), digest=digest,
                        institution_id=institution_id, account_id=account_id,
                        currency=currency)


@router.post("/agent/imports/confirm")
async def agent_confirm_import(
    request: Request,
    file: UploadFile = File(...),
    expected_sha256: str = Form(...),
    institution_id: str | None = Form(None),
    account_id: str | None = Form(None),
    currency: str | None = Form(None),
) -> dict:
    user_id = _agent_owner(request, "imports:write")
    name, data, digest = await _uploaded_file(file)
    with _request_file(name, data) as path, write_conn(user_id) as conn:
        return _confirm(conn, path, expected_sha256=expected_sha256,
                        actual_sha256=digest, institution_id=institution_id,
                        source_name=name, account_id=account_id, currency=currency)


@router.post("/reconcile")
def run_reconcile(request: Request) -> dict:
    with write_conn(request.state.user_id) as conn:
        return reconcile(conn)


def _reprocess(conn, statement_file_id: str) -> dict:
    statement = conn.execute(
        "SELECT id,source_path,period_start,period_end,row_count "
        "FROM statement_file WHERE id=%s", (statement_file_id,),
    ).fetchone()
    if not statement:
        raise HTTPException(404, "statement file not found")
    cards_updated = reattribute_cards(conn, statement_file_id=statement_file_id)
    summary = reconcile(conn)
    return {
        "statement": dict(statement),
        "cards_updated": cards_updated,
        "reconcile": summary,
    }


@router.post("/imports/{statement_file_id}/reprocess")
def reprocess_import(statement_file_id: str, request: Request) -> dict:
    """Re-derive automatic links and attribution for an imported statement."""
    with write_conn(request.state.user_id) as conn:
        return _reprocess(conn, statement_file_id)


@router.post("/agent/imports/{statement_file_id}/reprocess")
def agent_reprocess_import(statement_file_id: str, request: Request) -> dict:
    user_id = _agent_owner(request, "imports:write")
    with write_conn(user_id) as conn:
        return _reprocess(conn, statement_file_id)


@router.post("/reattribute")
def run_reattribute(request: Request) -> dict:
    """Re-run card resolution over existing rows, e.g. after adding a reissue."""
    from ...ingest import reattribute_cards
    with write_conn(request.state.user_id) as conn:
        return {"updated": reattribute_cards(conn)}


@router.post("/fx/harvest")
def run_fx_harvest(request: Request) -> dict:
    from ...fx import harvest_rates
    with write_conn(request.state.user_id) as conn:
        return {"rates": harvest_rates(conn)}


@router.get("/imports/history")
def import_history(limit: int = 50, conn=Depends(get_conn)) -> dict:
    rows = [dict(r) for r in conn.execute(
        "SELECT sf.id, sf.source_path, sf.institution_id, sf.account_id, "
        "       sf.file_format, sf.parser_id, sf.imported_at, sf.row_count, "
        "       sf.period_start, sf.period_end, "
        "       (SELECT COUNT(*) FROM txn WHERE statement_file_id = sf.id) AS txn_count "
        "FROM statement_file sf ORDER BY sf.imported_at DESC LIMIT %s", (limit,))]
    return {"files": rows}


def _humanize(value: str) -> str:
    words = value.replace("_", " ").replace("-", " ").split()
    acronyms = {"amex": "AMEX", "hk": "HK", "us": "US", "hsbc": "HSBC"}
    return " ".join(acronyms.get(word.lower(), word.title()) for word in words)
