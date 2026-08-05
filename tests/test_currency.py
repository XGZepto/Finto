"""Per-currency positions and FX conversion.

The rule under test throughout: the backend never combines currencies. A
position is per (account, currency); conversion is an explicit, dated,
labelled operation for presentation only.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal

from fin import db as dbm
from fin import fx as fxm
from fin.models import Account, FxRate, Money
from fin.reporting import positions, summary, totals


def _add(conn, account, amount, ccy, when="2025-01-15", desc="THING"):
    from fin.db import insert_txns
    from fin.models import Txn
    conn.execute(
        "INSERT INTO statement_file (id, source_path, file_sha256, "
        "institution_id, account_id, file_format, parser_id, parser_version, "
        "imported_at, row_count) VALUES "
        "('sf','x','x','amex_us','amex_us_main','csv','t','1','2025-01-01',0) "
        "ON CONFLICT (id) DO NOTHING")
    insert_txns(conn, [Txn(
        account_id=account, txn_date=date.fromisoformat(when),
        booked=Money(amount=amount, currency=ccy), description_raw=desc,
        statement_file_id="sf")])
    conn.commit()


# ---------------------------------------------------------------------------
# Positions stay per currency
# ---------------------------------------------------------------------------

def test_multi_currency_account_reports_one_position_per_currency(conn):
    _add(conn, "amex_hk_main", -100000, "HKD", desc="HK PURCHASE")
    _add(conn, "amex_hk_main", -20000, "USD", desc="US PURCHASE")

    rows = [p for p in positions(conn) if p["account_id"] == "amex_hk_main"]
    assert len(rows) == 2
    by_ccy = {r["currency"]: r for r in rows}
    assert by_ccy["HKD"]["net"]["amount"] == -100000
    assert by_ccy["USD"]["net"]["amount"] == -20000
    # Critically: no row combines them.
    assert all(r["net"]["currency"] in ("HKD", "USD") for r in rows)


def test_positions_never_sum_across_currencies(conn):
    _add(conn, "amex_hk_main", -100000, "HKD")
    _add(conn, "amex_hk_main", -20000, "USD")
    rows = positions(conn)
    currencies = {r["currency"] for r in rows}
    # Every returned amount is tagged with exactly one real currency.
    assert currencies <= {"HKD", "USD"}
    assert all(r["net"]["currency"] == r["currency"] for r in rows)


def test_summary_is_grouped_by_currency(conn):
    _add(conn, "amex_hk_main", -100000, "HKD", when="2025-01-10")
    _add(conn, "amex_hk_main", -20000, "USD", when="2025-01-20")
    rows = summary(conn, group_by="month")
    # One January bucket per currency, not one merged January.
    jan = [r for r in rows if r["bucket"] == "2025-01"]
    assert {r["currency"] for r in jan} == {"HKD", "USD"}


def test_totals_are_per_currency(conn):
    _add(conn, "amex_hk_main", -100000, "HKD")
    _add(conn, "amex_hk_main", -20000, "USD")
    rows = totals(conn)
    assert len(rows) == 2
    assert {r["currency"] for r in rows} == {"HKD", "USD"}


def test_position_prefers_the_statement_balance_over_movements(conn):
    from fin.integrity import record_balance
    _add(conn, "hsbc_hk_current", -100000, "HKD")
    record_balance(conn, account_id="hsbc_hk_current", as_of=date(2025, 1, 31),
                   balance=Money(amount=5000000, currency="HKD"))
    conn.commit()
    row = next(p for p in positions(conn) if p["account_id"] == "hsbc_hk_current")
    assert row["balance"]["amount"] == 5000000
    assert row["basis"] == "statement"
    # The movement figure is still available and unchanged.
    assert row["net"]["amount"] == -100000


def test_position_falls_back_to_movements_and_says_so(conn):
    _add(conn, "amex_us_main", -12345, "USD")
    row = next(p for p in positions(conn) if p["account_id"] == "amex_us_main")
    assert row["basis"] == "movements"
    assert row["balance"]["amount"] == -12345


# ---------------------------------------------------------------------------
# Settlement currencies
# ---------------------------------------------------------------------------

def test_settlement_currencies_default_to_primary(conn):
    accounts = dbm.load_accounts(conn)
    assert accounts["amex_us_main"].settlement_currencies == ["USD"]


def test_multi_currency_account_declares_both(conn):
    accounts = dbm.load_accounts(conn)
    assert set(accounts["amex_hk_main"].settlement_currencies) == {"HKD", "USD"}


def test_undeclared_currency_is_flagged(conn):
    """A single-currency card transacting in an undeclared currency is a bug."""
    from fin.integrity import find_violations
    _add(conn, "amex_us_main", -5000, "JPY")
    problems = {v["check"] for v in find_violations(conn)}
    assert "currency_not_settleable" in problems


def test_declared_currency_is_not_flagged(conn):
    from fin.integrity import find_violations
    _add(conn, "amex_hk_main", -5000, "USD")     # declared on this card
    problems = {v["check"] for v in find_violations(conn)}
    assert "currency_not_settleable" not in problems


def test_settlement_currencies_are_replaced_not_merged(conn):
    """Removing a currency from config must revoke it."""
    dbm.upsert_account(conn, Account(
        id="amex_hk_main", institution_id="amex_hk", display_name="AMEX HK",
        account_type="credit_card", primary_currency="HKD",
        settlement_currencies=["HKD"]))
    conn.commit()
    assert dbm.load_accounts(conn)["amex_hk_main"].settlement_currencies == ["HKD"]


# ---------------------------------------------------------------------------
# FX
# ---------------------------------------------------------------------------

def test_rates_are_harvested_from_transactions(conn):
    from fin.db import insert_txns
    from fin.models import Txn
    conn.execute(
        "INSERT INTO statement_file (id, source_path, file_sha256, institution_id, "
        "account_id, file_format, parser_id, parser_version, imported_at, row_count) "
        "VALUES ('sf','x','x','amex_us','amex_us_main','csv','t','1','2025-01-01',0) "
        "ON CONFLICT (id) DO NOTHING")
    insert_txns(conn, [Txn(
        account_id="amex_us_main", txn_date=date(2025, 1, 15),
        booked=Money(amount=-7820, currency="USD"),
        native=Money(amount=-1200000, currency="JPY"),
        description_raw="UNIQLO TOKYO", statement_file_id="sf")])
    conn.commit()

    assert fxm.harvest_rates(conn) == 1
    pairs = fxm.available_pairs(conn)
    assert any(p["base"] == "JPY" and p["quote"] == "USD" for p in pairs)


def test_conversion_reports_the_rate_it_used(conn):
    dbm.upsert_fx_rate(conn, FxRate(rate_date=date(2025, 1, 1), base="HKD",
                                    quote="USD", rate=Decimal("0.128")))
    conn.commit()
    got = fxm.convert(conn, Money(amount=100000, currency="HKD"), "USD",
                      on=date(2025, 1, 15))
    assert got.ok
    assert got.amount == 12800                 # HKD 1,000.00 -> USD 128.00
    assert got.as_dict()["converted"] is True
    assert got.as_dict()["rate"] == "0.128"
    assert got.as_dict()["rate_date"] == "2025-01-01"


def test_conversion_uses_the_inverse_pair_when_needed(conn):
    dbm.upsert_fx_rate(conn, FxRate(rate_date=date(2025, 1, 1), base="USD",
                                    quote="HKD", rate=Decimal("7.8")))
    conn.commit()
    got = fxm.convert(conn, Money(amount=78000, currency="HKD"), "USD",
                      on=date(2025, 1, 15))
    assert got.ok and got.amount == 10000      # HKD 780.00 -> USD 100.00


def test_missing_rate_never_guesses(conn):
    got = fxm.convert(conn, Money(amount=100000, currency="HKD"), "USD")
    assert not got.ok
    # The original amount passes through untouched, still labelled HKD.
    assert got.amount == 100000 and got.currency == "HKD"
    assert got.as_dict()["converted"] is False


def test_conversion_handles_zero_decimal_currencies(conn):
    """JPY has no minor units; a naive conversion is out by 100x."""
    dbm.upsert_fx_rate(conn, FxRate(rate_date=date(2025, 1, 1), base="USD",
                                    quote="JPY", rate=Decimal(150)))
    conn.commit()
    got = fxm.convert(conn, Money(amount=10000, currency="USD"), "JPY",
                      on=date(2025, 1, 15))
    assert got.ok
    # USD 100.00 * 150 = JPY 15,000, which in JPY minor units is 15000.
    assert got.amount == 15000


def test_missing_pairs_are_reported(conn):
    _add(conn, "amex_hk_main", -100000, "HKD")
    _add(conn, "amex_hk_main", -20000, "USD")
    assert set(fxm.missing_pairs(conn, "USD")) == {"HKD"}


def test_convert_rows_keeps_the_native_amount(conn):
    dbm.upsert_fx_rate(conn, FxRate(rate_date=date(2025, 1, 1), base="HKD",
                                    quote="USD", rate=Decimal("0.128")))
    conn.commit()
    _add(conn, "hsbc_hk_current", -100000, "HKD")
    rows = fxm.convert_rows(conn, positions(conn), fields=("net", "inflow"),
                            to_currency="USD", on=date(2025, 6, 1))
    row = next(r for r in rows if r["account_id"] == "hsbc_hk_current")
    assert row["net"]["amount"] == -100000          # native untouched
    assert row["net"]["currency"] == "HKD"
    assert row["net_converted"]["currency"] == "USD"
    assert row["net_converted"]["converted"] is True
    assert row["inflow_converted"]["currency"] == "USD"


def test_rates_load_from_csv(conn, tmp_path):
    f = tmp_path / "rates.csv"
    f.write_text("date,base,quote,rate,source\n"
                 "2025-01-01,HKD,USD,0.1280,ecb\n"
                 "2025-02-01,HKD,USD,0.1275,ecb\n")
    assert fxm.load_rates_csv(conn, f) == 2
    got = fxm.convert(conn, Money(amount=100000, currency="HKD"), "USD",
                      on=date(2025, 2, 15))
    assert got.as_dict()["rate_date"] == "2025-02-01"
