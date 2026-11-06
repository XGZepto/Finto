"""Ledger corrections proposed by Ask, applied or undone by the user."""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

from fastapi import APIRouter, Depends, HTTPException

from ... import ledger_changes as changes
from ..deps import get_conn

router = APIRouter(tags=["changes"])


def _act(conn, action: Callable[[Any, str], dict], change_id: str) -> dict:
    try:
        result = action(conn, change_id)
    except LookupError as error:
        conn.rollback()
        raise HTTPException(404, str(error)) from error
    except changes.ChangeError as error:
        conn.rollback()
        raise HTTPException(409, str(error)) from error
    except Exception:
        conn.rollback()
        raise
    conn.commit()
    return result


@router.get("/changes/{change_id}")
def get_change(change_id: str, conn=Depends(get_conn)) -> dict:
    return _act(conn, changes.get, change_id)


@router.post("/changes/{change_id}/apply")
def apply_change(change_id: str, conn=Depends(get_conn)) -> dict:
    """Apply a proposed correction as the signed-in user's own edit."""
    return _act(conn, changes.apply, change_id)


@router.post("/changes/{change_id}/undo")
def undo_change(change_id: str, conn=Depends(get_conn)) -> dict:
    return _act(conn, changes.undo, change_id)


@router.post("/changes/{change_id}/dismiss")
def dismiss_change(change_id: str, conn=Depends(get_conn)) -> dict:
    return _act(conn, changes.dismiss, change_id)
