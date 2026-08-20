from __future__ import annotations

from conftest import write_pdf

from fin import db as dbm
from fin.investment import (
    parse_hsbc_mpf_pdf_bundle,
    save_activities,
    save_snapshot,
)
from fin.models import Account


def _mpf_bundle(tmp_path):
    member = write_pdf(tmp_path / "member.pdf", [
        "MPF Member Returns",
        "Overall account balance (HKD) Net contributions and net transfer-in (HKD) "
        "Account gain/(loss) (HKD)",
        "180,523.93 168,759.96 11,763.97",
        "Balances as at 19 Aug 2026",
        "Alpha Fund 1.0000 60000.0000 60,000.00",
        "Beta Fund 1.0000 40000.0000 40,000.00",
        "Gamma Fund 1.0000 80523.9300 80,523.93",
    ])
    accounts = []
    for filename, role, member_no, balance in (
        ("regular.pdf", "Regular Employee", "65841230", "63,849.84"),
        ("personal.pdf", "Personal Account Holder", "15921678", "47,775.61"),
        (
            "tdvc.pdf",
            "Tax Deductible Voluntary Contribution Account Holder",
            "84303079",
            "68,898.48",
        ),
    ):
        accounts.append(write_pdf(tmp_path / filename, [
            "MPF Account Returns",
            role,
            f"Member account number: {member_no}",
            "(Based on unit price of Constituent Fund as at 18 Aug 2026)",
            "Total account balance (HKD) = Net contributions and net transfer-in (HKD) "
            "+ Account gain/(loss) (HKD)",
            f"{balance} 1.00 1.00",
        ]))
    histories = []
    for filename, member_no, row in (
        (
            "regular-history.pdf",
            "65841230",
            "12 Aug 2026 Employee mandatory contributions Regular Contribution 1,500.00",
        ),
        ("personal-history.pdf", "15921678", "28 Apr 2026 Transfer In 30,345.26"),
        ("tdvc-history.pdf", "84303079", "27 Mar 2026 Regular Contribution 60,000.00"),
    ):
        histories.append(write_pdf(tmp_path / filename, [
            "MPF Contribution History",
            "MPF Transaction history",
            f"Member account number: {member_no}",
            row,
        ]))
    return [member, *accounts, *histories]


def test_hsbc_mpf_pdf_bundle_reconciles_snapshot_and_activities(tmp_path):
    snapshot, activities, documents = parse_hsbc_mpf_pdf_bundle(_mpf_bundle(tmp_path))

    assert snapshot.as_of_date.isoformat() == "2026-08-18"
    assert snapshot.total_value.amount == 18052393
    assert sum(item.balance.amount for item in snapshot.subaccounts) == 18052393
    assert sum(item.market_value.amount for item in snapshot.holdings) == 18052393
    assert len(activities) == 3
    assert {item.activity_type for item in activities} == {
        "regular_contribution", "transfer_in",
    }
    assert {item["classification"] for item in documents} == {
        "member_returns", "account_returns", "contribution_history",
    }


def test_mpf_activity_import_is_deterministically_idempotent(conn, tmp_path):
    for account_id, label in (
        ("hsbc_mpf_regular", "MPF Regular"),
        ("hsbc_mpf_personal", "MPF Personal"),
        ("hsbc_mpf_tdvc", "MPF TDVC"),
    ):
        dbm.upsert_account(conn, Account(
            id=account_id,
            institution_id="hsbc_hk",
            display_name=label,
            account_type="investment",
            primary_currency="HKD",
            balance_group="hsbc_mpf",
        ))
    conn.commit()
    snapshot, activities, _documents = parse_hsbc_mpf_pdf_bundle(_mpf_bundle(tmp_path))

    snapshot_id = save_snapshot(conn, snapshot)
    first = save_activities(conn, activities)
    second = save_activities(conn, activities)

    assert snapshot_id
    assert first == {"inserted": 3, "skipped": 0}
    assert second == {"inserted": 0, "skipped": 3}
