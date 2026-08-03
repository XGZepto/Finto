"""Column geometry: turning x-positions into meaning.

On a statement, what a number *means* is encoded in where it sits. HSBC prints
Deposit, Withdrawal and Balance as three columns of identical-looking figures;
only the horizontal position says whether 100.00 left the account or entered it.
This module recovers that mapping.

Two ways to locate columns, because issuers differ in how helpful their headers
are:

* **Anchors** — find the header row, take each heading's box, and split the gaps
  between neighbours. Preferred: it re-derives the geometry from each file, so
  a layout shift moves the columns with it.
* **Fixed fractions** — explicit boundaries as a fraction of page width, for
  issuers whose headings are unusable. HSBC's card statements set the header as
  a single run-together token ("PostdateTransdate"), so there is nothing to
  anchor to.

Either way the result is checked downstream against the statement's own totals,
so geometry that drifts produces a loud reconciliation failure rather than
quietly mis-signed money.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from .extract import TextLine

# A money token as statements write them. Requires a decimal part or a comma
# group, so reference numbers and dates are not mistaken for amounts.
MONEY_TOKEN = re.compile(
    r"^[-+(]?\s*(?:HK\$|US\$|[$€£¥])?\s*"
    r"(?:\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+\.\d{2})"
    r"\s*\)?\s*(?:CR|DR)?$",
    re.IGNORECASE,
)

# Amounts with no decimals and no thousands separator ("0" in Mox's JPY
# sub-ledger, where the currency has no minor unit).
BARE_INT_TOKEN = re.compile(r"^[-+]?\d{1,3}$")


def is_money(token: str) -> bool:
    t = token.strip()
    return bool(MONEY_TOKEN.match(t))


@dataclass(frozen=True)
class Column:
    name: str
    x0: float
    x1: float

    def contains(self, mid: float) -> bool:
        return self.x0 <= mid < self.x1


@dataclass
class ColumnSet:
    columns: list[Column]

    def get(self, name: str) -> Column | None:
        for c in self.columns:
            if c.name == name:
                return c
        return None

    def text(self, line: TextLine, name: str) -> str:
        c = self.get(name)
        if c is None:
            return ""
        return line.text_between(c.x0, c.x1)

    def cells(self, line: TextLine) -> dict[str, str]:
        return {c.name: line.text_between(c.x0, c.x1) for c in self.columns}


def columns_from_anchors(
    header: TextLine,
    anchors: dict[str, str],
    *,
    page_width: float,
    required: set[str] | None = None,
) -> ColumnSet | None:
    """Derive columns by locating each heading in a header row.

    `anchors` maps column name to a regex matched against the header text.
    Boundaries fall midway between adjacent headings, which is where the
    whitespace gutter sits on every statement layout observed.

    A heading that cannot be found is skipped, because issuers stack multi-word
    headings across two or three lines and only part of one lands on the row we
    anchor to. Skipping widens its neighbours rather than losing the column, so
    the text still reaches the description. Headings named in `required` must
    resolve: those carry the date and the money, and guessing at their position
    would mis-sign transactions instead of merely garbling a description.
    """
    required = required or set()
    found: list[tuple[str, float, float]] = []
    for name, pattern in anchors.items():
        rx = re.compile(pattern, re.IGNORECASE)
        span = _locate(header, rx)
        if span is None:
            if name in required:
                return None
            continue
        found.append((name, span[0], span[1]))

    if not found:
        return None

    found.sort(key=lambda t: t[1])
    cols: list[Column] = []
    for i, (name, x0, x1) in enumerate(found):
        left = 0.0 if i == 0 else (found[i - 1][2] + x0) / 2
        right = page_width if i == len(found) - 1 else (x1 + found[i + 1][1]) / 2
        cols.append(Column(name, left, right))
    return ColumnSet(cols)


def _locate(line: TextLine, rx: re.Pattern) -> tuple[float, float] | None:
    """Find the box covering a regex match within a line's words.

    Matching is done per word first (the common case), then over the joined
    text so a heading split across words like "AMOUNT HK$" still resolves.
    """
    for w in line.words:
        if rx.search(w.text):
            return (w.x0, w.x1)

    # Multi-word heading: walk adjacent runs of words.
    for i in range(len(line.words)):
        for j in range(i + 1, min(i + 5, len(line.words)) + 1):
            run = line.words[i:j]
            if rx.search(" ".join(w.text for w in run)):
                return (run[0].x0, run[-1].x1)
    return None


def columns_from_fractions(
    bounds: list[tuple[str, float, float]], page_width: float
) -> ColumnSet:
    """Build columns from explicit fractions of the page width."""
    return ColumnSet(
        [Column(name, f0 * page_width, f1 * page_width) for name, f0, f1 in bounds]
    )


def detect_money_columns(
    lines: list[TextLine], *, min_rows: int = 3, tolerance: float = 6.0
) -> list[tuple[float, float]]:
    """Find right-aligned numeric columns by clustering money tokens.

    Numeric columns on a statement are right-aligned, so the *right* edges of
    their tokens line up tightly even when the numbers vary in width. Used to
    propose a layout for an unrecognised issuer, and by the template-derivation
    prompt to tell an LLM where the amount columns actually are.
    """
    edges: list[tuple[float, float]] = []
    for line in lines:
        for w in line.words:
            if is_money(w.text):
                edges.append((w.x1, w.x0))
    if not edges:
        return []

    edges.sort()
    clusters: list[list[tuple[float, float]]] = [[edges[0]]]
    for e in edges[1:]:
        if e[0] - clusters[-1][-1][0] <= tolerance:
            clusters[-1].append(e)
        else:
            clusters.append([e])

    out = []
    for c in clusters:
        if len(c) < min_rows:
            continue
        right = max(x1 for x1, _ in c)
        left = min(x0 for _, x0 in c)
        out.append((left, right))
    return out


def find_line(
    lines: list[TextLine], pattern: str, *, start: int = 0
) -> int | None:
    """Index of the first line matching a regex, or None."""
    rx = re.compile(pattern, re.IGNORECASE)
    for i in range(start, len(lines)):
        if rx.search(lines[i].text):
            return i
    return None
