"""Import review: checks before a statement is written, and a pass after.

Before commit (``preflight``) the parse result is compared with the ledger:
is this file or its content already in, which account does it land in, does it
overlap or leave a gap after the last statement, are the dates, signs and
currencies plausible. These checks are deterministic. An optional model
verdict reads them with a sample of rows and says whether to go ahead.

After commit (``review_statement``) the new rows are categorised, named and
tagged by the classification model, card payments filed as spending are put
back under transfers, and the links the reconcile made (or could not make)
are laid out: unlinked payments, open transfer and duplicate candidates,
balance checks. The analysis model then writes a short summary with next
actions. The model never edits amounts or resolves a match; it categorises
within the closed taxonomy and adjusts candidate scores within the band the
deterministic thresholds allow.
"""

from __future__ import annotations

import json
import uuid
from collections import Counter, defaultdict
from datetime import date, datetime, timedelta, timezone
from typing import Any

from . import db as dbm
from .llm.provider import LLMProvider, LLMUnavailable
from .reporting import MOVEMENT_KINDS

LEVELS = ("ok", "info", "warn", "bad")

# Kind -> the category a movement row belongs under.
_MOVEMENT_CATEGORY = {
    "cc_payment": ("transfers", "card_payment"),
    "fx_conversion": ("transfers", "fx"),
}


def _check(cid: str, level: str, title: str, detail: str = "") -> dict:
    return {"id": cid, "level": level, "title": title, "detail": detail}


def _grade(checks: list[dict]) -> str:
    return max((c["level"] for c in checks), key=LEVELS.index, default="ok")


def _fmt(amount_minor: int, currency: str) -> str:
    return f"{amount_minor / 100:,.2f} {currency}"


# ---------------------------------------------------------------------------
# Before commit
# ---------------------------------------------------------------------------

def preflight(conn, result, *, parser_id: str, digest: str,
              account_id: str | None, today: date | None = None) -> dict:
    """Deterministic checks of a parse result against the ledger."""
    from .ingest import statement_content_fingerprint
    from .models import normalize_alias

    today = today or date.today()
    checks: list[dict] = []
    txns = list(result.txns)

    if dbm.file_already_imported(conn, digest):
        checks.append(_check(
            "duplicate_file", "bad", "This file is already imported",
            "Importing it again changes nothing."))
    elif dbm.statement_content_imported(
            conn, statement_content_fingerprint(parser_id, result)):
        checks.append(_check(
            "duplicate_content", "bad", "This statement's content is already imported",
            "Another copy of the same statement is in the ledger, so it will be skipped."))

    # Where the rows land: the chosen account, the parser's, or per-row hints.
    accounts = dbm.load_accounts(conn)
    alias_index = dbm.load_account_alias_index(conn)
    resolved = account_id or result.account_id
    routed: list[str | None] = []
    for p in txns:
        hint = (p.extra or {}).get("account_hint", "")
        routed.append((alias_index.get(normalize_alias(hint)) if hint else None)
                      or resolved)
    missing = sum(1 for a in routed if not a)
    if txns and missing:
        checks.append(_check(
            "account", "bad", f"No account for {missing} of {len(txns)} rows",
            "Choose the account this statement belongs to."))
    elif txns:
        names = sorted({accounts[a].display_name if a in accounts else a for a in routed if a})
        checks.append(_check("account", "ok", "Goes to " + ", ".join(names)))

    by_account: dict[str, list] = defaultdict(list)
    for p, a in zip(txns, routed):
        if a:
            by_account[a].append(p)

    for acct, rows in by_account.items():
        name = accounts[acct].display_name if acct in accounts else acct
        dates = sorted(p.txn_date for p in rows)
        lo, hi = dates[0], dates[-1]

        # Rows already present: same account, day and amount.
        existing = Counter(
            (r["txn_date"], r["amount_booked"]) for r in conn.execute(
                "SELECT txn_date, amount_booked FROM txn WHERE account_id=%s "
                "AND txn_date BETWEEN %s AND %s AND duplicate_of_id IS NULL "
                "AND status <> 'void'", (acct, lo.isoformat(), hi.isoformat())))
        matched = 0
        for p in rows:
            key = (p.txn_date.isoformat(), p.booked.amount)
            if existing[key] > 0:
                existing[key] -= 1
                matched += 1
        if matched == len(rows):
            checks.append(_check(
                f"present:{acct}", "warn", f"All {len(rows)} {name} rows are already in the ledger",
                "They will merge as duplicates; nothing new would be added."))
        elif matched:
            checks.append(_check(
                f"present:{acct}", "info",
                f"{matched} of {len(rows)} {name} rows match rows already in the ledger",
                "Overlapping rows merge as duplicates rather than counting twice."))

        # Continuity with the account's previous statements.
        start = result.period_start or lo
        end = result.period_end or hi
        prior = conn.execute(
            "SELECT MAX(COALESCE(sf.period_end, t.hi)) AS last_end FROM statement_file sf "
            "LEFT JOIN LATERAL (SELECT MAX(txn_date) AS hi FROM txn "
            "  WHERE statement_file_id = sf.id) t ON TRUE "
            "WHERE sf.account_id=%s AND COALESCE(sf.period_end, t.hi) < %s",
            (acct, start.isoformat())).fetchone()
        last_end = prior and prior["last_end"]
        if last_end:
            last = date.fromisoformat(str(last_end)[:10])
            if (start - last).days > 7:
                checks.append(_check(
                    f"gap:{acct}", "warn",
                    f"Gap before this statement for {name}",
                    f"Nothing is imported between {last.isoformat()} and {start.isoformat()}."))
        overlap = conn.execute(
            "SELECT COUNT(*) AS n FROM statement_file WHERE account_id=%s "
            "AND period_start IS NOT NULL AND period_end IS NOT NULL "
            "AND period_start <= %s AND period_end >= %s",
            (acct, end.isoformat(), start.isoformat())).fetchone()["n"]
        if overlap:
            checks.append(_check(
                f"overlap:{acct}", "info", f"Overlaps {overlap} earlier {name} statement(s)",
                "Expected for exports that straddle a statement date."))

        # Currencies the account can settle in.
        allowed = set(accounts[acct].settlement_currencies or [accounts[acct].primary_currency]) \
            if acct in accounts else set()
        foreign = sorted({p.booked.currency for p in rows} - allowed) if allowed else []
        if foreign:
            checks.append(_check(
                f"currency:{acct}", "warn",
                f"{name} does not settle in {', '.join(foreign)}",
                "Check the account or the currency default."))

    future = [p for p in txns if p.txn_date > today + timedelta(days=1)]
    if future:
        checks.append(_check(
            "future_dates", "bad", f"{len(future)} rows are dated in the future",
            "A day/month swap usually does this; check the first rows below."))
    if result.period_start and result.period_end:
        outside = [p for p in txns if not (
            result.period_start - timedelta(days=3) <= p.txn_date
            <= result.period_end + timedelta(days=3))]
        if outside:
            checks.append(_check(
                "period", "warn", f"{len(outside)} rows fall outside the statement period",
                f"Period {result.period_start} → {result.period_end}."))

    if len(txns) >= 6:
        signs = {p.booked.amount > 0 for p in txns}
        if len(signs) == 1:
            direction = "money in" if signs.pop() else "money out"
            checks.append(_check(
                "signs", "warn", f"Every row is {direction}",
                "Statements usually mix both; check the sign convention."))

    repeats = Counter((p.txn_date, p.booked.amount, p.description_raw) for p in txns)
    repeated = sum(n - 1 for n in repeats.values() if n > 1)
    if repeated:
        checks.append(_check(
            "repeats", "info", f"{repeated} rows repeat within the file",
            "Same day, amount and description. Often genuine (two identical fares)."))

    if result.warnings:
        checks.append(_check(
            "parser", "warn", f"The parser raised {len(result.warnings)} warning(s)",
            "; ".join(result.warnings[:3])))

    if result.balances:
        checks.append(_check(
            "balances", "ok", f"{len(result.balances)} printed balance(s) to verify against",
            "Checked against the rows after import."))
    elif txns:
        checks.append(_check(
            "balances", "info", "No printed balances",
            "Completeness cannot be proven from this file alone."))

    if not txns and not result.allow_empty:
        checks.append(_check("empty", "bad", "No rows were read from this file"))

    return {"grade": _grade(checks), "checks": checks}


VERDICT_SCHEMA = {
    "type": "object",
    "properties": {
        "verdict": {"type": "string", "enum": ["import", "review", "hold"]},
        "headline": {"type": "string"},
        "notes": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["verdict", "headline", "notes"],
    "additionalProperties": False,
}

VERDICT_SYSTEM = """You check a bank or card statement before it is imported \
into a personal finance ledger. You receive what the parser read (period, \
account, row count, totals, a sample of rows) and deterministic checks already \
run against the ledger.

Decide:
- "import": nothing suggests a misread or a duplicate.
- "review": something deserves a look before importing (say what to look at).
- "hold": importing would very likely corrupt the ledger (already imported, \
dates or signs clearly misread, rows with no account).

headline: one plain sentence for the person importing. notes: at most three \
short, specific observations that the checks do not already state, such as a \
column that looks shifted, an amount that looks like a balance, a merchant \
name that suggests the wrong account. Use an empty list when there is nothing \
to add. Do not restate the checks or invent facts beyond the data."""


def preflight_verdict(provider: LLMProvider, preview: dict) -> dict | None:
    """The analysis model's go/no-go on a preview, or None when unavailable."""
    sample = preview.get("sample") or []
    payload = {
        "file": preview.get("filename"),
        "parser": preview.get("parser"),
        "period": [preview.get("period_start"), preview.get("period_end")],
        "rows": preview.get("txn_count"),
        "totals": preview.get("totals"),
        "checks": [{k: c[k] for k in ("level", "title", "detail")}
                   for c in (preview.get("quality") or {}).get("checks", [])],
        "sample": [{k: s.get(k) for k in ("date", "description", "amount", "currency")}
                   for s in sample[:15]],
    }
    try:
        response = provider.complete_structured(
            VERDICT_SYSTEM, json.dumps(payload, default=str), VERDICT_SCHEMA)
    except (LLMUnavailable, ValueError):
        return None
    data = response.data if isinstance(response.data, dict) else {}
    if data.get("verdict") not in ("import", "review", "hold"):
        return None
    return {
        "verdict": data["verdict"],
        "headline": str(data.get("headline") or "")[:300],
        "notes": [str(n)[:300] for n in (data.get("notes") or [])][:3],
        "model": response.model,
    }


# ---------------------------------------------------------------------------
# After commit
# ---------------------------------------------------------------------------

SUMMARY_SCHEMA = {
    "type": "object",
    "properties": {
        "status": {"type": "string", "enum": ["clean", "attention"]},
        "headline": {"type": "string"},
        "notes": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["status", "headline", "notes"],
    "additionalProperties": False,
}

SUMMARY_SYSTEM = """You review a statement that was just imported into a \
personal finance ledger. You receive facts gathered after import: how its rows \
were categorised (by rule, by a model, still uncategorised), which money \
movements were linked to their other leg, open match candidates, balance \
checks, and fixes already applied.

status: "clean" when nothing needs the person's attention, else "attention".
headline: one plain sentence on the state of this import.
notes: at most four short, specific points worth acting on, most important \
first, naming merchants, amounts and accounts from the facts. Prefer what to do \
over what happened. An empty list when the import is clean. Use only the facts \
given."""


def _statement_rows(conn, statement_file_id: str) -> list[dict]:
    return [dict(r) for r in conn.execute(
        "SELECT t.id, t.account_id, t.txn_date, t.amount_booked, t.currency_booked, "
        "t.description_raw, t.description_norm, t.merchant, t.kind, t.category, "
        "t.subcategory, t.transfer_group_id, t.refund_of_id, t.installment_plan_id "
        "FROM txn t WHERE t.statement_file_id=%s AND t.duplicate_of_id IS NULL "
        "AND t.status <> 'void' ORDER BY t.txn_date, t.id", (statement_file_id,))]


def _fix_movement_categories(conn, rows: list[dict]) -> int:
    """Card payments and FX swaps belong under transfers, whatever matched them.

    A manual category is left alone: the person decided.
    """
    fixed = 0
    for r in rows:
        target = _MOVEMENT_CATEGORY.get(r["kind"])
        if not target or r["category"] == "transfers":
            continue
        manual = conn.execute(
            "SELECT 1 FROM txn_annotation WHERE txn_id=%s AND field='category' "
            "AND source='manual'", (r["id"],)).fetchone()
        if manual:
            continue
        conn.execute("UPDATE txn SET category=%s, subcategory=%s WHERE id=%s",
                     (*target, r["id"]))
        r["category"], r["subcategory"] = target
        fixed += 1
    return fixed


def review_statement(
    conn, statement_file_id: str, *,
    classify: LLMProvider, analyse: LLMProvider | None = None,
    user_id: str | None = None, max_merchants: int = 120,
) -> dict:
    """Categorise, tag and link-check one imported statement, then summarise."""
    from .integrity import check_account
    from .llm.adjudicate import adjudicate_duplicates, adjudicate_transfers
    from .llm.categorize import apply_merchants, apply_tags, apply_to_ledger

    statement = conn.execute(
        "SELECT id, source_path, account_id, period_start, period_end, imported_at "
        "FROM statement_file WHERE id=%s", (statement_file_id,)).fetchone()
    if statement is None:
        raise LookupError("statement file not found")
    rows = _statement_rows(conn, statement_file_id)
    ids = [r["id"] for r in rows]
    model_ready = classify.name not in ("null",)

    # 1. Categories, merchant names and tags for just these rows.
    movement_fixed = _fix_movement_categories(conn, rows)
    categorised = named = tagged = {}
    if model_ready and ids:
        categorised = apply_to_ledger(conn, classify, txn_ids=ids, max_merchants=max_merchants)
        named = apply_merchants(conn, classify, txn_ids=ids, max_merchants=max_merchants)
        tagged = apply_tags(conn, classify, txn_ids=ids, max_merchants=max_merchants)
    conn.commit()
    rows = _statement_rows(conn, statement_file_id)

    sources = {r["txn_id"]: r["source"] for r in conn.execute(
        "SELECT txn_id, source FROM txn_annotation WHERE field='category' "
        "AND txn_id = ANY(%s)", (ids,))}
    by_source = Counter()
    uncategorised: Counter = Counter()
    for r in rows:
        if r["category"] is None or r["subcategory"] == "uncategorised":
            by_source["uncategorised"] += 1
            uncategorised[r["merchant"] or r["description_raw"]] += 1
        elif sources.get(r["id"]) == "llm":
            by_source["model"] += 1
        elif sources.get(r["id"]) == "manual":
            by_source["manual"] += 1
        else:
            by_source["rules"] += 1
    tag_count = conn.execute(
        "SELECT COUNT(*) AS n FROM txn_tag WHERE txn_id = ANY(%s)", (ids,)).fetchone()["n"]

    # 2. Links: let the model weigh in-band candidates, then report the state.
    adjudication: dict[str, Any] = {}
    if model_ready:
        adjudication = {
            "transfers": adjudicate_transfers(conn, classify),
            "duplicates": adjudicate_duplicates(conn, classify),
        }
    movement = [r for r in rows if r["kind"] in (*MOVEMENT_KINDS, "transfer")]
    unlinked = [r for r in movement if not r["transfer_group_id"]]
    # Every row the file produced, including ones already merged as duplicates.
    all_ids = [r["id"] for r in conn.execute(
        "SELECT id FROM txn WHERE statement_file_id=%s", (statement_file_id,))]
    open_transfers = conn.execute(
        "SELECT COUNT(*) AS n FROM transfer_candidate WHERE resolution='open' "
        "AND (out_txn_id = ANY(%s) OR in_txn_id = ANY(%s))", (all_ids, all_ids)).fetchone()["n"]
    open_duplicates = conn.execute(
        "SELECT COUNT(*) AS n FROM duplicate_candidate WHERE resolution='open' "
        "AND (keep_txn_id = ANY(%s) OR dupe_txn_id = ANY(%s))",
        (all_ids, all_ids)).fetchone()["n"]
    merged_duplicates = len(all_ids) - len(rows)

    # 3. Balance checks over the statement's period, per account it touched.
    accounts = sorted({r["account_id"] for r in rows} | {statement["account_id"]})
    names = {a.id: a.display_name for a in dbm.load_accounts(conn).values()}
    start = str(statement["period_start"] or (rows[0]["txn_date"] if rows else ""))[:10]
    end = str(statement["period_end"] or (rows[-1]["txn_date"] if rows else ""))[:10]
    balance_problems = []
    for acct in accounts:
        for c in check_account(conn, acct, record=False):
            if c.get("status") != "discrepancy":
                continue
            if start and end and not (str(c["period_start"]) <= end
                                      and str(c["period_end"]) >= start):
                continue
            balance_problems.append({
                "account_id": acct, "account_name": names.get(acct, acct),
                "period_start": str(c["period_start"]), "period_end": str(c["period_end"]),
                "discrepancy": c["discrepancy"],
            })

    facts = {
        "statement": {
            "id": statement["id"], "file": statement["source_path"],
            "accounts": [names.get(a, a) for a in accounts],
            "period": [start or None, end or None], "rows": len(rows),
        },
        "categories": {
            "by_source": dict(by_source),
            "movement_fixed": movement_fixed,
            "model_applied": categorised.get("applied", 0),
            "low_confidence": categorised.get("skipped_low_confidence", 0),
            "merchants_named": named.get("named", 0),
            "tagged_rows": tagged.get("tagged", 0),
            "tags": tag_count,
            "top_uncategorised": [
                {"description": d, "rows": n} for d, n in uncategorised.most_common(8)],
        },
        "links": {
            "movements": len(movement),
            "linked": len(movement) - len(unlinked),
            "unlinked": [{
                "id": r["id"], "date": r["txn_date"], "account": names.get(r["account_id"]),
                "description": r["merchant"] or r["description_raw"],
                "amount": _fmt(r["amount_booked"], r["currency_booked"]),
                "kind": r["kind"],
            } for r in unlinked[:10]],
            "unlinked_count": len(unlinked),
            "open_transfer_candidates": open_transfers,
            "open_duplicate_candidates": open_duplicates,
            "merged_duplicates": merged_duplicates,
            "refunds_linked": sum(1 for r in rows if r["refund_of_id"]),
            "instalment_rows": sum(1 for r in rows if r["installment_plan_id"]),
            "adjudication": adjudication,
        },
        "balances": balance_problems,
        "model": {"available": model_ready,
                  "classification": getattr(classify, "model", None)},
    }

    summary = None
    if analyse is not None and analyse.name != "null":
        try:
            response = analyse.complete_structured(
                SUMMARY_SYSTEM, json.dumps(facts, default=str), SUMMARY_SCHEMA)
            data = response.data if isinstance(response.data, dict) else {}
            if data.get("status") in ("clean", "attention"):
                summary = {
                    "status": data["status"],
                    "headline": str(data.get("headline") or "")[:300],
                    "notes": [str(n)[:300] for n in (data.get("notes") or [])][:4],
                    "model": response.model,
                }
        except (LLMUnavailable, ValueError):
            summary = None
    if summary is None:
        summary = _fallback_summary(facts)
    report = {**facts, "summary": summary,
              "reviewed_at": datetime.now(timezone.utc).isoformat()}

    conn.execute(
        "INSERT INTO agent_operation (id, subject, action, user_id, applied, result, created_at) "
        "VALUES (%s,%s,%s,%s,%s,%s::jsonb,%s)",
        (str(uuid.uuid4()), f"statement:{statement_file_id}", "import_review", user_id,
         1, json.dumps(report, default=str), report["reviewed_at"]))
    conn.commit()
    return report


def _fallback_summary(facts: dict) -> dict:
    """A plain summary when no analysis model is available."""
    cats, links = facts["categories"], facts["links"]
    notes = []
    if cats["by_source"].get("uncategorised"):
        notes.append(f"{cats['by_source']['uncategorised']} rows are still uncategorised.")
    if links["unlinked_count"]:
        notes.append(f"{links['unlinked_count']} payments or transfers have no linked other leg.")
    if links["open_transfer_candidates"] or links["open_duplicate_candidates"]:
        notes.append("Matching suggestions are waiting for a decision.")
    if facts["balances"]:
        notes.append(f"{len(facts['balances'])} balance check(s) disagree with the rows.")
    return {
        "status": "attention" if notes else "clean",
        "headline": "Needs a look." if notes else "Imported cleanly.",
        "notes": notes, "model": None,
    }


def latest_review(conn, statement_file_id: str) -> dict | None:
    row = conn.execute(
        "SELECT result FROM agent_operation WHERE action='import_review' AND subject=%s "
        "ORDER BY created_at DESC LIMIT 1", (f"statement:{statement_file_id}",)).fetchone()
    if not row:
        return None
    value = row["result"]
    return value if isinstance(value, dict) else json.loads(value)
