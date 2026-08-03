"""Background job status."""

from __future__ import annotations

from fastapi import APIRouter, HTTPException

from ...jobs import runner

router = APIRouter(tags=["jobs"])


@router.get("/jobs")
def list_jobs(limit: int = 20) -> dict:
    return {"jobs": [j.as_dict() for j in runner.recent(limit)]}


@router.get("/jobs/{job_id}")
def get_job(job_id: str) -> dict:
    job = runner.get(job_id)
    if job is None:
        raise HTTPException(404, "no such job")
    return job.as_dict()
