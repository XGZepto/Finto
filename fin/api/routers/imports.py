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

import tempfile
import uuid
from pathlib import Path

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile

from ...ingest import ingest_file, reconcile
from ...jobs import runner
from ...parsers.base import ParseContext, read_csv_rows, select_parser
from ..deps import get_conn, write_conn

router = APIRouter(tags=["imports"])

# Staged uploads live here until confirmed or discarded.
STAGING = Path(tempfile.gettempdir()) / "finto-staging"
STAGING.mkdir(exist_ok=True)

ALLOWED_SUFFIXES = {".csv", ".tsv", ".txt", ".ofx", ".qfx", ".pdf", ".xlsx"}
MAX_UPLOAD_BYTES = 64 * 1024 * 1024


@router.post("/imports/stage")
async def stage_upload(
    file: UploadFile = File(...),
    institution_id: str | None = Form(None),
    account_id: str | None = Form(None),
    currency: str | None = Form(None),
) -> dict:
    """Accept a file, parse it in memory, and return what *would* be imported."""
    name = Path(file.filename or "upload").name
    if Path(name).suffix.lower() not in ALLOWED_SUFFIXES:
        raise HTTPException(
            400, f"unsupported file type — expected one of "
                 f"{sorted(ALLOWED_SUFFIXES)}")

    staged_id = str(uuid.uuid4())
    target = STAGING / f"{staged_id}__{name}"
    size = 0
    with target.open("wb") as out:
        while chunk := await file.read(1 << 20):
            size += len(chunk)
            if size > MAX_UPLOAD_BYTES:
                out.close()
                target.unlink(missing_ok=True)
                raise HTTPException(413, "file too large")
            out.write(chunk)

    ctx = ParseContext(path=target, institution_id=institution_id,
                       account_id=account_id, default_currency=currency)
    parser = select_parser(ctx)

    preview: dict = {
        "staged_id": staged_id,
        "filename": name,
        "size_bytes": size,
        "parser": parser.parser_id if parser else None,
        "parser_version": parser.version if parser else None,
        "institution_id": institution_id,
        "account_id": account_id,
        "currency": currency,
    }

    if parser is None:
        suffix = Path(name).suffix.lower()
        preview["error"] = (
            "No parser recognised this file."
            + (" PDFs need a text layer — a scanned statement cannot be read."
               if suffix == ".pdf" else "")
            + (" Spreadsheet import is not supported; export CSV instead."
               if suffix == ".xlsx" else ""))
        return preview

    try:
        header, rows = read_csv_rows(target)
        preview["header"] = header
        preview["first_row"] = rows[0] if rows else None
    except Exception:
        preview["header"] = None       # not a CSV — a PDF, for instance

    result = parser.parse(ctx)
    preview.update({
        "txn_count": len(result.txns),
        "balance_count": len(result.balances),
        "period_start": str(result.period_start) if result.period_start else None,
        "period_end": str(result.period_end) if result.period_end else None,
        "warnings": result.warnings[:20],
        # The point of the preview: see the parsed dates, signs and amounts
        # before anything is written.
        "sample": [{
            "date": str(t.txn_date),
            "description": t.description_raw[:80],
            "amount": t.booked.amount,
            "currency": t.booked.currency,
            "installment": list(t.installment_hint) if t.installment_hint else None,
            "details": t.details,
        } for t in result.txns[:10]],
    })
    if not result.txns:
        preview["error"] = (
            "Parsed zero transactions. The file is not being recorded, so you "
            "can re-import once this is resolved.")
    return preview


@router.post("/imports/{staged_id}/confirm")
def confirm_import(staged_id: str, institution_id: str | None = Form(None),
                   account_id: str | None = Form(None),
                   currency: str | None = Form(None)) -> dict:
    """Commit a staged file, then reconcile the whole ledger."""
    matches = list(STAGING.glob(f"{staged_id}__*"))
    if not matches:
        raise HTTPException(404, "staged file not found — re-upload it")
    path = matches[0]

    def work(job):
        with write_conn() as conn:
            job.progress = "importing"
            result = ingest_file(conn, path, institution_id=institution_id,
                                 account_id=account_id, default_currency=currency)
            # Reconcile always runs over the full ledger: a duplicate or a
            # transfer counterpart usually lives in a different file from a
            # different institution.
            job.progress = "reconciling"
            summary = reconcile(conn)
        path.unlink(missing_ok=True)
        return {"import": result, "reconcile": summary}

    return runner.submit("import", work).as_dict()


@router.delete("/imports/{staged_id}")
def discard_staged(staged_id: str) -> dict:
    for p in STAGING.glob(f"{staged_id}__*"):
        p.unlink(missing_ok=True)
    return {"discarded": staged_id}


@router.post("/reconcile")
def run_reconcile() -> dict:
    def work(job):
        with write_conn() as conn:
            job.progress = "reconciling"
            return reconcile(conn)

    return runner.submit("reconcile", work).as_dict()


@router.post("/reattribute")
def run_reattribute() -> dict:
    """Re-run card resolution over existing rows, e.g. after adding a reissue."""
    def work(job):
        from ...ingest import reattribute_cards
        with write_conn() as conn:
            return {"updated": reattribute_cards(conn)}

    return runner.submit("reattribute", work).as_dict()


@router.post("/fx/harvest")
def run_fx_harvest() -> dict:
    def work(job):
        from ...fx import harvest_rates
        with write_conn() as conn:
            return {"rates": harvest_rates(conn)}

    return runner.submit("fx-harvest", work).as_dict()


@router.get("/imports/history")
def import_history(limit: int = 50, conn=Depends(get_conn)) -> dict:
    rows = [dict(r) for r in conn.execute(
        "SELECT sf.id, sf.source_path, sf.institution_id, sf.account_id, "
        "       sf.file_format, sf.parser_id, sf.imported_at, sf.row_count, "
        "       sf.period_start, sf.period_end, "
        "       (SELECT COUNT(*) FROM txn WHERE statement_file_id = sf.id) AS txn_count "
        "FROM statement_file sf ORDER BY sf.imported_at DESC LIMIT ?", (limit,))]
    return {"files": rows}
