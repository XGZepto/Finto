"""Template discovery.

Templates ship as JSON beside this module and may also be stored in the
database, which is what makes a correction durable: an agent that fixes a
layout writes a new template row, and every later import uses it without a code
change or release. Database templates win ties against built-ins of the same
id, so a local correction always takes precedence.
"""

from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path

from .extract import PdfDocument
from .template import StatementTemplate, TemplateError

TEMPLATE_DIR = Path(__file__).parent / "templates"


@lru_cache(maxsize=1)
def builtin_templates() -> tuple[StatementTemplate, ...]:
    out = []
    if TEMPLATE_DIR.is_dir():
        for path in sorted(TEMPLATE_DIR.glob("*.json")):
            try:
                out.append(StatementTemplate.load(path))
            except (TemplateError, json.JSONDecodeError) as e:  # pragma: no cover
                raise TemplateError(f"{path.name}: {e}") from e
    return tuple(out)


def db_templates(conn) -> list[StatementTemplate]:
    """Templates stored in the ledger, including agent-authored corrections."""
    if conn is None:
        return []
    try:
        rows = conn.execute(
            "SELECT body FROM pdf_template WHERE active=1 ORDER BY created_at DESC"
        ).fetchall()
    except Exception:
        # No pdf_template table (or a transient error) just means no overrides.
        return []
    out = []
    for r in rows:
        try:
            out.append(StatementTemplate.from_dict(json.loads(r["body"])))
        except (TemplateError, json.JSONDecodeError):
            continue
    return out


def available_templates(conn=None) -> list[StatementTemplate]:
    """All templates, database overrides shadowing built-ins by id."""
    overrides = db_templates(conn)
    seen = {t.template_id for t in overrides}
    return overrides + [t for t in builtin_templates() if t.template_id not in seen]


def select_template(
    doc: PdfDocument, conn=None, *, min_confidence: float = 0.5
) -> tuple[StatementTemplate | None, float]:
    """Pick the best-matching template for a document."""
    best: StatementTemplate | None = None
    best_score = 0.0
    for tpl in available_templates(conn):
        score = tpl.matches(doc)
        if score > best_score:
            best, best_score = tpl, score
    if best is None or best_score < min_confidence:
        return None, best_score
    return best, best_score


def save_template(conn, tpl: StatementTemplate, *, note: str = "") -> None:
    """Persist a template so later imports pick it up."""
    from datetime import datetime

    conn.execute(
        "INSERT OR REPLACE INTO pdf_template "
        "(template_id, institution_id, version, source, note, body, active, created_at) "
        "VALUES (?,?,?,?,?,?,1,?)",
        (
            tpl.template_id,
            tpl.institution_id,
            tpl.version,
            tpl.source,
            note,
            json.dumps(tpl.to_dict(), separators=(",", ":")),
            datetime.now().isoformat(),
        ),
    )
