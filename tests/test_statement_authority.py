"""The statement is authoritative; a CSV export restates it in other words.

Pins the supersession rule and the two ways it can go wrong: suppressing a
movement the statement never carried, and collapsing two real movements that
look identical.
"""

from __future__ import annotations

from datetime import date

import pytest
from fastapi.testclient import TestClient

from fin import db as dbm
from fin.dedup import run_dedup, supersede_with_statements
from fin.models import Account, Institution, Money, Txn


def _txn(**kw) -> Txn:
    base = dict(
        account_id="hsbc",
        txn_date=date(2026, 3, 1),
        booked=Money(amount=-5000, currency="HKD"),
        description_raw="CAFE ABC",
        statement_file_id="stmt",
    )
    base.update(kw)
    return Txn(**base)


# ---------------------------------------------------------------------------
# Supersession
# ---------------------------------------------------------------------------

def test_statement_supersedes_an_export_that_reworded_the_same_payment():
    """The real HSBC case: same payment, payee and reference swapped round."""
    statement = _txn(description_raw="HC12552952988759 29MAY ZHOU YIXIANG",
                     statement_file_id="stmt")
    export = _txn(description_raw="ZHOU Y****** HC12552952988759",
                  statement_file_id="export")
    assert statement.dedup_key != export.dedup_key   # wording defeats the key

    merged = supersede_with_statements([statement, export], {statement.id})
    assert merged == 1
    assert export.duplicate_of_id == statement.id
    assert statement.duplicate_of_id is None


def test_export_row_outside_any_statement_survives():
    """The last 60 days of exports cover a period no statement has reached."""
    statement = _txn(txn_date=date(2026, 3, 1), statement_file_id="stmt")
    export = _txn(txn_date=date(2026, 8, 1), statement_file_id="export")
    merged = supersede_with_statements([statement, export], {statement.id})
    assert merged == 0
    assert export.duplicate_of_id is None


def test_counts_are_matched_not_collapsed():
    """Two statement rows suppress two export rows; a third export row stays."""
    statements = [_txn(statement_file_id="stmt") for _ in range(2)]
    exports = [_txn(statement_file_id="export") for _ in range(3)]
    merged = supersede_with_statements(
        statements + exports, {t.id for t in statements})
    assert merged == 2
    assert sum(1 for t in exports if t.duplicate_of_id is None) == 1
    assert all(t.duplicate_of_id is None for t in statements)


def test_a_statement_is_never_superseded_by_an_export():
    statement = _txn(statement_file_id="stmt")
    export = _txn(statement_file_id="export")
    supersede_with_statements([export, statement], {statement.id})
    assert statement.duplicate_of_id is None
    assert export.duplicate_of_id == statement.id


def test_run_dedup_leaves_statements_out_of_fuzzy_matching():
    """A statement is never merged on a similarity score."""
    statement = _txn(txn_date=date(2026, 3, 1),
                     description_raw="CAFE ABC KOWLOON", statement_file_id="stmt")
    export = _txn(txn_date=date(2026, 3, 3),
                  description_raw="CAFE ABC KOWLOON", statement_file_id="export")
    report = run_dedup([statement, export], statement_txn_ids={statement.id})
    assert statement.duplicate_of_id is None
    assert report.candidates == []


# ---------------------------------------------------------------------------
# The same rule, end to end through the API
# ---------------------------------------------------------------------------

@pytest.fixture
def client(tmp_path, monkeypatch):
    """An API over a ledger carrying an FX charge and its structured detail."""
    db = tmp_path / "authority.db"
    monkeypatch.setenv("FINTO_DB", str(db))
    import fin.api.deps as deps
    monkeypatch.setattr(deps, "DEFAULT_DB", str(db))

    conn = dbm.connect(db)
    dbm.init_db(conn)
    dbm.upsert_institution(conn, Institution(
        id="amex_hk", display_name="AMEX HK", country="HK"))
    dbm.upsert_account(conn, Account(
        id="amex_hk_explorer", institution_id="amex_hk", display_name="Explorer",
        account_type="credit_card", primary_currency="HKD"))
    conn.execute(
        "INSERT INTO statement_file (id, source_path, file_sha256, institution_id, "
        "account_id, file_format, parser_id, parser_version, imported_at, row_count) "
        "VALUES ('stmt','/tmp/s.pdf','abc','amex_hk','amex_hk_explorer','pdf',"
        "'pdf_statement','2.0.0','2026-08-04',1)")

    charge = Txn(
        account_id="amex_hk_explorer", txn_date=date(2026, 5, 8),
        booked=Money(amount=-11743, currency="HKD"),
        native=Money(amount=-1304, currency="EUR"),
        fx_rate="9.005",
        description_raw="UBER TRIP HTTPS://HELP.UB",
        external_ref="AT251290004000010012713",
        statement_file_id="stmt",
        details={"travel.passenger_name": "YIXIANG ZHOU",
                 "payment.wallet": "GOOGLE PAY"},
    )
    dbm.insert_txns(conn, [charge])
    conn.commit()
    conn.close()

    from fin.api.app import app
    with TestClient(app) as c:
        c.txn_id = charge.id
        yield c


def test_api_returns_the_foreign_charge_and_the_issuers_rate(client):
    """The native pair and the rate reach the wire, in integer minor units."""
    item = client.get("/api/transactions").json()["items"][0]
    assert item["booked"] == {"amount": -11743, "currency": "HKD"}
    assert item["native"] == {"amount": -1304, "currency": "EUR"}
    assert item["fx_rate"] == "9.005"


def test_api_exposes_structured_detail_and_the_issuer_reference(client):
    item = client.get(f"/api/transactions/{client.txn_id}").json()
    assert item["details"]["travel.passenger_name"] == "YIXIANG ZHOU"
    assert item["details"]["payment.wallet"] == "GOOGLE PAY"
    assert item["external_ref"] == "AT251290004000010012713"


def test_detail_keys_are_listed_with_counts(client):
    keys = {k["key"]: k for k in client.get("/api/details").json()["keys"]}
    assert keys["travel.passenger_name"]["transactions"] == 1


def test_transactions_filter_on_an_exact_detail_value(client):
    """The question txn_detail exists to answer: every trip for one passenger."""
    hit = client.get(
        "/api/transactions?detail=travel.passenger_name=YIXIANG ZHOU").json()
    assert hit["total"] == 1
    miss = client.get(
        "/api/transactions?detail=travel.passenger_name=SOMEONE ELSE").json()
    assert miss["total"] == 0


def test_detail_values_endpoint(client):
    body = client.get("/api/details/payment.wallet").json()
    assert body["values"] == [{"value": "GOOGLE PAY", "transactions": 1}]


def test_integrity_reports_unverified_rather_than_healthy(client):
    """An account with no balance assertion is unchecked, not proven correct."""
    body = client.get("/api/integrity").json()
    assert body["summary"]["unverified_account_count"] == 1


def test_investments_endpoint_is_empty_but_present(client):
    assert client.get("/api/investments").json() == {"snapshots": []}


# ---------------------------------------------------------------------------
# One product, several currencies
# ---------------------------------------------------------------------------

def test_a_charge_lands_on_the_account_that_settles_its_currency(tmp_path):
    """HSBC bills the Pulse card's CNY and HKD spending in one document."""
    from fin.ingest import _settle_in_currency

    conn = dbm.connect(tmp_path / "route.db")
    dbm.init_db(conn)
    dbm.upsert_institution(conn, Institution(
        id="hsbc_hk", display_name="HSBC HK", country="HK"))
    for acct in (
        Account(id="pulse_hkd", institution_id="hsbc_hk", display_name="Pulse HKD",
                account_type="credit_card", primary_currency="HKD",
                balance_group="hsbc_pulse"),
        Account(id="pulse_cny", institution_id="hsbc_hk", display_name="Pulse CNY",
                account_type="credit_card", primary_currency="CNY",
                balance_group="hsbc_pulse"),
        Account(id="everymile", institution_id="hsbc_hk", display_name="EveryMile",
                account_type="credit_card", primary_currency="HKD"),
    ):
        dbm.upsert_account(conn, acct)
    conn.commit()

    accounts = dbm.load_accounts(conn)
    assert _settle_in_currency(accounts, "pulse_hkd", "CNY") == "pulse_cny"
    assert _settle_in_currency(accounts, "pulse_cny", "HKD") == "pulse_hkd"
    # Its own currency needs no rerouting.
    assert _settle_in_currency(accounts, "pulse_hkd", "HKD") is None
    # No sibling: the rows stay put and the currency check flags them.
    assert _settle_in_currency(accounts, "everymile", "CNY") is None
    conn.close()


# ---------------------------------------------------------------------------
# Classification
# ---------------------------------------------------------------------------

def test_a_card_charge_left_unlabelled_is_a_purchase(tmp_path):
    """Everything else on a card is named by the issuer and labelled already."""

    from fin.ingest import assign_default_kinds
    from fin.models import AccountType, TxnKind

    accounts = {
        "card": Account(id="card", institution_id="i", display_name="Card",
                        account_type=AccountType.CREDIT_CARD, primary_currency="HKD"),
        "bank": Account(id="bank", institution_id="i", display_name="Bank",
                        account_type=AccountType.SAVINGS, primary_currency="HKD"),
    }
    charge = _txn(account_id="card", booked=Money(amount=-5000, currency="HKD"))
    credit = _txn(account_id="card", booked=Money(amount=5000, currency="HKD"))
    fee = _txn(account_id="card", booked=Money(amount=-100, currency="HKD"),
               kind=TxnKind.FEE)
    debit = _txn(account_id="bank", booked=Money(amount=-5000, currency="HKD"))

    assert assign_default_kinds([charge, credit, fee, debit], accounts) == 2
    assert charge.kind is TxnKind.PURCHASE
    assert credit.kind is TxnKind.REFUND
    assert fee.kind is TxnKind.FEE            # an existing label is never overwritten
    # Spending, or half of an unmatched transfer.
    assert debit.kind is TxnKind.UNKNOWN


def test_income_is_never_detected_on_a_credit_card():
    """AMEX rebates a subscription monthly: a benefit, not earnings."""
    from fin.income import detect_regular_income

    rebates = [
        _txn(account_id="card", txn_date=date(2026, m, 25),
             booked=Money(amount=1429, currency="USD"),
             description_raw="Platinum Walmart+ Credit")
        for m in (1, 2, 3, 4)
    ]
    assert detect_regular_income(rebates) != []           # looks regular
    assert detect_regular_income(rebates, income_accounts=set()) == []


def test_issuer_stated_category_drives_a_rule(tmp_path):
    """AMEX states the merchant's category; mapping its vocabulary is a rename."""
    from fin.ingest import apply_category_rules
    from fin.models import CategoryRule

    conn = dbm.connect(tmp_path / "rules.db")
    dbm.init_db(conn)
    dbm.upsert_category_rule(conn, CategoryRule(
        id="cat_transport", match_field="merchant_category", match_type="regex",
        pattern="TAXICAB|LIMOUSINE", set_category="transport"))
    conn.commit()

    ride = _txn(details={"merchant.category": "TAXICAB & LIMOUSINE"})
    other = _txn(details={"merchant.category": "GROCERY STORE"})
    apply_category_rules(conn, [ride, other])
    assert ride.category == "transport"
    assert other.category is None
    conn.close()


# ---------------------------------------------------------------------------
# Payment gateways
# ---------------------------------------------------------------------------

def test_gateway_that_names_the_merchant_yields_the_merchant():
    from fin.enrich import payment_gateway

    assert payment_gateway("Alipay*DIDI Taxi Shanghai") == ("Alipay", "DIDI Taxi")
    # Trailing place tokens go; ones inside the name stay.
    assert payment_gateway("AlipayHK*Ichiran Hong K HKG HK") == (
        "AlipayHK", "Ichiran Hong K")
    assert payment_gateway("UBER TRIP HTTPS://HELP.UB") is None


def test_gateway_that_names_no_merchant_says_so():
    from fin.enrich import payment_gateway

    for description in ("Alipay* Shanghai", "Alipay China Shanghai",
                        "Tenpay Technology Company CHN CN",
                        "UNIONPAY MERCHANT CHN CN", "Wechat Pay HK Limited",
                        # The acquirer naming itself, and China's clearing house.
                        "AlipayHK*ALIPAY HKG HK", "AlipayHK*NUCC HKG HK"):
        gateway, merchant = payment_gateway(description)
        assert gateway and merchant == "", description


def test_undisclosed_gateway_charges_are_categorised_not_left_blank():
    """A withheld merchant is a fact about the statement, not an unread row."""
    from fin.ingest import label_payment_gateways

    hidden = _txn(description_raw="Alipay* Shanghai")
    named = _txn(description_raw="Alipay*DIDI Taxi Shanghai")
    plain = _txn(description_raw="UBER TRIP HTTPS://HELP.UB")

    assert label_payment_gateways([hidden, named, plain]) == 2

    assert hidden.category == "proxy_payment"
    assert hidden.subcategory == "alipay"
    assert hidden.details["payment.gateway"] == "Alipay"
    assert hidden.details["merchant.disclosed"] == "no"

    # A named merchant leaves the category free for the merchant rules.
    assert named.merchant == "DIDI Taxi"
    assert named.category is None
    assert named.details["merchant.disclosed"] == "yes"

    assert plain.details == {}


def test_an_existing_category_is_never_overwritten_by_the_gateway_label():
    from fin.ingest import label_payment_gateways

    already = _txn(description_raw="Alipay* Shanghai", category="dining")
    label_payment_gateways([already])
    assert already.category == "dining"
    assert already.details["payment.gateway"] == "Alipay"


# ---------------------------------------------------------------------------
# Querying
# ---------------------------------------------------------------------------

def _seeded(tmp_path):
    conn = dbm.connect(tmp_path / "q.db")
    dbm.init_db(conn)
    dbm.upsert_institution(conn, Institution(
        id="amex_hk", display_name="AMEX HK", country="HK"))
    dbm.upsert_account(conn, Account(
        id="card", institution_id="amex_hk", display_name="Card",
        account_type="credit_card", primary_currency="HKD"))
    conn.execute(
        "INSERT INTO statement_file (id, source_path, file_sha256, institution_id, "
        "account_id, file_format, parser_id, parser_version, imported_at, row_count) "
        "VALUES ('stmt','/tmp/s.pdf','abc','amex_hk','card','pdf','p','1','2026-08-04',0)")
    return conn


def test_search_narrows_on_every_term(tmp_path):
    from fin.reporting import transactions

    conn = _seeded(tmp_path)
    dbm.insert_txns(conn, [
        _txn(account_id="card", description_raw="UBER TRIP SHANGHAI"),
        _txn(account_id="card", description_raw="UBER TRIP LONDON"),
        _txn(account_id="card", description_raw="TAXI SHANGHAI"),
    ])
    conn.commit()

    assert transactions(conn, filters={"q": "uber"})["total"] == 2
    assert transactions(conn, filters={"q": "uber shanghai"})["total"] == 1
    conn.close()


def test_a_filtered_page_reports_what_the_whole_match_comes_to(tmp_path):
    """The page shows 100 rows; the question is what all of them add up to."""
    from fin.reporting import transactions

    conn = _seeded(tmp_path)
    dbm.insert_txns(conn, [
        _txn(account_id="card", booked=Money(amount=-1000, currency="HKD"),
             description_raw="UBER ONE"),
        _txn(account_id="card", booked=Money(amount=-2500, currency="HKD"),
             description_raw="UBER TWO"),
        _txn(account_id="card", booked=Money(amount=-9999, currency="HKD"),
             description_raw="SOMETHING ELSE"),
    ])
    conn.commit()

    page = transactions(conn, filters={"q": "uber"}, limit=1)
    assert len(page["items"]) == 1              # one row on the page
    assert page["total"] == 2                   # two matched
    hkd = next(t for t in page["totals"] if t["currency"] == "HKD")
    assert hkd["spend"]["amount"] == 3500       # both, not just the page
    conn.close()


def test_flows_separate_your_own_accounts_from_the_boundary(tmp_path):
    """Moving your own money is not income, however it looks in one account."""
    from fin.models import TransferGroup, TransferKind, TransferLeg
    from fin.reporting import flows

    conn = _seeded(tmp_path)
    dbm.upsert_account(conn, Account(
        id="bank", institution_id="amex_hk", display_name="Bank",
        account_type="savings", primary_currency="HKD"))
    out = _txn(account_id="bank", booked=Money(amount=-5000, currency="HKD"))
    inc = _txn(account_id="card", booked=Money(amount=5000, currency="HKD"))
    salary = _txn(account_id="bank", booked=Money(amount=9000, currency="HKD"),
                  description_raw="SALARY")
    # Rows, then the group, then the links — the order reconcile uses, and the
    # only one the foreign keys allow in both directions.
    dbm.insert_txns(conn, [out, inc, salary])
    group = TransferGroup(kind=TransferKind.CC_PAYMENT, legs=[
        TransferLeg(txn_id=out.id, role="out"), TransferLeg(txn_id=inc.id, role="in")])
    dbm.insert_transfer_groups(conn, [group])
    out.transfer_group_id = inc.transfer_group_id = group.id
    dbm.update_txn_links(conn, [out, inc, salary])
    conn.commit()

    f = flows(conn)
    assert f["internal"] == [{
        "from_account": "bank", "to_account": "card", "moves": 1,
        "amount": {"amount": 5000, "currency": "HKD"},
    }]
    # Only the salary crossed the boundary; the transfer's legs are excluded.
    hkd = next(e for e in f["external"] if e["currency"] == "HKD")
    assert hkd["in"]["amount"] == 9000
    assert hkd["out"]["amount"] == 0
    conn.close()
