"""Transfer linking with self/account/person aliases, and MPF positions."""

from __future__ import annotations

from datetime import date
from pathlib import Path

from fin.db import (
    init_db,
    load_account_alias_index,
    load_self_aliases,
    upsert_account,
    upsert_institution,
    upsert_party,
)
from fin.investment import parse_hsbc_mpf_position_xlsx, save_snapshot, snapshot_detail
from fin.models import (
    Account,
    AccountType,
    Institution,
    Money,
    Party,
    Txn,
    TxnKind,
)
from fin.transfers import TransferContext, find_transfers


def _txn(aid, amount, d, desc, *, counterparty=None, currency="HKD"):
    return Txn(
        account_id=aid,
        txn_date=d,
        booked=Money(amount=amount, currency=currency),
        description_raw=desc,
        description_norm=desc.upper(),
        counterparty=counterparty,
        kind=TxnKind.UNKNOWN,
        dedup_key=f"{aid}:{d}:{amount}:{desc}",
        statement_file_id="sf",
    )


def test_self_transfer_named_destination_auto_links(tmp_path):
    """HSBC FPS 'to MOX' must link even when another same-amount P2P exists."""
    accounts = {
        "hsbc": Account(id="hsbc", institution_id="hsbc_hk", display_name="HSBC",
                        account_type=AccountType.SAVINGS, primary_currency="HKD",
                        aliases=["HSBC"]),
        "mox": Account(id="mox", institution_id="mox", display_name="Mox",
                       account_type=AccountType.CHECKING, primary_currency="HKD",
                       aliases=["MOX", "MOX BANK"]),
    }
    d = date(2025, 3, 1)
    txns = [
        _txn("hsbc", -500000, d, "FPS to MOX BANK"),
        _txn("mox", 500000, d, "FPS from YIXIANG ZHOU"),
        # A same-day equal P2P that must NOT steal the match.
        _txn("hsbc", -500000, d, "FPS to FRIEND NAME", counterparty="FRIEND NAME"),
    ]
    ctx = TransferContext(
        self_aliases={"YIXIANGZHOU"},
        account_aliases={"MOX": "mox", "MOXBANK": "mox", "HSBC": "hsbc"},
        person_aliases={"FRIENDNAME": "person_friend"},
    )
    report = find_transfers(txns, accounts, context=ctx)
    assert len(report.groups) == 1
    legs = {L.txn_id for L in report.groups[0].legs}
    assert txns[0].id in legs and txns[1].id in legs
    assert txns[0].kind == TxnKind.TRANSFER
    assert txns[2].transfer_group_id is None  # P2P left alone


def test_p2p_is_penalised_against_self_transfer(tmp_path):
    accounts = {
        "hsbc": Account(id="hsbc", institution_id="hsbc_hk", display_name="HSBC",
                        account_type=AccountType.SAVINGS, primary_currency="HKD"),
        "mox": Account(id="mox", institution_id="mox", display_name="Mox",
                       account_type=AccountType.CHECKING, primary_currency="HKD",
                       aliases=["MOX"]),
    }
    d = date(2025, 3, 1)
    txns = [
        _txn("hsbc", -10000, d, "FPS to ALICE WONG", counterparty="ALICE WONG"),
        _txn("mox", 10000, d, "unrelated inflow"),
    ]
    ctx = TransferContext(
        account_aliases={"MOX": "mox"},
        person_aliases={"ALICEWONG": "person_alice"},
    )
    report = find_transfers(txns, accounts, context=ctx)
    # Penalised below auto-link; may still appear as a review candidate or not.
    assert all(g.confidence < 0.90 for g in report.groups)


def test_cc_payment_window_is_wider_than_internal():
    accounts = {
        "hsbc": Account(id="hsbc", institution_id="hsbc_hk", display_name="HSBC",
                        account_type=AccountType.SAVINGS, primary_currency="HKD"),
        "amex": Account(id="amex", institution_id="amex_hk", display_name="AMEX",
                        account_type=AccountType.CREDIT_CARD, primary_currency="HKD",
                        aliases=["AMEX"]),
    }
    out = _txn("hsbc", -917870, date(2025, 1, 1), "AMEX AUTOPAY")
    inc = _txn("amex", 917870, date(2025, 1, 8), "PAYMENT RECEIVED THRU EPS")
    ctx = TransferContext(account_aliases={"AMEX": "amex"})
    report = find_transfers([out, inc], accounts, context=ctx)
    assert len(report.groups) == 1
    assert report.groups[0].kind.value == "cc_payment"


def test_party_aliases_round_trip(tmp_path):
    import sqlite3
    conn = sqlite3.connect(tmp_path / "t.db")
    conn.row_factory = sqlite3.Row
    init_db(conn)
    upsert_institution(conn, Institution(id="mox", display_name="Mox", country="HK"))
    upsert_account(conn, Account(
        id="mox_hkd", institution_id="mox", display_name="Mox HKD",
        account_type=AccountType.CHECKING, primary_currency="HKD",
        aliases=["MOX BANK"],
    ))
    upsert_party(conn, Party(
        id="self", display_name="Me", kind="self",
        aliases=["YIXIANG ZHOU", "ZEPTO ZHOU YIXIANG"],
    ))
    conn.commit()
    assert "MOXBANK" in load_account_alias_index(conn)
    assert "YIXIANGZHOU" in load_self_aliases(conn)


def test_hsbc_mpf_position_xlsx_parses_and_stores():
    path = Path(
        "Documents/Finto-Data/HSBC_HK/MPF_Investment/Positions/"
        "HSBC_MPF_Position_2026-07-31.xlsx")
    path = Path.home() / path
    if not path.exists():
        import pytest
        pytest.skip("MPF position file not present")
    snap = parse_hsbc_mpf_position_xlsx(path)
    assert snap.scheme == "hsbc_mpf"
    assert snap.as_of_date == date(2026, 7, 31)
    assert snap.total_value.amount == 16593537
    assert len(snap.subaccounts) == 3
    assert len(snap.holdings) == 8
    assert {s.account_id for s in snap.subaccounts} == {
        "hsbc_mpf_regular", "hsbc_mpf_personal", "hsbc_mpf_tdvc",
    }

    import sqlite3
    conn = sqlite3.connect(":memory:")
    conn.row_factory = sqlite3.Row
    init_db(conn)
    upsert_institution(conn, Institution(id="hsbc_hk", display_name="HSBC", country="HK"))
    for aid, name in [
        ("hsbc_mpf_regular", "Regular"),
        ("hsbc_mpf_personal", "Personal"),
        ("hsbc_mpf_tdvc", "TDVC"),
    ]:
        upsert_account(conn, Account(
            id=aid, institution_id="hsbc_hk", display_name=name,
            account_type=AccountType.INVESTMENT, primary_currency="HKD",
            balance_group="hsbc_mpf",
        ))
    snap_id = save_snapshot(conn, snap)
    detail = snapshot_detail(conn, snap_id)
    assert detail["total"]["amount"] == 16593537
    assert len(detail["holdings"]) == 8
    assert len(detail["subaccounts"]) == 3
