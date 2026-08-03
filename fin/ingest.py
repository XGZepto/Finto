"""Ingest orchestration: file -> parser -> canonical Txn -> SQLite.

Pipeline order matters and is fixed:

    hash file
      -> skip if already imported          (duplicate source #1)
      -> select parser by sniffing
      -> parse to ParsedTxn
      -> resolve account + supplementary card
      -> promote to Txn (normalise, compute dedup_key)
      -> categorise by rule
      -> persist raw rows + txns
      -> dedup pass across the whole ledger
      -> transfer matching pass

Dedup and transfer matching run over the FULL ledger, not just the new file,
because a duplicate or a transfer counterpart usually lives in a different
file from a different institution.
"""

from __future__ import annotations

import hashlib
import re
import uuid
from datetime import datetime
from pathlib import Path
from typing import Iterable

from . import db as dbm
from .dedup import run_dedup
from .integrity import check_all, find_violations, record_balance, resolve_duplicate_chains
from .models import (
    Account, Card, FileFormat, ParsedTxn, RawRecord, StatementFile, Txn, TxnKind,
)
from .parsers.base import ParseContext, select_parser
from .parsers import institutions as _institutions  # noqa: F401 (registers parsers)
from .transfers import find_transfers


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _format_of(path: Path) -> FileFormat:
    ext = path.suffix.lower().lstrip(".")
    try:
        return FileFormat(ext)
    except ValueError:
        return FileFormat.CSV


def resolve_card(parsed: ParsedTxn, account_id: str, cards: list[Card]) -> str | None:
    """Attribute a charge to a supplementary card.

    Prefers last4 (unambiguous) and falls back to a loose name match on the
    'Card Member' field, which AMEX writes in varying case and order.
    """
    scoped = [c for c in cards if c.account_id == account_id]
    if not scoped:
        return None
    if parsed.card_last4:
        for c in scoped:
            if c.last4 and c.last4 == parsed.card_last4:
                return c.id
    if parsed.cardholder_hint:
        want = re.sub(r"[^a-z]", "", parsed.cardholder_hint.lower())
        for c in scoped:
            have = re.sub(r"[^a-z]", "", c.cardholder_name.lower())
            if want and (want == have or want in have or have in want):
                return c.id
    return None


def to_txn(
    parsed: ParsedTxn,
    *,
    account_id: str,
    statement_file_id: str,
    raw_record_id: str | None,
    cards: list[Card],
) -> Txn:
    return Txn(
        account_id=account_id,
        card_id=resolve_card(parsed, account_id, cards),
        txn_date=parsed.txn_date,
        posted_date=parsed.posted_date,
        status=parsed.status,
        booked=parsed.booked,
        native=parsed.native,
        fx_rate=parsed.fx_rate,
        fx_fee=parsed.fx_fee,
        description_raw=parsed.description_raw,
        merchant=parsed.merchant,
        counterparty=parsed.counterparty,
        external_ref=parsed.external_ref,
        kind=parsed.kind_hint or TxnKind.UNKNOWN,
        statement_file_id=statement_file_id,
        raw_record_id=raw_record_id,
    )


def apply_category_rules(conn, txns: Iterable[Txn]) -> int:
    rules = list(conn.execute(
        "SELECT * FROM category_rule WHERE enabled=1 ORDER BY priority ASC"))
    if not rules:
        return 0
    touched = 0
    for t in txns:
        for r in rules:
            if r["account_id"] and r["account_id"] != t.account_id:
                continue
            field = {
                "description_norm": t.description_norm,
                "merchant": t.merchant or "",
                "counterparty": t.counterparty or "",
                "external_ref": t.external_ref or "",
            }[r["match_field"]]
            pat = r["pattern"]
            hit = (
                pat.upper() in field.upper() if r["match_type"] == "contains"
                else field.upper() == pat.upper() if r["match_type"] == "exact"
                else bool(re.search(pat, field, re.I))
            )
            if hit:
                if r["set_category"]:
                    t.category = r["set_category"]
                if r["set_subcategory"]:
                    t.subcategory = r["set_subcategory"]
                if r["set_kind"]:
                    t.kind = TxnKind(r["set_kind"])
                touched += 1
                break
    return touched


def ingest_file(
    conn,
    path: Path,
    *,
    institution_id: str | None = None,
    account_id: str | None = None,
    default_currency: str | None = None,
    dry_run: bool = False,
) -> dict:
    """Import one statement file. Returns a small result summary."""
    digest = sha256_file(path)
    if dbm.file_already_imported(conn, digest):
        return {"path": str(path), "status": "skipped", "reason": "already imported"}

    ctx = ParseContext(
        path=path,
        institution_id=institution_id,
        account_id=account_id,
        default_currency=default_currency,
    )
    parser = select_parser(ctx)
    if parser is None:
        return {"path": str(path), "status": "error", "reason": "no parser matched"}

    result = parser.parse(ctx)
    resolved_account = account_id or result.account_id
    if not resolved_account:
        return {"path": str(path), "status": "error",
                "reason": "account_id unknown — pass --account"}

    sf = StatementFile(
        source_path=str(path),
        file_sha256=digest,
        institution_id=institution_id or parser.institution_id,
        account_id=resolved_account,
        file_format=_format_of(path),
        parser_id=parser.parser_id,
        parser_version=parser.version,
        period_start=result.period_start,
        period_end=result.period_end,
        statement_date=result.statement_date,
        row_count=len(result.txns),
    )

    raws = [RawRecord(statement_file_id=sf.id, line_no=i, payload=row)
            for i, row in enumerate(result.raw_rows)]
    raw_by_line = {r.line_no: r.id for r in raws}
    cards = dbm.load_cards(conn)

    txns = [
        to_txn(p, account_id=resolved_account, statement_file_id=sf.id,
               raw_record_id=raw_by_line.get(p.line_no), cards=cards)
        for p in result.txns
    ]

    if dry_run:
        return {"path": str(path), "status": "dry-run", "parser": parser.parser_id,
                "txns": len(txns), "warnings": result.warnings[:10]}

    dbm.insert_statement_file(conn, sf)
    dbm.insert_raw_records(conn, raws)
    apply_category_rules(conn, txns)
    dbm.insert_txns(conn, txns)

    # Store the statement's own balance figures — the independent check that we
    # captured every row.
    for as_of, bal in result.balances:
        record_balance(conn, account_id=resolved_account, as_of=as_of,
                       balance=bal, source="statement_running",
                       statement_file_id=sf.id)
    conn.commit()

    return {"path": str(path), "status": "imported", "parser": parser.parser_id,
            "txns": len(txns), "balances": len(result.balances),
            "warnings": result.warnings[:10]}


def cross_account_dupe_pairs(conn) -> set[tuple[str, str]]:
    """Account pairs where a cross-account duplicate is genuinely plausible.

    Note on supplementary cards: they are NOT this case. Supplementary charges
    post to the parent account's statement, so they share an account_id and are
    handled by ordinary same-account dedup. What this whitelist is actually for
    is accounts sharing a `balance_group` — a Wise or Mox login whose per-
    currency balances we model as separate accounts, where the provider can
    report one movement under two of them.

    Everything else stays out deliberately: without this restriction, every
    genuine transfer between your own accounts would look like a duplicate.
    """
    accts = dbm.load_accounts(conn)
    pairs = set()
    groups: dict[str, list[str]] = {}
    for aid, a in accts.items():
        if a.balance_group:
            groups.setdefault(a.balance_group, []).append(aid)
    for members in groups.values():
        for i, x in enumerate(members):
            for y in members[i + 1:]:
                pairs.add(tuple(sorted((x, y))))
    return pairs


def reconcile(conn, *, use_llm: bool = False) -> dict:
    """Run dedup + transfer matching over the whole ledger.

    Order is deliberate: deterministic passes run first and settle everything
    they are confident about. The LLM, if enabled, only ever sees what is left
    over, and only adjusts scores — the merge decision stays with the
    deterministic threshold.
    """
    txns = dbm.load_txns(conn, include_duplicates=True)
    accounts = dbm.load_accounts(conn)

    report = run_dedup(txns, cross_account_pairs=cross_account_dupe_pairs(conn))
    dbm.insert_duplicate_candidates(conn, report.candidates)

    live = [t for t in txns if t.duplicate_of_id is None]
    tr = find_transfers(live, accounts, fx_lookup=dbm.make_fx_lookup(conn))
    dbm.insert_transfer_groups(conn, tr.groups)
    dbm.insert_transfer_candidates(conn, tr.candidates)
    dbm.update_txn_links(conn, txns)
    conn.commit()

    summary = {
        "transactions": len(txns),
        "duplicates_merged": report.exact_merged,
        "duplicate_candidates": len(report.candidates),
        "transfers_linked": len(tr.groups),
        "transfer_candidates": len(tr.candidates),
    }

    if use_llm:
        from .llm.adjudicate import adjudicate_duplicates, adjudicate_transfers
        from .llm.provider import build_provider
        provider = build_provider(conn)
        summary["llm_duplicates"] = adjudicate_duplicates(conn, provider)
        summary["llm_transfers"] = adjudicate_transfers(conn, provider)

    # Structural hygiene and the balance cross-check.
    summary["chains_collapsed"] = resolve_duplicate_chains(conn)
    summary["violations"] = find_violations(conn)
    summary["balance_checks"] = [c for c in check_all(conn)
                                 if c.get("status") == "discrepancy"]
    return summary
