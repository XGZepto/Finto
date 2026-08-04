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
import json
import re
from collections.abc import Iterable, Sequence
from datetime import date
from pathlib import Path

from . import db as dbm
from .dedup import run_dedup
from .installments import find_installments, find_origination_pairs
from .integrity import (
    check_all,
    find_violations,
    prune_orphan_transfer_groups,
    record_balance,
    resolve_duplicate_chains,
)
from .models import (
    Card,
    FileFormat,
    Money,
    ParsedTxn,
    RawRecord,
    StatementFile,
    TransferGroup,
    TransferKind,
    TransferLeg,
    Txn,
    TxnKind,
    normalize_alias,
)
from .parsers import institutions as _institutions  # noqa: F401 (registers parsers)
from .parsers import pdf as _pdf_parser  # noqa: F401 (registers the PDF parser)
from .parsers.base import ParseContext, select_parser
from .refunds import apply_refund_links, find_refunds
from .transfers import find_transfers, transfer_group_id


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _institution_of(conn, account_id: str | None) -> str | None:
    """The account map is the authority on which institution a file belongs to;
    parsers only guess. Feeding it into the ParseContext is also what tells
    institution-agnostic parsers (AMEX writes US-style dates in every market)
    which convention a specific export uses.
    """
    if account_id is None:
        return None
    row = conn.execute(
        "SELECT institution_id FROM account WHERE id=?", (account_id,)
    ).fetchone()
    return row["institution_id"] if row else None


def _format_of(path: Path) -> FileFormat:
    ext = path.suffix.lower().lstrip(".")
    try:
        return FileFormat(ext)
    except ValueError:
        return FileFormat.CSV


def resolve_card(parsed: ParsedTxn, account_id: str, cards: list[Card]) -> str | None:
    """Attribute a charge to a specific card on the account.

    Prefers last4 (unambiguous) and falls back to the cardholder name, which is
    what rescues attribution across a reissue: the number changes, the name
    doesn't. Cards are date-scoped by issued_on/closed_on when those are set, so
    a reissued number can't claim charges made before it existed.

    Name matching is intentionally exact-first: a substring match is a last
    resort, because a short hint like "JO" would otherwise claim "JOANNA CHAN".
    """
    scoped = [c for c in cards if c.account_id == account_id
              and _card_active_on(c, parsed.txn_date)]
    if not scoped:
        return None

    if parsed.card_last4:
        for c in scoped:
            if c.last4 and c.last4 == parsed.card_last4:
                return c.id

    if parsed.cardholder_hint:
        want = re.sub(r"[^a-z]", "", parsed.cardholder_hint.lower())
        if want:
            names = [(c, re.sub(r"[^a-z]", "", c.cardholder_name.lower()))
                     for c in scoped]
            for c, have in names:
                if want == have:
                    return c.id
            partial = [c for c, have in names
                       if have and (want in have or have in want)]
            # Only trust a substring match when it is unambiguous.
            if len(partial) == 1:
                return partial[0].id
    return None


def _card_active_on(card: Card, when) -> bool:
    if card.issued_on and when < card.issued_on:
        return False
    if card.closed_on and when > card.closed_on:
        return False
    return True


def unattributed_card_warnings(
    parsed: Sequence[ParsedTxn], txns: Sequence[Txn], account_id: str,
    cards: list[Card],
) -> list[str]:
    """Flag card numbers the statement used that no registered card matches.

    Without this, a reissued card fails attribution silently: card_id is simply
    NULL and per-cardholder reporting quietly under-reports until you happen to
    notice the totals don't add up.
    """
    if not any(c.account_id == account_id for c in cards):
        return []   # no cards registered for this account at all — nothing to say
    unknown = sorted({p.card_last4 for p, t in zip(parsed, txns)
                      if t.card_id is None and p.card_last4})
    if not unknown:
        return []
    return [f"last4 {', '.join(unknown)} matched no registered card on "
            f"{account_id}. If the card was reissued, add it to accounts.yaml "
            f"with replaces_card_id pointing at the card it replaced."]


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
        details=parsed.details,
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
        institution_id=institution_id or _institution_of(conn, account_id),
        account_id=account_id,
        default_currency=default_currency,
    )
    parser = select_parser(ctx)
    if parser is None:
        return {"path": str(path), "status": "error", "reason": "no parser matched"}

    result = parser.parse(ctx)
    if not result.txns and not result.allow_empty:
        # Deliberately do NOT record the file. Recording it would burn its
        # sha256, and `file_already_imported` would then refuse the re-import
        # forever — so a statement you believe is in the ledger never would be.
        # allow_empty is the exception: a verified extraction proves the month
        # was genuinely idle, so it imports with zero rows like any other.
        return {"path": str(path), "status": "error",
                "reason": f"{parser.parser_id} parsed 0 transactions — file not "
                          "recorded, so you can re-import once this is resolved",
                "warnings": result.warnings[:10]}

    resolved_account = account_id or result.account_id

    # Consolidated statements (Chase checking+savings, HSBC One HKD+CNY, Mox
    # HKD+JPY) carry rows for several accounts in one file. Each row's
    # account_hint resolves to an account through the alias registry; rows
    # whose hint resolves nothing fall back to the file's account.
    alias_index = dbm.load_account_alias_index(conn)

    def route(hint: str) -> str | None:
        return alias_index.get(normalize_alias(hint)) if hint else None

    routed = [route((p.extra or {}).get("account_hint", "")) for p in result.txns]
    # Idle consolidated months have no rows but still carry per-account
    # balance figures — use those hints so the file can import without
    # a forced --account.
    balance_routes = [
        route(hint) for _as_of, _bal, hint in result.balances if hint
    ]
    sf_account = (
        resolved_account
        or next((a for a in routed if a), None)
        or next((a for a in balance_routes if a), None)
    )
    if sf_account is None:
        return {"path": str(path), "status": "error",
                "reason": "account_id unknown — pass --account"}

    unrouted = [p for p, a in zip(result.txns, routed) if a is None
                and resolved_account is None]
    if unrouted:
        return {"path": str(path), "status": "error",
                "reason": f"{len(unrouted)} rows carry no resolvable account hint "
                          f"and no --account was given — refusing to guess"}

    sf = StatementFile(
        source_path=str(path),
        file_sha256=digest,
        institution_id=ctx.institution_id or parser.institution_id,
        account_id=sf_account,
        file_format=_format_of(path),
        parser_id=parser.parser_id,
        parser_version=parser.version,
        period_start=result.period_start,
        period_end=result.period_end,
        statement_date=result.statement_date,
        row_count=len(result.txns),
    )

    # The PDF parser is institution-agnostic ("generic"), so a PDF import
    # without an account mapping would fail its foreign-key check against
    # `institution`. The template that matched knows the issuer; use it.
    if sf.institution_id == "generic" and path.suffix.lower() == ".pdf":
        from .pdf.extract import extract_document
        from .pdf.registry import select_template
        try:
            tpl, _ = select_template(extract_document(path))
            if tpl is not None:
                sf.institution_id = tpl.institution_id
        except Exception:
            pass

    raws = [RawRecord(statement_file_id=sf.id, line_no=i, payload=row)
            for i, row in enumerate(result.raw_rows)]
    raw_by_line = {r.line_no: r.id for r in raws}
    cards = dbm.load_cards(conn)

    txns = [
        to_txn(p, account_id=routed[i] or resolved_account, statement_file_id=sf.id,
               raw_record_id=raw_by_line.get(p.line_no), cards=cards)
        for i, p in enumerate(result.txns)
    ]

    warnings = list(result.warnings)
    warnings += unattributed_card_warnings(result.txns, txns, resolved_account, cards)

    if dry_run:
        return {"path": str(path), "status": "dry-run", "parser": parser.parser_id,
                "txns": len(txns), "warnings": warnings[:10]}

    dbm.insert_statement_file(conn, sf)
    dbm.insert_raw_records(conn, raws)
    apply_category_rules(conn, txns)
    dbm.insert_txns(conn, txns)

    # Store the statement's own balance figures — the independent check that we
    # captured every row. Consolidated files route each figure to its account.
    for as_of, bal, hint in result.balances:
        record_balance(conn, account_id=route(hint) or sf_account, as_of=as_of,
                       balance=bal, source="statement_running",
                       statement_file_id=sf.id)
    conn.commit()

    return {"path": str(path), "status": "imported", "parser": parser.parser_id,
            "txns": len(txns), "balances": len(result.balances),
            "warnings": warnings[:10]}


def reattribute_cards(conn) -> int:
    """Re-resolve card_id for every transaction using the current card registry.

    card_id is assigned at ingest time, so registering a reissued card afterwards
    leaves everything imported before it unattributed. Re-running resolution
    against the stored raw rows fixes that without re-importing anything.
    """
    cards = dbm.load_cards(conn)
    if not cards:
        return 0
    updates: list[tuple[str | None, str]] = []
    for r in conn.execute(
            "SELECT t.id, t.account_id, t.txn_date, t.card_id, t.description_raw, "
            "       rr.payload "
            "FROM txn t LEFT JOIN raw_record rr ON rr.id = t.raw_record_id"):
        payload = json.loads(r["payload"]) if r["payload"] else {}
        last4 = (payload.get("Account #") or payload.get("Card Number") or "")[-4:]
        hint = payload.get("Card Member") or payload.get("Cardholder") or None
        parsed = ParsedTxn(
            txn_date=date.fromisoformat(r["txn_date"]),
            booked=Money(amount=0, currency="XXX"),
            description_raw=r["description_raw"],
            card_last4=last4 or None,
            cardholder_hint=hint,
        )
        resolved = resolve_card(parsed, r["account_id"], cards)
        if resolved != r["card_id"]:
            updates.append((resolved, r["id"]))
    conn.executemany("UPDATE txn SET card_id=? WHERE id=?", updates)
    return len(updates)


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
    from .transfers import TransferContext
    tr_ctx = TransferContext(
        self_aliases=dbm.load_self_aliases(conn),
        account_aliases=dbm.load_account_alias_index(conn),
        person_aliases=dbm.load_person_aliases(conn),
    )
    tr = find_transfers(live, accounts, fx_lookup=dbm.make_fx_lookup(conn),
                        context=tr_ctx)
    dbm.insert_transfer_groups(conn, tr.groups)
    dbm.insert_transfer_candidates(conn, tr.candidates)

    # Instalment plans. Runs after dedup so a plan is never built out of two
    # copies of the same charge, and before refunds so an instalment reversal
    # is not mistaken for a merchant refund.
    inst = find_installments(live)
    dbm.insert_installment_plans(conn, inst.plans)
    dbm.insert_installment_candidates(conn, inst.candidates)
    for t in live:
        assignment = inst.assignments.get(t.id)
        if assignment:
            t.installment_plan_id, t.installment_seq = assignment
            t.kind = TxnKind.INSTALLMENT

    # Gross-then-reversed plan bookings net to zero; pair them so they do.
    origination_groups = []
    for charge, credit in find_origination_pairs(live):
        if charge.transfer_group_id or credit.transfer_group_id:
            continue
        gid = transfer_group_id([charge.id, credit.id])
        origination_groups.append(TransferGroup(
            id=gid, kind=TransferKind.INSTALLMENT_ORIGINATION,
            match_method="auto", confidence=0.95,
            legs=[TransferLeg(txn_id=charge.id, role="out"),
                  TransferLeg(txn_id=credit.id, role="in")]))
        for t in (charge, credit):
            t.transfer_group_id = gid
            t.kind = TxnKind.INSTALLMENT_ORIGINATION
    dbm.insert_transfer_groups(conn, origination_groups)

    # Refunds last: they need transfer links already set so a card payment is
    # never mistaken for a refund.
    refunds = find_refunds(live)
    refunds_linked = apply_refund_links(live, refunds)

    dbm.update_txn_links(conn, txns)
    conn.commit()

    summary = {
        "transactions": len(txns),
        "duplicates_merged": report.exact_merged,
        "duplicate_candidates": len(report.candidates),
        "transfers_linked": len(tr.groups),
        "transfer_candidates": len(tr.candidates),
        "installment_plans": len(inst.plans),
        "installment_candidates": len(inst.candidates),
        "installment_originations": len(origination_groups),
        "refunds_linked": refunds_linked,
        "refunds_unmatched": len(refunds.unmatched),
    }

    if use_llm:
        from .llm.adjudicate import adjudicate_duplicates, adjudicate_transfers
        from .llm.provider import build_provider
        provider = build_provider(conn)
        summary["llm_duplicates"] = adjudicate_duplicates(conn, provider)
        summary["llm_transfers"] = adjudicate_transfers(conn, provider)

    # Structural hygiene and the balance cross-check.
    summary["chains_collapsed"] = resolve_duplicate_chains(conn)
    summary["stale_groups_pruned"] = prune_orphan_transfer_groups(conn)
    summary["stale_plans_pruned"] = dbm.prune_orphan_installment_plans(conn)
    conn.commit()
    summary["violations"] = find_violations(conn)
    summary["balance_checks"] = [c for c in check_all(conn)
                                 if c.get("status") == "discrepancy"]
    return summary
