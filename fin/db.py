"""SQLite persistence. Thin, explicit, no ORM."""

from __future__ import annotations

import json
import sqlite3
from datetime import date, datetime
from decimal import Decimal
from pathlib import Path
from typing import Iterable, Sequence

from .models import (
    Account, Card, DuplicateCandidate, FxRate, Institution, Money, RawRecord,
    StatementFile, TransferCandidate, TransferGroup, Txn,
)

SCHEMA_PATH = Path(__file__).with_name("schema.sql")


def connect(db_path: str | Path) -> sqlite3.Connection:
    conn = sqlite3.connect(str(db_path))
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA journal_mode = WAL")
    return conn


def init_db(conn: sqlite3.Connection) -> None:
    conn.executescript(SCHEMA_PATH.read_text())
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
        "VALUES (?,?,?,?) ON CONFLICT(id) DO UPDATE SET "
        "display_name=excluded.display_name, country=excluded.country, "
        "timezone=excluded.timezone",
        (inst.id, inst.display_name, inst.country, inst.timezone),
    )


def upsert_account(conn, a: Account) -> None:
    conn.execute(
        "INSERT INTO account (id, institution_id, display_name, account_type, "
        "primary_currency, balance_group, masked_number, is_own_account, "
        "opened_on, closed_on, notes) VALUES (?,?,?,?,?,?,?,?,?,?,?) "
        "ON CONFLICT(id) DO UPDATE SET display_name=excluded.display_name, "
        "account_type=excluded.account_type, primary_currency=excluded.primary_currency, "
        "balance_group=excluded.balance_group, masked_number=excluded.masked_number, "
        "is_own_account=excluded.is_own_account, notes=excluded.notes",
        (a.id, a.institution_id, a.display_name, a.account_type.value,
         a.primary_currency, a.balance_group, a.masked_number,
         int(a.is_own_account), _iso(a.opened_on), _iso(a.closed_on), a.notes),
    )


def upsert_card(conn, c: Card) -> None:
    conn.execute(
        "INSERT INTO card (id, account_id, cardholder_name, last4, "
        "is_supplementary, issued_on, closed_on) VALUES (?,?,?,?,?,?,?) "
        "ON CONFLICT(id) DO UPDATE SET cardholder_name=excluded.cardholder_name, "
        "last4=excluded.last4, is_supplementary=excluded.is_supplementary",
        (c.id, c.account_id, c.cardholder_name, c.last4,
         int(c.is_supplementary), _iso(c.issued_on), _iso(c.closed_on)),
    )


def load_accounts(conn) -> dict[str, Account]:
    out = {}
    for r in conn.execute("SELECT * FROM account"):
        out[r["id"]] = Account(
            id=r["id"], institution_id=r["institution_id"],
            display_name=r["display_name"], account_type=r["account_type"],
            primary_currency=r["primary_currency"], balance_group=r["balance_group"],
            masked_number=r["masked_number"], is_own_account=bool(r["is_own_account"]),
            notes=r["notes"],
        )
    return out


def load_cards(conn) -> list[Card]:
    return [
        Card(id=r["id"], account_id=r["account_id"],
             cardholder_name=r["cardholder_name"], last4=r["last4"],
             is_supplementary=bool(r["is_supplementary"]))
        for r in conn.execute("SELECT * FROM card")
    ]


# ---------------------------------------------------------------------------
# Files & raw records
# ---------------------------------------------------------------------------

def file_already_imported(conn, sha256: str) -> bool:
    row = conn.execute(
        "SELECT 1 FROM statement_file WHERE file_sha256=?", (sha256,)).fetchone()
    return row is not None


def insert_statement_file(conn, sf: StatementFile) -> None:
    conn.execute(
        "INSERT INTO statement_file (id, source_path, file_sha256, institution_id, "
        "account_id, file_format, parser_id, parser_version, period_start, "
        "period_end, statement_date, imported_at, row_count) "
        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
        (sf.id, sf.source_path, sf.file_sha256, sf.institution_id, sf.account_id,
         sf.file_format.value, sf.parser_id, sf.parser_version,
         _iso(sf.period_start), _iso(sf.period_end), _iso(sf.statement_date),
         _iso(sf.imported_at), sf.row_count),
    )


def insert_raw_records(conn, records: Iterable[RawRecord]) -> None:
    conn.executemany(
        "INSERT OR IGNORE INTO raw_record (id, statement_file_id, line_no, "
        "payload, row_sha256) VALUES (?,?,?,?,?)",
        [(r.id, r.statement_file_id, r.line_no, json.dumps(r.payload, default=str),
          r.row_sha256) for r in records],
    )


# ---------------------------------------------------------------------------
# Transactions
# ---------------------------------------------------------------------------

def insert_txns(conn, txns: Sequence[Txn]) -> int:
    rows = []
    for t in txns:
        rows.append((
            t.id, t.account_id, t.card_id, _iso(t.txn_date), _iso(t.posted_date),
            t.status.value, t.booked.amount, t.booked.currency,
            t.native.amount if t.native else None,
            t.native.currency if t.native else None,
            str(t.fx_rate) if t.fx_rate is not None else None,
            t.fx_fee.amount if t.fx_fee else None,
            t.description_raw, t.description_norm, t.merchant, t.counterparty,
            t.external_ref, t.kind.value, t.category, t.subcategory,
            t.transfer_group_id, t.duplicate_of_id, t.dedup_key,
            t.statement_file_id, t.raw_record_id, t.review_state, t.notes,
            _iso(t.created_at), _iso(t.updated_at),
        ))
    conn.executemany(
        "INSERT OR REPLACE INTO txn (id, account_id, card_id, txn_date, posted_date, "
        "status, amount_booked, currency_booked, amount_native, currency_native, "
        "fx_rate, fx_fee_booked, description_raw, description_norm, merchant, "
        "counterparty, external_ref, kind, category, subcategory, transfer_group_id, "
        "duplicate_of_id, dedup_key, statement_file_id, raw_record_id, review_state, "
        "notes, created_at, updated_at) VALUES (" + ",".join("?" * 29) + ")",
        rows,
    )
    return len(rows)


def load_txns(conn, *, include_duplicates: bool = False) -> list[Txn]:
    sql = "SELECT * FROM txn"
    if not include_duplicates:
        sql += " WHERE duplicate_of_id IS NULL"
    out = []
    for r in conn.execute(sql):
        out.append(Txn(
            id=r["id"], account_id=r["account_id"], card_id=r["card_id"],
            txn_date=date.fromisoformat(r["txn_date"]),
            posted_date=date.fromisoformat(r["posted_date"]) if r["posted_date"] else None,
            status=r["status"],
            booked=Money(amount=r["amount_booked"], currency=r["currency_booked"]),
            native=(Money(amount=r["amount_native"], currency=r["currency_native"])
                    if r["amount_native"] is not None else None),
            fx_rate=Decimal(r["fx_rate"]) if r["fx_rate"] else None,
            fx_fee=(Money(amount=r["fx_fee_booked"], currency=r["currency_booked"])
                    if r["fx_fee_booked"] is not None else None),
            description_raw=r["description_raw"], description_norm=r["description_norm"],
            merchant=r["merchant"], counterparty=r["counterparty"],
            external_ref=r["external_ref"], kind=r["kind"], category=r["category"],
            subcategory=r["subcategory"], transfer_group_id=r["transfer_group_id"],
            duplicate_of_id=r["duplicate_of_id"], dedup_key=r["dedup_key"],
            statement_file_id=r["statement_file_id"], raw_record_id=r["raw_record_id"],
            review_state=r["review_state"], notes=r["notes"],
        ))
    return out


def update_txn_links(conn, txns: Sequence[Txn]) -> None:
    """Persist only the fields the dedup/transfer passes mutate."""
    conn.executemany(
        "UPDATE txn SET duplicate_of_id=?, transfer_group_id=?, kind=?, "
        "updated_at=? WHERE id=?",
        [(t.duplicate_of_id, t.transfer_group_id, t.kind.value,
          datetime.now().isoformat(), t.id) for t in txns],
    )


# ---------------------------------------------------------------------------
# Transfers & candidates
# ---------------------------------------------------------------------------

def insert_transfer_groups(conn, groups: Sequence[TransferGroup]) -> None:
    for g in groups:
        conn.execute(
            "INSERT OR REPLACE INTO transfer_group (id, kind, match_method, "
            "confidence, fee_booked, fee_currency, is_confirmed, notes, created_at) "
            "VALUES (?,?,?,?,?,?,?,?,?)",
            (g.id, g.kind.value, g.match_method, g.confidence,
             g.fee.amount if g.fee else None, g.fee.currency if g.fee else None,
             int(g.is_confirmed), g.notes, _iso(g.created_at)),
        )
        conn.executemany(
            "INSERT OR REPLACE INTO transfer_leg (transfer_group_id, txn_id, role) "
            "VALUES (?,?,?)",
            [(g.id, l.txn_id, l.role) for l in g.legs],
        )


def insert_transfer_candidates(conn, cands: Sequence[TransferCandidate]) -> None:
    conn.executemany(
        "INSERT OR IGNORE INTO transfer_candidate (id, out_txn_id, in_txn_id, "
        "score, date_delta, amount_delta, reasons, resolution, created_at) "
        "VALUES (?,?,?,?,?,?,?,?,?)",
        [(c.id, c.out_txn_id, c.in_txn_id, c.score, c.date_delta, c.amount_delta,
          json.dumps(c.reasons), c.resolution, _iso(c.created_at)) for c in cands],
    )


def insert_duplicate_candidates(conn, cands: Sequence[DuplicateCandidate]) -> None:
    conn.executemany(
        "INSERT OR IGNORE INTO duplicate_candidate (id, keep_txn_id, dupe_txn_id, "
        "score, reasons, resolution, created_at) VALUES (?,?,?,?,?,?,?)",
        [(c.id, c.keep_txn_id, c.dupe_txn_id, c.score, json.dumps(c.reasons),
          c.resolution, _iso(c.created_at)) for c in cands],
    )


# ---------------------------------------------------------------------------
# FX
# ---------------------------------------------------------------------------

def upsert_fx_rate(conn, r: FxRate) -> None:
    conn.execute(
        "INSERT OR REPLACE INTO fx_rate (rate_date, base, quote, rate, source) "
        "VALUES (?,?,?,?,?)",
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
            "SELECT rate FROM fx_rate WHERE base=? AND quote=? AND rate_date<=? "
            "ORDER BY rate_date DESC LIMIT 1", (base, quote, _iso(d))).fetchone()
        if row:
            return Decimal(row["rate"])
        row = conn.execute(
            "SELECT rate FROM fx_rate WHERE base=? AND quote=? AND rate_date<=? "
            "ORDER BY rate_date DESC LIMIT 1", (quote, base, _iso(d))).fetchone()
        if row and Decimal(row["rate"]) != 0:
            return Decimal(1) / Decimal(row["rate"])
        return None
    return lookup
