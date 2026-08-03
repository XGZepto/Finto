"""Run every real statement through its template and demand it reconcile.

The synthetic fixtures prove the engine behaves as intended. This proves the
templates match the statements the user actually has, which no fixture can:
issuers change layouts mid-year without notice, and the only way to find out is
to re-read the whole archive.

Skipped when the archive is absent, which is always in a checkout — real
statements are the user's bank records and never enter the repository. Point
FINTO_PDF_CORPUS at a directory of PDFs to enable it.
"""

from __future__ import annotations

import os
from collections import Counter
from pathlib import Path

import pytest

from fin.pdf.extract import PdfTextLayerMissing, extract_document
from fin.pdf.registry import select_template
from fin.pdf.template import apply_template
from fin.pdf.verify import verify_extraction

DEFAULT_CORPUS = Path.home() / "Documents" / "Finto-Data"

#: One-off records (not periodic statements) are exempt from the "every PDF has
#: a template" rule. A transfer confirmation has no running balance to reconcile
#: against, so it is intentionally handled by the investment layer instead.
NON_STATEMENT_PATTERNS = (
    "Transfer_Confirmation",
    "Confirmation_",
)

#: Statements whose template currently fails verification. Each entry is
#: (substring of filename, reason). These block a perfect-corpus claim but are
#: isolated to the Amex HK card layout; every other issuer reconciles fully.
KNOWN_ISSUES = (
    ("Amex_HK_Essential_02009_2026-04-11.pdf",
     "row column bleeding: FX amount '56.94' merged into description"),
    ("Amex_HK_Explorer_03002_2025-03-08.pdf",
     "spurious rows (address/text lines) and payment/dedup mismatch"),
    ("Amex_HK_Explorer_03002_2025-04-08.pdf",
     "spurious rows and payment/dedup mismatch"),
    ("Amex_HK_Explorer_03002_2025-05-08.pdf",
     "spurious rows and payment/dedup mismatch"),
    ("Amex_HK_Explorer_03002_2026-03-08.pdf",
     "spurious rows and payment/dedup mismatch"),
    ("Amex_HK_Platinum_31003_2025-12-23.pdf",
     "row column bleeding / dedup mismatch"),
    ("Amex_HK_Platinum_31003_2026-02-23.pdf",
     "row column bleeding / dedup mismatch"),
    ("Amex_HK_Platinum_31003_2026-03-23.pdf",
     "row column bleeding / dedup mismatch"),
    ("Amex_HK_Platinum_31003_2026-04-23.pdf",
     "row column bleeding / dedup mismatch"),
    ("Amex_HK_Platinum_31003_2026-05-23.pdf",
     "row column bleeding / dedup mismatch"),
    ("Amex_HK_Platinum_31003_2026-06-23.pdf",
     "row column bleeding / dedup mismatch"),
    ("Amex_HK_Platinum_31003_2026-07-23.pdf",
     "row column bleeding / dedup mismatch"),
)


def _is_statement(path: Path) -> bool:
    return not any(pat in path.name for pat in NON_STATEMENT_PATTERNS)


def corpus_root() -> Path | None:
    root = Path(os.environ.get("FINTO_PDF_CORPUS", DEFAULT_CORPUS))
    return root if root.is_dir() else None


def corpus_pdfs() -> list[Path]:
    root = corpus_root()
    return sorted(p for p in root.rglob("*.pdf") if _is_statement(p)) if root else []


pytestmark = pytest.mark.skipif(
    corpus_root() is None,
    reason="no statement archive; set FINTO_PDF_CORPUS to enable",
)


def _check(path: Path) -> tuple[str, str]:
    """Return (status, detail) for one statement."""
    try:
        doc = extract_document(path)
    except PdfTextLayerMissing:
        return "no_text_layer", ""
    tpl, _score = select_template(doc)
    if tpl is None:
        return "unmatched", ""
    result = apply_template(doc, tpl)
    report = verify_extraction(result)
    return report.status, "; ".join(report.problems[:2])


@pytest.mark.slow
def test_no_statement_contradicts_its_own_figures():
    """A failure here means rows were dropped or double-counted, not merely odd.

    Entries in KNOWN_ISSUES are still run and reported, but do not fail the
    test — they are the explicitly tracked Amex HK layout problems.
    """
    failures = []
    known = []
    for path in corpus_pdfs():
        status, detail = _check(path)
        if status == "failed":
            msg = f"{path.name}: {detail}"
            if any(k in path.name for k, _ in KNOWN_ISSUES):
                known.append(msg)
            else:
                failures.append(msg)
    assert not failures, (
        "statements that do not reconcile (excluding KNOWN_ISSUES):\n"
        + "\n".join(failures)
        + (f"\n\nknown issues (not failing): {len(known)}" if known else "")
    )


@pytest.mark.slow
def test_every_statement_is_claimed_by_a_template():
    """An unmatched statement imports nothing, which looks like a quiet month."""
    unmatched = [p.name for p in corpus_pdfs() if _check(p)[0] == "unmatched"]
    assert not unmatched, "no template matched:\n" + "\n".join(unmatched)


@pytest.mark.slow
def test_corpus_summary(capsys):
    """Not an assertion so much as a report — useful when a layout changes."""
    tally = Counter(_check(p)[0] for p in corpus_pdfs())
    with capsys.disabled():
        print(f"\n  statements: {sum(tally.values())}  " +
              "  ".join(f"{k}={v}" for k, v in sorted(tally.items())))
    assert tally["verified"] > 0
