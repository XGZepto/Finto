"""Read-only natural-language ledger analysis."""

from __future__ import annotations

from fastapi import APIRouter, Depends

from ...llm.agent import answer_question
from ...llm.provider import build_provider
from ..deps import get_conn
from ..schemas import QueryRequest

router = APIRouter(tags=["query"])


@router.post("/query")
def natural_language_query(req: QueryRequest, conn=Depends(get_conn)) -> dict:
    provider = build_provider(conn, purpose="analysis")
    if provider.name == "null":
        return {
            "ok": False,
            "error": "Ask is not configured.",
            "question": req.question,
        }

    return answer_question(
        conn, provider, req.question,
        reporting_currency=req.convert_to,
    )


@router.get("/query/context")
def query_context(conn=Depends(get_conn)) -> dict:
    """The vocabulary the translator is allowed to use — useful for debugging."""
    from ...llm.query import build_context
    return build_context(conn)
