"""Per-request PostgreSQL connections."""

from __future__ import annotations

from contextlib import contextmanager

from fastapi import HTTPException, Request

from .. import db as dbm


def get_conn(request: Request):
    """Yield one PostgreSQL connection for one API request."""
    existing = getattr(request.state, "db_conn", None)
    if existing is not None:
        yield existing
        return
    conn = dbm.connect()
    try:
        user_id = getattr(request.state, "user_id", None)
        if not user_id:
            raise HTTPException(401, "authentication required")
        dbm.apply_acl(conn, user_id)
        yield conn
    finally:
        conn.close()


@contextmanager
def write_conn(user_id: str):
    """Connection for background jobs, committing atomically on success."""
    conn = dbm.connect()
    try:
        dbm.apply_acl(conn, user_id)
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()
