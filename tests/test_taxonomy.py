from __future__ import annotations

from datetime import date

import pytest

from fin import db as dbm
from fin.models import Account, AccountType, Institution, Money, Txn
from fin.taxonomy import add_category, audit_backfill, load_taxonomy, register_tag


@pytest.fixture
def conn(database_url):
    connection = dbm.connect(database_url)
    dbm.init_db(connection)
    dbm.upsert_institution(
        connection, Institution(id="bank", display_name="Bank", country="HK")
    )
    dbm.upsert_account(
        connection,
        Account(
            id="acct", institution_id="bank", display_name="Current",
            account_type=AccountType.CHECKING, primary_currency="HKD",
        ),
    )
    connection.execute(
        "INSERT INTO statement_file (id,source_path,file_sha256,institution_id,account_id,"
        "file_format,parser_id,parser_version,imported_at,row_count) "
        "VALUES ('sf','x','x','bank','acct','csv','test','1',CURRENT_TIMESTAMP::text,0)"
    )
    connection.commit()
    yield connection
    connection.close()


def _txn(description: str, *, category: str | None = None,
         subcategory: str | None = None, merchant: str | None = None) -> Txn:
    return Txn(
        account_id="acct", txn_date=date(2026, 1, 1),
        booked=Money(amount=-1000, currency="HKD"), description_raw=description,
        statement_file_id="sf", category=category, subcategory=subcategory,
        merchant=merchant,
    )


def test_schema_seeds_the_category_pool(conn):
    taxonomy = load_taxonomy(conn)
    assert "dining" in taxonomy
    assert "coffee" in taxonomy["dining"]


def test_new_category_is_explicitly_added_to_the_pool(conn):
    add_category(conn, "Pets", "Veterinary Care")
    assert "veterinary_care" in load_taxonomy(conn)["pets"]


def test_add_tag_reuses_canonical_spelling(conn):
    first = register_tag(conn, "owner", "Japan Trip")
    second = register_tag(conn, "owner", "  japan   trip ")
    assert first["id"] == second["id"]
    assert second["display_name"] == "Japan Trip"
    assert conn.execute("SELECT COUNT(*) n FROM tag_definition").fetchone()["n"] == 1


def test_db_add_tag_always_uses_the_pool(conn):
    first, second = _txn("A"), _txn("B")
    dbm.insert_txns(conn, [first, second])
    dbm.add_tag(conn, first.id, "Project Alpha")
    dbm.add_tag(conn, second.id, " project   alpha ")
    conn.commit()
    tags = [row["tag"] for row in conn.execute("SELECT tag FROM txn_tag ORDER BY txn_id")]
    assert tags == ["Project Alpha", "Project Alpha"]


def test_backfill_propagates_only_unanimous_exact_evidence(conn):
    known = _txn(
        "BLUE BOTTLE IFC", category="dining", subcategory="coffee",
        merchant="Blue Bottle",
    )
    missing = _txn("BLUE BOTTLE IFC")
    dbm.insert_txns(conn, [known, missing])
    conn.commit()

    audit = audit_backfill(conn)
    assert audit["category_proposals"] == 1
    assert audit["merchant_proposals"] == 1
    assert audit["pool_changes"]["merchants"] == 1
    assert audit["pool_sizes"]["merchants"] == 0
    assert conn.execute("SELECT category FROM txn WHERE id=%s", (missing.id,)).fetchone()[
        "category"
    ] is None

    result = audit_backfill(conn, apply=True)
    updated = conn.execute(
        "SELECT category,subcategory,merchant FROM txn WHERE id=%s", (missing.id,)
    ).fetchone()
    assert dict(updated) == {
        "category": "dining", "subcategory": "coffee", "merchant": "Blue Bottle",
    }
    assert result["applied_categories"] == 1
    assert result["applied_merchants"] == 1
    assert result["pool_sizes"]["merchants"] == 1
    assert conn.execute("SELECT COUNT(*) n FROM merchant_definition").fetchone()["n"] == 1


def test_backfill_does_not_guess_when_categories_conflict(conn):
    rows = [
        _txn("CORNER SHOP", category="groceries", subcategory="convenience"),
        _txn("CORNER SHOP", category="shopping", subcategory="general"),
        _txn("CORNER SHOP"),
    ]
    dbm.insert_txns(conn, rows)
    conn.commit()
    result = audit_backfill(conn, apply=True)
    assert result["applied_categories"] == 0
    assert result["conflicts"]
    assert conn.execute("SELECT category FROM txn WHERE id=%s", (rows[-1].id,)).fetchone()[
        "category"
    ] is None


def test_tags_are_canonicalised_but_never_propagated(conn):
    first, second = _txn("HOTEL A"), _txn("HOTEL A")
    dbm.insert_txns(conn, [first, second])
    conn.execute(
        "INSERT INTO txn_tag (txn_id,tag,source,created_at) VALUES "
        "(%s,'Japan Trip','manual',CURRENT_TIMESTAMP::text),"
        "(%s,' japan   trip ','manual',CURRENT_TIMESTAMP::text)",
        (first.id, second.id),
    )
    third = _txn("HOTEL A")
    dbm.insert_txns(conn, [third])
    conn.commit()
    result = audit_backfill(conn, apply=True)
    assert result["canonicalised_tags"] == 1
    assert conn.execute("SELECT COUNT(*) n FROM txn_tag").fetchone()["n"] == 2
    assert conn.execute("SELECT COUNT(*) n FROM txn_tag WHERE txn_id=%s", (third.id,)).fetchone()[
        "n"
    ] == 0


def test_merchant_spelling_variants_resolve_to_one_canonical_name(conn):
    known_a = _txn(
        "BLUE BOTTLE IFC", category="dining", subcategory="coffee",
        merchant="Blue Bottle",
    )
    known_b = _txn(
        "BLUE BOTTLE IFC", category="dining", subcategory="coffee",
        merchant="BLUE BOTTLE",
    )
    missing = _txn("BLUE BOTTLE IFC")
    dbm.insert_txns(conn, [known_a, known_b, missing])
    conn.commit()
    result = audit_backfill(conn, apply=True)
    assert result["applied_merchants"] == 1
    assert conn.execute("SELECT merchant FROM txn WHERE id=%s", (missing.id,)).fetchone()[
        "merchant"
    ] == "Blue Bottle"
    assert conn.execute("SELECT COUNT(*) n FROM merchant_definition").fetchone()["n"] == 1


def test_unknown_category_pairs_are_reported_not_registered(conn):
    dbm.insert_txns(conn, [_txn("VET", category="pets", subcategory="veterinary")])
    conn.commit()
    result = audit_backfill(conn, apply=True)
    assert result["unknown_category_pairs"] == [
        {"category": "pets", "subcategory": "veterinary", "transactions": 1}
    ]
    assert "pets" not in load_taxonomy(conn)
