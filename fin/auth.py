"""Database-backed credentials and signed, persistent user sessions."""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import secrets
import time
import uuid
from datetime import datetime, timezone

SCRYPT_N = 2**14
SESSION_MAX_AGE = 60 * 60 * 24 * 365


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def _unb64(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def password_digest(password: str, salt: bytes | None = None) -> tuple[str, str]:
    if not password:
        raise ValueError("password cannot be empty")
    salt = salt or secrets.token_bytes(16)
    digest = hashlib.scrypt(
        password.encode(), salt=salt, n=SCRYPT_N, r=8, p=1, dklen=32,
    )
    return _b64(digest), _b64(salt)


def password_matches(password: str, expected: str, salt: str) -> bool:
    if expected == "!" or salt == "!":
        return False
    try:
        actual, _ = password_digest(password, _unb64(salt))
    except (ValueError, TypeError):
        return False
    return hmac.compare_digest(actual, expected)


def bootstrap_owner(conn, *, username: str, email: str, password: str) -> dict:
    """Create or rotate the single owner credential; never exposed over HTTP."""
    now = datetime.now(timezone.utc).isoformat()
    digest, salt = password_digest(password)
    conn.execute(
        "UPDATE app_user SET username=%s, email=%s, password_hash=%s, "
        "password_salt=%s, is_active=1, updated_at=%s WHERE id='owner'",
        (username.strip(), email.strip(), digest, salt, now),
    )
    return user_by_id(conn, "owner")


def create_user(conn, *, username: str, email: str, password: str,
                user_id: str | None = None) -> dict:
    """Provision a user from trusted administration code."""
    user_id = user_id or str(uuid.uuid4())
    now = datetime.now(timezone.utc).isoformat()
    digest, salt = password_digest(password)
    conn.execute(
        "INSERT INTO app_user (id,username,email,password_hash,password_salt,"
        "preferences,is_active,created_at,updated_at) "
        "VALUES (%s,%s,%s,%s,%s,'{}'::jsonb,1,%s,%s)",
        (user_id, username.strip(), email.strip(), digest, salt, now, now),
    )
    return user_by_id(conn, user_id)


def grant_account(conn, *, account_id: str, user_id: str, role: str,
                  granted_by: str = "owner") -> None:
    if role not in {"viewer", "editor", "owner"}:
        raise ValueError("role must be viewer, editor, or owner")
    conn.execute(
        "INSERT INTO account_acl (account_id,user_id,access_role,granted_at,granted_by) "
        "VALUES (%s,%s,%s,%s,%s) ON CONFLICT (account_id,user_id) DO UPDATE SET "
        "access_role=EXCLUDED.access_role, granted_at=EXCLUDED.granted_at, "
        "granted_by=EXCLUDED.granted_by",
        (account_id, user_id, role, datetime.now(timezone.utc).isoformat(), granted_by),
    )


def authenticate(conn, identifier: str, password: str) -> dict | None:
    row = conn.execute(
        "SELECT * FROM app_user WHERE is_active=1 AND "
        "(lower(username)=lower(%s) OR lower(email)=lower(%s))",
        (identifier.strip(), identifier.strip()),
    ).fetchone()
    if not row or not password_matches(password, row["password_hash"], row["password_salt"]):
        return None
    now = datetime.now(timezone.utc).isoformat()
    conn.execute("UPDATE app_user SET last_login_at=%s WHERE id=%s", (now, row["id"]))
    return dict(row)


def user_by_id(conn, user_id: str) -> dict | None:
    row = conn.execute(
        "SELECT id, username, email, preferences, created_at, updated_at, last_login_at "
        "FROM app_user WHERE id=%s AND is_active=1", (user_id,),
    ).fetchone()
    return dict(row) if row else None


def public_user(row: dict) -> dict:
    preferences = row.get("preferences") or {}
    if isinstance(preferences, str):
        preferences = json.loads(preferences)
    return {
        "id": row["id"], "username": row["username"], "email": row["email"],
        "preferences": preferences,
    }


def issue_session(
    conn, user_id: str, secret: str, user_agent: str | None = None,
) -> tuple[str, int]:
    if not secret:
        raise RuntimeError("FINTO_SESSION_SECRET must be set")
    expires = int(time.time()) + SESSION_MAX_AGE
    session_id = str(uuid.uuid4())
    payload = f"v1.{user_id}.{expires}.{session_id}"
    signature = _b64(hmac.new(secret.encode(), payload.encode(), hashlib.sha256).digest())
    token = f"{payload}.{signature}"
    now = datetime.now(timezone.utc).isoformat()
    conn.execute(
        "INSERT INTO auth_session (id,user_id,token_hash,created_at,expires_at,user_agent) "
        "VALUES (%s,%s,%s,%s,%s,%s)",
        (session_id, user_id, hashlib.sha256(token.encode()).hexdigest(), now,
         datetime.fromtimestamp(expires, timezone.utc).isoformat(), user_agent),
    )
    return token, SESSION_MAX_AGE


def parse_session(token: str | None, secret: str) -> dict | None:
    if not token or not secret:
        return None
    parts = token.split(".")
    if len(parts) != 5 or parts[0] != "v1":
        return None
    version, user_id, expires_raw, session_id, signature = parts
    try:
        expires = int(expires_raw)
    except ValueError:
        return None
    if expires < int(time.time()):
        return None
    payload = f"{version}.{user_id}.{expires}.{session_id}"
    expected = _b64(hmac.new(secret.encode(), payload.encode(), hashlib.sha256).digest())
    if not hmac.compare_digest(signature, expected):
        return None
    return {"user_id": user_id, "session_id": session_id, "expires": expires}


def revoke_session(conn, token: str | None, secret: str) -> None:
    parsed = parse_session(token, secret)
    if not parsed:
        return
    conn.execute(
        "UPDATE auth_session SET revoked_at=%s WHERE id=%s AND user_id=%s",
        (datetime.now(timezone.utc).isoformat(), parsed["session_id"], parsed["user_id"]),
    )
