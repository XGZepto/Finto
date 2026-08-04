"""Finto HTTP API.

A second entry point over the same `fin` package the CLI uses — no business
logic lives here, only transport.

Two rules the whole surface obeys:

**Money is an integer minor-unit amount plus a currency code**, never a decimal
number. `{"amount": -123456, "currency": "HKD"}`. The ledger is built on integer
minor units precisely to avoid float error; emitting `-1234.56` as JSON hands
that error straight to JavaScript, where `0.1 + 0.2 != 0.3` and every total ends
up wrong in the last cent. Formatting is the client's job.

**Cross-currency totals are never produced here.** Positions and summaries come
back per currency. A normalised view is available, but only through the explicit
`/api/fx/convert`-style parameters that attach a rate and a date to the result
and label it as converted.

Binds to localhost only. There is no authentication, and the project's premise
is that the data never leaves the machine.
"""

from __future__ import annotations

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .routers import (
    accounts,
    imports,
    installments,
    integrity,
    investments,
    jobs,
    query,
    review,
    summary,
    transactions,
)

app = FastAPI(
    title="Finto",
    description="Local personal finance ledger",
    version="0.2.0",
)

# The Angular dev server is a different origin. Production serves the built
# frontend from this same app, so no CORS is needed there.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:4200", "http://127.0.0.1:4200"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

for router in (transactions, summary, accounts, imports, review, integrity,
               installments, investments, jobs, query):
    app.include_router(router.router, prefix="/api")


@app.get("/api/health")
def health() -> dict:
    from .deps import db_path
    return {"status": "ok", "database": str(db_path())}


def main() -> None:
    """Run the API. Localhost only, by design."""
    import argparse

    import uvicorn

    p = argparse.ArgumentParser(prog="finto-api")
    p.add_argument("--host", default="127.0.0.1",
                   help="bind address (localhost by design — there is no auth)")
    p.add_argument("--port", type=int, default=8000)
    p.add_argument("--db", help="path to finto.db")
    p.add_argument("--reload", action="store_true")
    args = p.parse_args()

    if args.db:
        import os
        os.environ["FINTO_DB"] = args.db

    uvicorn.run("fin.api.app:app", host=args.host, port=args.port,
                reload=args.reload)


if __name__ == "__main__":
    main()
