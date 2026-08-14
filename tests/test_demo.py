"""Deterministic public demo data."""

from fin import db as dbm
from fin.demo import ACCOUNTS, seed_demo


def test_seed_demo_creates_a_read_only_viewer(database_url, monkeypatch):
    monkeypatch.setenv("FINTO_DEMO_USERNAME", "demo")
    monkeypatch.setenv("FINTO_DEMO_EMAIL", "demo@finto.app")
    monkeypatch.setenv("FINTO_DEMO_PASSWORD", "public-demo-password")
    conn = dbm.connect(database_url)

    inserted = seed_demo(conn=conn, version="test-deployment")

    user = conn.execute(
        "SELECT username,email FROM app_user WHERE id='demo'"
    ).fetchone()
    roles = conn.execute(
        "SELECT access_role FROM account_acl WHERE user_id='demo'"
    ).fetchall()
    version = conn.execute(
        "SELECT value FROM setting WHERE key='demo_seed_version'"
    ).fetchone()
    assert inserted > 1_000
    assert user == {"username": "demo", "email": "demo@finto.app"}
    assert len(roles) == len(ACCOUNTS)
    assert {row["access_role"] for row in roles} == {"viewer"}
    assert version["value"] == "test-deployment"
    conn.close()
