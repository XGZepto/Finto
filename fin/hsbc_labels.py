"""Readable titles for HSBC One savings rows.

The statement prints Faster Payment refs, card PANs and GOLD/EXCHANGE on the
amount line, with the counterparty on a dated line above. After wrap joins
those pieces the blotter still shows `HC1268… 20AUG 8383…` — not something a
person can scan. This derives the merchant the UI actually displays.
"""

from __future__ import annotations

import re

_HC = re.compile(r"\bHC\d{8,}\b", re.IGNORECASE)
_NREF = re.compile(r"\bN\d{8,}(?:\(\d{2}[A-Z]{3}\d{2}\))?\b", re.IGNORECASE)
_DATE_TOKEN = re.compile(
    r"\b\d{2}(?:JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)(?:\d{2})?\b",
    re.IGNORECASE,
)
_PAN = re.compile(r"\b(\d{4})[-\s]?(\d{4})[-\s]?(\d{4})[-\s]?(\d{4,})\b")
_SELF = re.compile(r"\bZHOU\s+Y\S*", re.IGNORECASE)
_LEGAL = re.compile(r"\b(?:HK\s+)?(?:LIMITED|LTD\.?|PLC|INC\.?|CO\.?)\b", re.IGNORECASE)

# PANs printed on HSBC One as the other side of an FPS or card payment.
_LAST4_NAMES = {
    "0071": "Pulse Dual Currency",
    "3315": "EveryMile",
    "2217": "Mox",
}


def _plain(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip()


def _strip_noise(raw: str) -> str:
    text = _PAN.sub(" ", raw)
    text = _HC.sub(" ", text)
    text = _NREF.sub(" ", text)
    text = _DATE_TOKEN.sub(" ", text)
    text = re.sub(r"[-–]+", " ", text)
    return re.sub(r"\s+", " ", text).strip(" .")


def _named_account(raw: str) -> str | None:
    digits = re.sub(r"\D", "", raw)
    if "838383" in digits:
        return "Mox"
    for match in _PAN.finditer(raw):
        last4 = match.group(4)[-4:]
        if last4 in _LAST4_NAMES:
            return _LAST4_NAMES[last4]
    return None


def hsbc_savings_merchant(description: str, amount: int | None = None) -> str | None:
    """A blotter title for one HSBC One savings description, or None."""
    raw = (description or "").strip()
    if not raw or raw.lower().startswith("(no description)"):
        return None
    named = _named_account(raw)
    text = _strip_noise(raw)
    u = text.upper()

    if "SALARY" in u:
        rest = _SELF.sub(" ", re.sub(r"SALARY", " ", text, flags=re.IGNORECASE))
        rest = _LEGAL.sub(" ", rest)
        rest = re.sub(r"\s+", " ", rest).strip(" .*")
        return f"Salary — {_plain(rest)}" if rest else "Salary"

    if "AMERICAN EXPRESS" in u:
        return "AMEX"

    if "GOLD" in u and "EXCHANGE" in u:
        return named or "HSBC Gold / Exchange"

    if named:
        return named

    if "CITIBANK" in u:
        return "Citibank Europe"
    if "PAYME" in u:
        return "PayMe"
    if "WISE" in u:
        return "Wise"
    if "PRO FUND" in u or "CHMPFSP" in u:
        return "HSBC MPF"
    if "CONSULATE" in u:
        return "Consulate General"
    if "CREDIT INTEREST" in u:
        return "Credit interest"
    if "CASH DEPOSIT" in u:
        return "Cash deposit"
    if "MOBILE WITHDRAWAL" in u or u.startswith("ATM"):
        return "ATM"

    if _SELF.search(raw):
        return "Zhou Yixiang"

    if "WITHDRAWAL" in u and "DEPOSIT" in u:
        return "CNY withdrawal" if (amount or 0) < 0 else "CNY deposit"
    if u == "WITHDRAWAL" or u.startswith("WITHDRAWAL "):
        return "CNY withdrawal"
    if u == "DEPOSIT" or u.startswith("DEPOSIT "):
        return "CNY deposit"

    cleaned = _LEGAL.sub(" ", text)
    cleaned = _SELF.sub(" ", cleaned)
    cleaned = re.sub(r"\s+", " ", cleaned).strip(" .")
    if cleaned:
        return _plain(cleaned)
    return named or "HSBC transfer"


def label_hsbc_savings_merchants(txns) -> int:
    """Fill merchant on HSBC One savings rows that still show the raw ref."""
    labelled = 0
    for t in txns:
        if not (t.account_id or "").startswith("hsbc_hk_savings"):
            continue
        if t.merchant:
            continue
        amount = t.booked.amount if getattr(t, "booked", None) is not None else None
        name = hsbc_savings_merchant(t.description_raw, amount)
        if name:
            t.merchant = name
            labelled += 1
    return labelled
