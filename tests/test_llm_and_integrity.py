"""Tests for the LLM layer and the integrity checks.

The LLM tests use EchoProvider — no network, no API key, fully deterministic.
What they verify is not "does the model give good answers" but the far more
important question: **are the guardrails real?**
"""

from __future__ import annotations

from datetime import date

import pytest

from fin import db as dbm
from fin.integrity import (
    check_account,
    find_violations,
    record_balance,
    resolve_duplicate_chains,
)
from fin.llm import cache as llm_cache
from fin.llm.adjudicate import adjudicate_duplicates
from fin.llm.categorize import TAXONOMY, apply_to_ledger, categorize_merchants, promote_to_rules
from fin.llm.provider import EchoProvider, NullProvider, _extract_json
from fin.models import Account, AccountType, Institution, Money, Txn


@pytest.fixture
def conn(tmp_path):
    c = dbm.connect(tmp_path / "t.db")
    dbm.init_db(c)
    dbm.upsert_institution(c, Institution(id="hsbc_hk", display_name="HSBC", country="HK"))
    dbm.upsert_account(c, Account(id="acct1", institution_id="hsbc_hk",
                                  display_name="HSBC Current",
                                  account_type=AccountType.CHECKING,
                                  primary_currency="HKD"))
    c.commit()
    return c


def _txn(**kw) -> Txn:
    base = dict(account_id="acct1", txn_date=date(2025, 3, 1),
                booked=Money(amount=-5000, currency="HKD"),
                description_raw="CAFE ABC", statement_file_id="sf1")
    base.update(kw)
    return Txn(**base)


def _seed_file(conn):
    conn.execute(
        "INSERT OR IGNORE INTO statement_file (id, source_path, file_sha256, "
        "institution_id, account_id, file_format, parser_id, parser_version, "
        "imported_at, row_count) VALUES ('sf1','x','x','hsbc_hk','acct1','csv',"
        "'test','1',datetime('now'),0)")
    conn.commit()


# ---------------------------------------------------------------------------
# JSON extraction — models wrap output in prose and fences
# ---------------------------------------------------------------------------

def test_extract_json_from_fenced_block():
    assert _extract_json('```json\n[{"a": 1}]\n```') == [{"a": 1}]


def test_extract_json_from_surrounding_prose():
    assert _extract_json('Here you go:\n[{"a": 1}]\nHope that helps!') == [{"a": 1}]


def test_extract_json_raises_rather_than_guessing():
    with pytest.raises(ValueError):
        _extract_json("I couldn't complete that request.")


# ---------------------------------------------------------------------------
# Categorisation guardrails
# ---------------------------------------------------------------------------

def test_category_outside_taxonomy_is_rejected(conn):
    provider = EchoProvider({"categorise": [
        {"i": 0, "category": "cryptocurrency", "subcategory": "memecoins",
         "merchant": "X", "confidence": 0.99},
    ]})
    out = categorize_merchants(conn, provider, ["SOME MERCHANT"])
    assert out == {}          # invented category discarded entirely


def test_valid_category_is_accepted_and_cached(conn):
    provider = EchoProvider({"categorise": [
        {"i": 0, "category": "dining", "subcategory": "coffee",
         "merchant": "Blue Bottle", "confidence": 0.95},
    ]})
    out = categorize_merchants(conn, provider, ["BLUE BOTTLE HK"])
    assert out["BLUE BOTTLE HK"]["category"] == "dining"

    # Second call must hit cache, not the provider.
    before = len(provider.calls)
    again = categorize_merchants(conn, provider, ["BLUE BOTTLE HK"])
    assert len(provider.calls) == before
    assert again["BLUE BOTTLE HK"]["merchant"] == "Blue Bottle"


def test_low_confidence_is_not_applied(conn):
    _seed_file(conn)
    dbm.insert_txns(conn, [_txn(description_raw="MYSTERY MERCHANT 88")])
    conn.commit()
    provider = EchoProvider({"categorise": [
        {"i": 0, "category": "shopping", "subcategory": "general",
         "merchant": "?", "confidence": 0.30},
    ]})
    res = apply_to_ledger(conn, provider)
    assert res["applied"] == 0
    assert res["skipped_low_confidence"] == 1
    row = conn.execute("SELECT category FROM txn").fetchone()
    assert row["category"] is None      # left honest rather than guessed


def test_llm_never_touches_amounts(conn):
    _seed_file(conn)
    t = _txn(description_raw="STARBUCKS KOWLOON")
    dbm.insert_txns(conn, [t])
    conn.commit()
    before = conn.execute("SELECT amount_booked, currency_booked, txn_date, "
                          "account_id FROM txn").fetchone()
    provider = EchoProvider({"categorise": [
        {"i": 0, "category": "dining", "subcategory": "coffee",
         "merchant": "Starbucks", "confidence": 0.98,
         "amount": 999999, "account": "hacked"},   # model tries to send extras
    ]})
    apply_to_ledger(conn, provider)
    after = conn.execute("SELECT amount_booked, currency_booked, txn_date, "
                         "account_id FROM txn").fetchone()
    assert tuple(before) == tuple(after)   # money and identity untouched
    assert conn.execute("SELECT category FROM txn").fetchone()["category"] == "dining"


def test_annotation_records_llm_as_source(conn):
    _seed_file(conn)
    dbm.insert_txns(conn, [_txn(description_raw="PARKNSHOP TST")])
    conn.commit()
    provider = EchoProvider({"categorise": [
        {"i": 0, "category": "groceries", "subcategory": "supermarket",
         "merchant": "ParknShop", "confidence": 0.97},
    ]})
    apply_to_ledger(conn, provider)
    ann = conn.execute("SELECT source, confidence FROM txn_annotation "
                       "WHERE field='category'").fetchone()
    assert ann["source"] == "llm"
    assert ann["confidence"] == pytest.approx(0.97)


def test_transfers_are_never_sent_to_the_llm(conn):
    _seed_file(conn)
    t = _txn(description_raw="FPS TRANSFER TO MOX")
    t.kind = "transfer"
    dbm.insert_txns(conn, [t])
    conn.commit()
    provider = EchoProvider({"categorise": []})
    res = apply_to_ledger(conn, provider)
    assert res["transactions"] == 0     # excluded by the query, no call made
    assert provider.calls == []


def test_promote_to_rules_creates_deterministic_rule(conn):
    _seed_file(conn)
    dbm.insert_txns(conn, [_txn(description_raw="PARKNSHOP TST", txn_date=date(2025, 3, d))
                           for d in (1, 2, 3)])
    conn.commit()
    provider = EchoProvider({"categorise": [
        {"i": 0, "category": "groceries", "subcategory": "supermarket",
         "merchant": "ParknShop", "confidence": 0.98},
    ]})
    apply_to_ledger(conn, provider)
    assert promote_to_rules(conn) == 1
    rule = conn.execute("SELECT * FROM category_rule").fetchone()
    assert rule["set_category"] == "groceries"
    # The point of promotion: the model is never consulted about this again.


def test_null_provider_degrades_without_crashing(conn):
    _seed_file(conn)
    dbm.insert_txns(conn, [_txn(description_raw="SOMETHING NEW")])
    conn.commit()
    res = apply_to_ledger(conn, NullProvider())
    assert res["applied"] == 0          # no exception; ledger simply unchanged


def test_dry_run_makes_no_calls(conn):
    _seed_file(conn)
    dbm.insert_txns(conn, [_txn(description_raw="SOMETHING NEW")])
    conn.commit()
    provider = EchoProvider({})
    res = apply_to_ledger(conn, provider, dry_run=True)
    assert provider.calls == []
    assert res["distinct_merchants"] == 1


# ---------------------------------------------------------------------------
# Adjudication guardrails
# ---------------------------------------------------------------------------

def test_adjudication_only_adjusts_score_never_merges(conn):
    _seed_file(conn)
    a = _txn(description_raw="CAFE ABC", txn_date=date(2025, 3, 1))
    b = _txn(description_raw="CAFE ABC KLN", txn_date=date(2025, 3, 3))
    dbm.insert_txns(conn, [a, b])
    conn.execute(
        "INSERT INTO duplicate_candidate (id, keep_txn_id, dupe_txn_id, score, "
        "reasons, resolution, created_at) VALUES ('dc1',?,?,0.80,'[]','open',"
        "datetime('now'))", (a.id, b.id))
    conn.commit()

    provider = EchoProvider({"duplicate": [
        {"i": 0, "verdict": "duplicate", "confidence": 1.0, "reason": "same shop"},
    ]})
    res = adjudicate_duplicates(conn, provider)
    assert res["promoted"] == 1

    score = conn.execute("SELECT score FROM duplicate_candidate").fetchone()["score"]
    assert score == pytest.approx(1.0)          # 0.80 + capped 0.20
    # Crucially: nothing was actually merged.
    assert conn.execute("SELECT COUNT(*) n FROM txn WHERE duplicate_of_id IS NOT NULL"
                        ).fetchone()["n"] == 0


def test_adjudication_skips_pairs_with_different_amounts(conn):
    _seed_file(conn)
    a = _txn(booked=Money(amount=-5000, currency="HKD"))
    b = _txn(booked=Money(amount=-6000, currency="HKD"))
    dbm.insert_txns(conn, [a, b])
    conn.execute(
        "INSERT INTO duplicate_candidate (id, keep_txn_id, dupe_txn_id, score, "
        "reasons, resolution, created_at) VALUES ('dc1',?,?,0.80,'[]','open',"
        "datetime('now'))", (a.id, b.id))
    conn.commit()
    provider = EchoProvider({"duplicate": [
        {"i": 0, "verdict": "duplicate", "confidence": 1.0, "reason": "looks same"},
    ]})
    res = adjudicate_duplicates(conn, provider)
    # Amounts disagree, so the pair never reaches the model at all.
    assert res["adjudicated"] == 0
    assert provider.calls == []


def test_adjudication_respects_the_confidence_band(conn):
    _seed_file(conn)
    a, b = _txn(), _txn(description_raw="CAFE ABD")
    dbm.insert_txns(conn, [a, b])
    # 0.99 is above the band ceiling — deterministic layer already decided.
    conn.execute(
        "INSERT INTO duplicate_candidate (id, keep_txn_id, dupe_txn_id, score, "
        "reasons, resolution, created_at) VALUES ('dc1',?,?,0.99,'[]','open',"
        "datetime('now'))", (a.id, b.id))
    conn.commit()
    provider = EchoProvider({})
    res = adjudicate_duplicates(conn, provider)
    assert res["considered"] == 0
    assert provider.calls == []


def test_unsure_verdict_leaves_score_untouched(conn):
    _seed_file(conn)
    a, b = _txn(), _txn(description_raw="CAFE ABC 2")
    dbm.insert_txns(conn, [a, b])
    conn.execute(
        "INSERT INTO duplicate_candidate (id, keep_txn_id, dupe_txn_id, score, "
        "reasons, resolution, created_at) VALUES ('dc1',?,?,0.80,'[]','open',"
        "datetime('now'))", (a.id, b.id))
    conn.commit()
    provider = EchoProvider({"duplicate": [
        {"i": 0, "verdict": "unsure", "confidence": 0.5, "reason": "ambiguous"},
    ]})
    res = adjudicate_duplicates(conn, provider)
    assert res["unsure"] == 1
    assert conn.execute("SELECT score FROM duplicate_candidate").fetchone()["score"] \
        == pytest.approx(0.80)


def test_llm_decisions_are_auditable(conn):
    _seed_file(conn)
    provider = EchoProvider({"categorise": [
        {"i": 0, "category": "dining", "subcategory": "coffee",
         "merchant": "Cafe", "confidence": 0.9},
    ]})
    categorize_merchants(conn, provider, ["CAFE ABC"])
    row = conn.execute("SELECT * FROM llm_decision").fetchone()
    assert row["task"] == "categorize"
    assert row["input_summary"] == "CAFE ABC"
    assert row["model"] == "echo"
    assert row["prompt_version"]          # every decision is version-stamped


def test_cache_invalidation(conn):
    provider = EchoProvider({"categorise": [
        {"i": 0, "category": "dining", "subcategory": "coffee",
         "merchant": "Cafe", "confidence": 0.9},
    ]})
    categorize_merchants(conn, provider, ["CAFE ABC"])
    assert llm_cache.invalidate(conn, task="categorize") == 1
    conn.commit()
    assert conn.execute("SELECT COUNT(*) n FROM llm_decision").fetchone()["n"] == 0


def test_taxonomy_is_closed_and_consistent():
    for category, subs in TAXONOMY.items():
        assert subs, f"{category} has no subcategories"
        assert len(set(subs)) == len(subs), f"{category} has duplicates"


# ---------------------------------------------------------------------------
# Integrity
# ---------------------------------------------------------------------------

def test_balance_check_passes_when_transactions_reconcile(conn):
    _seed_file(conn)
    record_balance(conn, account_id="acct1", as_of=date(2025, 3, 1),
                   balance=Money(amount=100000, currency="HKD"))
    dbm.insert_txns(conn, [
        _txn(txn_date=date(2025, 3, 5), booked=Money(amount=-30000, currency="HKD")),
        _txn(txn_date=date(2025, 3, 10), booked=Money(amount=10000, currency="HKD"),
             description_raw="REFUND"),
    ])
    record_balance(conn, account_id="acct1", as_of=date(2025, 3, 31),
                   balance=Money(amount=80000, currency="HKD"))
    conn.commit()
    checks = check_account(conn, "acct1")
    assert checks[0]["status"] == "ok"


def test_balance_check_detects_a_dropped_transaction(conn):
    """The scenario this whole mechanism exists for: a parser silently skipped
    a row, and nothing else in the system would ever notice."""
    _seed_file(conn)
    record_balance(conn, account_id="acct1", as_of=date(2025, 3, 1),
                   balance=Money(amount=100000, currency="HKD"))
    dbm.insert_txns(conn, [
        _txn(txn_date=date(2025, 3, 5), booked=Money(amount=-30000, currency="HKD")),
        # A second -200.00 charge existed but was never parsed.
    ])
    record_balance(conn, account_id="acct1", as_of=date(2025, 3, 31),
                   balance=Money(amount=50000, currency="HKD"))
    conn.commit()
    checks = check_account(conn, "acct1")
    assert checks[0]["status"] == "discrepancy"
    # Minor units, like every other money value that crosses a boundary here.
    assert checks[0]["discrepancy"] == {"amount": 20000, "currency": "HKD"}
    assert checks[0]["period_start"] == "2025-03-01"
    assert checks[0]["period_end"] == "2025-03-31"


def test_duplicate_chains_are_collapsed(conn):
    _seed_file(conn)
    a = _txn(description_raw="A")
    b = _txn(description_raw="B")
    c = _txn(description_raw="C")
    dbm.insert_txns(conn, [a, b, c])
    conn.execute("UPDATE txn SET duplicate_of_id=? WHERE id=?", (b.id, a.id))
    conn.execute("UPDATE txn SET duplicate_of_id=? WHERE id=?", (c.id, b.id))
    conn.commit()
    assert resolve_duplicate_chains(conn) == 1
    root = conn.execute("SELECT duplicate_of_id FROM txn WHERE id=?", (a.id,)).fetchone()
    assert root["duplicate_of_id"] == c.id     # A now points straight at the root


def test_violation_detector_catches_self_duplicate(conn):
    _seed_file(conn)
    a = _txn()
    dbm.insert_txns(conn, [a])
    conn.execute("UPDATE txn SET duplicate_of_id=id WHERE id=?", (a.id,))
    conn.commit()
    names = {v["check"] for v in find_violations(conn)}
    assert "self_duplicate" in names


def test_clean_ledger_has_no_violations(conn):
    _seed_file(conn)
    dbm.insert_txns(conn, [_txn(), _txn(description_raw="OTHER")])
    conn.commit()
    assert find_violations(conn) == []
