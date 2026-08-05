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

Local development binds to localhost. Production is protected by a signed
session at the routing layer and stores the ledger in managed PostgreSQL.
"""

from __future__ import annotations

import hashlib
import json
import os
import secrets
import uuid
from datetime import datetime, timezone

from fastapi import Depends, FastAPI, HTTPException, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from .deps import get_conn as api_get_conn
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
    description="Personal finance ledger",
    version="0.2.1",
)


def _ensure_live_schema() -> None:
    """Upgrade the live ledger once before serving a new release."""
    from .. import db as dbm

    conn = dbm.connect()
    try:
        ready = conn.execute(
            "SELECT to_regclass('app_user') IS NOT NULL AS users, "
            "to_regclass('account_acl') IS NOT NULL AS acl, "
            "to_regclass('category_definition') IS NOT NULL AS categories, "
            "to_regclass('tag_definition') IS NOT NULL AS tags, "
            "to_regclass('merchant_definition') IS NOT NULL AS merchants, "
            "to_regclass('agent_operation') IS NOT NULL AS agent_operations, "
            "to_regclass('user_api_key') IS NOT NULL AS api_keys, "
            "EXISTS (SELECT 1 FROM information_schema.columns "
            "WHERE table_schema=current_schema() AND table_name='account' "
            "AND column_name='user_id') AS ownership, "
            "EXISTS (SELECT 1 FROM information_schema.columns "
            "WHERE table_schema=current_schema() AND table_name='statement_file' "
            "AND column_name='user_id') AS statement_ownership"
        ).fetchone()
        if not all(ready.values()):
            dbm.init_db(conn)
    finally:
        conn.close()


_ensure_live_schema()


class LoginRequest(BaseModel):
    identifier: str
    password: str


class PreferencesRequest(BaseModel):
    theme: str | None = None
    language: str | None = None
    base_currency: str | None = None


class ApiKeyRequest(BaseModel):
    name: str = "Agent access"


def _api_key_owner(request: Request, required_scope: str) -> tuple[str, str]:
    from .. import db as dbm
    header = request.headers.get("authorization", "")
    if not header.startswith("Bearer "):
        raise HTTPException(401, "API key required")
    token = header.removeprefix("Bearer ").strip()
    if not token.startswith("finto_"):
        raise HTTPException(401, "invalid API key")
    digest = hashlib.sha256(token.encode()).hexdigest()
    conn = dbm.connect()
    try:
        row = conn.execute(
            "SELECT id,user_id,scopes FROM user_api_key "
            "WHERE key_hash=%s AND revoked_at IS NULL", (digest,),
        ).fetchone()
        if not row:
            raise HTTPException(401, "invalid API key")
        scopes = row["scopes"] if isinstance(row["scopes"], list) else json.loads(row["scopes"])
        if required_scope not in scopes:
            raise HTTPException(403, "API key lacks taxonomy access")
        conn.execute("UPDATE user_api_key SET last_used_at=%s WHERE id=%s",
                     (datetime.now(timezone.utc).isoformat(), row["id"]))
        conn.commit()
        return row["user_id"], row["id"]
    finally:
        conn.close()


def _taxonomy_operation(request: Request, *, apply: bool) -> dict:
    from .. import db as dbm
    from ..taxonomy import audit_backfill

    user_id, key_id = _api_key_owner(
        request, "taxonomy:write" if apply else "taxonomy:read")
    if apply and request.headers.get("x-finto-confirm") != "apply-taxonomy":
        raise HTTPException(409, "explicit apply confirmation required")
    conn = dbm.connect()
    try:
        result = audit_backfill(conn, apply=apply, user_id=user_id)
        conn.execute(
            "INSERT INTO agent_operation (id,subject,action,user_id,applied,result,created_at) "
            "VALUES (%s,%s,%s,%s,%s,%s::jsonb,%s)",
            (str(uuid.uuid4()), f"api-key:{key_id}", "taxonomy_backfill", user_id, int(apply),
             json.dumps(result), datetime.now(timezone.utc).isoformat()),
        )
        conn.commit()
        return {"ok": True, "applied": apply, "result": result}
    finally:
        conn.close()


@app.get("/api/agent/taxonomy/audit")
def agent_taxonomy_audit(request: Request) -> dict:
    return _taxonomy_operation(request, apply=False)


@app.post("/api/agent/taxonomy/apply")
def agent_taxonomy_apply(request: Request) -> dict:
    return _taxonomy_operation(request, apply=True)


def _session_secret() -> str:
    secret = os.environ.get("FINTO_SESSION_SECRET", "")
    if not secret:
        raise HTTPException(503, "authentication is not configured")
    return secret


def _current_user(request: Request, conn) -> dict:
    from ..auth import parse_session, user_by_id

    token = request.cookies.get("finto_session")
    parsed = parse_session(token, _session_secret())
    if not parsed:
        raise HTTPException(401, "authentication required")
    session = conn.execute(
        "SELECT id FROM auth_session WHERE id=%s AND user_id=%s AND revoked_at IS NULL "
        "AND token_hash=%s",
        (parsed["session_id"], parsed["user_id"],
         hashlib.sha256(token.encode()).hexdigest()),
    ).fetchone()
    user = user_by_id(conn, parsed["user_id"])
    if not session or not user:
        raise HTTPException(401, "authentication required")
    return user


def _public_api_key(row) -> dict:
    return {
        "id": row["id"], "name": row["name"], "prefix": row["key_prefix"],
        "scopes": row["scopes"] if isinstance(row["scopes"], list) else json.loads(row["scopes"]),
        "created_at": row["created_at"], "last_used_at": row["last_used_at"],
    }


@app.get("/api/auth/api-keys")
def list_api_keys(request: Request) -> dict:
    from .. import db as dbm
    conn = dbm.connect()
    try:
        user = _current_user(request, conn)
        rows = conn.execute(
            "SELECT * FROM user_api_key WHERE user_id=%s AND revoked_at IS NULL "
            "ORDER BY created_at DESC", (user["id"],),
        ).fetchall()
        return {"keys": [_public_api_key(row) for row in rows]}
    finally:
        conn.close()


@app.post("/api/auth/api-keys")
def create_api_key(req: ApiKeyRequest, request: Request) -> dict:
    from .. import db as dbm
    conn = dbm.connect()
    try:
        user = _current_user(request, conn)
        token = "finto_" + secrets.token_urlsafe(32)
        key_id = str(uuid.uuid4())
        now = datetime.now(timezone.utc).isoformat()
        scopes = ["taxonomy:read", "taxonomy:write"]
        conn.execute(
            "INSERT INTO user_api_key (id,user_id,name,key_prefix,key_hash,scopes,created_at) "
            "VALUES (%s,%s,%s,%s,%s,%s::jsonb,%s)",
            (key_id, user["id"], req.name.strip() or "Agent access", token[:12],
             hashlib.sha256(token.encode()).hexdigest(), json.dumps(scopes), now),
        )
        conn.commit()
        row = conn.execute("SELECT * FROM user_api_key WHERE id=%s", (key_id,)).fetchone()
        return {**_public_api_key(row), "key": token}
    finally:
        conn.close()


@app.delete("/api/auth/api-keys/{key_id}")
def revoke_api_key(key_id: str, request: Request) -> dict:
    from .. import db as dbm
    conn = dbm.connect()
    try:
        user = _current_user(request, conn)
        changed = conn.execute(
            "UPDATE user_api_key SET revoked_at=%s WHERE id=%s AND user_id=%s "
            "AND revoked_at IS NULL",
            (datetime.now(timezone.utc).isoformat(), key_id, user["id"]),
        ).rowcount
        conn.commit()
        if not changed:
            raise HTTPException(404, "API key not found")
        return {"ok": True}
    finally:
        conn.close()


@app.post("/api/auth/login")
def login(req: LoginRequest, request: Request, response: Response) -> dict:
    from .. import db as dbm
    from ..auth import authenticate, issue_session, public_user

    conn = dbm.connect()
    try:
        user = authenticate(conn, req.identifier, req.password)
        if not user:
            raise HTTPException(401, "incorrect username, email, or password")
        token, max_age = issue_session(
            conn, user["id"], _session_secret(), request.headers.get("user-agent"),
        )
        conn.commit()
    finally:
        conn.close()
    response.set_cookie(
        "finto_session", token, max_age=max_age, httponly=True, secure=True,
        samesite="strict", path="/",
    )
    return {"ok": True, "user": public_user(user)}


@app.post("/api/auth/logout")
def logout(request: Request, response: Response) -> dict:
    from .. import db as dbm
    from ..auth import revoke_session

    conn = dbm.connect()
    try:
        revoke_session(conn, request.cookies.get("finto_session"), _session_secret())
        conn.commit()
    finally:
        conn.close()
    response.delete_cookie("finto_session", path="/", secure=True, samesite="strict")
    return {"ok": True}


@app.get("/api/auth/me")
def me(request: Request) -> dict:
    from .. import db as dbm
    from ..auth import public_user

    conn = dbm.connect()
    try:
        return public_user(_current_user(request, conn))
    finally:
        conn.close()


@app.patch("/api/auth/preferences")
def update_preferences(req: PreferencesRequest, request: Request) -> dict:
    from .. import db as dbm
    from ..auth import public_user, user_by_id

    changes = req.model_dump(exclude_none=True)
    if "theme" in changes and changes["theme"] not in {"system", "dark", "light"}:
        raise HTTPException(422, "invalid theme")
    if "language" in changes and changes["language"] not in {"en", "zh-Hant"}:
        raise HTTPException(422, "invalid language")
    if "base_currency" in changes:
        changes["base_currency"] = changes["base_currency"].upper()
        if len(changes["base_currency"]) != 3 or not changes["base_currency"].isalpha():
            raise HTTPException(422, "invalid base currency")
    conn = dbm.connect()
    try:
        user = _current_user(request, conn)
        preferences = dict(user.get("preferences") or {})
        preferences.update(changes)
        conn.execute(
            "UPDATE app_user SET preferences=%s::jsonb, updated_at=%s WHERE id=%s",
            (json.dumps(preferences), datetime.now(timezone.utc).isoformat(), user["id"]),
        )
        conn.commit()
        return public_user(user_by_id(conn, user["id"]))
    finally:
        conn.close()

# The Angular dev server is a different origin. Production serves the built
# frontend from this same app, so no CORS is needed there.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:4200", "http://127.0.0.1:4200"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.middleware("http")
async def authenticated_api_context(request: Request, call_next):
    """Verify the database session and attach its user to every private API call."""
    public = request.url.path in {"/api/auth/login", "/api/auth/logout"} \
        or request.url.path.startswith("/api/agent/")
    if request.url.path.startswith("/api/") and not public:
        from .. import db as dbm

        conn = dbm.connect()
        try:
            user = _current_user(request, conn)
            request.state.user_id = user["id"]
            dbm.apply_acl(conn, user["id"])
            request.state.db_conn = conn
            return await call_next(request)
        except HTTPException as error:
            return Response(
                json.dumps({"detail": error.detail}), status_code=error.status_code,
                media_type="application/json", headers={"Cache-Control": "no-store"},
            )
        finally:
            conn.close()
    return await call_next(request)


@app.middleware("http")
async def private_route_cache(request: Request, call_next):
    """Let one signed-in browser reuse route data without shared CDN caching."""
    response = await call_next(request)
    if request.method == "GET" and request.url.path.startswith("/api/"):
        path = request.url.path
        stable = path in {
            "/api/accounts", "/api/cards", "/api/facets", "/api/institutions",
            "/api/fx/rates", "/api/details",
        }
        volatile = path.startswith("/api/jobs/") or path == "/api/jobs"
        computed = path in {
            "/api/summary", "/api/positions", "/api/coverage", "/api/flows",
            "/api/integrity", "/api/installments", "/api/investments",
            "/api/statement-freshness", "/api/composition",
        } or path.startswith(("/api/investments/", "/api/installments/", "/api/details/"))
        response.headers["Cache-Control"] = (
            "no-store" if path.startswith(("/api/auth/", "/api/agent/")) or volatile
            else "private, max-age=3600, stale-while-revalidate=86400" if stable
            else "private, max-age=1800, stale-while-revalidate=86400" if computed
            else "private, max-age=300, stale-while-revalidate=3600"
        )
        response.headers["Vary"] = "Cookie"
    else:
        response.headers.setdefault("Cache-Control", "no-store")
    return response

for router in (transactions, summary, accounts, imports, review, integrity,
               installments, investments, jobs, query):
    app.include_router(router.router, prefix="/api")


@app.get("/api/health")
def health(conn=Depends(api_get_conn)) -> dict:
    conn.execute("SELECT 1")
    return {"status": "ok", "storage": "postgresql"}


@app.post("/api/admin/rebuild-transfers")
def rebuild_transfers(month: str, start_day: int = 1, end_day: int = 31,
                      conn=Depends(api_get_conn)) -> dict:
    """Authenticated, bounded transfer maintenance for one part of a month."""
    from .. import db as dbm
    from ..transfers import TransferContext, find_transfers
    try:
        from datetime import date, timedelta
        start = date.fromisoformat(f"{month}-{start_day:02d}")
        next_month = (start.replace(day=28) + timedelta(days=4)).replace(day=1)
        last_day = (next_month - timedelta(days=1)).day
        end = date.fromisoformat(f"{month}-{min(end_day, last_day):02d}")
        window_start, window_end = start - timedelta(days=10), end + timedelta(days=10)
        txns = dbm.load_txns(
            conn, include_duplicates=True,
            from_date=window_start, to_date=window_end,
        )
        accounts_by_id = dbm.load_accounts(conn)
        report = find_transfers(
            txns, accounts_by_id, fx_lookup=dbm.make_fx_lookup(conn),
            context=TransferContext(
                self_aliases=dbm.load_self_aliases(conn),
                account_aliases=dbm.load_account_alias_index(conn),
                person_aliases=dbm.load_person_aliases(conn),
            ),
        )
        dbm.insert_transfer_groups(conn, report.groups)
        dbm.insert_transfer_candidates(conn, report.candidates)
        linked = [t for t in txns if t.transfer_group_id]
        with conn.cursor() as cursor:
            cursor.executemany(
                "UPDATE txn SET transfer_group_id=%s, kind=%s WHERE id=%s",
                [(t.transfer_group_id, t.kind.value, t.id) for t in linked],
            )
        conn.commit()
        target = conn.execute(
            "SELECT transfer_group_id FROM txn WHERE id=%s",
            ("1c3847b9-cb9e-4588-8fae-423156d5ed12",),
        ).fetchone()
        return {"month": month, "range": [str(start), str(end)],
                "groups": len(report.groups),
                "candidates": len(report.candidates),
                "target_group": target["transfer_group_id"] if target else None}
    except Exception:
        conn.rollback()
        raise


def main() -> None:
    """Run the API. Localhost only, by design."""
    import argparse

    import uvicorn

    p = argparse.ArgumentParser(prog="finto-api")
    p.add_argument("--host", default="127.0.0.1",
                   help="bind address for local development")
    p.add_argument("--port", type=int, default=8000)
    p.add_argument("--database-url", help="PostgreSQL connection URL")
    p.add_argument("--reload", action="store_true")
    args = p.parse_args()

    if args.database_url:
        import os
        os.environ["DATABASE_URL"] = args.database_url

    uvicorn.run("fin.api.app:app", host=args.host, port=args.port,
                reload=args.reload)


if __name__ == "__main__":
    main()
