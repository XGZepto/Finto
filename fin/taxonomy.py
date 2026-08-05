"""Canonical category, tag, and merchant registries.

The ledger stores denormalised labels on transactions for fast reporting.  The
tables managed here are the authority for which labels may be written and for
which statement aliases refer to the same merchant.  Backfill is deliberately
limited to exact, unanimous evidence already present in the ledger.
"""

from __future__ import annotations

import re
import uuid
from collections import Counter, defaultdict
from datetime import datetime, timezone
from typing import Any

BASE_TAXONOMY: dict[str, tuple[str, ...]] = {
    "dining": ("restaurants", "coffee", "bars", "delivery", "fast_food"),
    "groceries": ("supermarket", "convenience"),
    "transport": ("transit", "taxi_rideshare", "fuel", "parking", "tolls"),
    "travel": ("hotels", "flights", "tours", "agency"),
    "shopping": ("clothing", "electronics", "home_goods", "general", "cosmetics"),
    "services": ("subscriptions", "software", "telecom", "professional", "education"),
    "housing": ("rent", "utilities", "internet", "maintenance"),
    "health": ("medical", "pharmacy", "fitness"),
    "entertainment": ("streaming", "events", "gaming", "hobbies"),
    "fees": ("bank", "card", "service"),
    "interest": ("interest",),
    "income": ("salary", "refund", "other_income"),
    "rewards": ("cashback", "points"),
    "other": ("charity", "gifts", "cash", "uncategorised"),
}


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def label(value: str) -> str:
    return value.replace("_", " ").strip().title()


def canonical_key(value: str) -> str:
    """Case/spacing-insensitive key that preserves non-Latin text."""
    return re.sub(r"[^\w]+", " ", value.casefold(), flags=re.UNICODE).strip()


def _preferred_display(values: Counter[str]) -> str:
    """Pick a stable spelling: frequency first, then already-clean text."""
    value = min(
        values,
        key=lambda item: (
            -values[item],
            item != " ".join(item.strip().split()),
            item.isupper() and any(char.isalpha() for char in item),
            item.casefold(),
            item,
        ),
    )
    return " ".join(value.strip().split())


def seed_base_taxonomy(conn) -> int:
    created = 0
    for category, subcategories in BASE_TAXONOMY.items():
        for subcategory in subcategories:
            cur = conn.execute(
                "INSERT INTO category_definition (category,subcategory,category_label,"
                "subcategory_label,source,active,created_at) VALUES (%s,%s,%s,%s,'builtin',1,%s) "
                "ON CONFLICT (category,subcategory) DO NOTHING",
                (category, subcategory, label(category), label(subcategory),
                 _now()),
            )
            created += cur.rowcount
    return created


def load_taxonomy(conn) -> dict[str, list[str]]:
    out: dict[str, list[str]] = defaultdict(list)
    for row in conn.execute(
        "SELECT category,subcategory FROM category_definition WHERE active=1 "
        "ORDER BY category,subcategory"
    ):
        out[row["category"]].append(row["subcategory"])
    return dict(out)


def add_category(
    conn, category: str, subcategory: str, *, category_label: str | None = None,
    subcategory_label: str | None = None, source: str = "manual",
) -> None:
    category = canonical_key(category).replace(" ", "_")
    subcategory = canonical_key(subcategory).replace(" ", "_")
    if not category or not subcategory:
        raise ValueError("category and subcategory are required")
    conn.execute(
        "INSERT INTO category_definition (category,subcategory,category_label,"
        "subcategory_label,source,active,created_at) VALUES (%s,%s,%s,%s,%s,1,%s) "
        "ON CONFLICT (category,subcategory) DO UPDATE SET category_label=EXCLUDED.category_label,"
        "subcategory_label=EXCLUDED.subcategory_label,active=1",
        (category, subcategory, category_label or label(category),
         subcategory_label or label(subcategory), source, _now()),
    )


def category_exists(conn, category: str, subcategory: str | None = None) -> bool:
    if subcategory is None:
        row = conn.execute(
            "SELECT 1 FROM category_definition WHERE category=%s AND active=1 LIMIT 1",
            (category,),
        ).fetchone()
    else:
        row = conn.execute(
            "SELECT 1 FROM category_definition WHERE category=%s AND subcategory=%s "
            "AND active=1",
            (category, subcategory),
        ).fetchone()
    return row is not None


def _stable_id(namespace: str, user_id: str, key: str) -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f"finto:{namespace}:{user_id}:{key}"))


def register_tag(
    conn, user_id: str, display_name: str, *, source: str = "manual",
) -> dict[str, str]:
    display = " ".join(display_name.strip().split())
    slug = canonical_key(display)
    if not slug:
        raise ValueError("tag cannot be empty")
    row = conn.execute(
        "SELECT id,display_name FROM tag_definition WHERE user_id=%s AND slug=%s",
        (user_id, slug),
    ).fetchone()
    if row:
        return {"id": row["id"], "display_name": row["display_name"], "slug": slug}
    tag_id = _stable_id("tag", user_id, slug)
    conn.execute(
        "INSERT INTO tag_definition (id,user_id,slug,display_name,source,active,created_at) "
        "VALUES (%s,%s,%s,%s,%s,1,%s) ON CONFLICT (user_id,slug) DO NOTHING",
        (tag_id, user_id, slug, display, source, _now()),
    )
    conn.execute(
        "INSERT INTO tag_alias (user_id,alias_key,tag_id) VALUES (%s,%s,%s) "
        "ON CONFLICT (user_id,alias_key) DO UPDATE SET tag_id=EXCLUDED.tag_id",
        (user_id, slug, tag_id),
    )
    return {"id": tag_id, "display_name": display, "slug": slug}


def register_merchant(
    conn, user_id: str, display_name: str, *, aliases: list[str] | None = None,
    category: str | None = None, subcategory: str | None = None,
    source: str = "observed",
) -> dict[str, str]:
    display = " ".join(display_name.strip().split())
    key = canonical_key(display)
    if not key:
        raise ValueError("merchant cannot be empty")
    row = conn.execute(
        "SELECT id,display_name FROM merchant_definition WHERE user_id=%s AND name_key=%s",
        (user_id, key),
    ).fetchone()
    merchant_id = row["id"] if row else _stable_id("merchant", user_id, key)
    if not row:
        conn.execute(
            "INSERT INTO merchant_definition (id,user_id,name_key,display_name,category,"
            "subcategory,source,active,created_at) VALUES (%s,%s,%s,%s,%s,%s,%s,1,%s)",
            (merchant_id, user_id, key, display, category, subcategory, source, _now()),
        )
    elif category and subcategory:
        conn.execute(
            "UPDATE merchant_definition SET category=COALESCE(category,%s),"
            "subcategory=COALESCE(subcategory,%s) WHERE id=%s",
            (category, subcategory, merchant_id),
        )
    for alias in [display, *(aliases or [])]:
        alias_key = canonical_key(alias)
        if alias_key:
            conn.execute(
                "INSERT INTO merchant_alias (user_id,alias_key,merchant_id) VALUES (%s,%s,%s) "
                "ON CONFLICT (user_id,alias_key) DO NOTHING",
                (user_id, alias_key, merchant_id),
            )
    return {"id": merchant_id, "display_name": row["display_name"] if row else display,
            "name_key": key}


def _owner_clause(user_id: str | None) -> tuple[str, tuple[Any, ...]]:
    return (" AND a.user_id=%s", (user_id,)) if user_id else ("", ())


def audit_backfill(conn, *, apply: bool = False, user_id: str | None = None) -> dict[str, Any]:
    """Audit or apply only exact, conflict-free taxonomy propagation.

    Tags are registered and spelling variants are collapsed, but are never
    propagated to other transactions.  Rows with competing evidence remain in
    ``conflicts`` for manual review.
    """
    owner_sql, owner_params = _owner_clause(user_id)
    rows = [dict(row) for row in conn.execute(
        "SELECT t.id,t.description_norm,t.description_raw,t.merchant,t.category,"
        "t.subcategory,a.user_id,COALESCE(ann.source,'') AS category_source "
        "FROM txn t JOIN account a ON a.id=t.account_id "
        "LEFT JOIN txn_annotation ann ON ann.txn_id=t.id AND ann.field='category' "
        "WHERE t.duplicate_of_id IS NULL AND t.status<>'void' "
        "AND t.kind NOT IN ('transfer','cc_payment','fx_conversion')" + owner_sql,
        owner_params,
    )]

    known = {(r["category"], r["subcategory"]) for r in conn.execute(
        "SELECT category,subcategory FROM category_definition WHERE active=1"
    )}
    observed_pairs = Counter(
        (r["category"], r["subcategory"]) for r in rows
        if r["category"] and r["subcategory"]
    )
    unknown_pairs = [
        {"category": pair[0], "subcategory": pair[1], "transactions": count}
        for pair, count in sorted(observed_pairs.items()) if pair not in known
    ]

    evidence: dict[tuple[str, str, str], Counter] = defaultdict(Counter)
    merchant_names: dict[tuple[str, str], Counter] = defaultdict(Counter)
    merchant_groups: dict[tuple[str, str], dict[str, Any]] = {}
    for row in rows:
        pair = (row["category"], row["subcategory"])
        if pair not in known:
            continue
        desc_key = canonical_key(row["description_norm"] or row["description_raw"] or "")
        merchant_key = canonical_key(row["merchant"] or "")
        if desc_key:
            evidence[(row["user_id"], "description", desc_key)][pair] += 1
            if row["merchant"]:
                merchant_names[(row["user_id"], desc_key)][row["merchant"]] += 1
        if merchant_key:
            evidence[(row["user_id"], "merchant", merchant_key)][pair] += 1
            group = merchant_groups.setdefault(
                (row["user_id"], merchant_key),
                {"names": Counter(), "aliases": set(), "categories": Counter()},
            )
            group["names"][row["merchant"]] += 1
            if desc_key:
                group["aliases"].add(row["description_norm"] or row["description_raw"])
            if pair in known:
                group["categories"][pair] += 1

    resolved: dict[tuple[str, str, str], tuple[str, str]] = {}
    conflicts: list[dict[str, Any]] = []
    for key, counts in evidence.items():
        if len(counts) == 1:
            resolved[key] = next(iter(counts))
        else:
            conflicts.append({
                "user_id": key[0], "field": key[1], "value": key[2],
                "categories": [
                    {"category": pair[0], "subcategory": pair[1], "transactions": count}
                    for pair, count in counts.most_common()
                ],
            })

    proposals: list[dict[str, Any]] = []
    for row in rows:
        desc_key = canonical_key(row["description_norm"] or row["description_raw"] or "")
        merchant_key = canonical_key(row["merchant"] or "")
        proposal: dict[str, Any] = {"txn_id": row["id"], "user_id": row["user_id"]}
        if not row["category"] and row["category_source"] != "manual":
            pair = resolved.get((row["user_id"], "merchant", merchant_key)) if merchant_key else None
            method = "exact_merchant"
            if pair is None and desc_key:
                pair = resolved.get((row["user_id"], "description", desc_key))
                method = "exact_description"
            if pair:
                proposal.update(category=pair[0], subcategory=pair[1], category_method=method)
        if not row["merchant"] and desc_key:
            names = merchant_names.get((row["user_id"], desc_key), Counter())
            name_keys = {canonical_key(name) for name in names}
            if len(name_keys) == 1:
                proposal.update(
                    merchant=_preferred_display(names), merchant_method="exact_description"
                )
        if len(proposal) > 2:
            proposals.append(proposal)

    tag_rows = [dict(row) for row in conn.execute(
        "SELECT tt.txn_id,tt.tag,a.user_id FROM txn_tag tt JOIN txn t ON t.id=tt.txn_id "
        "JOIN account a ON a.id=t.account_id WHERE t.duplicate_of_id IS NULL "
        "AND t.status<>'void'" + owner_sql,
        owner_params,
    )]
    tag_groups: dict[tuple[str, str], Counter] = defaultdict(Counter)
    for row in tag_rows:
        tag_groups[(row["user_id"], canonical_key(row["tag"]))][row["tag"]] += 1
    tag_variants = [
        {"user_id": uid, "key": key, "canonical": _preferred_display(counts),
         "variants": dict(counts)}
        for (uid, key), counts in sorted(tag_groups.items()) if len(counts) > 1
    ]

    existing_tag_keys = {
        (row["user_id"], row["slug"])
        for row in conn.execute("SELECT user_id,slug FROM tag_definition")
    }
    existing_merchant_keys = {
        (row["user_id"], row["name_key"])
        for row in conn.execute("SELECT user_id,name_key FROM merchant_definition")
    }
    proposed_tag_keys = set(tag_groups)
    proposed_merchant_keys = set(merchant_groups)

    applied_categories = applied_merchants = canonicalised_tags = 0
    if apply:
        for (uid, _key), counts in tag_groups.items():
            register_tag(conn, uid, _preferred_display(counts), source="observed")
        for (uid, _key), group in merchant_groups.items():
            pair = next(iter(group["categories"])) if len(group["categories"]) == 1 else None
            register_merchant(
                conn, uid, _preferred_display(group["names"]),
                aliases=sorted(group["aliases"]),
                category=pair[0] if pair else None,
                subcategory=pair[1] if pair else None,
            )
        for proposal in proposals:
            if proposal.get("category"):
                cur = conn.execute(
                    "UPDATE txn SET category=%s,subcategory=%s,updated_at=%s "
                    "WHERE id=%s AND category IS NULL",
                    (proposal["category"], proposal["subcategory"], _now(),
                     proposal["txn_id"]),
                )
                applied_categories += cur.rowcount
                if cur.rowcount:
                    conn.execute(
                        "INSERT INTO txn_annotation (txn_id,field,value,source,confidence,created_at) "
                        "VALUES (%s,'category',%s,'rule',1.0,%s) ON CONFLICT (txn_id,field) "
                        "DO UPDATE SET value=EXCLUDED.value,source='rule',confidence=1.0,"
                        "created_at=EXCLUDED.created_at",
                        (proposal["txn_id"], proposal["category"], _now()),
                    )
            if proposal.get("merchant"):
                cur = conn.execute(
                    "UPDATE txn SET merchant=%s,updated_at=%s WHERE id=%s AND merchant IS NULL",
                    (proposal["merchant"], _now(), proposal["txn_id"]),
                )
                applied_merchants += cur.rowcount
        for row in tag_rows:
            canonical = _preferred_display(
                tag_groups[(row["user_id"], canonical_key(row["tag"]))]
            )
            registered = register_tag(conn, row["user_id"], canonical, source="observed")
            canonical = registered["display_name"]
            if row["tag"] != canonical:
                conn.execute(
                    "INSERT INTO txn_tag (txn_id,tag,source,created_at) "
                    "SELECT txn_id,%s,source,created_at FROM txn_tag WHERE txn_id=%s AND tag=%s "
                    "ON CONFLICT (txn_id,tag) DO NOTHING",
                    (canonical, row["txn_id"], row["tag"]),
                )
                conn.execute("DELETE FROM txn_tag WHERE txn_id=%s AND tag=%s",
                             (row["txn_id"], row["tag"]))
                canonicalised_tags += 1
        conn.commit()

    unresolved = sum(1 for row in rows if not row["category"]) - applied_categories
    return {
        "mode": "apply" if apply else "audit",
        "transactions_scanned": len(rows),
        "category_proposals": sum(1 for p in proposals if p.get("category")),
        "merchant_proposals": sum(1 for p in proposals if p.get("merchant")),
        "applied_categories": applied_categories,
        "applied_merchants": applied_merchants,
        "canonicalised_tags": canonicalised_tags,
        "unresolved_uncategorised": max(0, unresolved),
        "unknown_category_pairs": unknown_pairs,
        "conflicts": conflicts,
        "tag_variants": tag_variants,
        "pool_changes": {
            "categories": 0,
            "tags": len(proposed_tag_keys - existing_tag_keys),
            "merchants": len(proposed_merchant_keys - existing_merchant_keys),
        },
        "pool_sizes": {
            "category_pairs": len(known),
            "tags": len(existing_tag_keys | proposed_tag_keys) if apply else len(existing_tag_keys),
            "merchants": (
                len(existing_merchant_keys | proposed_merchant_keys)
                if apply else len(existing_merchant_keys)
            ),
        },
    }
