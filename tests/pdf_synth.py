"""Build synthetic statement pages for testing.

Real statements cannot go in the repository — they are the user's bank records,
and the whole project is built on the promise that they never leave the
machine. But the layouts *can* be reproduced, and the layout is what the
templates actually depend on.

A fixture is written as monospaced text, where the horizontal position of each
word is the meaningful part:

    DATE        DESCRIPTION                 DEPOSIT    WITHDRAWAL    BALANCE
    14 Dec      B/F BALANCE                                           213.30
    20 Dec      OCTOPUS CARDS LIMITED                     100.00      113.30

Each character column maps to a fixed x-offset, so a word under "WITHDRAWAL"
lands in the withdrawal column exactly as it would in the PDF. That makes the
fixtures readable as statements while still exercising the real code path:
column resolution, sign-by-position, multi-row headers and all.
"""

from __future__ import annotations

from fin.pdf.extract import PdfDocument, PdfPage, TextLine, Word

#: Points per character column, and per row. Chosen so a 100-column fixture
#: fits the 612pt width of US Letter, matching the real statements.
CHAR_WIDTH = 6.0
ROW_HEIGHT = 12.0
PAGE_WIDTH = 612.0
PAGE_HEIGHT = 792.0


def row(*cells: tuple[int, str]) -> str:
    """Place text at given character columns: row((0, "14 Dec"), (40, "100.00")).

    Safer than counting spaces by hand, and it keeps a fixture's column
    positions visible at the point they matter.
    """
    out = ""
    for col, text in cells:
        if col > len(out):
            out += " " * (col - len(out))
        elif out and not out.endswith(" "):
            out += " "
        out += text
    return out


def right(col: int, text: str) -> tuple[int, str]:
    """Right-align text so it ENDS at `col`, as statements set money columns."""
    return (max(0, col - len(text)), text)


def make_page(text: str, page_no: int = 0) -> PdfPage:
    """Turn monospaced text into a page of positioned words."""
    lines: list[TextLine] = []
    for row, raw in enumerate(text.split("\n")):
        if not raw.strip():
            continue
        words = []
        col = 0
        for chunk in raw.split(" "):
            if chunk:
                words.append(
                    Word(
                        text=chunk,
                        x0=col * CHAR_WIDTH,
                        x1=(col + len(chunk)) * CHAR_WIDTH,
                        top=row * ROW_HEIGHT,
                        bottom=row * ROW_HEIGHT + 10.0,
                    )
                )
            col += len(chunk) + 1
        if words:
            lines.append(
                TextLine(
                    words=words,
                    top=row * ROW_HEIGHT,
                    page_no=page_no,
                    line_no=len(lines),
                )
            )
    return PdfPage(page_no=page_no, width=PAGE_WIDTH, height=PAGE_HEIGHT, lines=lines)


def make_document(*pages: str, path: str = "synthetic.pdf") -> PdfDocument:
    """Build a document from one text block per page."""
    return PdfDocument(
        path=path,
        pages=[make_page(text, i) for i, text in enumerate(pages)],
        content_hash="synthetic",
    )


def amounts(result) -> list[tuple[str, int]]:
    """(iso date, minor units) for each extracted row — the thing worth asserting."""
    return [(r.txn_date.isoformat(), r.amount.amount) for r in result.rows]


def by_section(result) -> dict[str, list[int]]:
    out: dict[str, list[int]] = {}
    for r in result.rows:
        out.setdefault(r.section, []).append(r.amount.amount)
    return out
