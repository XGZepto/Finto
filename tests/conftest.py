"""Shared fixtures and synthetic statement builders.

No real financial data anywhere in this repo — everything here is invented.
"""

from __future__ import annotations

import os
import shutil
import socket
import subprocess
import uuid
import zlib
from pathlib import Path

import pytest

from fin import db as dbm
from fin.models import Account, Card, Institution

FIXTURES = Path(__file__).parent / "fixtures"


@pytest.fixture(scope="session")
def postgres_base_url(tmp_path_factory):
    """Use the configured test server or start one for this test session."""
    configured = os.environ.get("FINTO_TEST_DATABASE_URL")
    owned = configured is None
    cluster = None
    pg_ctl = None
    if owned:
        pg_bin = Path("/opt/homebrew/opt/postgresql@17/bin")
        initdb = shutil.which("initdb") or str(pg_bin / "initdb")
        pg_ctl = shutil.which("pg_ctl") or str(pg_bin / "pg_ctl")
        if not Path(initdb).exists() or not Path(pg_ctl).exists():
            pytest.skip("PostgreSQL 17 is required for database tests")
        cluster = tmp_path_factory.mktemp("postgres")
        subprocess.run([initdb, "-D", str(cluster), "--no-locale", "--encoding=UTF8"],
                       check=True, capture_output=True)
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        subprocess.run([pg_ctl, "-D", str(cluster), "-o", f"-p {port}",
                        "-l", str(cluster / "server.log"), "start"],
                       check=True, capture_output=True)
        configured = f"postgresql://127.0.0.1:{port}/postgres"
    try:
        yield configured
    finally:
        if owned and cluster is not None and pg_ctl is not None:
            subprocess.run([pg_ctl, "-D", str(cluster), "stop"],
                           check=True, capture_output=True)


@pytest.fixture
def database_url(postgres_base_url):
    """Isolated PostgreSQL schema for a test."""
    configured = postgres_base_url
    schema = "test_" + uuid.uuid4().hex
    admin = dbm.connect(configured)
    admin.execute(f'CREATE SCHEMA "{schema}"')
    admin.commit()
    admin.close()
    separator = "&" if "?" in configured else "?"
    scoped = (f"{configured}{separator}options=-csearch_path%3D{schema}"
              f"&application_name={schema}")
    try:
        yield scoped
    finally:
        admin = dbm.connect(configured)
        admin.execute(
            "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
            "WHERE application_name=%s AND pid <> pg_backend_pid()", (schema,))
        admin.execute(f'DROP SCHEMA IF EXISTS "{schema}" CASCADE')
        admin.commit()
        admin.close()


@pytest.fixture
def conn(database_url):
    """A database with the standard set of test accounts registered."""
    c = dbm.connect(database_url)
    dbm.init_db(c)
    for inst in (
        Institution(id="hsbc_hk", display_name="HSBC HK", country="HK"),
        Institution(id="wise", display_name="Wise", country="HK"),
        Institution(id="amex_us", display_name="AMEX US", country="US"),
        Institution(id="amex_hk", display_name="AMEX HK", country="HK"),
        Institution(id="mox", display_name="Mox", country="HK"),
    ):
        dbm.upsert_institution(c, inst)

    for acct in (
        Account(id="hsbc_hk_current", institution_id="hsbc_hk",
                display_name="HSBC Current", account_type="checking",
                primary_currency="HKD"),
        Account(id="wise_hkd", institution_id="wise", display_name="Wise HKD",
                account_type="multi_currency", primary_currency="HKD",
                balance_group="wise_personal"),
        Account(id="wise_usd", institution_id="wise", display_name="Wise USD",
                account_type="multi_currency", primary_currency="USD",
                balance_group="wise_personal"),
        Account(id="amex_us_main", institution_id="amex_us", display_name="AMEX US",
                account_type="credit_card", primary_currency="USD"),
        # A genuinely multi-currency card: settles in both HKD and USD.
        Account(id="amex_hk_main", institution_id="amex_hk", display_name="AMEX HK",
                account_type="credit_card", primary_currency="HKD",
                settlement_currencies=["HKD", "USD"]),
        Account(id="mox_main", institution_id="mox", display_name="Mox",
                account_type="checking", primary_currency="HKD"),
    ):
        dbm.upsert_account(c, acct)

    dbm.upsert_card(c, Card(id="amex_us_main_primary", account_id="amex_us_main",
                            cardholder_name="ALEX E", last4="1001"))
    dbm.upsert_card(c, Card(id="amex_us_main_supp1", account_id="amex_us_main",
                            cardholder_name="SUPP HOLDER", last4="1009",
                            is_supplementary=True))
    c.commit()
    yield c
    c.close()


# ---------------------------------------------------------------------------
# Minimal PDF builder
# ---------------------------------------------------------------------------

def write_pdf(path: Path, lines: list[str]) -> Path:
    """Write a single-page PDF whose text layer contains `lines`.

    Hand-built rather than pulled from a library so the tests have no extra
    dependency and the bytes are fully deterministic.
    """
    escaped = [ln.replace("\\", r"\\").replace("(", r"\(").replace(")", r"\)")
               for ln in lines]
    body = "BT /F1 10 Tf 40 780 Td 12 TL\n"
    body += "\n".join(f"({ln}) Tj T*" for ln in escaped)
    body += "\nET"
    stream = zlib.compress(body.encode("latin-1"))

    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
        b"/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
        b"<< /Length " + str(len(stream)).encode() + b" /Filter /FlateDecode >>\nstream\n"
        + stream + b"\nendstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Courier >>",
    ]

    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for i, obj in enumerate(objects, start=1):
        offsets.append(len(out))
        out += f"{i} 0 obj\n".encode() + obj + b"\nendobj\n"

    xref_at = len(out)
    out += f"xref\n0 {len(objects) + 1}\n".encode()
    out += b"0000000000 65535 f \n"
    for off in offsets:
        out += f"{off:010d} 00000 n \n".encode()
    out += (f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\n"
            f"startxref\n{xref_at}\n%%EOF\n").encode()

    path.write_bytes(bytes(out))
    return path
