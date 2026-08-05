"""Optional model-assisted merchant categorisation.

Only descriptions unmatched by deterministic rules are submitted. Results must
use the stored taxonomy, meet the confidence threshold, and are recorded in the
annotation audit trail.
"""

from __future__ import annotations

import json
from collections import defaultdict
from collections.abc import Sequence

from .. import db as dbm
from ..taxonomy import BASE_TAXONOMY, load_taxonomy, register_merchant
from . import cache
from .provider import LLMProvider, LLMUnavailable

PROMPT_VERSION = "cat-v2"

# Model output is limited to the same taxonomy used by deterministic rules.
TAXONOMY: dict[str, list[str]] = {key: list(values) for key, values in BASE_TAXONOMY.items()}

# Cross-cutting attributes a category cannot express: a coffee can be recurring,
# a flight can be business. Closed set, so tags stay aggregatable.
TAG_VOCABULARY: tuple[str, ...] = (
    "recurring", "subscription", "travel", "business",
    "online", "cash", "fee", "gift",
)

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
"merchant": <cleaned merchant name>, "tags": [<tag>, ...], "confidence": <0.0-1.0>}

Rules:
- category and subcategory MUST come from the provided taxonomy. Never invent one.
- tags MUST come from the provided tag vocabulary. Return between 0 and 3, and \
only ones you are confident about. They describe how the money was spent, not \
what was bought: a monthly cloud bill is ["subscription", "recurring", "online"], \
a hotel abroad is ["travel"], an ATM withdrawal is ["cash"]. Return [] when none \
clearly apply.
- merchant is the human-readable name, e.g. "CTY SPR TST 3 KLN" -> "City Super".
- If you cannot identify the merchant with reasonable certainty, use category \
"other", subcategory "uncategorised", and set confidence below 0.5. Guessing is \
worse than abstaining here.
- Hong Kong context matters: "PARKNSHOP" and "WELLCOME" are supermarkets, \
"OCTOPUS" is transit stored-value, "MTR" is the metro, "HKTVMALL" is retail.
- Return only the JSON array. No commentary."""


def _user_prompt(descriptions: Sequence[str], taxonomy: dict[str, list[str]]) -> str:
    return (
        f"Taxonomy:\n{json.dumps(taxonomy, indent=0)}\n\n"
        f"Tag vocabulary:\n{json.dumps(list(TAG_VOCABULARY), indent=0)}\n\n"
        f"Descriptions:\n{json.dumps(list(descriptions), indent=0)}"
    )


def _valid(cat: str, sub: str, taxonomy: dict[str, list[str]]) -> bool:
    return cat in taxonomy and sub in taxonomy[cat]


def _clean_tags(value) -> list[str]:
    if not isinstance(value, list):
        return []
    seen = {str(tag).strip().lower() for tag in value if isinstance(tag, str)}
    return [tag for tag in TAG_VOCABULARY if tag in seen][:3]


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
    taxonomy = load_taxonomy(conn) or TAXONOMY

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
            resp = provider.complete_json(
                SYSTEM, _user_prompt(batch, taxonomy), max_tokens=4000
            )
        except (LLMUnavailable, ValueError):
            # Leave the rows uncategorised when the provider is unavailable.
            continue

        rows = resp.data if isinstance(resp.data, list) else []
        for row in rows:
            try:
                idx = int(row["i"])
                desc = batch[idx]
            except (KeyError, ValueError, IndexError, TypeError):
                continue
            cat, sub = row.get("category"), row.get("subcategory")
            if not _valid(cat, sub, taxonomy):
                continue          # reject anything outside the closed taxonomy
            conf = float(row.get("confidence", 0.0))
            out = {"category": cat, "subcategory": sub,
                   "merchant": row.get("merchant"),
                   "tags": _clean_tags(row.get("tags")),
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
        "SELECT t.id, t.description_norm, t.description_raw, a.user_id FROM txn t "
        "JOIN account a ON a.id=t.account_id "
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
            "SELECT id FROM llm_decision WHERE task='categorize' AND input_hash=%s",
            (cache.input_hash("categorize", desc),)).fetchone()
        for tid in txn_ids:
            conn.execute(
                "UPDATE txn SET category=%s, subcategory=%s, merchant=COALESCE(merchant,%s) "
                "WHERE id=%s",
                (res["category"], res["subcategory"], res.get("merchant"), tid))
            cache.annotate(conn, txn_id=tid, field="category",
                           value=res["category"], source="llm",
                           confidence=res["confidence"],
                           decision_id=did["id"] if did else None)
            for tag in res.get("tags") or []:
                dbm.add_tag(conn, tid, tag, source="llm")
            applied += 1
        if res.get("merchant"):
            owner = next(r["user_id"] for r in rows if r["id"] in txn_ids)
            register_merchant(
                conn, owner, res["merchant"], aliases=[desc],
                category=res["category"], subcategory=res["subcategory"], source="llm",
            )
    conn.commit()
    return {"transactions": len(rows), "distinct_merchants": len(distinct),
            "applied": applied, "skipped_low_confidence": skipped}


def apply_tags(conn, provider: LLMProvider, *, dry_run: bool = False) -> dict:
    """Tag transactions that carry no tags yet, categorised or not.

    Categories were backfilled before the model was asked for tags, so most
    rows already have one and no tags — invisible to apply_to_ledger, which
    only considers uncategorised rows.
    """
    rows = list(conn.execute(
        "SELECT t.id, t.description_norm, t.description_raw FROM txn t "
        "WHERE t.duplicate_of_id IS NULL "
        "AND t.kind NOT IN ('transfer','cc_payment','fx_conversion') "
        "AND NOT EXISTS (SELECT 1 FROM txn_tag g WHERE g.txn_id = t.id)"))
    if not rows:
        return {"transactions": 0, "distinct_merchants": 0, "tagged": 0}

    by_desc: dict[str, list[str]] = defaultdict(list)
    for r in rows:
        by_desc[r["description_norm"] or r["description_raw"]].append(r["id"])

    distinct = sorted(by_desc)
    if dry_run:
        return {"transactions": len(rows), "distinct_merchants": len(distinct),
                "tagged": 0, "note": "dry run — no calls made"}

    results = categorize_merchants(conn, provider, distinct)

    tagged = tags_written = 0
    for desc, txn_ids in by_desc.items():
        res = results.get(desc)
        if not res or res["confidence"] < CONFIDENCE_FLOOR:
            continue
        tags = _clean_tags(res.get("tags"))
        if not tags:
            continue
        for tid in txn_ids:
            for tag in tags:
                dbm.add_tag(conn, tid, tag, source="llm")
                tags_written += 1
            tagged += 1
    conn.commit()
    return {"transactions": len(rows), "distinct_merchants": len(distinct),
            "tagged": tagged, "tags_written": tags_written}


def promote_to_rules(conn, *, min_confidence: float = 0.9, min_occurrences: int = 3) -> int:
    """Promote frequent high-confidence classifications to exact rules."""
    import uuid
    rows = list(conn.execute(
        "SELECT d.input_summary AS desc, d.output, d.confidence, COUNT(t.id) n "
        "FROM llm_decision d "
        "JOIN txn t ON t.description_norm = d.input_summary "
        "WHERE d.task='categorize' AND d.confidence >= %s "
        "GROUP BY d.input_summary, d.output, d.confidence HAVING COUNT(t.id) >= %s",
        (min_confidence, min_occurrences)))
    created = 0
    for r in rows:
        out = json.loads(r["output"])
        exists = conn.execute(
            "SELECT 1 FROM category_rule WHERE pattern=%s AND match_field='description_norm'",
            (r["desc"],)).fetchone()
        if exists:
            continue
        conn.execute(
            "INSERT INTO category_rule (id, priority, match_field, match_type, "
            "pattern, set_category, set_subcategory, enabled) "
            "VALUES (%s,%s,%s,%s,%s,%s,%s,1)",
            (str(uuid.uuid4()), 50, "description_norm", "exact", r["desc"],
             out["category"], out["subcategory"]))
        created += 1
    conn.commit()
    return created
