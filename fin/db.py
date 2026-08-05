"""PostgreSQL persistence. Thin, explicit, no ORM."""

from __future__ import annotations

import json
import os
from collections.abc import Iterable, Sequence
from datetime import date, datetime
from decimal import Decimal
from pathlib import Path

import psycopg
from psycopg.rows import dict_row

from .models import (
    Account,
    Card,
    CategoryRule,
    DuplicateCandidate,
    FxRate,
    InstallmentCandidate,
    InstallmentPlan,
    Institution,
    Money,
    RawRecord,
    StatementFile,
    TransferCandidate,
    TransferGroup,
    Txn,
)

SCHEMA_PATH = Path(__file__).with_name("schema.sql")


def database_url(value: str | None = None) -> str:
    """Resolve the PostgreSQL URL used by the CLI and API."""
    url = (
        value
        or os.environ.get("POSTGRES_URL_NON_POOLING")
        or os.environ.get("POSTGRES_URL")
        or os.environ.get("DATABASE_URL")
    )
    if not url:
        raise RuntimeError("POSTGRES_URL_NON_POOLING, POSTGRES_URL, or DATABASE_URL must be set")
    return url


def connect(url: str | None = None) -> psycopg.Connection:
    """Open an administrative connection; API dependencies clear the ACL bypass."""
    conn = psycopg.connect(database_url(url), row_factory=dict_row)
    conn.execute("SELECT set_config('finto.bypass_acl', '1', false)")
    return conn


def apply_acl(conn: psycopg.Connection, user_id: str) -> None:
    """Restrict this connection to one authenticated user's account grants."""
    conn.execute("SET ROLE finto_api_rls")
    conn.execute("SELECT set_config('finto.bypass_acl', '0', false)")
    conn.execute("SELECT set_config('finto.user_id', %s, false)", (user_id,))


def execute_many(conn: psycopg.Connection, query: str, params) -> None:
    """Execute a PostgreSQL statement for every parameter tuple."""
    with conn.cursor() as cursor:
        cursor.executemany(query, params)


def init_db(conn: psycopg.Connection) -> None:
    """Create the PostgreSQL schema idempotently."""
    conn.execute(SCHEMA_PATH.read_text())
    from .taxonomy import seed_base_taxonomy

    seed_base_taxonomy(conn)
    execute_many(
        conn,
        "INSERT INTO setting (key, value) VALUES (%s, %s) ON CONFLICT (key) DO NOTHING",
        (
            ("base_currency", "HKD"),
            ("period_start", "2025-01-01"),
            ("llm_enabled", "0"),
            ("llm_model", "claude-haiku-4-5-20251001"),
        ),
    )
    password = os.environ.get("FINTO_AUTH_PASSWORD")
    owner = conn.execute(
        "SELECT password_hash FROM app_user WHERE id='owner'"
    ).fetchone()
    if password and owner and owner["password_hash"] == "!":
        from .auth import bootstrap_owner
        bootstrap_owner(
            conn,
            username=os.environ.get("FINTO_AUTH_USERNAME")
            or os.environ.get("FINTO_AUTH_USER", "owner"),
            email=os.environ.get("FINTO_AUTH_EMAIL", "owner@localhost"),
            password=password,
        )
    conn.commit()


def _iso(v) -> str | None:
    if v is None:
        return None
    if isinstance(v, (date, datetime)):
        return v.isoformat()
    return str(v)


# ---------------------------------------------------------------------------
# Reference data
# ---------------------------------------------------------------------------


def upsert_institution(conn, inst: Institution) -> None:
    conn.execute(
        "INSERT INTO institution (id, display_name, country, timezone) "
        "VALUES (%s,%s,%s,%s) ON CONFLICT(id) DO UPDATE SET "
        "display_name=excluded.display_name, country=excluded.country, "
        "timezone=excluded.timezone",
        (inst.id, inst.display_name, inst.country, inst.timezone),
    )


def upsert_account(conn, a: Account, *, user_id: str = "owner") -> None:
    conn.execute(
        "INSERT INTO account (id, user_id, institution_id, display_name, account_type, "
        "primary_currency, balance_group, masked_number, is_own_account, "
        "opened_on, closed_on, notes) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) "
        "ON CONFLICT(id) DO UPDATE SET display_name=excluded.display_name, "
        "account_type=excluded.account_type, primary_currency=excluded.primary_currency, "
        "balance_group=excluded.balance_group, masked_number=excluded.masked_number, "
        "is_own_account=excluded.is_own_account, notes=excluded.notes",
        (
            a.id,
            user_id,
            a.institution_id,
            a.display_name,
            a.account_type.value,
            a.primary_currency,
            a.balance_group,
            a.masked_number,
            int(a.is_own_account),
            _iso(a.opened_on),
            _iso(a.closed_on),
            a.notes,
        ),
    )
    # Declared settlement currencies are replaced wholesale: the config file is
    # the authority, and a currency removed there must stop being permitted.
    conn.execute("DELETE FROM account_currency WHERE account_id=%s", (a.id,))
    execute_many(
        conn,
        "INSERT INTO account_currency (account_id, currency, is_primary) VALUES (%s,%s,%s)",
        [(a.id, c, int(c == a.primary_currency)) for c in a.settlement_currencies],
    )
    from .models import normalize_alias

    conn.execute("DELETE FROM account_alias WHERE account_id=%s", (a.id,))
    aliases = list(
        dict.fromkeys(normalize_alias(x) for x in ([a.display_name] + list(a.aliases)) if x)
    )
    execute_many(
        conn,
        "INSERT INTO account_alias (account_id, alias) VALUES (%s,%s) "
        "ON CONFLICT (account_id, alias) DO NOTHING",
        [(a.id, al) for al in aliases if al],
    )
    conn.execute(
        "INSERT INTO account_acl (account_id,user_id,access_role,granted_at,granted_by) "
        "SELECT id,user_id,'owner',CURRENT_TIMESTAMP::text,user_id FROM account WHERE id=%s "
        "ON CONFLICT (account_id,user_id) DO UPDATE SET access_role='owner'",
        (a.id,),
    )


def upsert_party(conn, p) -> None:
    from .models import normalize_alias

    conn.execute(
        "INSERT INTO party (id, display_name, kind, notes) VALUES (%s,%s,%s,%s) "
        "ON CONFLICT(id) DO UPDATE SET display_name=excluded.display_name, "
        "kind=excluded.kind, notes=excluded.notes",
        (p.id, p.display_name, p.kind, p.notes),
    )
    conn.execute("DELETE FROM party_alias WHERE party_id=%s", (p.id,))
    aliases = list(
        dict.fromkeys(normalize_alias(x) for x in ([p.display_name] + list(p.aliases)) if x)
    )
    execute_many(
        conn,
        "INSERT INTO party_alias (party_id, alias) VALUES (%s,%s) "
        "ON CONFLICT (party_id, alias) DO NOTHING",
        [(p.id, al) for al in aliases if al],
    )


def add_tag(conn, txn_id: str, tag: str, *, source: str = "manual") -> None:
    from .taxonomy import register_tag

    owner = conn.execute(
        "SELECT a.user_id FROM txn t JOIN account a ON a.id=t.account_id WHERE t.id=%s",
        (txn_id,),
    ).fetchone()
    if owner is None:
        raise ValueError(f"unknown transaction {txn_id}")
    canonical = register_tag(conn, owner["user_id"], tag, source=source)["display_name"]

    conn.execute(
        "INSERT INTO txn_tag (txn_id, tag, source, created_at) "
        "VALUES (%s,%s,%s,%s) ON CONFLICT (txn_id, tag) DO NOTHING",
        (txn_id, canonical, source, datetime.now().isoformat()),
    )


def remove_tag(conn, txn_id: str, tag: str) -> None:
    conn.execute("DELETE FROM txn_tag WHERE txn_id=%s AND tag=%s", (txn_id, tag))


def load_tags(conn, txn_ids) -> dict:
    ids = list(txn_ids)
    if not ids:
        return {}
    out: dict[str, list[str]] = {}
    for chunk in (ids[i : i + 500] for i in range(0, len(ids), 500)):
        q = ",".join(["%s"] * len(chunk))
        for r in conn.execute(
            f"SELECT txn_id, tag FROM txn_tag WHERE txn_id IN ({q}) ORDER BY tag", tuple(chunk)
        ):
            out.setdefault(r["txn_id"], []).append(r["tag"])
    return out


def all_tags(conn) -> list[dict]:
    return [
        dict(r)
        for r in conn.execute(
            "SELECT d.display_name AS tag, COUNT(a.id) AS transactions "
            "FROM tag_definition d "
            "LEFT JOIN txn_tag tt ON tt.tag=d.display_name "
            "LEFT JOIN txn x ON x.id=tt.txn_id AND x.duplicate_of_id IS NULL "
            "AND x.status<>'void' LEFT JOIN account a ON a.id=x.account_id "
            "AND a.user_id=d.user_id GROUP BY d.id,d.display_name "
            "ORDER BY transactions DESC,d.display_name"
        )
    ]


def statement_txn_ids(conn) -> set[str]:
    """Transactions from an issuer's own statement rather than a CSV export."""
    return {
        r["id"]
        for r in conn.execute(
            "SELECT t.id FROM txn t JOIN statement_file sf ON sf.id = t.statement_file_id "
            "WHERE sf.file_format = 'pdf'"
        )
    }


def upsert_category_rule(conn, r: CategoryRule) -> None:
    conn.execute(
        "INSERT INTO category_rule (id, priority, match_field, match_type, pattern, "
        "account_id, set_kind, set_category, set_subcategory, enabled) "
        "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) ON CONFLICT(id) DO UPDATE SET "
        "priority=excluded.priority, match_field=excluded.match_field, "
        "match_type=excluded.match_type, pattern=excluded.pattern, "
        "account_id=excluded.account_id, set_kind=excluded.set_kind, "
        "set_category=excluded.set_category, "
        "set_subcategory=excluded.set_subcategory, enabled=excluded.enabled",
        (
            r.id,
            r.priority,
            r.match_field,
            r.match_type,
            r.pattern,
            r.account_id,
            r.set_kind,
            r.set_category,
            r.set_subcategory,
            int(r.enabled),
        ),
    )


def load_accounts(conn) -> dict[str, Account]:
    ccys: dict[str, list[str]] = {}
    for r in conn.execute(
        "SELECT account_id, currency FROM account_currency ORDER BY is_primary DESC, currency"
    ):
        ccys.setdefault(r["account_id"], []).append(r["currency"])
    aliases: dict[str, list[str]] = {}
    for r in conn.execute("SELECT account_id, alias FROM account_alias"):
        aliases.setdefault(r["account_id"], []).append(r["alias"])
    out = {}
    for r in conn.execute("SELECT * FROM account"):
        out[r["id"]] = Account(
            id=r["id"],
            institution_id=r["institution_id"],
            display_name=r["display_name"],
            account_type=r["account_type"],
            primary_currency=r["primary_currency"],
            settlement_currencies=ccys.get(r["id"], []),
            balance_group=r["balance_group"],
            masked_number=r["masked_number"],
            is_own_account=bool(r["is_own_account"]),
            aliases=aliases.get(r["id"], []),
            notes=r["notes"],
        )
    return out


def load_self_aliases(conn) -> set[str]:
    """Normalised names that mean 'me' — used by the transfer matcher."""
    rows = conn.execute(
        "SELECT a.alias FROM party_alias a JOIN party p ON p.id = a.party_id WHERE p.kind='self'"
    )
    return {r["alias"] for r in rows}


def load_person_aliases(conn) -> dict[str, str]:
    """alias -> party_id for P2P counterparties (not netted as self-transfers)."""
    return {
        r["alias"]: r["party_id"]
        for r in conn.execute(
            "SELECT a.alias, a.party_id FROM party_alias a "
            "JOIN party p ON p.id = a.party_id WHERE p.kind='person'"
        )
    }


def load_account_alias_index(conn) -> dict[str, str]:
    """alias -> account_id for destination hints in transfer descriptions."""
    return {
        r["alias"]: r["account_id"]
        for r in conn.execute("SELECT alias, account_id FROM account_alias")
    }


def upsert_card(conn, c: Card) -> None:
    conn.execute(
        "INSERT INTO card (id, account_id, cardholder_name, last4, "
        "is_supplementary, issued_on, closed_on, replaces_card_id) "
        "VALUES (%s,%s,%s,%s,%s,%s,%s,%s) "
        "ON CONFLICT(id) DO UPDATE SET cardholder_name=excluded.cardholder_name, "
        "last4=excluded.last4, is_supplementary=excluded.is_supplementary, "
        "issued_on=excluded.issued_on, closed_on=excluded.closed_on, "
        "replaces_card_id=excluded.replaces_card_id",
        (
            c.id,
            c.account_id,
            c.cardholder_name,
            c.last4,
            int(c.is_supplementary),
            _iso(c.issued_on),
            _iso(c.closed_on),
            c.replaces_card_id,
        ),
    )


def load_cards(conn) -> list[Card]:
    return [
        Card(
            id=r["id"],
            account_id=r["account_id"],
            cardholder_name=r["cardholder_name"],
            last4=r["last4"],
            is_supplementary=bool(r["is_supplementary"]),
            issued_on=date.fromisoformat(r["issued_on"]) if r["issued_on"] else None,
            closed_on=date.fromisoformat(r["closed_on"]) if r["closed_on"] else None,
            replaces_card_id=r["replaces_card_id"],
        )
        for r in conn.execute("SELECT * FROM card ORDER BY id")
    ]


def card_lineage_roots(conn) -> dict[str, str]:
    """Map every card id to the root of its reissue chain.

    A card reissued twice gives C -> B -> A; all three report as A, so
    "spend on this card" survives renumbering. Cycles (a config mistake) resolve
    to the card itself rather than looping.
    """
    parent = {
        r["id"]: r["replaces_card_id"]
        for r in conn.execute("SELECT id, replaces_card_id FROM card")
    }
    roots: dict[str, str] = {}
    for cid in parent:
        seen, cur = {cid}, cid
        while parent.get(cur) and parent[cur] not in seen:
            cur = parent[cur]
            seen.add(cur)
        roots[cid] = cur
    return roots


# ---------------------------------------------------------------------------
# Files & raw records
# ---------------------------------------------------------------------------


def file_already_imported(conn, sha256: str) -> bool:
    row = conn.execute("SELECT 1 FROM statement_file WHERE file_sha256=%s", (sha256,)).fetchone()
    return row is not None


def insert_statement_file(conn, sf: StatementFile) -> None:
    conn.execute(
        "INSERT INTO statement_file (id, source_path, file_sha256, institution_id, "
        "account_id, file_format, parser_id, parser_version, period_start, "
        "period_end, statement_date, imported_at, row_count) "
        "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
        (
            sf.id,
            sf.source_path,
            sf.file_sha256,
            sf.institution_id,
            sf.account_id,
            sf.file_format.value,
            sf.parser_id,
            sf.parser_version,
            _iso(sf.period_start),
            _iso(sf.period_end),
            _iso(sf.statement_date),
            _iso(sf.imported_at),
            sf.row_count,
        ),
    )


def insert_raw_records(conn, records: Iterable[RawRecord]) -> None:
    execute_many(
        conn,
        "INSERT INTO raw_record (id, statement_file_id, line_no, "
        "payload, row_sha256) VALUES (%s,%s,%s,%s,%s) "
        "ON CONFLICT (statement_file_id, line_no) DO NOTHING",
        [
            (r.id, r.statement_file_id, r.line_no, json.dumps(r.payload, default=str), r.row_sha256)
            for r in records
        ],
    )


# ---------------------------------------------------------------------------
# Transactions
# ---------------------------------------------------------------------------

_TXN_COLUMNS = (
    "id",
    "account_id",
    "card_id",
    "txn_date",
    "posted_date",
    "status",
    "amount_booked",
    "currency_booked",
    "amount_native",
    "currency_native",
    "fx_rate",
    "fx_fee_booked",
    "description_raw",
    "description_norm",
    "merchant",
    "counterparty",
    "external_ref",
    "kind",
    "category",
    "subcategory",
    "transfer_group_id",
    "duplicate_of_id",
    "installment_plan_id",
    "installment_seq",
    "refund_of_id",
    "dedup_key",
    "statement_file_id",
    "raw_record_id",
    "review_state",
    "notes",
    "created_at",
    "updated_at",
)
_TXN_UPDATES = ", ".join(f"{column}=EXCLUDED.{column}" for column in _TXN_COLUMNS if column != "id")


def insert_txns(conn, txns: Sequence[Txn]) -> int:
    rows = []
    for t in txns:
        rows.append(
            (
                t.id,
                t.account_id,
                t.card_id,
                _iso(t.txn_date),
                _iso(t.posted_date),
                t.status.value,
                t.booked.amount,
                t.booked.currency,
                t.native.amount if t.native else None,
                t.native.currency if t.native else None,
                str(t.fx_rate) if t.fx_rate is not None else None,
                t.fx_fee.amount if t.fx_fee else None,
                t.description_raw,
                t.description_norm,
                t.merchant,
                t.counterparty,
                t.external_ref,
                t.kind.value,
                t.category,
                t.subcategory,
                t.transfer_group_id,
                t.duplicate_of_id,
                t.installment_plan_id,
                t.installment_seq,
                t.refund_of_id,
                t.dedup_key,
                t.statement_file_id,
                t.raw_record_id,
                t.review_state,
                t.notes,
                _iso(t.created_at),
                _iso(t.updated_at),
            )
        )
    execute_many(
        conn,
        f"INSERT INTO txn ({', '.join(_TXN_COLUMNS)}) "
        f"VALUES ({','.join(['%s'] * len(_TXN_COLUMNS))}) "
        f"ON CONFLICT (id) DO UPDATE SET {_TXN_UPDATES}",
        rows,
    )
    insert_txn_details(conn, txns)
    return len(rows)


def insert_txn_details(conn, txns: Sequence[Txn]) -> int:
    """Persist extracted structured facts (flight routing, passenger, address).

    Parser-sourced details never overwrite a manual correction, so this inserts
    with OR IGNORE and leaves anything already recorded by a human alone.
    """
    rows = [(t.id, k, v, "parser") for t in txns for k, v in (t.details or {}).items() if v]
    if not rows:
        return 0
    execute_many(
        conn,
        "INSERT INTO txn_detail (txn_id, key, value, source) "
        "VALUES (%s,%s,%s,%s) ON CONFLICT (txn_id, key) DO NOTHING",
        rows,
    )
    return len(rows)


def load_txn_details(conn, txn_ids: Sequence[str]) -> dict[str, dict[str, str]]:
    if not txn_ids:
        return {}
    out: dict[str, dict[str, str]] = {}
    for chunk in (txn_ids[i : i + 500] for i in range(0, len(txn_ids), 500)):
        q = ",".join(["%s"] * len(chunk))
        for r in conn.execute(
            f"SELECT txn_id, key, value FROM txn_detail WHERE txn_id IN ({q})", tuple(chunk)
        ):
            out.setdefault(r["txn_id"], {})[r["key"]] = r["value"]
    return out


def load_txns(conn, *, include_duplicates: bool = False,
              from_date: date | None = None,
              to_date: date | None = None) -> list[Txn]:
    sql = "SELECT * FROM txn"
    clauses: list[str] = []
    params: list[str] = []
    if not include_duplicates:
        clauses.append("duplicate_of_id IS NULL")
    if from_date:
        clauses.append("txn_date >= %s")
        params.append(from_date.isoformat())
    if to_date:
        clauses.append("txn_date <= %s")
        params.append(to_date.isoformat())
    if clauses:
        sql += " WHERE " + " AND ".join(clauses)
    out = []
    for r in conn.execute(sql, params):
        out.append(
            Txn(
                id=r["id"],
                account_id=r["account_id"],
                card_id=r["card_id"],
                txn_date=date.fromisoformat(r["txn_date"]),
                posted_date=date.fromisoformat(r["posted_date"]) if r["posted_date"] else None,
                status=r["status"],
                booked=Money(amount=r["amount_booked"], currency=r["currency_booked"]),
                native=(
                    Money(amount=r["amount_native"], currency=r["currency_native"])
                    if r["amount_native"] is not None
                    else None
                ),
                fx_rate=Decimal(r["fx_rate"]) if r["fx_rate"] else None,
                fx_fee=(
                    Money(amount=r["fx_fee_booked"], currency=r["currency_booked"])
                    if r["fx_fee_booked"] is not None
                    else None
                ),
                description_raw=r["description_raw"],
                description_norm=r["description_norm"],
                merchant=r["merchant"],
                counterparty=r["counterparty"],
                external_ref=r["external_ref"],
                kind=r["kind"],
                category=r["category"],
                subcategory=r["subcategory"],
                transfer_group_id=r["transfer_group_id"],
                duplicate_of_id=r["duplicate_of_id"],
                installment_plan_id=r["installment_plan_id"],
                installment_seq=r["installment_seq"],
                refund_of_id=r["refund_of_id"],
                dedup_key=r["dedup_key"],
                statement_file_id=r["statement_file_id"],
                raw_record_id=r["raw_record_id"],
                review_state=r["review_state"],
                notes=r["notes"],
            )
        )
    details = load_txn_details(conn, [t.id for t in out])
    for t in out:
        t.details = details.get(t.id, {})
    return out


def update_txn_links(conn, txns: Sequence[Txn]) -> None:
    """Persist everything the reconcile passes mutate.

    Category, merchant and details as well as the links: gateway labelling
    recovers merchants and income detection tags streams. Every pass declines
    to overwrite a value already present, so a manual correction survives.
    """
    execute_many(
        conn,
        "UPDATE txn SET duplicate_of_id=%s, transfer_group_id=%s, kind=%s, "
        "installment_plan_id=%s, installment_seq=%s, refund_of_id=%s, "
        "category=%s, subcategory=%s, merchant=%s, updated_at=%s WHERE id=%s",
        [
            (
                t.duplicate_of_id,
                t.transfer_group_id,
                t.kind.value,
                t.installment_plan_id,
                t.installment_seq,
                t.refund_of_id,
                t.category,
                t.subcategory,
                t.merchant,
                datetime.now().isoformat(),
                t.id,
            )
            for t in txns
        ],
    )
    insert_txn_details(conn, txns)
    merge_duplicate_details(conn)


def merge_duplicate_details(conn) -> int:
    """Give a survivor every fact its suppressed copies carried.

    The same charge from a statement and from a CSV export describes one
    movement; each source records details the other omits — the PDF names the
    cardholder from its section header, the CSV from a Card Member column. When
    one supersedes the other the loser's rows are hidden, so its facts move to
    the survivor, never overwriting one already there.
    """
    cur = conn.execute(
        "INSERT INTO txn_detail (txn_id, key, value, source) "
        "SELECT t.duplicate_of_id, d.key, d.value, d.source "
        "FROM txn t JOIN txn_detail d ON d.txn_id = t.id "
        "WHERE t.duplicate_of_id IS NOT NULL "
        "ON CONFLICT (txn_id, key) DO NOTHING"
    )
    conn.commit()
    return cur.rowcount


# ---------------------------------------------------------------------------
# Transfers & candidates
# ---------------------------------------------------------------------------


def insert_transfer_groups(conn, groups: Sequence[TransferGroup]) -> None:
    """Upsert transfer groups, keyed on the group's (deterministic) id.

    Re-running reconcile re-proposes the same groups, so this must converge
    rather than accumulate. A group you confirmed by hand is never downgraded by
    a later automatic pass — hence the WHERE on the upsert.
    """
    for g in groups:
        conn.execute(
            "INSERT INTO transfer_group (id, kind, match_method, "
            "confidence, fee_booked, fee_currency, is_confirmed, notes, created_at) "
            "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s) "
            "ON CONFLICT(id) DO UPDATE SET kind=excluded.kind, "
            "match_method=excluded.match_method, confidence=excluded.confidence, "
            "fee_booked=excluded.fee_booked, fee_currency=excluded.fee_currency, "
            "notes=excluded.notes "
            "WHERE transfer_group.is_confirmed = 0",
            (
                g.id,
                g.kind.value,
                g.match_method,
                g.confidence,
                g.fee.amount if g.fee else None,
                g.fee.currency if g.fee else None,
                int(g.is_confirmed),
                g.notes,
                _iso(g.created_at),
            ),
        )
        execute_many(
            conn,
            "INSERT INTO transfer_leg (transfer_group_id, txn_id, role) "
            "VALUES (%s,%s,%s) ON CONFLICT (transfer_group_id, txn_id) "
            "DO UPDATE SET role=EXCLUDED.role",
            [(g.id, leg.txn_id, leg.role) for leg in g.legs],
        )


def insert_transfer_candidates(conn, cands: Sequence[TransferCandidate]) -> None:
    execute_many(
        conn,
        "INSERT INTO transfer_candidate (id, out_txn_id, in_txn_id, "
        "score, date_delta, amount_delta, reasons, resolution, created_at) "
        "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s) "
        "ON CONFLICT (out_txn_id, in_txn_id) DO NOTHING",
        [
            (
                c.id,
                c.out_txn_id,
                c.in_txn_id,
                c.score,
                c.date_delta,
                c.amount_delta,
                json.dumps(c.reasons),
                c.resolution,
                _iso(c.created_at),
            )
            for c in cands
        ],
    )


def insert_duplicate_candidates(conn, cands: Sequence[DuplicateCandidate]) -> None:
    execute_many(
        conn,
        "INSERT INTO duplicate_candidate (id, keep_txn_id, dupe_txn_id, "
        "score, reasons, resolution, created_at) VALUES (%s,%s,%s,%s,%s,%s,%s) "
        "ON CONFLICT (keep_txn_id, dupe_txn_id) DO NOTHING",
        [
            (
                c.id,
                c.keep_txn_id,
                c.dupe_txn_id,
                c.score,
                json.dumps(c.reasons),
                c.resolution,
                _iso(c.created_at),
            )
            for c in cands
        ],
    )


# ---------------------------------------------------------------------------
# Installment plans
# ---------------------------------------------------------------------------


def insert_installment_plans(conn, plans: Sequence[InstallmentPlan]) -> None:
    """Upsert plans on their deterministic id, preserving manual confirmations."""
    for p in plans:
        conn.execute(
            "INSERT INTO installment_plan (id, account_id, card_id, merchant, "
            "description, principal, currency, term_months, start_date, fee_total, "
            "apr, external_ref, status, match_method, confidence, is_confirmed, "
            "notes, created_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) "
            "ON CONFLICT(id) DO UPDATE SET merchant=excluded.merchant, "
            "description=excluded.description, principal=excluded.principal, "
            "term_months=excluded.term_months, start_date=excluded.start_date, "
            "status=excluded.status, confidence=excluded.confidence, "
            "card_id=excluded.card_id "
            "WHERE installment_plan.is_confirmed = 0",
            (
                p.id,
                p.account_id,
                p.card_id,
                p.merchant,
                p.description,
                p.principal.amount,
                p.principal.currency,
                p.term_months,
                _iso(p.start_date),
                p.fee_total.amount if p.fee_total else None,
                str(p.apr) if p.apr is not None else None,
                p.external_ref,
                p.status.value,
                p.match_method,
                p.confidence,
                int(p.is_confirmed),
                p.notes,
                _iso(p.created_at),
            ),
        )


def insert_installment_candidates(conn, cands: Sequence[InstallmentCandidate]) -> None:
    execute_many(
        conn,
        "INSERT INTO installment_candidate (id, account_id, description, "
        "txn_ids, term_months, score, reasons, resolution, created_at) "
        "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s) ON CONFLICT (id) DO NOTHING",
        [
            (
                c.id,
                c.account_id,
                c.description,
                json.dumps(c.txn_ids),
                c.term_months,
                c.score,
                json.dumps(c.reasons),
                c.resolution,
                _iso(c.created_at),
            )
            for c in cands
        ],
    )


def prune_orphan_installment_plans(conn) -> int:
    """Drop unconfirmed plans no transaction belongs to any more."""
    cur = conn.execute(
        "DELETE FROM installment_plan WHERE is_confirmed = 0 AND id NOT IN "
        "(SELECT installment_plan_id FROM txn WHERE installment_plan_id IS NOT NULL)"
    )
    return cur.rowcount


def load_installment_plans(conn, *, active_only: bool = False) -> list[dict]:
    """Plans with progress: how many instalments are in the ledger, and what's left."""
    sql = """
        SELECT p.*,
               COUNT(t.id) FILTER (WHERE t.installment_seq IS NOT NULL) AS paid_count,
               COALESCE(SUM(t.amount_booked), 0) AS paid_minor
        FROM installment_plan p
        LEFT JOIN txn t ON t.installment_plan_id = p.id AND t.duplicate_of_id IS NULL
        {where}
        GROUP BY p.id
        ORDER BY p.start_date DESC
    """.format(where="WHERE p.status = 'active'" if active_only else "")
    out = []
    for r in conn.execute(sql):
        per = abs(r["principal"]) // r["term_months"] if r["term_months"] else 0
        settled_early = r["status"] == "completed" and r["paid_count"] < r["term_months"]
        remaining = (0 if r["status"] == "completed"
                     else max(0, r["term_months"] - r["paid_count"]))
        out.append(
            {
                "id": r["id"],
                "account_id": r["account_id"],
                "card_id": r["card_id"],
                "merchant": r["merchant"],
                "description": r["description"],
                "principal": {"amount": r["principal"], "currency": r["currency"]},
                "term_months": r["term_months"],
                "start_date": r["start_date"],
                "status": r["status"],
                "confidence": r["confidence"],
                "is_confirmed": bool(r["is_confirmed"]),
                "paid_count": r["paid_count"],
                "settled_early": settled_early,
                "paid": {"amount": r["paid_minor"], "currency": r["currency"]},
                "remaining_count": remaining,
                "outstanding": {"amount": -(per * remaining), "currency": r["currency"]},
                "per_installment": {"amount": -per, "currency": r["currency"]},
            }
        )
    return out


# ---------------------------------------------------------------------------
# FX
# ---------------------------------------------------------------------------


def upsert_fx_rate(conn, r: FxRate) -> None:
    conn.execute(
        "INSERT INTO fx_rate (rate_date, base, quote, rate, source) "
        "VALUES (%s,%s,%s,%s,%s) ON CONFLICT (rate_date, base, quote, source) "
        "DO UPDATE SET rate=EXCLUDED.rate",
        (_iso(r.rate_date), r.base.upper(), r.quote.upper(), str(r.rate), r.source),
    )


def make_fx_lookup(conn):
    """Return fx_lookup(date, base, quote) -> Decimal | None.

    Falls back to the most recent rate on or before the requested date, and to
    the inverse pair when only one direction is stored.
    """

    def lookup(d, base: str, quote: str):
        base, quote = base.upper(), quote.upper()
        if base == quote:
            return Decimal(1)
        row = conn.execute(
            "SELECT rate FROM fx_rate WHERE base=%s AND quote=%s AND rate_date<=%s "
            "ORDER BY rate_date DESC LIMIT 1",
            (base, quote, _iso(d)),
        ).fetchone()
        if row:
            return Decimal(row["rate"])
        row = conn.execute(
            "SELECT rate FROM fx_rate WHERE base=%s AND quote=%s AND rate_date<=%s "
            "ORDER BY rate_date DESC LIMIT 1",
            (quote, base, _iso(d)),
        ).fetchone()
        if row and Decimal(row["rate"]) != 0:
            return Decimal(1) / Decimal(row["rate"])
        return None

    return lookup
