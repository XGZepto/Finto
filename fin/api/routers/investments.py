"""Investment positions — MPF holdings.

These are units, not cash. A contribution that left a bank account is an
ordinary transaction and reconciles like one; what lives here is the valuation
of what those contributions bought, which moves with the market and must never
be fed into the balance checks.
"""

from __future__ import annotations

import hashlib
import hmac
import tempfile
from pathlib import Path

from fastapi import APIRouter, Depends, File, Form, HTTPException, Request, UploadFile

from ...investment import (
    list_activities,
    list_snapshots,
    parse_hsbc_mpf_pdf_bundle,
    save_activities,
    save_snapshot,
    snapshot_detail,
    valuation_history,
)
from ...llm.provider import LLMUnavailable, NullProvider, build_provider
from ..deps import get_conn, write_conn

router = APIRouter(tags=["investments"])
MAX_BUNDLE_FILE_BYTES = 32 * 1024 * 1024


@router.get("/investments")
def list_investment_snapshots(conn=Depends(get_conn)) -> dict:
    return {"snapshots": list_snapshots(conn)}


@router.get("/investments/history")
def get_investment_history(
    scheme: str | None = None,
    account_id: str | None = None,
    conn=Depends(get_conn),
) -> dict:
    return valuation_history(conn, scheme=scheme, account_id=account_id)


@router.get("/investments/activities")
def get_investment_activities(
    account_id: str | None = None,
    limit: int = 200,
    conn=Depends(get_conn),
) -> dict:
    return {"activities": list_activities(
        conn, account_id=account_id, limit=min(max(limit, 1), 1000))}


@router.get("/agent/investments")
def agent_list_investment_snapshots(request: Request) -> dict:
    from .imports import _agent_owner

    user_id = _agent_owner(request, "ledger:read")
    with write_conn(user_id) as conn:
        return {"snapshots": list_snapshots(conn)}


@router.get("/agent/investments/activities")
def agent_get_investment_activities(
    request: Request,
    account_id: str | None = None,
    limit: int = 200,
) -> dict:
    from .imports import _agent_owner

    user_id = _agent_owner(request, "ledger:read")
    with write_conn(user_id) as conn:
        return {"activities": list_activities(
            conn,
            account_id=account_id,
            limit=min(max(limit, 1), 1000),
        )}


@router.get("/agent/investments/{snapshot_id}")
def agent_get_investment_snapshot(snapshot_id: str, request: Request) -> dict:
    from .imports import _agent_owner

    user_id = _agent_owner(request, "ledger:read")
    with write_conn(user_id) as conn:
        found = snapshot_detail(conn, snapshot_id)
        if found is None:
            raise HTTPException(404, "snapshot not found")
        return found


async def _read_bundle(files: list[UploadFile]) -> list[tuple[str, bytes, str]]:
    if not 1 <= len(files) <= 12:
        raise HTTPException(400, "upload between 1 and 12 MPF PDFs")
    bundle = []
    for upload in files:
        name = Path(upload.filename or "upload.pdf").name
        if Path(name).suffix.lower() != ".pdf":
            raise HTTPException(400, f"{name}: MPF bundle accepts PDF files only")
        data = await upload.read(MAX_BUNDLE_FILE_BYTES + 1)
        if len(data) > MAX_BUNDLE_FILE_BYTES:
            raise HTTPException(413, f"{name}: file too large")
        bundle.append((name, data, hashlib.sha256(data).hexdigest()))
    return bundle


def _bundle_sha256(bundle: list[tuple[str, bytes, str]]) -> str:
    hashes = "\n".join(sorted(item[2] for item in bundle))
    return hashlib.sha256(hashes.encode()).hexdigest()


def _parse_bundle(bundle: list[tuple[str, bytes, str]], analysis_mode: str = "auto"):
    if analysis_mode not in {"auto", "deterministic", "llm"}:
        raise ValueError("analysis_mode must be auto, deterministic, or llm")
    provider = None
    if analysis_mode != "deterministic":
        candidate = build_provider(purpose="analysis")
        provider = None if isinstance(candidate, NullProvider) else candidate
    if analysis_mode == "llm" and provider is None:
        raise ValueError("LLM MPF parsing is not configured")
    with tempfile.TemporaryDirectory(prefix="finto-mpf-") as directory:
        paths = []
        for index, (name, data, _digest) in enumerate(bundle):
            path = Path(directory) / f"{index:02d}-{name}"
            path.write_bytes(data)
            paths.append(path)
        try:
            return parse_hsbc_mpf_pdf_bundle(
                paths,
                llm_provider=provider,
                force_llm=analysis_mode == "llm",
            )
        except LLMUnavailable as error:
            raise ValueError(f"LLM MPF parsing unavailable: {error}") from error


def _preview_payload(bundle, snapshot, activities, documents) -> dict:
    return {
        "bundle_sha256": _bundle_sha256(bundle),
        "documents": documents,
        "snapshot": {
            "as_of_date": snapshot.as_of_date.isoformat(),
            "reported_date": snapshot.notes.split(";")[0].removeprefix("Reported "),
            "total": {
                "amount": snapshot.total_value.amount,
                "currency": snapshot.total_value.currency,
            },
            "subaccounts": [{
                "account_id": item.account_id,
                "member_no": item.member_no,
                "balance": {
                    "amount": item.balance.amount,
                    "currency": item.balance.currency,
                },
            } for item in snapshot.subaccounts],
            "holdings": len(snapshot.holdings),
        },
        "activities": {
            "count": len(activities),
            "by_account": {
                account_id: sum(1 for item in activities if item.account_id == account_id)
                for account_id in sorted({item.account_id for item in activities})
            },
        },
    }


@router.post("/investments/imports/preview")
async def preview_mpf_bundle(
    files: list[UploadFile] = File(...),
    analysis_mode: str = Form("auto"),
) -> dict:
    bundle = await _read_bundle(files)
    try:
        snapshot, activities, documents = _parse_bundle(bundle, analysis_mode)
    except ValueError as error:
        raise HTTPException(422, str(error)) from error
    return _preview_payload(bundle, snapshot, activities, documents)


def _save_bundle(
    conn, bundle, expected_bundle_sha256: str, analysis_mode: str = "auto",
) -> dict:
    actual = _bundle_sha256(bundle)
    if not hmac.compare_digest(expected_bundle_sha256.lower(), actual.lower()):
        raise HTTPException(409, "MPF bundle does not match the preview SHA-256")
    try:
        snapshot, activities, documents = _parse_bundle(bundle, analysis_mode)
    except ValueError as error:
        raise HTTPException(422, str(error)) from error
    existing = conn.execute(
        "SELECT id,total_value FROM investment_snapshot "
        "WHERE scheme=%s AND as_of_date=%s AND source=%s",
        (snapshot.scheme, snapshot.as_of_date.isoformat(), snapshot.source),
    ).fetchone()
    if existing and existing["total_value"] == snapshot.total_value.amount:
        snapshot_result = {"status": "skipped", "id": existing["id"]}
    else:
        snapshot_result = {
            "status": "imported",
            "id": save_snapshot(conn, snapshot, commit=False),
        }
    activity_result = save_activities(conn, activities, commit=False)
    return {
        "bundle_sha256": actual,
        "documents": documents,
        "snapshot": snapshot_result,
        "activities": activity_result,
    }


@router.post("/investments/imports/confirm")
async def confirm_mpf_bundle(
    request: Request,
    files: list[UploadFile] = File(...),
    expected_bundle_sha256: str = Form(...),
    analysis_mode: str = Form("auto"),
) -> dict:
    bundle = await _read_bundle(files)
    with write_conn(request.state.user_id) as conn:
        return _save_bundle(conn, bundle, expected_bundle_sha256, analysis_mode)


@router.post("/agent/investments/imports/preview")
async def agent_preview_mpf_bundle(
    request: Request,
    files: list[UploadFile] = File(...),
    analysis_mode: str = Form("auto"),
) -> dict:
    from .imports import _agent_owner
    _agent_owner(request, "imports:write")
    bundle = await _read_bundle(files)
    try:
        snapshot, activities, documents = _parse_bundle(bundle, analysis_mode)
    except ValueError as error:
        raise HTTPException(422, str(error)) from error
    return _preview_payload(bundle, snapshot, activities, documents)


@router.post("/agent/investments/imports/confirm")
async def agent_confirm_mpf_bundle(
    request: Request,
    files: list[UploadFile] = File(...),
    expected_bundle_sha256: str = Form(...),
    analysis_mode: str = Form("auto"),
) -> dict:
    from .imports import _agent_owner
    user_id = _agent_owner(request, "imports:write")
    bundle = await _read_bundle(files)
    with write_conn(user_id) as conn:
        return _save_bundle(conn, bundle, expected_bundle_sha256, analysis_mode)


@router.get("/investments/{snapshot_id}")
def get_investment_snapshot(snapshot_id: str, conn=Depends(get_conn)) -> dict:
    found = snapshot_detail(conn, snapshot_id)
    if found is None:
        raise HTTPException(404, "no such snapshot")
    return found
