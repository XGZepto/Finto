"""Natural language query.

The model translates a question into a filter; the database answers it. The
filter travels back with the result so the client can render it as editable
chips — the user sees exactly what was searched, and can correct it.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends

from ... import fx as fxm
from ... import reporting
from ...llm.provider import build_provider
from ...llm.query import translate
from ..deps import get_conn
from ..schemas import QueryRequest

router = APIRouter(tags=["query"])


@router.post("/query")
def natural_language_query(req: QueryRequest, conn=Depends(get_conn)) -> dict:
    provider = build_provider(conn)
    if provider.name == "null":
        return {
            "ok": False,
            "error": "The LLM layer is off. Enable it with "
                     "`config set llm_enabled 1` and set ANTHROPIC_API_KEY.",
            "question": req.question,
        }

    plan = translate(conn, provider, req.question)
    if not plan.get("ok"):
        return {**plan, "question": req.question}

    filters = dict(plan["filter"])
    group_by = plan.get("group_by")

    result: dict = {
        "ok": True,
        "question": req.question,
        # Echoed so the UI can show it as chips. A misreading has to be visible.
        "filter": filters,
        "group_by": group_by,
        "intent": plan["intent"],
        "confidence": plan["confidence"],
        "explanation": plan["explanation"],
        "unsupported": plan.get("unsupported"),
        "dropped_fields": plan.get("dropped_fields", []),
        "cached": plan.get("cached", False),
    }

    # The model never produces figures. Everything below is computed by SQL.
    result["totals"] = reporting.totals(conn, filters=filters)
    if group_by:
        result["rows"] = reporting.summary(conn, group_by=group_by, filters=filters)
    result["transactions"] = reporting.transactions(
        conn, filters=filters, limit=50)

    if req.convert_to:
        result["totals"] = fxm.convert_rows(
            conn, result["totals"], fields=("net",), to_currency=req.convert_to)
        if "rows" in result:
            result["rows"] = fxm.convert_rows(
                conn, result["rows"], fields=("net",), to_currency=req.convert_to)
    return result


@router.get("/query/context")
def query_context(conn=Depends(get_conn)) -> dict:
    """The vocabulary the translator is allowed to use — useful for debugging."""
    from ...llm.query import build_context
    return build_context(conn)
