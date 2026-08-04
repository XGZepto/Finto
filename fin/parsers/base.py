"""Parser contract and registry.

Adding an institution means writing one class with two methods and decorating
it with @register. Nothing else in the pipeline changes.
"""

from __future__ import annotations

import csv
import io
import re
from abc import ABC, abstractmethod
from collections.abc import Sequence
from dataclasses import dataclass, field
from datetime import date, datetime
from decimal import Decimal, InvalidOperation
from pathlib import Path

from ..models import FileFormat, Money, ParsedTxn, minor_exponent


@dataclass
class ParseContext:
    """Everything a parser is allowed to know about where the file came from."""

    path: Path
    institution_id: str | None = None
    account_id: str | None = None
    default_currency: str | None = None
    hints: dict[str, str] = field(default_factory=dict)


@dataclass
class ParseResult:
    txns: list[ParsedTxn]
    raw_rows: list[dict]
    period_start: date | None = None
    period_end: date | None = None
    statement_date: date | None = None
    account_id: str | None = None
    warnings: list[str] = field(default_factory=list)
    # (date, Money, account_hint) triples from the statement's own balance
    # figures. These are the independent check on whether we captured every
    # row — always capture them when the source provides a balance. The hint
    # routes the figure to an account in consolidated (multi-account) files.
    balances: list[tuple] = field(default_factory=list)
    #: A statement can legitimately contain no transactions (an idle account
    #: month). Only set when the extraction was verified against the issuer's
    #: own balances, so "no rows" is a fact, not a parser failure.
    allow_empty: bool = False


class StatementParser(ABC):
    """Base class for all statement parsers."""

    parser_id: str = "abstract"
    version: str = "0.0.0"
    institution_id: str = ""
    file_format: FileFormat = FileFormat.CSV

    @abstractmethod
    def sniff(self, ctx: ParseContext, sample: bytes) -> float:
        """Return confidence 0.0-1.0 that this parser handles the file.

        Sample is the first ~64KB. Cheap checks only: header signatures,
        filename patterns, magic bytes.
        """

    @abstractmethod
    def parse(self, ctx: ParseContext) -> ParseResult:
        """Extract transactions. Do not normalise, categorise or dedup here."""


# ---------------------------------------------------------------------------
# Registry
# ---------------------------------------------------------------------------

_REGISTRY: list[type[StatementParser]] = []


def register(cls: type[StatementParser]) -> type[StatementParser]:
    _REGISTRY.append(cls)
    return cls


def all_parsers() -> list[StatementParser]:
    return [c() for c in _REGISTRY]


# Formats that are parsed as text. A parser declaring one of these must never be
# offered a binary file.
_TEXT_FORMATS = {FileFormat.CSV, FileFormat.OFX, FileFormat.QFX, FileFormat.JSON}
_BINARY_SUFFIXES = {".pdf", ".xlsx", ".xls", ".zip", ".ofc"}
_BINARY_MAGIC = (b"%PDF", b"PK\x03\x04", b"\xd0\xcf\x11\xe0")


def looks_binary(path: Path, sample: bytes) -> bool:
    """True when a file cannot sensibly be read as delimited text.

    Several parsers sniff on the filename ("amex" in the name scores 0.3, "mox"
    scores 0.6), so without this a CSV parser will happily claim `mox-statement.pdf`,
    read zero rows out of the binary, and report success.
    """
    if path.suffix.lower() in _BINARY_SUFFIXES:
        return True
    if sample.startswith(_BINARY_MAGIC):
        return True
    return b"\x00" in sample[:8192]


def select_parser(ctx: ParseContext, min_confidence: float = 0.5) -> StatementParser | None:
    """Pick the highest-confidence parser for a file, or None."""
    sample = ctx.path.read_bytes()[:65536]
    binary = looks_binary(ctx.path, sample)
    scored = []
    for p in all_parsers():
        if binary and p.file_format in _TEXT_FORMATS:
            continue
        try:
            score = p.sniff(ctx, sample)
        except Exception:
            score = 0.0
        if score > 0:
            scored.append((score, p))
    if not scored:
        return None
    scored.sort(key=lambda t: t[0], reverse=True)
    best_score, best = scored[0]
    return best if best_score >= min_confidence else None


# ---------------------------------------------------------------------------
# Shared helpers — the fiddly bits every parser needs
# ---------------------------------------------------------------------------

_DATE_FORMATS = (
    "%Y-%m-%d", "%d/%m/%Y", "%m/%d/%Y", "%d-%m-%Y", "%d %b %Y", "%d %B %Y",
    "%b %d, %Y", "%d/%m/%y", "%m/%d/%y", "%Y%m%d", "%d%b%Y",
)


def parse_date(value: str, dayfirst: bool = True) -> date:
    """Parse a date string, trying regional orderings in the right priority.

    `dayfirst` matters: HSBC HK and AMEX HK write 03/08/2026 as 3 August,
    AMEX US writes it as 3 March. Pass the institution's convention.
    """
    v = value.strip()
    if not v:
        raise ValueError("empty date")
    orders = ("%d/%m/%Y", "%m/%d/%Y") if dayfirst else ("%m/%d/%Y", "%d/%m/%Y")
    for fmt in (*orders, *[f for f in _DATE_FORMATS if f not in orders]):
        try:
            return datetime.strptime(v, fmt).date()
        except ValueError:
            continue
    raise ValueError(f"unrecognised date: {value!r}")


_AMOUNT_CLEAN = re.compile(r"[\s ]")
_SEPARATOR = re.compile(r"[.,](\d+)")
_TRAILING_SIGN = re.compile(r"^(?P<num>[\d.,]+)\s*(?P<sign>CR|DR|\+|-)$", re.IGNORECASE)


def parse_amount(value: str, currency: str, *, credit_positive: bool = True) -> Money:
    """Parse an amount into signed minor units.

    Handles the notations statements actually use:
      "1,234.56"   plain
      "(1,234.56)" parenthesised negative
      "1,234.56CR" trailing credit marker (HSBC HK)
      "-1,234.56"  leading sign
      "5.420,95"   European grouping — AMEX bills a foreign charge in the
                   foreign market's own convention, so a EUR amount arrives
                   with the roles of '.' and ',' swapped
    """
    v = value.strip()
    if not v:
        raise ValueError("empty amount")

    negative = False
    if v.startswith("(") and v.endswith(")"):
        negative = True
        v = v[1:-1]

    v = _AMOUNT_CLEAN.sub("", v)
    v = re.sub(r"^[A-Z]{3}", "", v, flags=re.IGNORECASE)  # strip "HKD1,234.00"
    v = v.replace("$", "")

    m = _TRAILING_SIGN.match(v)
    if m:
        v = m.group("num")
        sign = m.group("sign").upper()
        if sign == "CR":
            # credit_positive: a CR marker means money entering the account.
            # Pass False for sources that write it the other way round.
            negative = not credit_positive
        elif sign == "DR":
            negative = credit_positive
        elif sign == "-":
            negative = True
        else:  # "+"
            negative = False

    if v.startswith("-"):
        negative = True
        v = v[1:]
    elif v.startswith("+"):
        v = v[1:]

    try:
        d = Decimal(_normalise_separators(v, currency))
    except InvalidOperation as e:
        raise ValueError(f"unrecognised amount: {value!r}") from e

    if negative:
        d = -d
    return Money.from_decimal(d, currency)


def _normalise_separators(v: str, currency: str) -> str:
    """Resolve '.' and ',' into a decimal point, using the currency to decide.

    Shape alone cannot: "1,234" is a thousand, "13,04" is thirteen and four
    hundredths. The trailing separator is the decimal point only when the
    currency's minor-unit digits follow it; every other separator groups.
    """
    exp = minor_exponent(currency)
    seps = list(_SEPARATOR.finditer(v))
    if exp and seps and seps[-1].end() == len(v) and len(seps[-1].group(1)) == exp:
        return re.sub(r"[.,]", "", v[:seps[-1].start()]) + "." + seps[-1].group(1)
    return re.sub(r"[.,]", "", v)


def read_csv_rows(path: Path, *, skip_preamble: bool = True) -> tuple[list[str], list[dict]]:
    """Read a CSV, tolerating the junk preamble banks put above the header.

    Finds the first row that looks like a header (>=3 non-empty cells, and a
    following row with the same cell count) and treats everything above it as
    preamble.
    """
    text = path.read_text(encoding="utf-8-sig", errors="replace")
    rows = list(csv.reader(io.StringIO(text)))
    rows = [r for r in rows if any(c.strip() for c in r)]
    if not rows:
        return [], []

    start = 0
    if skip_preamble:
        for i, row in enumerate(rows[:-1]):
            nonempty = sum(1 for c in row if c.strip())
            if nonempty >= 3 and len(rows[i + 1]) == len(row):
                start = i
                break

    header = [h.strip() for h in rows[start]]
    out = []
    for r in rows[start + 1:]:
        if len(r) < len(header):
            r = r + [""] * (len(header) - len(r))
        out.append({header[i]: r[i].strip() for i in range(len(header))})
    return header, out


def pick(row: dict, *names: str) -> str:
    """Case/space-insensitive column lookup with fallbacks."""
    lowered = {k.lower().replace(" ", "").replace("_", ""): v for k, v in row.items()}
    for n in names:
        key = n.lower().replace(" ", "").replace("_", "")
        if key in lowered and lowered[key].strip():
            return lowered[key].strip()
    return ""


def has_columns(header: Sequence[str], *required: str) -> bool:
    norm = {h.lower().replace(" ", "").replace("_", "") for h in header}
    return all(r.lower().replace(" ", "").replace("_", "") in norm for r in required)
