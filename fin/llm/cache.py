"""Persistent cache and audit trail for LLM decisions.

Every call is keyed by a hash of its canonical input plus the prompt version.
This is not just a cost optimisation — it is what makes the ledger reproducible.
A ledger whose numbers shift because a model was silently updated underneath it
is not a ledger. Cached decisions freeze the answer until you explicitly
invalidate them by bumping the prompt version.
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
        "WHERE task=? AND input_hash=? AND prompt_version=?",
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
        "INSERT OR REPLACE INTO llm_decision (id, task, input_hash, input_summary, "
        "output, confidence, model, prompt_version, applied, created_at) "
        "VALUES (?,?,?,?,?,?,?,?,0,?)",
        (did, task, ihash, summary[:500], json.dumps(output, default=str),
         confidence, model, prompt_version, datetime.now().isoformat()),
    )
    return did


def cached_decision(
    conn, *, task: str, input_summary: str, prompt_version: str, model: str,
    compute, payload: Any = None,
) -> tuple[Any, bool]:
    """Return a cached decision, or compute and store one.

    Returns (output, from_cache). The cache is keyed on the *input*, so an
    identical question always yields an identical answer regardless of which
    model version is configured today — which is what stops a ledger's numbers
    drifting under it.
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
        "INSERT OR REPLACE INTO txn_annotation (txn_id, field, value, source, "
        "confidence, decision_id, created_at) VALUES (?,?,?,?,?,?,?)",
        (txn_id, field, value, source, confidence, decision_id,
         datetime.now().isoformat()),
    )


def invalidate(conn, *, task: str | None = None, prompt_version: str | None = None) -> int:
    """Drop cached decisions so they are recomputed.

    Use after changing a prompt or switching models. Deterministic decisions are
    untouched — only the LLM layer is affected, which is the point of keeping
    them in a separate table.
    """
    sql, params = "DELETE FROM llm_decision WHERE 1=1", []
    if task:
        sql += " AND task=?"
        params.append(task)
    if prompt_version:
        sql += " AND prompt_version=?"
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
