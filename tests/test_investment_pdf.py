from __future__ import annotations

from conftest import write_pdf

from fin import db as dbm
from fin.investment import (
    parse_hsbc_mpf_pdf_bundle,
    save_activities,
    save_snapshot,
)
from fin.llm.provider import EchoProvider
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


def test_hsbc_mpf_bundle_can_use_validated_llm_extraction(tmp_path):
    paths = _mpf_bundle(tmp_path)
    provider = EchoProvider({
        "member.pdf": {
            "document_type": "member_returns",
            "reported_date": "2026-08-19",
            "valuation_date": "2026-08-18",
            "total_balance": "180523.93",
            "holdings": [
                {"instrument": "Alpha Fund", "units": "1", "unit_price": "60000",
                 "market_value": "60000.00"},
                {"instrument": "Beta Fund", "units": "1", "unit_price": "40000",
                 "market_value": "40000.00"},
                {"instrument": "Gamma Fund", "units": "1", "unit_price": "80523.93",
                 "market_value": "80523.93"},
            ],
        },
        "regular.pdf": {
            "document_type": "account_returns",
            "account_role": "regular",
            "member_no": "65841230",
            "total_balance": "63849.84",
        },
        "personal.pdf": {
            "document_type": "account_returns",
            "account_role": "personal",
            "member_no": "15921678",
            "total_balance": "47775.61",
        },
        "tdvc.pdf": {
            "document_type": "account_returns",
            "account_role": "tdvc",
            "member_no": "84303079",
            "total_balance": "68898.48",
        },
        "regular-history.pdf": {
            "document_type": "contribution_history",
            "account_role": "regular",
            "member_no": "65841230",
            "activities": [{
                "date": "2026-08-12",
                "contribution_type": "Employee mandatory contributions",
                "activity_type": "regular_contribution",
                "amount": "1500.00",
            }],
        },
        "personal-history.pdf": {
            "document_type": "contribution_history",
            "account_role": "personal",
            "member_no": "15921678",
            "activities": [{
                "date": "2026-04-28",
                "contribution_type": "Mandatory contributions from former employment(s)",
                "activity_type": "transfer_in",
                "amount": "30345.26",
            }],
        },
        "tdvc-history.pdf": {
            "document_type": "contribution_history",
            "account_role": "tdvc",
            "member_no": "84303079",
            "activities": [{
                "date": "2026-03-27",
                "contribution_type": "Tax Deductible Voluntary Contributions",
                "activity_type": "regular_contribution",
                "amount": "60000.00",
            }],
        },
    })

    snapshot, activities, documents = parse_hsbc_mpf_pdf_bundle(
        paths,
        llm_provider=provider,
        force_llm=True,
    )

    assert snapshot.as_of_date.isoformat() == "2026-08-18"
    assert snapshot.total_value.amount == 18052393
    assert len(activities) == 3
    assert all(item["parser"] == "llm" for item in documents)
    assert len(provider.calls) == 7


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
