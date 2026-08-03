"""LLM adjudication of ambiguous duplicate and transfer candidates.

This one needs more care than categorisation, because a wrong answer silently
deletes or fabricates money. The design constrains it heavily:

**The LLM only ever sees the middle band.** Candidates the deterministic layer
is confident about — exact key collisions, exact amount matches — never reach
it. It is asked only about pairs scoring between the review floor and the
auto-merge ceiling, which is a small slice.

**It cannot merge anything on its own.** Its verdict adjusts a score. Merging
still requires clearing the same deterministic threshold as before. In practice
the model can promote a 0.85 to auto-merge or demote it to rejected, but it
cannot conjure a merge out of a 0.4.

**It never adjudicates upward on amount mismatches.** If two rows differ in
amount, no amount of textual plausibility makes them the same transaction. That
check runs before the model is consulted.

**Its reasoning is stored.** Every verdict is recorded with the text it saw and
the explanation it gave, so a wrong merge is diagnosable rather than mysterious.

The genuine value here is context the deterministic layer cannot have: knowing
that "SQ *BLUE BOTTLE" and "BLUE BOTTLE COFFEE HK" are the same shop, or that
"AMEX AUTOPAY" on HSBC and "PAYMENT RECEIVED" on AMEX are two halves of one
event. String similarity cannot see that. A model can.
"""

from __future__ import annotations

import json
from typing import Sequence

from . import cache
from .provider import LLMProvider, LLMUnavailable

PROMPT_VERSION = "adj-v1"

# The band where the LLM is consulted. Outside it, deterministic scoring stands.
DUPE_BAND = (0.70, 0.97)
XFER_BAND = (0.55, 0.90)

# How much an LLM verdict can move a score. Deliberately bounded: the model
# adjusts confidence, it does not replace the scoring function.
MAX_ADJUSTMENT = 0.20

DUPE_SYSTEM = """You are auditing a personal finance ledger for duplicate \
transactions.

Each item gives you two transactions that have the SAME amount and currency and \
occur within a few days of each other. Decide whether they are the same real \
transaction recorded twice, or two genuinely separate transactions that happen \
to match.

Return a JSON array, one object per item, same order:
  {"i": <index>, "verdict": "duplicate"|"distinct"|"unsure", \
"confidence": <0.0-1.0>, "reason": "<one short sentence>"}

Guidance:
- Recurring charges are the main trap. A HKD 88 coffee subscription billed \
weekly, or two identical MTR top-ups on the same day, are DISTINCT despite \
matching perfectly.
- A pending entry and its posted counterpart are the same transaction, usually \
1-3 days apart with slightly different description formatting.
- The same charge appearing in two overlapping statement periods is a duplicate.
- Different merchant identity means distinct, regardless of matching amounts.
- Use "unsure" freely. It routes the pair to human review, which is the correct \
outcome when the evidence is genuinely ambiguous. Do not force a verdict.
Return only the JSON array."""

XFER_SYSTEM = """You are auditing a personal finance ledger for transfers \
between accounts the SAME person owns.

Each item gives you an outflow from one account and an inflow to another. \
Decide whether they are two halves of one movement of the user's own money \
(a transfer, a credit-card payment, or a currency conversion), or unrelated \
transactions.

Return a JSON array, one object per item, same order:
  {"i": <index>, "verdict": "linked"|"unrelated"|"unsure", \
"confidence": <0.0-1.0>, "kind": "internal_transfer"|"cc_payment"|\
"fx_conversion"|"atm_withdrawal"|null, "reason": "<one short sentence>"}

Guidance:
- Money landing on a credit card from a bank account is almost always a \
cc_payment, especially with wording like AUTOPAY, PAYMENT RECEIVED, THANK YOU.
- Hong Kong context: FPS is the local instant transfer rail; descriptions often \
name the destination bank.
- A salary deposit that coincidentally matches an unrelated payment is NOT a \
transfer. Look for corroborating wording, not just matching numbers.
- Use "unsure" when the evidence is thin. Human review is the right destination.
Return only the JSON array."""


def _describe_txn(t: dict) -> dict:
    """The projection given to the model. Amounts are context, never editable."""
    return {
        "account": t["account_id"],
        "date": t["txn_date"],
        "amount": t["amount_booked"] / 100.0,
        "currency": t["currency_booked"],
        "description": t["description_raw"],
        "merchant": t.get("merchant"),
        "status": t.get("status"),
    }


def adjudicate_duplicates(
    conn, provider: LLMProvider, *, limit: int = 100, dry_run: bool = False,
) -> dict:
    lo, hi = DUPE_BAND
    rows = list(conn.execute(
        """SELECT dc.id, dc.score,
                  a.account_id AS a_account, a.txn_date AS a_txn_date,
                  a.amount_booked AS a_amount_booked, a.currency_booked AS a_currency_booked,
                  a.description_raw AS a_description_raw, a.merchant AS a_merchant,
                  a.status AS a_status,
                  b.account_id AS b_account, b.txn_date AS b_txn_date,
                  b.amount_booked AS b_amount_booked, b.currency_booked AS b_currency_booked,
                  b.description_raw AS b_description_raw, b.merchant AS b_merchant,
                  b.status AS b_status
           FROM duplicate_candidate dc
           JOIN txn a ON a.id = dc.keep_txn_id
           JOIN txn b ON b.id = dc.dupe_txn_id
           WHERE dc.resolution='open' AND dc.score >= ? AND dc.score < ?
           LIMIT ?""", (lo, hi, limit)))
    if not rows or dry_run:
        return {"considered": len(rows), "adjudicated": 0,
                "note": "dry run" if dry_run else "nothing in band"}

    items, meta = [], []
    for i, r in enumerate(rows):
        a = {"account_id": r["a_account"], "txn_date": r["a_txn_date"],
             "amount_booked": r["a_amount_booked"], "currency_booked": r["a_currency_booked"],
             "description_raw": r["a_description_raw"], "merchant": r["a_merchant"],
             "status": r["a_status"]}
        b = {"account_id": r["b_account"], "txn_date": r["b_txn_date"],
             "amount_booked": r["b_amount_booked"], "currency_booked": r["b_currency_booked"],
             "description_raw": r["b_description_raw"], "merchant": r["b_merchant"],
             "status": r["b_status"]}
        # Hard invariant: never adjudicate a pair whose money disagrees.
        if a["amount_booked"] != b["amount_booked"] or \
                a["currency_booked"] != b["currency_booked"]:
            continue
        items.append({"i": len(items), "a": _describe_txn(a), "b": _describe_txn(b)})
        meta.append(r)

    return _run_adjudication(
        conn, provider, DUPE_SYSTEM, items, meta,
        task="adjudicate_duplicate", table="duplicate_candidate",
        positive="duplicate", negative="distinct")


def adjudicate_transfers(
    conn, provider: LLMProvider, *, limit: int = 100, dry_run: bool = False,
) -> dict:
    lo, hi = XFER_BAND
    rows = list(conn.execute(
        """SELECT tc.id, tc.score,
                  o.account_id AS a_account, o.txn_date AS a_txn_date,
                  o.amount_booked AS a_amount_booked, o.currency_booked AS a_currency_booked,
                  o.description_raw AS a_description_raw, o.merchant AS a_merchant,
                  o.status AS a_status,
                  i.account_id AS b_account, i.txn_date AS b_txn_date,
                  i.amount_booked AS b_amount_booked, i.currency_booked AS b_currency_booked,
                  i.description_raw AS b_description_raw, i.merchant AS b_merchant,
                  i.status AS b_status
           FROM transfer_candidate tc
           JOIN txn o ON o.id = tc.out_txn_id
           JOIN txn i ON i.id = tc.in_txn_id
           WHERE tc.resolution='open' AND tc.score >= ? AND tc.score < ?
           LIMIT ?""", (lo, hi, limit)))
    if not rows or dry_run:
        return {"considered": len(rows), "adjudicated": 0,
                "note": "dry run" if dry_run else "nothing in band"}

    items, meta = [], []
    for r in rows:
        out = {"account_id": r["a_account"], "txn_date": r["a_txn_date"],
               "amount_booked": r["a_amount_booked"], "currency_booked": r["a_currency_booked"],
               "description_raw": r["a_description_raw"], "merchant": r["a_merchant"],
               "status": r["a_status"]}
        inc = {"account_id": r["b_account"], "txn_date": r["b_txn_date"],
               "amount_booked": r["b_amount_booked"], "currency_booked": r["b_currency_booked"],
               "description_raw": r["b_description_raw"], "merchant": r["b_merchant"],
               "status": r["b_status"]}
        items.append({"i": len(items), "outflow": _describe_txn(out),
                      "inflow": _describe_txn(inc)})
        meta.append(r)

    return _run_adjudication(
        conn, provider, XFER_SYSTEM, items, meta,
        task="adjudicate_transfer", table="transfer_candidate",
        positive="linked", negative="unrelated")


def _run_adjudication(conn, provider, system, items, meta, *,
                      task, table, positive, negative) -> dict:
    if not items:
        return {"considered": 0, "adjudicated": 0}

    # Cache first — re-running reconcile should not re-bill you.
    verdicts: dict[int, dict] = {}
    uncached_items, uncached_map = [], {}
    for item in items:
        ih = cache.input_hash(task, item)
        hit = cache.lookup(conn, task, ih, PROMPT_VERSION)
        if hit:
            verdicts[item["i"]] = hit["output"]
        else:
            uncached_map[len(uncached_items)] = item["i"]
            clone = dict(item)
            clone["i"] = len(uncached_items)
            uncached_items.append(clone)

    if uncached_items:
        try:
            resp = provider.complete_json(
                system, json.dumps(uncached_items, default=str), max_tokens=4000)
            for row in (resp.data if isinstance(resp.data, list) else []):
                try:
                    local_i = int(row["i"])
                    original_i = uncached_map[local_i]
                except (KeyError, ValueError, TypeError):
                    continue
                verdicts[original_i] = row
                cache.record(
                    conn, task=task,
                    ihash=cache.input_hash(task, items[original_i]),
                    summary=json.dumps(items[original_i], default=str)[:400],
                    output=row, confidence=float(row.get("confidence", 0.0)),
                    model=resp.model, prompt_version=PROMPT_VERSION)
        except (LLMUnavailable, ValueError):
            conn.commit()
            return {"considered": len(items), "adjudicated": 0,
                    "note": "LLM unavailable — candidates left for human review"}

    promoted = demoted = unsure = 0
    for idx, v in verdicts.items():
        verdict = v.get("verdict")
        conf = float(v.get("confidence", 0.0))
        row = meta[idx]
        if verdict == positive:
            adjustment = MAX_ADJUSTMENT * conf
            new_score = min(1.0, row["score"] + adjustment)
            promoted += 1
        elif verdict == negative:
            adjustment = -MAX_ADJUSTMENT * conf
            new_score = max(0.0, row["score"] + adjustment)
            demoted += 1
        else:
            unsure += 1
            continue
        conn.execute(f"UPDATE {table} SET score=?, reasons=? WHERE id=?",
                     (new_score,
                      json.dumps([f"llm:{verdict} ({conf:.2f}) — "
                                  f"{v.get('reason', '')}"]),
                      row["id"]))
    conn.commit()
    return {"considered": len(items), "adjudicated": promoted + demoted,
            "promoted": promoted, "demoted": demoted, "unsure": unsure,
            "note": "scores adjusted; merging still requires the "
                    "deterministic threshold"}
