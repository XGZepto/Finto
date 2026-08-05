"""Database-enforced account ACLs."""

from __future__ import annotations

import pytest
from psycopg.errors import InsufficientPrivilege

from fin import db as dbm
from fin.auth import create_user, grant_account


def test_account_acl_roles_are_enforced_by_postgres(conn, database_url):
    viewer = create_user(
        conn, username="viewer", email="viewer@example.test", password="viewer password",
    )
    editor = create_user(
        conn, username="editor", email="editor@example.test", password="editor password",
    )
    grant_account(conn, account_id="hsbc_hk_current", user_id=viewer["id"], role="viewer")
    grant_account(conn, account_id="hsbc_hk_current", user_id=editor["id"], role="editor")
    conn.execute(
        "INSERT INTO statement_file (id,source_path,file_sha256,institution_id,account_id,"
        "file_format,parser_id,parser_version,imported_at,row_count) VALUES "
        "('acl-file','acl.csv','acl-hash','hsbc_hk','hsbc_hk_current',"
        "'csv','test','1','2026-08-01',1)"
    )
    conn.execute(
        "INSERT INTO txn (id,account_id,txn_date,status,amount_booked,currency_booked,"
        "description_raw,description_norm,kind,dedup_key,statement_file_id,"
        "created_at,updated_at) VALUES "
        "('acl-txn','hsbc_hk_current','2026-08-01','posted',-100,'HKD',"
        "'ACL TEST','ACL TEST','purchase','acl-dedup','acl-file','2026-08-01','2026-08-01')"
    )
    conn.commit()

    hidden = dbm.connect(database_url)
    dbm.apply_acl(hidden, "ungranted-user")
    assert hidden.execute("SELECT count(*) AS n FROM account").fetchone()["n"] == 0
    assert hidden.execute("SELECT count(*) AS n FROM txn").fetchone()["n"] == 0
    hidden.close()

    view_conn = dbm.connect(database_url)
    dbm.apply_acl(view_conn, viewer["id"])
    assert [r["id"] for r in view_conn.execute("SELECT id FROM account")] == [
        "hsbc_hk_current"
    ]
    assert view_conn.execute("SELECT id FROM txn").fetchone()["id"] == "acl-txn"
    with pytest.raises(InsufficientPrivilege):
        view_conn.execute("UPDATE txn SET description_raw='NO' WHERE id='acl-txn'")
    view_conn.rollback()
    view_conn.close()

    edit_conn = dbm.connect(database_url)
    dbm.apply_acl(edit_conn, editor["id"])
    edit_conn.execute("UPDATE txn SET description_raw='EDITED' WHERE id='acl-txn'")
    edit_conn.commit()
    assert edit_conn.execute(
        "SELECT description_raw FROM txn WHERE id='acl-txn'"
    ).fetchone()["description_raw"] == "EDITED"
    with pytest.raises(InsufficientPrivilege):
        edit_conn.execute(
            "UPDATE account SET display_name='NO' WHERE id='hsbc_hk_current'"
        )
    edit_conn.rollback()
    edit_conn.close()
