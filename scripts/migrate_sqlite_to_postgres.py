#!/usr/bin/env python3
"""One-shot, verified migration from a legacy Finto SQLite file to PostgreSQL."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sqlite3
import sys
from pathlib import Path

import psycopg
from psycopg import sql

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = ROOT / "fin" / "schema.sql"
DEFAULT_URL_ENV = "POSTGRES_URL_NON_POOLING"

# Parents always precede children. txn is ordered specially for its self-links.
TABLES = (
    "institution", "account", "card", "account_currency", "statement_file",
    "raw_record", "transfer_group", "installment_plan", "txn", "txn_tag",
    "txn_detail", "transfer_leg", "transfer_candidate", "duplicate_candidate",
    "installment_candidate", "fx_rate", "category_rule", "balance_assertion",
    "reconciliation_check", "llm_decision", "txn_annotation", "party",
    "party_alias", "account_alias", "investment_snapshot",
    "investment_subaccount_balance", "investment_holding", "setting", "import_run",
)


def _ident(name: str) -> sql.Identifier:
    if not re.fullmatch(r"[a-z_][a-z0-9_]*", name):
        raise ValueError(f"unsafe SQL identifier: {name!r}")
    return sql.Identifier(name)


def _columns(source: sqlite3.Connection, table: str) -> list[str]:
    return [row[1] for row in source.execute(f'PRAGMA table_info("{table}")')]


def _source_rows(source: sqlite3.Connection, table: str, columns: list[str]):
    quoted = ", ".join(f'"{c}"' for c in columns)
    rows = list(source.execute(f'SELECT {quoted} FROM "{table}"'))
    if table != "txn":
        return rows

    # refund_of_id and duplicate_of_id are self-references. Insert their parents
    # first so PostgreSQL can enforce the FK throughout the copy.
    ix = {name: columns.index(name) for name in ("id", "refund_of_id", "duplicate_of_id")}
    pending = {row[ix["id"]]: row for row in rows}
    ordered = []
    while pending:
        ready = [
            key for key, row in pending.items()
            if all(not row[ix[field]] or row[ix[field]] not in pending
                   for field in ("refund_of_id", "duplicate_of_id"))
        ]
        if not ready:
            raise RuntimeError("cycle detected in txn self-references")
        for key in sorted(ready):
            ordered.append(pending.pop(key))
    return ordered


def _normalise(value) -> str | None:
    if value is None:
        return None
    if isinstance(value, memoryview):
        value = bytes(value)
    if isinstance(value, bytes):
        return value.hex()
    if isinstance(value, float):
        # SQLite and PostgreSQL can render the same IEEE-754 value with a
        # different insignificant tail. Scores/confidences are non-monetary;
        # twelve significant digits is far beyond their stored precision.
        return format(value, ".12g")
    return str(value)


def _digest(rows) -> str:
    h = hashlib.sha256()
    lines = []
    for row in rows:
        payload = json.dumps([_normalise(v) for v in row], separators=(",", ":"))
        lines.append(payload)
    for payload in sorted(lines):
        h.update(payload.encode())
        h.update(b"\n")
    return h.hexdigest()


def _target_rows(target, schema: str, table: str, columns: list[str]):
    query = sql.SQL("SELECT {} FROM {}.{} ORDER BY {}").format(
        sql.SQL(", ").join(map(_ident, columns)),
        _ident(schema), _ident(table),
        sql.SQL(", ").join(map(_ident, columns)),
    )
    return target.execute(query).fetchall()


def migrate(source_path: Path, database_url: str, *, schema: str = "public",
            reset: bool = False, verify_only: bool = False) -> list[dict]:
    source_path = source_path.expanduser().resolve()
    if not source_path.is_file():
        raise FileNotFoundError(source_path)
    _ident(schema)

    source = sqlite3.connect(f"file:{source_path}?mode=ro&immutable=1", uri=True)
    source.execute("PRAGMA foreign_keys = ON")
    target = psycopg.connect(database_url)
    report = []
    try:
        with target.transaction():
            target.execute("SELECT set_config('finto.bypass_acl', '1', false)")
            if reset:
                target.execute(sql.SQL("DROP SCHEMA {} CASCADE").format(_ident(schema)))
                target.execute(sql.SQL("CREATE SCHEMA {}").format(_ident(schema)))
            target.execute(sql.SQL("SET search_path TO {}").format(_ident(schema)))
            if not verify_only:
                existing = target.execute(
                    "SELECT tablename FROM pg_tables WHERE schemaname = %s",
                    (schema,),
                ).fetchall()
                populated = []
                for (table,) in existing:
                    count = target.execute(
                        sql.SQL("SELECT count(*) FROM {}.{}").format(
                            _ident(schema), _ident(table)
                        )
                    ).fetchone()[0]
                    if count:
                        populated.append(f"{table} ({count})")
                if populated:
                    raise RuntimeError(
                        "target is not empty; pass --reset to replace it: " + ", ".join(populated)
                    )

                target.execute(SCHEMA.read_text())

                for table in TABLES:
                    columns = _columns(source, table)
                    rows = _source_rows(source, table, columns)
                    if not rows:
                        continue
                    copy_stmt = sql.SQL("COPY {}.{} ({}) FROM STDIN").format(
                        _ident(schema), _ident(table),
                        sql.SQL(", ").join(map(_ident, columns)),
                    )
                    with target.cursor().copy(copy_stmt) as copy:
                        for row in rows:
                            copy.write_row(row)

                target.execute(
                    "INSERT INTO account_acl "
                    "(account_id,user_id,access_role,granted_at,granted_by) "
                    "SELECT id,user_id,'owner',CURRENT_TIMESTAMP::text,user_id FROM account "
                    "ON CONFLICT (account_id,user_id) DO UPDATE SET access_role='owner'"
                )

            for table in TABLES:
                columns = _columns(source, table)
                source_rows = _source_rows(source, table, columns)
                target_rows = _target_rows(target, schema, table, columns)
                source_digest = _digest(source_rows)
                target_digest = _digest(target_rows)
                ok = len(source_rows) == len(target_rows) and source_digest == target_digest
                report.append({
                    "table": table, "rows": len(source_rows), "digest": source_digest,
                    "verified": ok,
                })
                if not ok:
                    raise RuntimeError(
                        f"verification failed for {table}: source={len(source_rows)} "
                        f"target={len(target_rows)}"
                    )
        return report
    finally:
        source.close()
        target.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sqlite_file", type=Path)
    parser.add_argument("--url-env", default=DEFAULT_URL_ENV,
                        help=f"environment variable containing the PostgreSQL URL "
                             f"(default: {DEFAULT_URL_ENV})")
    parser.add_argument("--schema", default="public")
    parser.add_argument("--reset", action="store_true",
                        help="drop and recreate the target schema before copying")
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    database_url = os.environ.get(args.url_env)
    if not database_url:
        parser.error(f"{args.url_env} is not set")

    report = migrate(args.sqlite_file, database_url, schema=args.schema,
                     reset=args.reset, verify_only=args.verify_only)
    total = sum(item["rows"] for item in report)
    print(json.dumps({"verified": True, "tables": len(report), "rows": total,
                      "details": report}, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
