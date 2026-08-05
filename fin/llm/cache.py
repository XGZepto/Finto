"""Persistent cache and audit trail for model decisions.

Cache keys include the canonical input and prompt version. Changing either
creates a new decision record; existing results remain reproducible until
explicit invalidation.
"""

from __future__ import annotations

import hashlib
import json
import uuid
from datetime import datetime
from typing import Any


def input_hash(task: str, payload: Any) -> str:
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"), default=str)
    return hashlib.sha256(f"{task}\x00{canonical}".encode()).hexdigest()


def lookup(conn, task: str, ihash: str, prompt_version: str) -> dict | None:
    row = conn.execute(
        "SELECT output, confidence FROM llm_decision "
        "WHERE task=%s AND input_hash=%s AND prompt_version=%s",
        (task, ihash, prompt_version),
    ).fetchone()
    if not row:
        return None
    return {"output": json.loads(row["output"]), "confidence": row["confidence"]}


def record(
    conn, *, task: str, ihash: str, summary: str, output: Any,
    confidence: float | None, model: str, prompt_version: str,
) -> str:
    did = str(uuid.uuid4())
    conn.execute(
        "INSERT INTO llm_decision (id, task, input_hash, input_summary, "
        "output, confidence, model, prompt_version, applied, created_at) "
        "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,0,%s) "
        "ON CONFLICT (task, input_hash, prompt_version) DO UPDATE SET "
        "input_summary=EXCLUDED.input_summary, output=EXCLUDED.output, "
        "confidence=EXCLUDED.confidence, model=EXCLUDED.model, "
        "created_at=EXCLUDED.created_at",
        (did, task, ihash, summary[:500], json.dumps(output, default=str),
         confidence, model, prompt_version, datetime.now().isoformat()),
    )
    return did


def cached_decision(
    conn, *, task: str, input_summary: str, prompt_version: str, model: str,
    compute, payload: Any = None,
) -> tuple[Any, bool]:
    """Return a cached decision, or compute and store one.

    Returns ``(output, from_cache)``. Identical inputs and prompt versions reuse
    the stored output.
    """
    ihash = input_hash(task, payload if payload is not None else input_summary)
    hit = lookup(conn, task, ihash, prompt_version)
    if hit is not None:
        return hit["output"], True

    output = compute()
    confidence = output.get("confidence") if isinstance(output, dict) else None
    record(conn, task=task, ihash=ihash, summary=input_summary, output=output,
           confidence=confidence, model=model, prompt_version=prompt_version)
    conn.commit()
    return output, False


def annotate(
    conn, *, txn_id: str, field: str, value: str | None, source: str,
    confidence: float | None = None, decision_id: str | None = None,
) -> None:
    """Record which layer set a field, so LLM output stays distinguishable."""
    conn.execute(
        "INSERT INTO txn_annotation (txn_id, field, value, source, "
        "confidence, decision_id, created_at) VALUES (%s,%s,%s,%s,%s,%s,%s) "
        "ON CONFLICT (txn_id, field) DO UPDATE SET value=EXCLUDED.value, "
        "source=EXCLUDED.source, confidence=EXCLUDED.confidence, "
        "decision_id=EXCLUDED.decision_id, created_at=EXCLUDED.created_at",
        (txn_id, field, value, source, confidence, decision_id,
         datetime.now().isoformat()),
    )


def invalidate(conn, *, task: str | None = None, prompt_version: str | None = None) -> int:
    """Drop cached decisions so they are recomputed.

    Use after changing a prompt or switching models. This does not modify
    deterministic rules or manual annotations.
    """
    sql, params = "DELETE FROM llm_decision WHERE 1=1", []
    if task:
        sql += " AND task=%s"
        params.append(task)
    if prompt_version:
        sql += " AND prompt_version=%s"
        params.append(prompt_version)
    cur = conn.execute(sql, params)
    return cur.rowcount


def stats(conn) -> dict:
    out = {}
    for r in conn.execute(
            "SELECT task, COUNT(*) n, AVG(confidence) avg_conf FROM llm_decision "
            "GROUP BY task"):
        out[r["task"]] = {"cached": r["n"], "avg_confidence": r["avg_conf"]}
    return out
