"""LLM-assisted categorisation.

This is the task LLMs are actually good at: turning "CTY SPR TST 3 KLN" into
"groceries". It is fuzzy by nature, low-stakes to get slightly wrong, and
touches no money math.

Three properties make it safe:

* **Deterministic rules always win.** The LLM is only asked about transactions
  that no rule matched. Once you write a rule for a merchant, the model is
  never consulted about it again.
* **It never sees or returns an amount.** The output schema contains a category
  and a confidence, nothing else. It cannot alter what a transaction cost.
* **It categorises MERCHANTS, not transactions.** Input is deduplicated by
  normalised description, so 300 Starbucks charges cost one classification. On
  a typical year of statements this collapses a few thousand transactions into
  a few hundred distinct merchants.

Low-confidence answers are left uncategorised rather than guessed at, and every
applied category is annotated with source='llm' so you can always tell what the
model touched.
"""

from __future__ import annotations

import json
from collections import defaultdict
from collections.abc import Sequence

from .. import db as dbm
from . import cache
from .provider import LLMProvider, LLMUnavailable

PROMPT_VERSION = "cat-v1"

# A closed taxonomy. The model must pick from this list; anything else is
# rejected. An open-ended taxonomy drifts — you get "Food", "food", "Dining",
# "Restaurants" and "Eating out" for the same thing, and your reports lie.
# The top level matches the deterministic category_rule scheme, so a category
# the model fills reads the same as one a rule filled. Subcategories add detail
# the flat rules don't carry.
TAXONOMY: dict[str, list[str]] = {
    "dining": ["restaurants", "coffee", "bars", "delivery", "fast_food"],
    "groceries": ["supermarket", "convenience"],
    "transport": ["transit", "taxi_rideshare", "fuel", "parking", "tolls"],
    "travel": ["hotels", "flights", "tours", "agency"],
    "shopping": ["clothing", "electronics", "home_goods", "general", "cosmetics"],
    "services": ["subscriptions", "software", "telecom", "professional", "education"],
    "housing": ["rent", "utilities", "internet", "maintenance"],
    "health": ["medical", "pharmacy", "fitness"],
    "entertainment": ["streaming", "events", "gaming", "hobbies"],
    "fees": ["bank", "card", "service"],
    "interest": ["interest"],
    "income": ["salary", "refund", "other_income"],
    "rewards": ["cashback", "points"],
    "other": ["charity", "gifts", "cash", "uncategorised"],
}

CONFIDENCE_FLOOR = 0.60   # below this, leave uncategorised rather than guess
BATCH_SIZE = 40

SYSTEM = """You categorise bank and credit-card transaction descriptions for a \
personal finance ledger covering Hong Kong and the United States.

You will receive a JSON array of merchant descriptions. They are raw statement \
strings: abbreviated, truncated, sometimes containing location codes or \
transliterated Chinese. Return a JSON array with one object per input, in the \
same order.

Each object must be exactly:
  {"i": <input index>, "category": <category>, "subcategory": <subcategory>, \
"merchant": <cleaned merchant name>, "confidence": <0.0-1.0>}

Rules:
- category and subcategory MUST come from the provided taxonomy. Never invent one.
- merchant is the human-readable name, e.g. "CTY SPR TST 3 KLN" -> "City Super".
- If you cannot identify the merchant with reasonable certainty, use category \
"other", subcategory "uncategorised", and set confidence below 0.5. Guessing is \
worse than abstaining here.
- Hong Kong context matters: "PARKNSHOP" and "WELLCOME" are supermarkets, \
"OCTOPUS" is transit stored-value, "MTR" is the metro, "HKTVMALL" is retail.
- Return only the JSON array. No commentary."""


def _user_prompt(descriptions: Sequence[str]) -> str:
    return (
        f"Taxonomy:\n{json.dumps(TAXONOMY, indent=0)}\n\n"
        f"Descriptions:\n{json.dumps(list(descriptions), indent=0)}"
    )


def _valid(cat: str, sub: str) -> bool:
    return cat in TAXONOMY and sub in TAXONOMY[cat]


def categorize_merchants(
    conn,
    provider: LLMProvider,
    descriptions: Sequence[str],
    *,
    batch_size: int = BATCH_SIZE,
) -> dict[str, dict]:
    """Classify distinct merchant strings. Returns {description: result}.

    Cache is consulted per-description, so a batch of 40 where 35 are already
    known costs one call about 5 items — or zero calls if all are known.
    """
    results: dict[str, dict] = {}
    todo: list[str] = []

    for d in descriptions:
        hit = cache.lookup(conn, "categorize", cache.input_hash("categorize", d),
                           PROMPT_VERSION)
        if hit:
            results[d] = hit["output"]
        else:
            todo.append(d)

    for start in range(0, len(todo), batch_size):
        batch = todo[start:start + batch_size]
        try:
            resp = provider.complete_json(SYSTEM, _user_prompt(batch), max_tokens=4000)
        except (LLMUnavailable, ValueError):
            # Degrade silently: uncategorised is a valid, honest state.
            continue

        rows = resp.data if isinstance(resp.data, list) else []
        for row in rows:
            try:
                idx = int(row["i"])
                desc = batch[idx]
            except (KeyError, ValueError, IndexError, TypeError):
                continue
            cat, sub = row.get("category"), row.get("subcategory")
            if not _valid(cat, sub):
                continue          # reject anything outside the closed taxonomy
            conf = float(row.get("confidence", 0.0))
            tags = [str(x).strip() for x in (row.get("tags") or [])
                    if str(x).strip()][:2]
            out = {"category": cat, "subcategory": sub,
                   "merchant": row.get("merchant"), "tags": tags,
                   "confidence": conf}
            cache.record(conn, task="categorize",
                         ihash=cache.input_hash("categorize", desc),
                         summary=desc, output=out, confidence=conf,
                         model=resp.model, prompt_version=PROMPT_VERSION)
            results[desc] = out
    conn.commit()
    return results


def apply_to_ledger(conn, provider: LLMProvider, *, dry_run: bool = False) -> dict:
    """Categorise every uncategorised transaction the rules didn't claim.

    Groups by normalised description first — that collapse is where nearly all
    of the cost saving comes from.
    """
    rows = list(conn.execute(
        "SELECT id, description_norm, description_raw FROM txn "
        "WHERE category IS NULL AND duplicate_of_id IS NULL "
        "AND kind NOT IN ('transfer','cc_payment','fx_conversion')"))
    if not rows:
        return {"transactions": 0, "distinct_merchants": 0, "applied": 0}

    by_desc: dict[str, list[str]] = defaultdict(list)
    for r in rows:
        by_desc[r["description_norm"] or r["description_raw"]].append(r["id"])

    distinct = sorted(by_desc)
    if dry_run:
        return {"transactions": len(rows), "distinct_merchants": len(distinct),
                "applied": 0, "note": "dry run — no calls made"}

    results = categorize_merchants(conn, provider, distinct)

    applied = skipped = 0
    for desc, txn_ids in by_desc.items():
        res = results.get(desc)
        if not res:
            continue
        if res["confidence"] < CONFIDENCE_FLOOR:
            skipped += len(txn_ids)
            continue
        did = conn.execute(
            "SELECT id FROM llm_decision WHERE task='categorize' AND input_hash=?",
            (cache.input_hash("categorize", desc),)).fetchone()
        for tid in txn_ids:
            conn.execute(
                "UPDATE txn SET category=?, subcategory=?, merchant=COALESCE(merchant,?) "
                "WHERE id=?",
                (res["category"], res["subcategory"], res.get("merchant"), tid))
            cache.annotate(conn, txn_id=tid, field="category",
                           value=res["category"], source="llm",
                           confidence=res["confidence"],
                           decision_id=did["id"] if did else None)
            for tag in res.get("tags") or []:
                dbm.add_tag(conn, tid, tag, source="llm")
            applied += 1
    conn.commit()
    return {"transactions": len(rows), "distinct_merchants": len(distinct),
            "applied": applied, "skipped_low_confidence": skipped}


def promote_to_rules(conn, *, min_confidence: float = 0.9, min_occurrences: int = 3) -> int:
    """Convert confident, frequently-seen LLM categorisations into real rules.

    This is the ratchet that stops the LLM layer from being a permanent
    dependency: merchants you see often get promoted to deterministic rules, so
    next import they're handled for free and identically. Over time the model
    only ever sees genuinely new merchants.
    """
    import uuid
    rows = list(conn.execute(
        "SELECT d.input_summary AS desc, d.output, d.confidence, COUNT(t.id) n "
        "FROM llm_decision d "
        "JOIN txn t ON t.description_norm = d.input_summary "
        "WHERE d.task='categorize' AND d.confidence >= ? "
        "GROUP BY d.input_summary HAVING n >= ?",
        (min_confidence, min_occurrences)))
    created = 0
    for r in rows:
        out = json.loads(r["output"])
        exists = conn.execute(
            "SELECT 1 FROM category_rule WHERE pattern=? AND match_field='description_norm'",
            (r["desc"],)).fetchone()
        if exists:
            continue
        conn.execute(
            "INSERT INTO category_rule (id, priority, match_field, match_type, "
            "pattern, set_category, set_subcategory, enabled) "
            "VALUES (?,?,?,?,?,?,?,1)",
            (str(uuid.uuid4()), 50, "description_norm", "exact", r["desc"],
             out["category"], out["subcategory"]))
        created += 1
    conn.commit()
    return created
