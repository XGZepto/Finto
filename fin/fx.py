"""FX rates.

Rates exist here for one purpose: letting a *presentation layer* show a
normalised view. They are never used to combine currencies inside the ledger.

That distinction is the whole design. A position is only meaningful in a
currency the account settles in — HKD 50,000 and USD 3,000 are two facts, and
"HKD 50,000 + USD 3,000" is not a third fact, it is a category error. Converting
them into one number requires choosing a rate and a date, and that choice is a
reporting decision that must be visible and labelled. So conversion is offered
as an explicit, dated, sourced operation and never happens implicitly.

The best rate source is the statements themselves. Wise prints the exact rate it
used, which beats any third-party daily close because it is the rate that
actually applied to your money. `harvest_rates` mines those.
"""

from __future__ import annotations

import csv
from dataclasses import dataclass
from datetime import date
from decimal import Decimal, InvalidOperation
from pathlib import Path

from . import db as dbm
from .models import FxRate, Money, minor_exponent

# ---------------------------------------------------------------------------
# Harvesting rates the statements already told us
# ---------------------------------------------------------------------------

def harvest_rates(conn) -> int:
    """Derive FX rates from transactions that carry both currency pairs.

    A transaction with a native amount and a booked amount in different
    currencies is itself a rate observation, and it is the rate you were
    actually charged — including the issuer's spread. That is more useful for
    reconciling your own statements than any published mid-market rate.
    """
    found = 0
    for r in conn.execute(
            "SELECT txn_date, amount_native, currency_native, amount_booked, "
            "       currency_booked, fx_rate FROM txn "
            "WHERE amount_native IS NOT NULL AND currency_native IS NOT NULL "
            "  AND currency_native <> currency_booked AND duplicate_of_id IS NULL"):
        native = Money(amount=r["amount_native"], currency=r["currency_native"])
        booked = Money(amount=r["amount_booked"], currency=r["currency_booked"])
        if native.amount == 0:
            continue
        try:
            rate = (abs(booked.to_decimal()) / abs(native.to_decimal()))
        except (InvalidOperation, ZeroDivisionError):
            continue
        dbm.upsert_fx_rate(conn, FxRate(
            rate_date=date.fromisoformat(r["txn_date"]),
            base=native.currency, quote=booked.currency,
            rate=rate.quantize(Decimal("0.00000001")),
            source="statement",
        ))
        found += 1
    conn.commit()
    return found


def load_rates_csv(conn, path: Path) -> int:
    """Load rates from a CSV with columns: date, base, quote, rate[, source].

    Deliberately a file import rather than a network fetch. A ledger that
    silently changes because a remote rate feed moved is not reproducible, and
    the project's whole premise is that your data stays on your machine.
    """
    text = Path(path).read_text(encoding="utf-8-sig")
    reader = csv.DictReader(text.splitlines())
    loaded = 0
    for row in reader:
        lower = {k.strip().lower(): (v or "").strip() for k, v in row.items()}
        try:
            dbm.upsert_fx_rate(conn, FxRate(
                rate_date=date.fromisoformat(lower["date"]),
                base=lower["base"], quote=lower["quote"],
                rate=Decimal(lower["rate"]),
                source=lower.get("source") or "manual",
            ))
            loaded += 1
        except (KeyError, ValueError, InvalidOperation):
            continue
    conn.commit()
    return loaded


# ---------------------------------------------------------------------------
# Conversion — explicit, dated, and always labelled
# ---------------------------------------------------------------------------

@dataclass
class Converted:
    """A converted amount that carries how it was produced.

    The rate and its date travel with the number so a UI can never present a
    converted figure as if it were an actual balance.
    """

    amount: int
    currency: str
    source_amount: int
    source_currency: str
    rate: Decimal | None
    rate_date: str | None
    ok: bool

    def as_dict(self) -> dict:
        return {
            "amount": self.amount, "currency": self.currency,
            "source": {"amount": self.source_amount,
                       "currency": self.source_currency},
            "rate": str(self.rate) if self.rate is not None else None,
            "rate_date": self.rate_date,
            "converted": self.source_currency != self.currency,
            "ok": self.ok,
        }


def convert(conn, money: Money, to_currency: str, on: date | str | None = None) -> Converted:
    """Convert one amount, reporting the rate used and whether it succeeded.

    Never raises and never guesses: when no rate is available `ok` is False and
    the original amount is passed through unchanged, so a caller cannot
    accidentally treat an unconverted figure as converted.
    """
    to_currency = to_currency.upper()
    if money.currency == to_currency:
        return Converted(money.amount, to_currency, money.amount, money.currency,
                         Decimal(1), None, True)

    when = on or date.today()
    if isinstance(when, str):
        when = date.fromisoformat(when)

    row = conn.execute(
        "SELECT rate, rate_date FROM fx_rate WHERE base=? AND quote=? "
        "AND rate_date <= ? ORDER BY rate_date DESC LIMIT 1",
        (money.currency, to_currency, when.isoformat())).fetchone()
    rate, rate_date = (Decimal(row["rate"]), row["rate_date"]) if row else (None, None)

    if rate is None:
        inv = conn.execute(
            "SELECT rate, rate_date FROM fx_rate WHERE base=? AND quote=? "
            "AND rate_date <= ? ORDER BY rate_date DESC LIMIT 1",
            (to_currency, money.currency, when.isoformat())).fetchone()
        if inv and Decimal(inv["rate"]) != 0:
            rate, rate_date = Decimal(1) / Decimal(inv["rate"]), inv["rate_date"]

    if rate is None:
        return Converted(money.amount, money.currency, money.amount,
                         money.currency, None, None, False)

    # Re-scale between currencies whose minor units differ (JPY has none).
    scale = Decimal(10) ** (minor_exponent(to_currency) - minor_exponent(money.currency))
    converted = (Decimal(money.amount) * rate * scale).quantize(Decimal(1))
    return Converted(int(converted), to_currency, money.amount, money.currency,
                     rate, rate_date, True)


def convert_rows(conn, rows: list[dict], *, field: str, to_currency: str,
                 on: date | str | None = None) -> list[dict]:
    """Attach a converted figure to report rows without replacing the original.

    The native amount always stays. Conversion is additive, so a client can show
    both and label the converted one.
    """
    out = []
    for r in rows:
        item = dict(r)
        m = r.get(field)
        if isinstance(m, dict) and "amount" in m:
            item[f"{field}_converted"] = convert(
                conn, Money(amount=m["amount"], currency=m["currency"]),
                to_currency, on).as_dict()
        out.append(item)
    return out


def available_pairs(conn) -> list[dict]:
    return [dict(r) for r in conn.execute(
        "SELECT base, quote, COUNT(*) AS observations, MIN(rate_date) AS first_date, "
        "       MAX(rate_date) AS last_date, source "
        "FROM fx_rate GROUP BY base, quote, source ORDER BY base, quote")]


def missing_pairs(conn, to_currency: str) -> list[str]:
    """Currencies held that cannot be converted to `to_currency`.

    A normalised view that silently omits a currency is worse than one that
    refuses, so the UI needs to know what it cannot show.
    """
    to_currency = to_currency.upper()
    held = {r["currency"] for r in conn.execute(
        "SELECT DISTINCT currency_booked AS currency FROM v_ledger")}
    missing = []
    for ccy in sorted(held):
        if ccy == to_currency:
            continue
        if not convert(conn, Money(amount=100, currency=ccy), to_currency).ok:
            missing.append(ccy)
    return missing
