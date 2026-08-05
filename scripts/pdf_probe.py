"""Development harness: run templates over real statements and report.

Not part of the package. Prints what a template extracted, alongside the
issuer's own balance figures, so a layout can be checked by eye before it is
trusted by the importer.

    python scripts/pdf_probe.py <pdf-or-directory> [--rows N] [--layout]
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from fin.models import minor_exponent
from fin.pdf.extract import extract_document
from fin.pdf.registry import select_template
from fin.pdf.template import apply_template
from fin.pdf.verify import verify_extraction


def fmt(m) -> str:
    if m is None:
        return "-"
    exp = minor_exponent(m.currency)
    return f"{m.amount / (10 ** exp):>12,.{exp}f} {m.currency}"


def probe(path: Path, *, rows: int, show_layout: bool) -> bool:
    doc = extract_document(path)
    tpl, score = select_template(doc)
    if show_layout:
        print(doc.layout)
    if tpl is None:
        print(f"  ✗ {path.name}: no template matched (best {score:.2f})")
        return False

    res = apply_template(doc, tpl)
    report = verify_extraction(res)
    mark = {"verified": "✓", "unverified": "~", "failed": "✗"}[report.status]
    print(f"  {mark} {path.name}: {tpl.template_id} ({score:.2f}) "
          f"{len(res.rows)} rows  {report.summary()}")

    for r in res.rows[:rows]:
        print(f"      {r.txn_date}  {fmt(r.amount)}  [{r.section}] {r.description[:58]}")
    if len(res.rows) > rows:
        print(f"      … {len(res.rows) - rows} more")
    for w in res.warnings[:6]:
        print(f"      ! {w}")
    for w in report.problems[:6]:
        print(f"      ✗ {w}")
    return report.ok


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("target")
    ap.add_argument("--rows", type=int, default=6)
    ap.add_argument("--layout", action="store_true")
    args = ap.parse_args()

    target = Path(args.target)
    paths = sorted(target.rglob("*.pdf")) if target.is_dir() else [target]

    ok = fail = 0
    for p in paths:
        try:
            if probe(p, rows=args.rows, show_layout=args.layout):
                ok += 1
            else:
                fail += 1
        except Exception as e:
            fail += 1
            print(f"  ✗ {p.name}: {type(e).__name__}: {e}")
    print(f"\n{ok} verified, {fail} failed, {ok + fail} total")
    return 0 if fail == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
