"""Background job runner.

Imports and reconciliation are full-ledger operations that take seconds, not
milliseconds, and SQLite permits exactly one writer. A CLI made concurrent
invocation awkward by accident; a UI with a button makes it trivial. So every
write-side operation goes through one worker thread and a queue.

Deliberately in-process and unpersisted: this is a local single-user
application, and a job that dies with the server should be re-run, not resumed
from a half-applied state. Ingest is idempotent by file hash and reconcile is
idempotent by construction, so re-running is always safe.
"""

from __future__ import annotations

import queue
import threading
import traceback
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Callable


@dataclass
class Job:
    id: str
    kind: str
    status: str = "queued"           # queued | running | done | error
    progress: str = ""
    result: Any = None
    error: str | None = None
    created_at: str = field(
        default_factory=lambda: datetime.now(timezone.utc).isoformat())
    finished_at: str | None = None

    def as_dict(self) -> dict:
        return {
            "id": self.id, "kind": self.kind, "status": self.status,
            "progress": self.progress, "result": self.result, "error": self.error,
            "created_at": self.created_at, "finished_at": self.finished_at,
        }


class JobRunner:
    """Serialises write operations onto a single worker thread."""

    def __init__(self) -> None:
        self._jobs: dict[str, Job] = {}
        self._queue: queue.Queue = queue.Queue()
        self._lock = threading.Lock()
        self._worker: threading.Thread | None = None

    def _ensure_worker(self) -> None:
        if self._worker is None or not self._worker.is_alive():
            self._worker = threading.Thread(target=self._run, daemon=True)
            self._worker.start()

    def submit(self, kind: str, fn: Callable[[Job], Any]) -> Job:
        job = Job(id=str(uuid.uuid4()), kind=kind)
        with self._lock:
            self._jobs[job.id] = job
        self._queue.put((job, fn))
        self._ensure_worker()
        return job

    def get(self, job_id: str) -> Job | None:
        with self._lock:
            return self._jobs.get(job_id)

    def recent(self, limit: int = 20) -> list[Job]:
        with self._lock:
            jobs = sorted(self._jobs.values(), key=lambda j: j.created_at,
                          reverse=True)
        return jobs[:limit]

    def wait(self, job_id: str, timeout: float = 30.0) -> Job | None:
        """Block until a job finishes. Used by tests and synchronous callers."""
        import time
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            job = self.get(job_id)
            if job and job.status in ("done", "error"):
                return job
            time.sleep(0.01)
        return self.get(job_id)

    def _run(self) -> None:
        while True:
            job, fn = self._queue.get()
            job.status = "running"
            try:
                job.result = fn(job)
                job.status = "done"
            except Exception as e:                      # noqa: BLE001
                job.status = "error"
                # Keep the traceback: a failed import needs to be diagnosable
                # without reproducing it.
                job.error = f"{type(e).__name__}: {e}\n{traceback.format_exc()}"
            finally:
                job.finished_at = datetime.now(timezone.utc).isoformat()
                self._queue.task_done()


runner = JobRunner()
