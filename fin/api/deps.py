"""Shared API dependencies: database location and per-request connections."""

from __future__ import annotations

import os
import sqlite3
from contextlib import contextmanager
from pathlib import Path

from .. import db as dbm

DB_ENV = "FINTO_DB"
DEFAULT_DB = "finto.db"


def db_path() -> Path:
    return Path(os.environ.get(DB_ENV, DEFAULT_DB))


def get_conn() -> sqlite3.Connection:
    """FastAPI dependency yielding a connection scoped to one request.

    One connection per request, never a shared one.

    `check_same_thread=False` is required rather than merely convenient. FastAPI
    runs a sync dependency and the sync endpoint that consumes it as two separate
    threadpool submissions, so the connection is opened on one worker thread and
    used on another. Under light load AnyIO hands back the same idle worker and
    it happens to work — which is exactly why a serialised test suite never sees
    this, and why the first page that fires several requests at once gets a wall
    of "SQLite objects created in a thread can only be used in that same thread".

    Turning the check off is safe *here* because those steps are strictly
    sequential: the endpoint cannot start before the dependency yields, and the
    close cannot run before the endpoint returns. No two threads ever touch this
    connection at the same time. WAL mode handles the concurrency that remains,
    between separate connections.
    """
    conn = dbm.connect(db_path(), check_same_thread=False)
    try:
        yield conn
    finally:
        conn.close()


@contextmanager
def write_conn():
    """Connection for the job worker, which is the only writer."""
    conn = dbm.connect(db_path(), check_same_thread=False)
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()
