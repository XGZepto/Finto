"""HTTP API.

The rule under test throughout: money crosses the wire as integer minor units
plus a currency code, and nothing ever sums across currencies.
"""

from __future__ import annotations

from pathlib import Path

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
    assert client.get("/api/health").json()["status"] == "ok"


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

    conn = dbm.connect(database_url)
    rows = conn.execute(
        "SELECT subject,applied FROM agent_operation ORDER BY created_at"
    ).fetchall()
    assert rows == [
        {"subject": f"api-key:{key_id}", "applied": 0},
        {"subject": f"api-key:{key_id}", "applied": 1},
    ]
    conn.close()

    assert client.delete(f"/api/auth/api-keys/{key_id}").status_code == 200
    assert client.get("/api/agent/taxonomy/audit", headers=auth).status_code == 401


def test_transactions_list(client):
    body = client.get("/api/transactions").json()
    assert body["total"] > 0
    assert len(body["items"]) == body["total"]


def test_transaction_totals_can_be_normalised(client):
    body = client.get("/api/transactions?convert_to=USD").json()
    assert body["normalised"]["net"]["currency"] == "USD"


def test_flow_report_includes_external_nodes_for_account_sankey(client):
    body = client.get("/api/flows").json()
    assert "external_nodes" in body["normalised"]
    assert all(node["in"]["currency"] == "USD" and node["out"]["currency"] == "USD"
               for node in body["normalised"]["external_nodes"])


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
    conn = dbm.connect(database_url)
    conn.execute(
        "INSERT INTO statement_file "
        "(id,source_path,file_sha256,institution_id,account_id,file_format,parser_id,"
        " parser_version,imported_at,row_count) "
        "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
        ("empty-aug", "Mox_2026-08.pdf", "empty-aug-hash", "mox", "mox_main",
         "pdf", "pdf_statement", "2.0", "2026-08-04", 0),
    )
    conn.execute(
        "INSERT INTO statement_file "
        "(id,source_path,file_sha256,institution_id,account_id,file_format,parser_id,"
        " parser_version,imported_at,row_count) "
        "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
        ("export-aug", "AMEX_transactions_2025-01-01_to_2026-08-03.csv",
         "export-aug-hash", "amex_us", "amex_us_main", "csv", "amex_csv",
         "1.0", "2026-08-04", 2),
    )
    conn.execute(
        "INSERT INTO balance_assertion "
        "(id,account_id,as_of_date,balance,currency,kind,statement_file_id) "
        "VALUES (%s,%s,%s,%s,%s,%s,%s)",
        ("empty-aug-wise", "wise_hkd", "2026-08-03", 0, "HKD", "closing", "empty-aug"),
    )
    conn.commit()
    conn.close()

    rows = {r["account_id"]: r for r in client.get("/api/statement-freshness").json()["accounts"]}
    assert rows["mox_main"]["status"] == "current"
    assert rows["mox_main"]["statement_empty"] is True
    assert rows["wise_hkd"]["status"] == "current"
    assert rows["amex_us_main"]["status"] == "current"
    assert rows["amex_us_main"]["statement_date"] == "2026-08-03"
    assert rows["amex_us_main"]["statement_empty"] is False


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

def test_stage_previews_without_importing(client):
    before = client.get("/api/transactions").json()["total"]
    with (FIXTURES / "hsbc_sample.csv").open("rb") as f:
        r = client.post("/api/imports/stage",
                        files={"file": ("new.csv", f, "text/csv")},
                        data={"institution_id": "hsbc_hk",
                              "account_id": "hsbc_hk_current", "currency": "HKD"})
    body = r.json()
    assert body["parser"] == "hsbc_hk_csv"
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

    job = client.post(f"/api/imports/{staged['staged_id']}/confirm",
                      data={"institution_id": "hsbc_hk",
                            "account_id": "hsbc_hk_current",
                            "currency": "HKD"}).json()

    from fin.jobs import runner
    finished = runner.wait(job["id"], timeout=30)
    assert finished.status == "done", finished.error
    assert finished.result["import"]["status"] == "imported"

    found = client.get("/api/transactions?q=NEW MERCHANT").json()
    assert found["total"] == 1


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


def test_reconcile_runs_as_a_job(client):
    from fin.jobs import runner
    job = client.post("/api/reconcile").json()
    finished = runner.wait(job["id"], timeout=30)
    assert finished.status == "done", finished.error
    assert "transactions" in finished.result


def test_job_status_is_queryable(client):
    from fin.jobs import runner
    job = client.post("/api/reconcile").json()
    runner.wait(job["id"], timeout=30)
    body = client.get(f"/api/jobs/{job['id']}").json()
    assert body["status"] in ("done", "running", "queued")


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
    assert "llm" in body["error"].lower()


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
