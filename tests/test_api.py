"""HTTP API.

The rule under test throughout: money crosses the wire as integer minor units
plus a currency code, and nothing ever sums across currencies.
"""

from __future__ import annotations

from pathlib import Path
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import pytest
from conftest import write_pdf
from fastapi.testclient import TestClient

from fin import db as dbm
from fin.ingest import ingest_file, reconcile

FIXTURES = Path(__file__).parent / "fixtures"


@pytest.fixture
def client(database_url, monkeypatch):
    """An API bound to a throwaway database seeded with the sample statements."""
    monkeypatch.setenv("DATABASE_URL", database_url)
    monkeypatch.setenv("FINTO_AUTH_USERNAME", "test-owner")
    monkeypatch.setenv("FINTO_AUTH_EMAIL", "owner@example.test")
    monkeypatch.setenv("FINTO_AUTH_PASSWORD", "correct horse battery staple")
    monkeypatch.setenv("FINTO_SESSION_SECRET", "test-session-secret-not-for-production")
    conn = dbm.connect(database_url)
    dbm.init_db(conn)
    from fin.models import Account, Card, Institution
    for inst in (Institution(id="hsbc_hk", display_name="HSBC HK", country="HK"),
                 Institution(id="wise", display_name="Wise", country="HK"),
                 Institution(id="amex_us", display_name="AMEX US", country="US"),
                 Institution(id="mox", display_name="Mox", country="HK")):
        dbm.upsert_institution(conn, inst)
    for acct in (
        Account(id="hsbc_hk_current", institution_id="hsbc_hk",
                display_name="HSBC Current", account_type="checking",
                primary_currency="HKD"),
        Account(id="wise_hkd", institution_id="wise", display_name="Wise HKD",
                account_type="multi_currency", primary_currency="HKD",
                balance_group="wise_personal"),
        Account(id="amex_us_main", institution_id="amex_us", display_name="AMEX US",
                account_type="credit_card", primary_currency="USD"),
        Account(id="mox_main", institution_id="mox", display_name="Mox",
                account_type="checking", primary_currency="HKD"),
    ):
        dbm.upsert_account(conn, acct)
    dbm.upsert_card(conn, Card(id="amex_us_main_primary", account_id="amex_us_main",
                               cardholder_name="ALEX E", last4="1001"))
    conn.commit()

    for name, inst, acct, ccy in (
        ("hsbc_sample.csv", "hsbc_hk", "hsbc_hk_current", "HKD"),
        ("wise_sample.csv", "wise", "wise_hkd", "HKD"),
        ("amex_us_sample.csv", "amex_us", "amex_us_main", "USD"),
    ):
        ingest_file(conn, FIXTURES / name, institution_id=inst,
                    account_id=acct, default_currency=ccy)
    reconcile(conn)
    conn.close()

    from fin.api.app import app
    with TestClient(app, base_url="https://testserver") as c:
        signed_in = c.post("/api/auth/login", json={
            "identifier": "test-owner",
            "password": "correct horse battery staple",
        })
        assert signed_in.status_code == 200
        yield c


# ---------------------------------------------------------------------------
# Basics
# ---------------------------------------------------------------------------

def test_health(client):
    client.post("/api/auth/logout")
    response = client.get("/api/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "storage": "postgresql"}


def test_database_authentication_and_user_preferences(client, database_url):
    bad = client.post("/api/auth/login", json={
        "identifier": "test-owner", "password": "wrong",
    })
    assert bad.status_code == 401

    login = client.post("/api/auth/login", json={
        "identifier": "owner@example.test",
        "password": "correct horse battery staple",
    })
    assert login.status_code == 200
    assert login.json()["user"]["username"] == "test-owner"
    assert "finto_session=" in login.headers["set-cookie"]
    assert "HttpOnly" in login.headers["set-cookie"]

    me = client.get("/api/auth/me")
    assert me.status_code == 200
    assert me.json()["email"] == "owner@example.test"
    changed = client.patch("/api/auth/preferences", json={
        "theme": "light", "language": "zh-Hant",
    })
    assert changed.json()["preferences"] == {
        "theme": "light", "language": "zh-Hant",
    }

    conn = dbm.connect(database_url)
    assert conn.execute("SELECT count(*) AS n FROM app_user").fetchone()["n"] == 1
    assert conn.execute(
        "SELECT count(*) AS n FROM account WHERE user_id='owner'"
    ).fetchone()["n"] == 4
    assert conn.execute(
        "SELECT password_hash <> %s AS salted FROM app_user WHERE id='owner'",
        ("correct horse battery staple",),
    ).fetchone()["salted"]
    conn.close()

    assert client.post("/api/auth/register", json={}).status_code == 404
    assert client.post("/api/auth/logout").status_code == 200
    assert client.get("/api/auth/me").status_code == 401


def test_agent_taxonomy_api_requires_confirmation_and_audits(
    client, database_url,
):
    created = client.post("/api/auth/api-keys", json={"name": "Backfill agent"})
    assert created.status_code == 200
    token = created.json()["key"]
    key_id = created.json()["id"]
    assert token.startswith("finto_")
    listed = client.get("/api/auth/api-keys").json()["keys"]
    assert listed[0]["prefix"] in token
    assert "key" not in listed[0]
    assert "ledger:write" in listed[0]["scopes"]
    auth = {"Authorization": f"Bearer {token}"}

    audit = client.get("/api/agent/taxonomy/audit", headers=auth)
    assert audit.status_code == 200
    assert audit.json()["applied"] is False
    assert client.post("/api/agent/taxonomy/apply", headers=auth).status_code == 409
    applied = client.post(
        "/api/agent/taxonomy/apply",
        headers={**auth, "X-Finto-Confirm": "apply-taxonomy"},
    )
    assert applied.status_code == 200
    assert applied.json()["applied"] is True

    rebuilt = client.post(
        "/api/agent/ledger/rebuild-transfers?month=2026-07&start_day=24&end_day=24",
        headers=auth,
    )
    assert rebuilt.status_code == 200
    assert rebuilt.json()["result"]["range"] == ["2026-07-24", "2026-07-24"]
    ledger = client.get(
        "/api/agent/ledger/transactions?date_from=2026-07-01&date_to=2026-07-31",
        headers=auth,
    )
    assert ledger.status_code == 200
    assert "items" in ledger.json()
    assert client.get(
        "/api/agent/ledger/transactions?date_from=2026-01-01&date_to=2026-07-31",
        headers=auth,
    ).status_code == 422

    conn = dbm.connect(database_url)
    rows = conn.execute(
        "SELECT subject,applied FROM agent_operation ORDER BY created_at"
    ).fetchall()
    assert rows == [
        {"subject": f"api-key:{key_id}", "applied": 0},
        {"subject": f"api-key:{key_id}", "applied": 1},
        {"subject": f"api-key:{key_id}", "applied": 1},
    ]
    conn.close()

    assert client.delete(f"/api/auth/api-keys/{key_id}").status_code == 200
    assert client.get("/api/agent/taxonomy/audit", headers=auth).status_code == 401


def test_agent_ledger_categorize_gates_apply_and_llm(client, monkeypatch):
    token = client.post("/api/auth/api-keys", json={"name": "cat"}).json()["key"]
    auth = {"Authorization": f"Bearer {token}"}

    audit = client.post("/api/agent/ledger/categorize", headers=auth)
    assert audit.status_code == 200
    assert audit.json()["applied"] is False
    assert "distinct_merchants" in audit.json()["result"]

    # Apply needs the explicit confirmation header.
    assert client.post(
        "/api/agent/ledger/categorize?apply=true", headers=auth).status_code == 409

    # With confirmation but no LLM key, it declines rather than guessing.
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    denied = client.post(
        "/api/agent/ledger/categorize?apply=true",
        headers={**auth, "X-Finto-Confirm": "apply-categorize"})
    assert denied.status_code == 503


def test_transactions_list(client):
    body = client.get("/api/transactions").json()
    assert body["total"] > 0
    assert len(body["items"]) == body["total"]
    assert body["review"] == {
        "total": body["total"], "unreviewed": body["total"],
        "confirmed": 0, "flagged": 0,
    }


def test_review_progress_updates_with_manual_confirmation(client):
    txn_id = client.get("/api/transactions").json()["items"][0]["id"]
    response = client.patch(
        f"/api/transactions/{txn_id}", json={"review_state": "confirmed"})
    assert response.status_code == 200

    review = client.get("/api/transactions").json()["review"]
    assert review["confirmed"] == 1
    assert review["unreviewed"] == review["total"] - 1


def test_category_suggestion_is_confidence_gated_and_not_applied(client, monkeypatch):
    from fin.llm.provider import EchoProvider

    body = client.get("/api/transactions").json()
    txn = next(item for item in body["items"] if not item["category"])
    provider = EchoProvider({"categorise": [{
        "i": 0, "category": "shopping", "subcategory": "general",
        "merchant": "Recognised merchant", "tags": [], "confidence": 0.91,
    }]})
    monkeypatch.setattr("fin.llm.provider.build_provider", lambda conn: provider)

    response = client.get(f"/api/transactions/{txn['id']}/category-suggestion")
    assert response.status_code == 200
    assert response.json()["suggestion"]["category"] == "shopping"
    # Suggestions are preview-only until the person taps the category.
    assert client.get(f"/api/transactions/{txn['id']}").json()["category"] is None


def test_transaction_totals_can_be_normalised(client):
    body = client.get("/api/transactions?convert_to=USD").json()
    assert body["normalised"]["net"]["currency"] == "USD"


def test_transactions_accept_an_exact_month_set(client):
    january = client.get("/api/transactions?months=2025-01").json()
    selected = client.get(
        "/api/transactions?months=2025-01&months=2025-03"
    ).json()
    assert january["total"] > 0
    assert selected["total"] == january["total"]
    assert client.get("/api/transactions?months=2025-13").status_code == 422


def test_flow_report_includes_external_nodes_for_account_sankey(client):
    body = client.get("/api/flows").json()
    assert "external_nodes" in body["normalised"]
    assert all(node["in"]["currency"] == "USD" and node["out"]["currency"] == "USD"
               for node in body["normalised"]["external_nodes"])


def test_positions_include_investment_snapshots_under_acl(client, database_url):
    from datetime import date

    from fin.investment import InvestmentSnapshot, SubaccountBalance, save_snapshot
    from fin.models import Account, Money

    conn = dbm.connect(database_url)
    dbm.upsert_account(conn, Account(
        id="hsbc_mpf_regular", institution_id="hsbc_hk", display_name="MPF Regular",
        account_type="investment", primary_currency="HKD", balance_group="hsbc_mpf",
    ))
    conn.commit()
    save_snapshot(conn, InvestmentSnapshot(
        as_of_date=date(2026, 7, 31), scheme="hsbc_mpf", currency="HKD",
        total_value=Money(amount=16593537, currency="HKD"), source="test",
        subaccounts=[SubaccountBalance(
            account_id="hsbc_mpf_regular", member_no=None,
            balance=Money(amount=16593537, currency="HKD"),
        )],
    ))
    save_snapshot(conn, InvestmentSnapshot(
        as_of_date=date(2026, 6, 30), scheme="hsbc_mpf", currency="HKD",
        total_value=Money(amount=16000000, currency="HKD"), source="test",
        subaccounts=[SubaccountBalance(
            account_id="hsbc_mpf_regular", member_no=None,
            balance=Money(amount=16000000, currency="HKD"),
        )],
    ))
    conn.close()

    body = client.get("/api/positions?convert_to=HKD").json()
    position = next(p for p in body["positions"] if p["account_id"] == "hsbc_mpf_regular")
    assert position["balance"]["amount"] == 16593537
    assert position["basis"] == "investment_snapshot"
    investment = next(
        r for r in body["normalised"]["by_type"] if r["account_type"] == "investment"
    )
    assert investment["balance"]["amount"] == 16593537

    scheme_history = client.get(
        "/api/investments/history?scheme=hsbc_mpf"
    ).json()
    assert [point["value"]["amount"] for point in scheme_history["points"]] == [
        16000000, 16593537,
    ]
    account_history = client.get(
        "/api/investments/history?scheme=hsbc_mpf&account_id=hsbc_mpf_regular"
    ).json()
    assert account_history["account_id"] == "hsbc_mpf_regular"
    assert account_history["points"][-1]["value"]["amount"] == 16593537


def test_money_is_integer_minor_units(client):
    """A float here would hand the ledger's rounding error to JavaScript."""
    item = client.get("/api/transactions").json()["items"][0]
    assert isinstance(item["booked"]["amount"], int)
    assert isinstance(item["booked"]["currency"], str)
    assert len(item["booked"]["currency"]) == 3


def test_empty_statement_advances_freshness_for_every_covered_account(
    client, database_url,
):
    """An idle month is coverage, including on a consolidated statement."""
    covered = (
        datetime.now(ZoneInfo("Asia/Hong_Kong")).date() - timedelta(days=10)
    ).isoformat()
    conn = dbm.connect(database_url)
    conn.execute(
        "INSERT INTO statement_file "
        "(id,source_path,file_sha256,institution_id,account_id,file_format,parser_id,"
        " parser_version,imported_at,row_count) "
        "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
        ("empty-aug", f"Mox_{covered}.pdf", "empty-aug-hash", "mox", "mox_main",
         "pdf", "pdf_statement", "2.0", covered, 0),
    )
    conn.execute(
        "INSERT INTO statement_file "
        "(id,source_path,file_sha256,institution_id,account_id,file_format,parser_id,"
        " parser_version,imported_at,row_count) "
        "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
        ("export-aug", f"AMEX_transactions_2025-01-01_to_{covered}.csv",
         "export-aug-hash", "amex_us", "amex_us_main", "csv", "amex_csv",
         "1.0", covered, 2),
    )
    conn.execute(
        "INSERT INTO balance_assertion "
        "(id,account_id,as_of_date,balance,currency,kind,statement_file_id) "
        "VALUES (%s,%s,%s,%s,%s,%s,%s)",
        ("empty-aug-wise", "wise_hkd", covered, 0, "HKD", "closing", "empty-aug"),
    )
    conn.commit()
    conn.close()

    rows = {r["account_id"]: r for r in client.get("/api/statement-freshness").json()["accounts"]}
    assert rows["mox_main"]["status"] == "current"
    assert rows["mox_main"]["statement_empty"] is True
    assert rows["wise_hkd"]["status"] == "current"
    assert rows["amex_us_main"]["status"] == "current"
    assert rows["amex_us_main"]["statement_date"] == covered
    assert rows["amex_us_main"]["statement_empty"] is False


def test_txn_dates_cover_freshness_when_the_file_has_no_period(
    client, database_url,
):
    """A Wise activity CSV has no printed statement date; the newest row is coverage."""
    covered = (
        datetime.now(ZoneInfo("Asia/Hong_Kong")).date() - timedelta(days=5)
    ).isoformat()
    conn = dbm.connect(database_url)
    conn.execute(
        "INSERT INTO statement_file "
        "(id,source_path,file_sha256,institution_id,account_id,file_format,parser_id,"
        " parser_version,imported_at,row_count) "
        "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
        ("wise-activity", "wise-new.csv", "wise-activity-hash", "wise", "wise_hkd",
         "csv", "wise_csv", "1.0", covered, 1),
    )
    conn.execute(
        "INSERT INTO txn (id,account_id,txn_date,status,amount_booked,currency_booked,"
        "description_raw,description_norm,kind,dedup_key,statement_file_id,"
        "created_at,updated_at) VALUES "
        "(%s,%s,%s,'posted',%s,%s,%s,%s,%s,%s,%s,%s,%s)",
        ("wise-activity-txn", "wise_hkd", covered, 20000, "HKD",
         "TOP UP", "TOP UP", "transfer", "wise-activity-dedup", "wise-activity",
         covered, covered),
    )
    conn.commit()
    conn.close()
    rows = {r["account_id"]: r for r in client.get("/api/statement-freshness").json()["accounts"]}
    assert rows["wise_hkd"]["statement_date"] == covered
    assert rows["wise_hkd"]["status"] == "current"


def test_closed_and_idle_accounts_are_not_statement_overdue(client, database_url):
    today = datetime.now(ZoneInfo("Asia/Hong_Kong")).date()
    old = (today - timedelta(days=80)).isoformat()
    conn = dbm.connect(database_url)
    from fin.models import Account
    dbm.upsert_account(conn, Account(
        id="amex_us_hysa", institution_id="amex_us",
        display_name="AMEX US High Yield Savings", account_type="savings",
        primary_currency="USD", closed_on=today - timedelta(days=30),
        watch_statements=False))
    dbm.upsert_account(conn, Account(
        id="wise_krw", institution_id="wise", display_name="Wise KRW",
        account_type="multi_currency", primary_currency="KRW",
        balance_group="wise_personal", watch_statements=False))
    dbm.upsert_account(conn, Account(
        id="hsbc_mpf", institution_id="hsbc_hk", display_name="HSBC MPF",
        account_type="investment", primary_currency="HKD"))
    for aid, sha in (("amex_us_hysa", "hysa-hash"), ("wise_krw", "krw-hash"),
                     ("hsbc_mpf", "mpf-hash")):
        conn.execute(
            "INSERT INTO statement_file "
            "(id,source_path,file_sha256,institution_id,account_id,file_format,parser_id,"
            " parser_version,imported_at,row_count,statement_date) "
            "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
            (f"stale-{aid}", f"{aid}.pdf", sha, "amex_us" if "amex" in aid else
             ("wise" if "wise" in aid else "hsbc_hk"),
             aid, "pdf", "pdf_statement", "2.0", old, 1, old),
        )
    conn.commit()
    conn.close()

    body = client.get("/api/statement-freshness").json()
    rows = {r["account_id"]: r for r in body["accounts"]}
    assert rows["amex_us_hysa"]["status"] == "closed"
    assert rows["wise_krw"]["status"] == "ignored"
    assert rows["hsbc_mpf"]["status"] == "ignored"
    assert rows["amex_us_hysa"]["account_id"] not in {
        r["account_id"] for r in body["accounts"] if r["status"] == "stale"
    }


def test_create_account_with_card(client):
    created = client.post("/api/accounts", json={
        "id": "hsbc_hk_red",
        "institution_id": "hsbc_hk",
        "display_name": "HSBC Red",
        "account_type": "credit_card",
        "primary_currency": "hkd",
        "masked_number": "****3757",
        "aliases": ["HSBC RED"],
        "cardholder_name": "ZHOU YIXIANG",
        "last4": "3757",
    })
    assert created.status_code == 201
    assert created.json()["id"] == "hsbc_hk_red"
    assert created.json()["primary_currency"] == "HKD"
    assert created.json()["watch_statements"] is True
    cards = client.get("/api/cards").json()["cards"]
    assert any(
        card["account_id"] == "hsbc_hk_red" and card["last4"] == "3757"
        for card in cards
    )
    again = client.post("/api/accounts", json={
        "id": "hsbc_hk_red",
        "institution_id": "hsbc_hk",
        "display_name": "HSBC Red",
        "account_type": "credit_card",
        "primary_currency": "HKD",
    })
    assert again.status_code == 409


def test_patch_account_marks_closed_and_stops_watching(client):
    listed = {a["id"]: a for a in client.get("/api/accounts").json()["accounts"]}
    assert listed["amex_us_main"]["watch_statements"] is True
    assert listed["amex_us_main"]["closed_on"] is None

    closed = client.patch("/api/accounts/amex_us_main", json={
        "closed_on": "2026-01-15", "watch_statements": False,
    })
    assert closed.status_code == 200
    assert closed.json()["closed_on"] == "2026-01-15"
    assert closed.json()["watch_statements"] is False
    assert client.get("/api/statement-freshness").json()["accounts"]
    row = next(r for r in client.get("/api/statement-freshness").json()["accounts"]
               if r["account_id"] == "amex_us_main")
    assert row["status"] == "closed"

    reopened = client.patch("/api/accounts/amex_us_main", json={
        "closed_on": None, "watch_statements": True,
    })
    assert reopened.status_code == 200
    assert reopened.json()["closed_on"] is None
    assert reopened.json()["watch_statements"] is True


def test_transfers_are_excluded_by_default(client):
    """Money moved between your own accounts is not spending."""
    default = client.get("/api/transactions").json()["total"]
    with_transfers = client.get(
        "/api/transactions?includeTransfers=true").json()["total"]
    assert with_transfers > default


def test_filters_narrow_results(client):
    body = client.get("/api/transactions?accounts=amex_us_main").json()
    assert body["total"] > 0
    assert all(i["account_id"] == "amex_us_main" for i in body["items"])


def test_date_range_filter(client):
    body = client.get("/api/transactions?from=2025-01-01&to=2025-01-10").json()
    assert all("2025-01-01" <= i["date"] <= "2025-01-10" for i in body["items"])


def test_text_search(client):
    body = client.get("/api/transactions?q=STARBUCKS").json()
    assert body["total"] >= 1
    assert any("STARBUCKS" in i["description"].upper() for i in body["items"])


def test_pagination(client):
    first = client.get("/api/transactions?limit=2&offset=0").json()
    second = client.get("/api/transactions?limit=2&offset=2").json()
    assert len(first["items"]) == 2
    assert first["total"] > 0
    assert "totals" in first
    assert second["total"] is None
    assert "totals" not in second
    assert {i["id"] for i in first["items"]} & {i["id"] for i in second["items"]} == set()


def test_transaction_detail_includes_provenance(client):
    txn_id = client.get("/api/transactions").json()["items"][0]["id"]
    body = client.get(f"/api/transactions/{txn_id}").json()
    # Every number must trace back to a line in a file you downloaded.
    assert "provenance" in body
    assert "raw_row" in body["provenance"]
    assert body["provenance"]["parser_id"]


def test_refund_links_are_bidirectional(client, database_url):
    original = client.get("/api/transactions").json()["items"][0]
    conn = dbm.connect(database_url)
    from datetime import date

    from fin.models import Money, Txn
    source = conn.execute(
        "SELECT statement_file_id FROM txn WHERE id=%s", (original["id"],)
    ).fetchone()["statement_file_id"]
    refund = Txn(
        account_id=original["account_id"], txn_date=date.fromisoformat(original["date"]),
        booked=Money(amount=100, currency=original["booked"]["currency"]),
        description_raw="REFUND TEST", kind="refund", refund_of_id=original["id"],
        statement_file_id=source,
    )
    dbm.insert_txns(conn, [refund])
    conn.commit()
    conn.close()

    purchase_detail = client.get(f"/api/transactions/{original['id']}").json()
    refund_detail = client.get(f"/api/transactions/{refund.id}").json()
    assert any(link["id"] == refund.id and link["relation"] == "refund"
               for link in purchase_detail["related_transactions"])
    assert any(link["id"] == original["id"] and link["relation"] == "purchase"
               for link in refund_detail["related_transactions"])


def test_unknown_transaction_is_404(client):
    assert client.get("/api/transactions/nope").status_code == 404


def test_patch_records_a_manual_annotation(client):
    txn_id = client.get("/api/transactions").json()["items"][0]["id"]
    r = client.patch(f"/api/transactions/{txn_id}",
                     json={"category": "dining", "notes": "team lunch"})
    assert r.status_code == 200
    assert r.json()["category"] == "dining"

    again = client.get(f"/api/transactions/{txn_id}").json()
    assert again["category"] == "dining"
    assert again["notes"] == "team lunch"


def test_patch_can_record_the_foreign_amount(client):
    txn_id = client.get("/api/transactions").json()["items"][0]["id"]
    r = client.patch(
        f"/api/transactions/{txn_id}",
        json={"native": {"amount": 3490, "currency": "cny"}},
    )
    assert r.status_code == 200
    assert r.json()["native"] == {"amount": 3490, "currency": "CNY"}

    bad = client.patch(
        f"/api/transactions/{txn_id}",
        json={"native": {"amount": 1, "currency": "renminbi"}},
    )
    assert bad.status_code == 422


def test_patch_can_void_a_row_that_never_happened(client):
    page = client.get("/api/transactions").json()
    txn_id = page["items"][0]["id"]
    assert client.patch(f"/api/transactions/{txn_id}", json={"status": "void"}).status_code == 200
    ids = [t["id"] for t in client.get("/api/transactions").json()["items"]]
    assert txn_id not in ids


def test_patch_can_rewrite_a_bad_statement_description(client):
    txn_id = client.get("/api/transactions").json()["items"][0]["id"]
    r = client.patch(
        f"/api/transactions/{txn_id}",
        json={"merchant": "Mox", "description": "Mox"},
    )
    assert r.status_code == 200
    body = r.json()
    assert body["merchant"] == "Mox"
    assert body["description"] == "Mox"


def test_patch_can_clear_subcategory_when_category_changes(client, conn):
    txn_id = client.get("/api/transactions").json()["items"][0]["id"]
    categorised = client.patch(
        f"/api/transactions/{txn_id}",
        json={"category": "dining", "subcategory": "coffee"},
    )
    assert categorised.status_code == 200

    changed = client.patch(
        f"/api/transactions/{txn_id}",
        json={"category": "shopping", "subcategory": None},
    )
    assert changed.status_code == 200
    assert changed.json()["category"] == "shopping"
    assert changed.json()["subcategory"] is None
    annotation = conn.execute(
        "SELECT value,source FROM txn_annotation WHERE txn_id=%s AND field='subcategory'",
        (txn_id,),
    ).fetchone()
    assert annotation["value"] is None
    assert annotation["source"] == "manual"


# ---------------------------------------------------------------------------
# Currency discipline
# ---------------------------------------------------------------------------

def test_positions_are_per_account_and_currency(client):
    body = client.get("/api/positions").json()
    assert body["positions"]
    for p in body["positions"]:
        assert p["balance"]["currency"] == p["currency"]
    # HSBC (HKD) and AMEX (USD) must be separate rows, never merged.
    pairs = {(p["account_id"], p["currency"]) for p in body["positions"]}
    assert ("hsbc_hk_current", "HKD") in pairs
    assert ("amex_us_main", "USD") in pairs


def test_summary_buckets_are_currency_scoped(client):
    body = client.get("/api/summary?group_by=month").json()
    for row in body["rows"]:
        assert row["net"]["currency"] == row["currency"]


def test_summary_totals_are_per_currency(client):
    totals = client.get("/api/summary").json()["totals"]
    assert len({t["currency"] for t in totals}) == len(totals)


def test_conversion_is_additive_and_labelled(client):
    """A converted figure never replaces the native one."""
    client.post("/api/fx/harvest")
    body = client.get("/api/positions?convert_to=USD").json()
    assert body["conversion"]["to"] == "USD"
    for p in body["positions"]:
        assert p["balance"]["currency"] == p["currency"]     # native intact
        conv = p["balance_converted"]
        assert "rate" in conv and "converted" in conv and "ok" in conv


def test_unconvertible_currencies_are_reported(client):
    body = client.get("/api/positions?convert_to=USD").json()
    # With no HKD/USD rate loaded, HKD must be declared unconvertible rather
    # than silently omitted from a "total".
    assert "unconvertible_currencies" in body["conversion"]


def test_invalid_group_by_is_rejected(client):
    r = client.get("/api/summary?group_by=DROP TABLE txn")
    assert r.status_code == 400


@pytest.mark.parametrize("dimension", [
    "month", "quarter", "year", "category", "merchant", "account",
    "institution", "card", "kind", "currency",
])
def test_all_aggregation_levels_work(client, dimension):
    r = client.get(f"/api/summary?group_by={dimension}")
    assert r.status_code == 200
    assert "rows" in r.json()


# ---------------------------------------------------------------------------
# Import flow
# ---------------------------------------------------------------------------

def test_import_capabilities_come_from_registered_formats(client):
    body = client.get("/api/imports/capabilities").json()
    assert set(body["extensions"]) == {".csv", ".pdf", ".tsv", ".txt"}
    assert ".xlsx" not in body["extensions"]
    assert any(item["id"] == "generic_csv" for item in body["formats"])
    assert any(item["id"] == "hsbc_hk_savings" for item in body["formats"])
    assert all(item["label"] for item in body["formats"])
    assert body["contribution"]["guide"].endswith("wiki/Statement-Formats")

def test_stage_previews_without_importing(client):
    before = client.get("/api/transactions").json()["total"]
    with (FIXTURES / "hsbc_sample.csv").open("rb") as f:
        r = client.post("/api/imports/stage",
                        files={"file": ("new.csv", f, "text/csv")},
                        data={"institution_id": "hsbc_hk",
                              "account_id": "hsbc_hk_current", "currency": "HKD"})
    body = r.json()
    assert body["parser"] == "hsbc_hk_csv"
    assert len(body["sha256"]) == 64
    assert body["txn_count"] == 8
    assert len(body["sample"]) > 0
    assert body["header"] == ["Date", "Transaction Details", "Deposit",
                              "Withdrawal", "Balance"]
    # Nothing has reached the ledger yet.
    assert client.get("/api/transactions").json()["total"] == before


def test_stage_then_confirm_imports(client, tmp_path):
    csv = tmp_path / "extra.csv"
    csv.write_text(
        "Date,Transaction Details,Deposit,Withdrawal,Balance\n"
        "05/02/2025,NEW MERCHANT LTD,,123.45,51354.80\n")
    with csv.open("rb") as f:
        staged = client.post("/api/imports/stage",
                             files={"file": ("extra.csv", f, "text/csv")},
                             data={"institution_id": "hsbc_hk",
                                   "account_id": "hsbc_hk_current",
                                   "currency": "HKD"}).json()
    assert staged["txn_count"] == 1

    with csv.open("rb") as f:
        result = client.post(
            "/api/imports/confirm",
            files={"file": ("extra.csv", f, "text/csv")},
            data={
                "expected_sha256": staged["sha256"],
                "institution_id": "hsbc_hk",
                "account_id": "hsbc_hk_current",
                "currency": "HKD",
            },
        ).json()
    assert result["import"]["status"] == "imported"
    assert result["reconcile"]["transactions"] > 0

    found = client.get("/api/transactions?q=NEW MERCHANT").json()
    assert found["total"] == 1


def test_existing_statement_reprocess_is_bounded(
    client, database_url, monkeypatch,
):
    conn = dbm.connect(database_url)
    statement = conn.execute(
        "SELECT sf.id,MIN(t.txn_date) AS period_start,"
        "MAX(t.txn_date) AS period_end "
        "FROM statement_file sf JOIN txn t ON t.statement_file_id=sf.id "
        "GROUP BY sf.id LIMIT 1"
    ).fetchone()
    conn.close()
    captured = {}

    from fin.api.routers import imports as imports_router

    def bounded_reconcile(conn, **kwargs):
        captured.update(kwargs)
        return {"range": [
            kwargs["from_date"].isoformat(),
            kwargs["to_date"].isoformat(),
        ]}

    monkeypatch.setattr(imports_router, "reconcile", bounded_reconcile)
    response = client.post(f"/api/imports/{statement['id']}/reprocess")

    assert response.status_code == 200
    assert captured["from_date"].isoformat() < str(statement["period_start"])
    assert captured["to_date"].isoformat() > str(statement["period_end"])


def test_staging_a_scanned_pdf_explains_the_problem(client, tmp_path):
    blank = write_pdf(tmp_path / "scan.pdf", [])
    with blank.open("rb") as f:
        body = client.post("/api/imports/stage",
                           files={"file": ("scan.pdf", f, "application/pdf")},
                           data={"account_id": "mox_main"}).json()
    assert body.get("error")
    assert "text layer" in body["error"].lower()


def test_unsupported_file_type_is_rejected(client, tmp_path):
    f = tmp_path / "notes.docx"
    f.write_bytes(b"PK\x03\x04garbage")
    with f.open("rb") as fh:
        r = client.post("/api/imports/stage",
                        files={"file": ("notes.docx", fh, "application/octet-stream")})
    assert r.status_code == 400


def test_confirm_rejects_bytes_that_differ_from_preview(client, tmp_path):
    original = tmp_path / "original.csv"
    original.write_text(
        "Date,Transaction Details,Deposit,Withdrawal,Balance\n"
        "05/02/2025,ORIGINAL,,1.00,100.00\n")
    changed = tmp_path / "changed.csv"
    changed.write_text(
        "Date,Transaction Details,Deposit,Withdrawal,Balance\n"
        "05/02/2025,CHANGED,,2.00,99.00\n")
    with original.open("rb") as file:
        preview = client.post(
            "/api/imports/preview",
            files={"file": ("original.csv", file, "text/csv")},
            data={"institution_id": "hsbc_hk", "account_id": "hsbc_hk_current"},
        ).json()
    with changed.open("rb") as file:
        response = client.post(
            "/api/imports/confirm",
            files={"file": ("changed.csv", file, "text/csv")},
            data={
                "expected_sha256": preview["sha256"],
                "institution_id": "hsbc_hk",
                "account_id": "hsbc_hk_current",
            },
        )
    assert response.status_code == 409


def test_agent_can_preview_and_confirm_without_server_staging(client, tmp_path):
    token = client.post("/api/auth/api-keys", json={"name": "Importer"}).json()["key"]
    auth = {"Authorization": f"Bearer {token}"}
    csv = tmp_path / "agent.csv"
    csv.write_text(
        "Date,Transaction Details,Deposit,Withdrawal,Balance\n"
        "06/02/2025,AGENT IMPORT,,3.00,97.00\n")
    with csv.open("rb") as file:
        preview = client.post(
            "/api/agent/imports/preview", headers=auth,
            files={"file": ("agent.csv", file, "text/csv")},
            data={"institution_id": "hsbc_hk", "account_id": "hsbc_hk_current"},
        )
    assert preview.status_code == 200
    with csv.open("rb") as file:
        confirmed = client.post(
            "/api/agent/imports/confirm", headers=auth,
            files={"file": ("agent.csv", file, "text/csv")},
            data={
                "expected_sha256": preview.json()["sha256"],
                "institution_id": "hsbc_hk",
                "account_id": "hsbc_hk_current",
            },
        )
    assert confirmed.status_code == 200
    assert confirmed.json()["import"]["status"] == "imported"
    history = client.get("/api/agent/imports/history", headers=auth)
    assert history.status_code == 200
    assert any(item["source_path"] == "agent.csv" for item in history.json()["files"])
    investments = client.get("/api/agent/investments", headers=auth)
    assert investments.status_code == 200
    assert "snapshots" in investments.json()
    activities = client.get("/api/agent/investments/activities", headers=auth)
    assert activities.status_code == 200
    assert "activities" in activities.json()
    integrity = client.get("/api/agent/integrity", headers=auth)
    assert integrity.status_code == 200
    assert integrity.json()["summary"]["violation_count"] == 0


def test_reconcile_returns_committed_result_directly(client):
    result = client.post("/api/reconcile").json()
    assert "transactions" in result


# ---------------------------------------------------------------------------
# Review, integrity, reference data
# ---------------------------------------------------------------------------

def test_review_queues_respond(client):
    for queue in ("duplicates", "transfers", "installments"):
        r = client.get(f"/api/review/{queue}")
        assert r.status_code == 200
        assert "items" in r.json()


def test_integrity_reports_health(client):
    body = client.get("/api/integrity").json()
    assert "healthy" in body
    assert body["summary"]["violation_count"] == 0
    # Accounts with no balance assertion are unverified, not healthy.
    assert "unverified_accounts" in body


def test_accounts_expose_settlement_currencies(client):
    accounts = {a["id"]: a for a in client.get("/api/accounts").json()["accounts"]}
    assert accounts["amex_us_main"]["settlement_currencies"] == ["USD"]


def test_cards_expose_lineage(client):
    cards = client.get("/api/cards").json()["cards"]
    assert cards
    assert all("lineage_root" in c for c in cards)


def test_facets_populate_filters(client):
    body = client.get("/api/facets").json()
    for key in ("accounts", "cards", "institutions", "categories", "kinds",
                "currencies", "date_range"):
        assert key in body


def test_stats(client):
    body = client.get("/api/stats").json()
    assert body["transactions"] > 0
    assert isinstance(body["positions"], list)


def test_installments_endpoint(client):
    body = client.get("/api/installments").json()
    assert "plans" in body
    assert "outstanding_by_currency" in body


def test_query_without_llm_explains_itself(client):
    body = client.post("/api/query", json={"question": "how much on dining?"}).json()
    assert body["ok"] is False
    assert body["error"] == "Ask is not configured."


def test_concurrent_requests_all_succeed(client):
    """Every other test issues one request at a time; a browser does not.

    A sync dependency and the sync endpoint consuming it are two separate
    threadpool submissions, so the connection is opened on one worker thread and
    used on another. Serialised, AnyIO hands back the same idle worker and the
    mismatch never shows. Load one page that fetches four endpoints at once and
    This checks that request-scoped database connections stay independent.
    """
    from concurrent.futures import ThreadPoolExecutor

    paths = ["/api/stats", "/api/positions", "/api/summary", "/api/facets",
             "/api/integrity", "/api/installments", "/api/transactions"]
    with ThreadPoolExecutor(max_workers=len(paths)) as pool:
        responses = list(pool.map(lambda p: (p, client.get(p)), paths * 3))

    failed = [(p, r.status_code) for p, r in responses if r.status_code != 200]
    assert not failed, f"concurrent requests failed: {failed}"


def test_reading_integrity_does_not_write(client, conn):
    """Reconciliation is arithmetic over stored data, so a GET must not persist.

    It used to append an audit row per account per request. That grew the table
    on every page refresh, and — because two readers each tried to upgrade the
    same deferred transaction to a write — deadlocked outright under load.
    """
    def audit_rows() -> int:
        return conn.execute("SELECT COUNT(*) AS n FROM reconciliation_check").fetchone()["n"]

    before = audit_rows()
    for _ in range(3):
        assert client.get("/api/integrity").status_code == 200
    assert audit_rows() == before


# ---------------------------------------------------------------------------
# Spending semantics
# ---------------------------------------------------------------------------

def test_spending_only_drops_unlinked_money_movement(client, database_url):
    """A card payment the linker has not paired is still not spending."""
    conn = dbm.connect(database_url)
    target = conn.execute(
        "SELECT id FROM txn WHERE amount_booked < 0 AND transfer_group_id IS NULL "
        "AND kind = 'purchase' ORDER BY amount_booked LIMIT 1").fetchone()["id"]
    conn.execute("UPDATE txn SET kind='cc_payment', category='fees' WHERE id=%s", (target,))
    conn.commit()
    conn.close()

    every = client.get("/api/transactions").json()["items"]
    spending = client.get("/api/transactions?spendingOnly=true").json()["items"]
    assert target in {row["id"] for row in every}
    assert target not in {row["id"] for row in spending}
    assert all(row["kind"] not in ("cc_payment", "fx_conversion") for row in spending)
    assert all(row.get("category") != "transfers" for row in spending)

    fees = client.get("/api/summary?group_by=category&spendingOnly=true").json()["rows"]
    assert all(row["bucket"] != "transfers" for row in fees)


def test_spending_only_keeps_a_categorised_bank_transfer(client, database_url):
    """Paying a contractor by bank transfer is spending; its category says so."""
    conn = dbm.connect(database_url)
    target = conn.execute(
        "SELECT id FROM txn WHERE amount_booked < 0 AND transfer_group_id IS NULL "
        "AND kind = 'purchase' ORDER BY amount_booked LIMIT 1").fetchone()["id"]
    conn.execute("UPDATE txn SET kind='transfer', category='services' WHERE id=%s", (target,))
    conn.commit()
    conn.close()
    spending = client.get("/api/transactions?spendingOnly=true&limit=1000").json()["items"]
    assert target in {row["id"] for row in spending}


def test_summary_reports_how_far_the_ledger_runs(client):
    """Views anchor "latest period" on data, not on the wall clock."""
    body = client.get("/api/summary?from=2999-01-01").json()
    assert body["rows"] == []
    every = client.get("/api/transactions?limit=1000").json()["items"]
    assert body["ledger_through"] == max(row["date"] for row in every)

    # The rest of the scope still applies: a spending view anchors on spending.
    spending = client.get("/api/transactions?spendingOnly=true&limit=1000").json()["items"]
    scoped = client.get("/api/summary?from=2999-01-01&spendingOnly=true").json()
    assert scoped["ledger_through"] == max(row["date"] for row in spending)


def test_composition_never_lists_other_twice(client, database_url):
    from fin import reporting

    conn = dbm.connect(database_url)
    keep = conn.execute(
        "SELECT category FROM txn WHERE category IS NOT NULL AND amount_booked < 0 "
        "GROUP BY category ORDER BY SUM(amount_booked) DESC LIMIT 1").fetchone()["category"]
    conn.execute("UPDATE txn SET category='other' WHERE category IS DISTINCT FROM %s", (keep,))
    conn.commit()
    body = reporting.composition(conn, dimension="category", to_currency="HKD", limit=1)
    conn.close()
    buckets = [series["bucket"] for series in body["series"]]
    assert buckets.count("other") == 1


# ---------------------------------------------------------------------------
# Import review
# ---------------------------------------------------------------------------

def _stage(client, path, name="extra.csv", **extra):
    with path.open("rb") as f:
        return client.post("/api/imports/preview",
                           files={"file": (name, f, "text/csv")},
                           data={"institution_id": "hsbc_hk",
                                 "account_id": "hsbc_hk_current",
                                 "currency": "HKD", **extra}).json()


def _new_statement(tmp_path):
    csv = tmp_path / "extra.csv"
    csv.write_text(
        "Date,Transaction Details,Deposit,Withdrawal,Balance\n"
        "05/02/2025,NEW MERCHANT LTD,,123.45,51354.80\n")
    return csv


def test_preview_flags_a_statement_already_in_the_ledger(client):
    preview = _stage(client, FIXTURES / "hsbc_sample.csv", name="again.csv")
    quality = preview["quality"]
    assert quality["grade"] == "bad"
    ids = {c["id"] for c in quality["checks"]}
    assert "duplicate_file" in ids
    assert any(i.startswith("present:") for i in ids)


def test_preview_of_new_rows_names_the_account_and_passes(client, tmp_path):
    preview = _stage(client, _new_statement(tmp_path))
    quality = preview["quality"]
    assert quality["grade"] != "bad"
    account = next(c for c in quality["checks"] if c["id"] == "account")
    assert account["level"] == "ok" and "HSBC Current" in account["title"]
    assert preview["totals"]["HKD"]["out"] == -12345
    # The model layer is off in tests, so asking for a verdict adds none.
    assert "agent" not in _stage(client, _new_statement(tmp_path), review="true")


def _confirm(client, path, preview):
    with path.open("rb") as f:
        return client.post("/api/imports/confirm",
                           files={"file": (path.name, f, "text/csv")},
                           data={"expected_sha256": preview["sha256"],
                                 "institution_id": "hsbc_hk",
                                 "account_id": "hsbc_hk_current",
                                 "currency": "HKD"}).json()


def test_review_categorises_the_new_rows_and_is_stored(client, tmp_path, monkeypatch):
    from fin.llm import provider as provider_module

    echo = provider_module.EchoProvider({
        "You categorise": [{"i": 0, "category": "shopping", "subcategory": "general",
                            "merchant": "New Merchant", "tags": ["online"],
                            "confidence": 0.9}],
        "You review a statement": {"status": "clean", "headline": "Imported cleanly.",
                                   "notes": []},
    })
    monkeypatch.setattr(provider_module, "build_provider", lambda *a, **k: echo)

    path = _new_statement(tmp_path)
    result = _confirm(client, path, _stage(client, path))
    statement_id = result["import"]["statement_file_id"]

    report = client.post(f"/api/imports/{statement_id}/review").json()
    assert report["categories"]["by_source"].get("model") == 1
    assert report["categories"]["tags"] >= 1
    assert report["summary"]["status"] == "clean"

    rows = client.get(f"/api/transactions?statements={statement_id}").json()
    assert rows["total"] == 1
    assert rows["items"][0]["category"] == "shopping"
    assert rows["items"][0]["merchant"] == "New Merchant"

    stored = client.get(f"/api/imports/{statement_id}/review").json()["review"]
    assert stored["summary"]["headline"] == "Imported cleanly."
    history = client.get("/api/imports/history").json()["files"]
    assert next(f for f in history if f["id"] == statement_id)["review_status"] == "clean"


def test_review_puts_a_card_payment_back_under_transfers(
    client, tmp_path, database_url, monkeypatch,
):
    from fin.llm import provider as provider_module

    monkeypatch.setattr(provider_module, "build_provider",
                        lambda *a, **k: provider_module.NullProvider())
    path = _new_statement(tmp_path)
    statement_id = _confirm(client, path, _stage(client, path))["import"]["statement_file_id"]
    conn = dbm.connect(database_url)
    conn.execute("UPDATE txn SET kind='cc_payment', category='fees', subcategory='card' "
                 "WHERE statement_file_id=%s", (statement_id,))
    conn.commit()
    conn.close()

    report = client.post(f"/api/imports/{statement_id}/review").json()
    assert report["categories"]["movement_fixed"] == 1
    assert report["links"]["unlinked_count"] == 1
    # Without a model the summary is still written, from the facts alone.
    assert report["summary"]["status"] == "attention"
    row = client.get(
        f"/api/transactions?statements={statement_id}&includeTransfers=true").json()["items"][0]
    assert (row["category"], row["subcategory"]) == ("transfers", "card_payment")


def test_review_of_an_unknown_statement_is_404(client):
    assert client.post("/api/imports/nope/review").status_code == 404


# ---------------------------------------------------------------------------
# Ask streaming
# ---------------------------------------------------------------------------

class _ScriptedAsk:
    """Calls one real tool, then answers, emitting what a streaming model would."""

    name = "scripted"
    model = "scripted"

    def __init__(self):
        self.prompts = []

    def _run(self, user, execute, emit):
        from fin.llm.provider import LLMResponse
        self.prompts.append(user)
        call = {"filter": {"categories": ["dining"]}}
        if emit:
            emit("tool", {"id": "t1", "name": "ledger_totals", "input": call, "state": "running"})
        execute("ledger_totals", call)
        if emit:
            emit("tool", {"id": "t1", "name": "ledger_totals", "input": call, "state": "done"})
            emit("text", {"delta": "Dining was "})
            emit("text", {"delta": "modest."})
        return LLMResponse(data={"answer": "Dining was modest."}, model="scripted",
                           raw="Dining was modest.", metadata={"usage": {}})

    def complete_with_tools(self, system, user, tools, execute, **_):
        return self._run(user, execute, None)

    def stream_with_tools(self, system, user, tools, execute, emit, **_):
        return self._run(user, execute, emit)


def _sse(body: str) -> list[tuple[str, dict]]:
    import json as _json
    out = []
    for block in body.split("\n\n"):
        kind = data = None
        for line in block.splitlines():
            if line.startswith("event: "):
                kind = line[7:]
            elif line.startswith("data: "):
                data = _json.loads(line[6:])
        if kind:
            out.append((kind, data))
    return out


def test_ask_streams_steps_figures_text_then_the_result(client, monkeypatch):
    from fin.api.routers import query as query_router

    scripted = _ScriptedAsk()
    monkeypatch.setattr(query_router, "build_provider", lambda *a, **k: scripted)
    response = client.post("/api/query/stream", json={"question": "dining?"})
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    events = _sse(response.text)
    kinds = [k for k, _ in events]
    assert kinds[0] == "tool" and kinds[-1] == "done"
    assert events[0][1]["label"].startswith("Totalling") and "dining" in events[0][1]["label"]
    data = next(d for k, d in events if k == "data")
    assert data["filter"] == {"categories": ["dining"]} and "totals" in data
    assert "".join(d["delta"] for k, d in events if k == "text") == "Dining was modest."
    done = events[-1][1]
    assert done["ok"] and done["answer"] == "Dining was modest."


def test_ask_follow_up_carries_the_earlier_turn(client, monkeypatch):
    from fin.api.routers import query as query_router

    scripted = _ScriptedAsk()
    monkeypatch.setattr(query_router, "build_provider", lambda *a, **k: scripted)
    client.post("/api/query", json={
        "question": "and in September?",
        "history": [{"question": "dining in October?", "answer": "HKD 4,239.77."}],
    })
    assert "dining in October?" in scripted.prompts[-1]
    assert scripted.prompts[-1].rstrip().endswith("and in September?")


# ---------------------------------------------------------------------------
# A card statement for an account the ledger does not have yet
# ---------------------------------------------------------------------------

def _card_statement(tmp_path, last4="4242"):
    """A one-page HSBC HK card statement for a card the ledger has never seen."""
    def row(settled, traded, description, amount):
        return f"{settled:<10}{traded:<6}{description:<59}{amount:>21}"

    return write_pdf(tmp_path / f"card-{last4}.pdf", [
        "Statement of HSBC Red Credit Card Account",
        f"{'':<52}Statement date    Statement balance",
        f"{'':<52}06 NOV 2026       HKD0.00",
        "Post date Trans date Description of transaction            Amount (HKD)",
        f"{'':<16}PREVIOUS BALANCE{'':<59}0.00",
        f"{'':<16}5555 0000 0000 {last4} ALEX SAMPLE",
        row("30OCT", "28OCT", "TEST TRAVEL HONG KONG HK", "1,602.16"),
        row("02NOV", "02NOV", "PPS PAYMENT - THANK YOU", "1,602.16CR"),
        f"{'':<16}Note: \"CR\" means Credit transaction / balance",
        f"{'':<16}STATEMENT BALANCE{'':<58}0.00",
        f"{'':<16}***** TRANSACTION SUMMARY *****",
    ])


def _post_pdf(client, path, endpoint="/api/imports/preview", **data):
    with path.open("rb") as f:
        return client.post(endpoint, files={"file": (path.name, f, "application/pdf")},
                           data=data)


def test_card_statement_with_no_account_names_the_issuer_and_card(client, tmp_path):
    preview = _post_pdf(client, _card_statement(tmp_path)).json()
    account = next(c for c in preview["quality"]["checks"] if c["id"] == "account")
    assert account["level"] == "bad"
    # Enough to file a new account under: the issuer, the card, the date.
    assert preview["institution_hint"] == "hsbc_hk"
    assert preview["cards"] == [{"last4": "4242", "holder": "ALEX SAMPLE", "rows": 2}]
    assert preview["statement_date"] == "2026-11-06"


def test_new_card_account_takes_its_statement_by_card_number(client, tmp_path):
    created = client.post("/api/accounts", json={
        "id": "hsbc_hk_card_4242", "institution_id": "hsbc_hk",
        "display_name": "HSBC Red", "account_type": "credit_card",
        "primary_currency": "HKD", "last4": "4242", "cardholder_name": "ALEX SAMPLE",
    })
    assert created.status_code == 201

    path = _card_statement(tmp_path)
    preview = _post_pdf(client, path).json()
    account = next(c for c in preview["quality"]["checks"] if c["id"] == "account")
    assert account["level"] == "ok" and "HSBC Red" in account["title"]

    confirmed = _post_pdf(client, path, "/api/imports/confirm",
                          expected_sha256=preview["sha256"])
    assert confirmed.status_code == 200, confirmed.text
    assert confirmed.json()["import"]["status"] == "imported"
    rows = client.get("/api/transactions", params={"accounts": ["hsbc_hk_card_4242"]}).json()
    assert len(rows["items"]) == 2


def test_chosen_account_takes_a_card_the_ledger_does_not_know(client, tmp_path):
    client.post("/api/accounts", json={
        "id": "hsbc_hk_red", "institution_id": "hsbc_hk", "display_name": "HSBC Red",
        "account_type": "credit_card", "primary_currency": "HKD",
    })
    path = _card_statement(tmp_path, last4="7777")
    preview = _post_pdf(client, path, account_id="hsbc_hk_red").json()
    account = next(c for c in preview["quality"]["checks"] if c["id"] == "account")
    assert account["level"] == "ok" and "HSBC Red" in account["title"]
    confirmed = _post_pdf(client, path, "/api/imports/confirm",
                          expected_sha256=preview["sha256"], account_id="hsbc_hk_red")
    assert confirmed.json()["import"]["status"] == "imported"
    rows = client.get("/api/transactions", params={"accounts": ["hsbc_hk_red"]}).json()
    assert len(rows["items"]) == 2


def test_an_account_whose_own_number_matches_takes_the_statement(client, tmp_path):
    # An account set up with its masked number and no card registry entry,
    # with an earlier statement filed by hand.
    created = client.post("/api/accounts", json={
        "id": "hsbc_red", "institution_id": "hsbc_hk", "display_name": "HSBC Red",
        "account_type": "credit_card", "primary_currency": "HKD",
        "masked_number": "****4242",
    })
    assert created.status_code == 201
    path = _card_statement(tmp_path)
    preview = _post_pdf(client, path).json()
    account = next(c for c in preview["quality"]["checks"] if c["id"] == "account")
    assert account["level"] == "ok" and "HSBC Red" in account["title"]


def test_a_card_number_on_two_accounts_routes_nowhere(conn):
    from fin.ingest import statement_routes
    from fin.models import Card, Money, ParsedTxn

    for account in ("amex_hk_main", "hsbc_hk_current"):
        dbm.upsert_card(conn, Card(id=f"{account}_4242", account_id=account,
                                   cardholder_name="ALEX SAMPLE", last4="4242"))
    row = ParsedTxn(txn_date=datetime(2026, 11, 2).date(), posted_date=None,
                    booked=Money(amount=-100, currency="HKD"), description_raw="TEST",
                    card_last4="4242", extra={"account_hint": "HSBC HK card"})
    assert statement_routes(conn, [row]) == [None]


def test_agent_adds_a_card_account_and_imports_its_statement(client, tmp_path):
    token = client.post("/api/auth/api-keys", json={"name": "Importer"}).json()["key"]
    auth = {"Authorization": f"Bearer {token}"}
    listed = client.get("/api/agent/accounts", headers=auth)
    assert listed.status_code == 200
    assert "hsbc_hk_current" in {a["id"] for a in listed.json()["accounts"]}

    created = client.post("/api/agent/accounts", headers=auth, json={
        "id": "hsbc_hk_card_4242", "institution_id": "hsbc_hk", "display_name": "HSBC Red",
        "account_type": "credit_card", "primary_currency": "HKD",
        "last4": "4242", "cardholder_name": "ALEX SAMPLE",
    })
    assert created.status_code == 201, created.text

    path = _card_statement(tmp_path)
    preview = _post_pdf(client, path, "/api/agent/imports/preview")
    assert preview.status_code == 401  # the key goes in the header
    with path.open("rb") as f:
        preview = client.post("/api/agent/imports/preview", headers=auth,
                              files={"file": (path.name, f, "application/pdf")}).json()
    account = next(c for c in preview["quality"]["checks"] if c["id"] == "account")
    assert account["level"] == "ok" and "HSBC Red" in account["title"]
    with path.open("rb") as f:
        confirmed = client.post("/api/agent/imports/confirm", headers=auth,
                                files={"file": (path.name, f, "application/pdf")},
                                data={"expected_sha256": preview["sha256"]})
    assert confirmed.status_code == 200, confirmed.text
    assert confirmed.json()["import"]["status"] == "imported"


def test_agent_account_routes_need_a_key(client):
    assert client.get("/api/agent/accounts").status_code == 401
    assert client.post("/api/agent/accounts", json={}).status_code in (401, 422)


def _amex_row(card_last4=None):
    from fin.models import Money, ParsedTxn
    return ParsedTxn(txn_date=datetime(2026, 11, 1).date(), posted_date=None,
                     booked=Money(amount=-12800, currency="HKD"), description_raw="SPOTIFY",
                     card_last4=card_last4, extra={"account_hint": "AMEX HK card"})


def test_a_statement_number_routes_rows_that_name_no_account(conn):
    from fin.ingest import account_for_number, statement_routes
    from fin.models import Account

    for account_id, masked in (("amex_hk_explorer", "****04242"), ("amex_hk_other", "****01111")):
        dbm.upsert_account(conn, Account(
            id=account_id, institution_id="amex_hk", display_name=account_id,
            account_type="credit_card", primary_currency="HKD", masked_number=masked))
    assert statement_routes(conn, [_amex_row()], "04242") == ["amex_hk_explorer"]
    # A number printed beside the row still beats the statement's own.
    assert statement_routes(conn, [_amex_row("01111")], "04242") == ["amex_hk_other"]
    assert statement_routes(conn, [_amex_row()], "09999") == [None]
    # An idle month has no rows to route; the file still finds its account.
    assert account_for_number(conn, "04242") == "amex_hk_explorer"


def test_card_numbers_match_on_their_last_four_digits(conn):
    """AMEX registers five-digit card endings (03002); statements and masks vary."""
    from fin.ingest import statement_routes
    from fin.models import Card

    dbm.upsert_card(conn, Card(id="amex_hk_main_primary", account_id="amex_hk_main",
                               cardholder_name="ALEX SAMPLE", last4="04242"))
    assert statement_routes(conn, [_amex_row()], "xxxx-xxxxxx-04242") == ["amex_hk_main"]
    assert statement_routes(conn, [_amex_row("4242")]) == ["amex_hk_main"]
