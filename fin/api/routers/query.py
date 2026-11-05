"""Read-only natural-language ledger analysis."""

from __future__ import annotations

import json
import logging
import queue
import threading

from fastapi import APIRouter, Depends, Request
from fastapi.responses import StreamingResponse

from ...llm.agent import answer_question
from ...llm.provider import build_provider
from ..deps import get_conn, write_conn
from ..schemas import QueryRequest

router = APIRouter(tags=["query"])
log = logging.getLogger(__name__)


@router.post("/query")
def natural_language_query(req: QueryRequest, conn=Depends(get_conn)) -> dict:
    provider = build_provider(conn, purpose="ask")
    if provider.name == "null":
        return {
            "ok": False,
            "error": "Ask is not configured.",
            "question": req.question,
        }

    return answer_question(
        conn, provider, req.question,
        reporting_currency=req.convert_to,
        history=[turn.model_dump() for turn in req.history],
    )


@router.post("/query/stream")
def stream_query(req: QueryRequest, request: Request) -> StreamingResponse:
    """The same answer as /query, sent as server-sent events while it forms.

    Events: ``tool`` (a step starting or finishing, with a readable label),
    ``data`` (figures as each step returns them), ``text`` (answer fragments),
    ``discard`` (drop text streamed before a tool call) and ``done`` (the
    complete result, identical to /query's). The work runs on its own
    connection: a dependency's connection is closed before a streamed body
    starts.
    """
    user_id = request.state.user_id
    events: queue.Queue = queue.Queue()

    def emit(kind: str, payload: dict) -> None:
        events.put((kind, payload))

    def work() -> None:
        try:
            with write_conn(user_id) as conn:
                provider = build_provider(conn, purpose="ask")
                if provider.name == "null":
                    result = {"ok": False, "error": "Ask is not configured.",
                              "question": req.question}
                else:
                    result = answer_question(
                        conn, provider, req.question,
                        reporting_currency=req.convert_to,
                        history=[turn.model_dump() for turn in req.history],
                        emit=emit,
                    )
            events.put(("done", result))
        except Exception:  # the stream must always end with an answer or a reason
            log.exception("ask stream failed")
            events.put(("done", {"ok": False, "error": "Ask could not finish.",
                                 "question": req.question}))
        finally:
            events.put(None)

    threading.Thread(target=work, daemon=True).start()

    def body():
        # A comment first, so the client sees the stream open immediately.
        yield ": open\n\n"
        while True:
            item = events.get()
            if item is None:
                return
            kind, payload = item
            yield f"event: {kind}\ndata: {json.dumps(payload, default=str)}\n\n"

    return StreamingResponse(body(), media_type="text/event-stream", headers={
        "Cache-Control": "no-store", "X-Accel-Buffering": "no",
    })


@router.get("/query/context")
def query_context(conn=Depends(get_conn)) -> dict:
    """The vocabulary the translator is allowed to use — useful for debugging."""
    from ...llm.query import build_context
    return build_context(conn)
