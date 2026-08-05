"""Balance reconciliation and structural integrity endpoints."""

from __future__ import annotations

from fastapi import APIRouter, Depends

from ...integrity import check_all, find_violations
from ..deps import get_conn

router = APIRouter(tags=["integrity"])


@router.get("/integrity")
def get_integrity(conn=Depends(get_conn)) -> dict:
    # record=False: answering the question must not write. The audit trail
    # belongs to import and to `finto check`, which run once per statement, not
    # to a page that may be open on a dashboard all day.
    checks = check_all(conn, record=False)
    violations = find_violations(conn)

    # Accounts with no balance assertion are *unverified*, not healthy. `check`
    # used to print nothing for them, which reads as "fine" when it means
    # "nothing was checked".
    verified = {c["account_id"] for c in checks
                if c.get("status") in ("ok", "discrepancy")}
    unverified = [dict(r) for r in conn.execute(
        "SELECT a.id AS account_id, a.display_name, COUNT(t.id) AS txn_count "
        "FROM account a JOIN txn t ON t.account_id = a.id "
        "GROUP BY a.id HAVING COUNT(t.id) > 0")]
    unverified = [u for u in unverified if u["account_id"] not in verified]

    discrepancies = [c for c in checks if c.get("status") == "discrepancy"]
    return {
        "healthy": not violations and not discrepancies,
        "violations": violations,
        "balance_checks": checks,
        "discrepancies": discrepancies,
        "unverified_accounts": unverified,
        "summary": {
            "checks_run": len(checks),
            "discrepancy_count": len(discrepancies),
            "violation_count": sum(v["count"] for v in violations),
            "unverified_account_count": len(unverified),
        },
    }
