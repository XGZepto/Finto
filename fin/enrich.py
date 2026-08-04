"""Structured detail extraction from statement rows.

Statements carry far more than date/amount/description, and almost all of it is
thrown away by a naive importer. AMEX in particular writes rich trip data into
its Extended Details field: passenger name, carrier, routing, ticket number,
travel dates. Hotels give check-in/check-out; car rentals give pickup location.

`raw_record` already stores every source row verbatim, so nothing is *lost* —
but a JSON blob is not queryable. You cannot ask "every flight I booked in 2025"
or "what did I spend on trips for this passenger" of an opaque string. This
module turns those blobs into namespaced key/value facts in `txn_detail`.

Design rules:

* **Never invent.** A field is emitted only where the source labelled it. The
  tempting shortcuts do not survive contact with real statements: matching
  SURNAME/FORENAME anywhere on a travel row turns "and/or Private Label" into a
  passenger 35 times over, and reading AAA/BBB as a route turns "opt out" into a
  flight from OPT to OUT. Guesses like those are worse than a missing field,
  because a missing field is visibly missing.
* **Never discard.** Detail lines we don't recognise are kept under `raw.line_N`.
  A parser that improves later can re-derive from raw_record, but only if we
  noticed the field was there.
* **Namespaced keys.** `travel.*`, `merchant.*`, `issuer.*` — so a UI can group
  them and a query can target one domain.
"""

from __future__ import annotations

import re
from collections.abc import Iterable

# Label -> namespaced key. Matching is case-insensitive and tolerant of the
# separator (colon, multiple spaces, or nothing).
_LABELS: dict[str, str] = {
    "passenger name": "travel.passenger_name",
    "passenger": "travel.passenger_name",
    "ticket number": "travel.ticket_number",
    "ticket no": "travel.ticket_number",
    "departure date": "travel.departure_date",
    "date of departure": "travel.departure_date",
    "document type": "travel.document_type",
    "depart": "travel.departure_date",
    "arrival date": "travel.arrival_date",
    "return date": "travel.return_date",
    "origin": "travel.origin",
    "from": "travel.origin",
    "destination": "travel.destination",
    "to": "travel.destination",
    "carrier": "travel.carrier",
    "airline": "travel.carrier",
    "flight": "travel.flight_number",
    "flight number": "travel.flight_number",
    "class of service": "travel.class",
    "class": "travel.class",
    "fare basis": "travel.fare_basis",
    "booking reference": "travel.booking_reference",
    "record locator": "travel.booking_reference",
    "check in": "lodging.check_in",
    "check-in": "lodging.check_in",
    "check out": "lodging.check_out",
    "check-out": "lodging.check_out",
    "nights": "lodging.nights",
    "room rate": "lodging.room_rate",
    "pick up": "rental.pickup",
    "pick-up": "rental.pickup",
    "drop off": "rental.dropoff",
    "drop-off": "rental.dropoff",
    "rental days": "rental.days",
    "merchant": "merchant.name",
    "address": "merchant.address",
    "city": "merchant.city",
    "city/state": "merchant.city",
    "state": "merchant.state",
    "zip code": "merchant.postcode",
    "zip": "merchant.postcode",
    "postcode": "merchant.postcode",
    "country": "merchant.country",
    "phone": "merchant.phone",
    "reference": "issuer.reference",
    "reference id": "issuer.reference",
    "category": "issuer.category",
    "card member": "issuer.card_member",
    "description": "issuer.description",
    "appears on your statement as": "issuer.statement_text",
}

def _label_pattern(labels, separator: str) -> re.Pattern:
    names = "|".join(re.escape(k) for k in sorted(labels, key=len, reverse=True))
    return re.compile(rf"^\s*({names})\b(?:{separator})(.+?)\s*$", re.I)


# How much punctuation a label needs before its value is believed.
#
# A distinctive label — "passenger name", "ticket number" — cannot occur in
# ordinary merchant text, so a single space is enough, which matters because
# CSV quoting collapses AMEX's Extended Details onto one line. Short labels
# ("to", "class", "city") occur in prose constantly, so those need a colon, a
# dash, or the column gap a statement leaves between a heading and its value.
#
# The word boundary applies to both, and is what stops "depart" matching inside
# "DEPARTMENT STORE" and filing the rest of the line as a travel date.
_SEP = r"\s*[:\-]\s*|\s{2,}"
_LABEL_RES = None    # built after _SPLIT_LABELS, which names the distinctive set

# Labels distinctive enough to split on when several are crammed onto one line.
# CSV quoting collapses the newlines in AMEX's Extended Details, so "PASSENGER
# NAME X TICKET NUMBER Y CARRIER Z" arrives as a single string and a greedy
# label match would swallow the lot into the first field.
#
# Short, common words ("to", "from", "city", "class") are deliberately excluded:
# splitting on those would shred ordinary merchant text.
_SPLIT_LABELS = [
    "passenger name", "ticket number", "date of departure", "document type",
    "ticket no", "departure date",
    "arrival date", "return date", "record locator", "booking reference",
    "fare basis", "class of service", "flight number", "carrier", "airline",
    "check-in", "check in", "check-out", "check out", "room rate",
    "rental days", "pick-up", "pick up", "drop-off", "drop off",
    "city/state", "zip code", "appears on your statement as", "card member",
    "reference id",
]
_SPLIT_RE = re.compile(
    r"(?=\b(?:" + "|".join(re.escape(k) for k in
                           sorted(_SPLIT_LABELS, key=len, reverse=True)) + r")\b)",
    re.I)

_LABEL_RES = (
    _label_pattern(_SPLIT_LABELS, _SEP + r"|\s+"),
    _label_pattern(_LABELS, _SEP),
)

# Ticket and reference numbers are written with spaces or dashes for
# readability; the identity is the digits.
_COMPACT_KEYS = {"travel.ticket_number", "travel.booking_reference"}

# Fields with a known value shape. Without these a label match runs to the end
# of the line and swallows whatever follows: "CARRIER: CX HKG/LHR" would record
# the carrier as "CX HKG/LHR" and lose the routing entirely. The captured group
# is the value; anything after it is handed back for further scanning.
_VALUE_SHAPES: dict[str, re.Pattern] = {
    "travel.carrier": re.compile(r"^([A-Z0-9]{2})\b"),
    "travel.origin": re.compile(r"^([A-Z]{3})\b"),
    "travel.destination": re.compile(r"^([A-Z]{3})\b"),
    "travel.class": re.compile(r"^([A-Z])\b"),
    "travel.ticket_number": re.compile(r"^([\d][\d\s\-]{8,20}\d)"),
    "travel.flight_number": re.compile(r"^([A-Z]{0,3}\s?\d{1,4}[A-Z]?)\b"),
    "lodging.nights": re.compile(r"^(\d{1,3})\b"),
    "rental.days": re.compile(r"^(\d{1,3})\b"),
}

# Lines that are pure card-number masking carry no information.
_MASK_ONLY = re.compile(r"^[X*\s\-]{6,}\d{0,4}$", re.I)


def extract_details(
    *,
    extended: str = "",
    columns: dict[str, str] | None = None,
    description: str = "",
) -> dict[str, str]:
    """Build namespaced detail facts from a statement row.

    `extended` is a free-text blob (AMEX Extended Details). `columns` is the
    row's own labelled fields, which are more reliable and therefore win on
    conflict.
    """
    details: dict[str, str] = {}

    # Free text first, so explicit columns can override anything it inferred.
    if extended:
        details.update(_from_blob(extended))

    for raw_key, value in (columns or {}).items():
        if not value or not str(value).strip():
            continue
        key = _LABELS.get(_norm_label(raw_key))
        if key:
            details[key] = str(value).strip()

    return {k: v for k, v in details.items() if v}


def _norm_label(s: str) -> str:
    return re.sub(r"[\s_]+", " ", s.strip().lower()).rstrip(":")


def _from_blob(blob: str) -> dict[str, str]:
    details: dict[str, str] = {}
    unrecognised: list[str] = []

    pending = [ln for ln in _split_lines(blob) if ln]
    while pending:
        line = pending.pop(0)
        if not line or _MASK_ONLY.match(line):
            continue

        m = next(filter(None, (rx.match(line) for rx in _LABEL_RES)), None)
        if m:
            key = _LABELS[_norm_label(m.group(1))]
            value = m.group(2).strip()
            shape = _VALUE_SHAPES.get(key)
            if shape:
                sm = shape.match(value.upper())
                if sm:
                    details.setdefault(key, _clean_value(key, sm.group(1)))
                    # Whatever followed the value is a separate fact.
                    rest = value[sm.end():].strip()
                    if rest:
                        pending.insert(0, rest)
                    continue
                # Shape didn't match — treat the whole line as unclassified
                # rather than recording a value we know is malformed.
                unrecognised.append(line)
                continue
            details.setdefault(key, _clean_value(key, value))
            continue

        unrecognised.append(line)

    # Keep what we could not classify rather than dropping it.
    for i, line in enumerate(unrecognised[:8]):
        details[f"raw.line_{i}"] = line

    return details



def _clean_value(key: str, value: str) -> str:
    v = value.strip().strip(",;")
    if key in _COMPACT_KEYS:
        v = re.sub(r"[\s\-]", "", v)
    return v


def _split_lines(blob: str) -> Iterable[str]:
    """Split a detail blob into logical lines.

    Three separators, because AMEX's field arrives differently depending on how
    it was exported: real newlines, runs of spaces, or — once CSV quoting has
    collapsed everything onto one line — nothing at all but the next label.
    """
    for raw in re.split(r"[\r\n]+|\s{3,}", blob):
        for chunk in _SPLIT_RE.split(raw):
            if chunk.strip():
                yield chunk.strip()


def is_travel(details: dict[str, str]) -> bool:
    """True when the row describes a trip — used to tag transactions."""
    return any(k.startswith(("travel.", "lodging.", "rental.")) for k in details)


# ---------------------------------------------------------------------------
# Payment gateways
# ---------------------------------------------------------------------------
# A charge routed through Alipay, WeChat Pay or UnionPay reaches the card as the
# gateway's own name. Sometimes the merchant survives — "Alipay*DIDI Taxi" —
# and sometimes it does not: "Alipay* Shanghai" is the single most common line
# in this ledger and says only that money went through Alipay in Shanghai.
#
# Neither case is "uncategorised". The first is an ordinary purchase that
# happens to name its rail; the second is a *known* state of affairs — the
# acquirer did not pass the merchant on — and recording it as such is the
# difference between "we could not read this" and "the statement does not say".
# Leaving them blank invites someone to keep trying to categorise rows that
# contain no answer.

#: Gateway patterns, longest legal name first so "AlipayHK" is not read as
#: "Alipay", and the canonical name to record.
_GATEWAYS: list[tuple[re.Pattern, str]] = [
    (re.compile(r"^(?:SALES:\s*)?ALIPAYHK\b", re.I), "AlipayHK"),
    (re.compile(r"^(?:SALES:\s*)?ALIPAY(?:\s+NETWORK\s+TECH)?\b", re.I), "Alipay"),
    (re.compile(r"^(?:SALES:\s*)?TENPAY(?:\s+TECHNOLOGY(?:\s+COMPANY)?)?\b", re.I),
     "Tenpay"),
    (re.compile(r"^(?:SALES:\s*)?TENCENT\b", re.I), "Tenpay"),
    (re.compile(r"^(?:SALES:\s*)?WECHAT\s*PAY"
                r"(?:\s+(?:HONG\s*KONG|HK))?(?:\s+(?:LIMITED|LIMI))?\b", re.I),
     "WeChat Pay"),
    (re.compile(r"^(?:SALES:\s*)?UNIONPAY(?:\s+MERCHANT)?\b", re.I), "UnionPay"),
    (re.compile(r"^(?:SALES:\s*)?TAOBAO(?:\s+MERCHANT)?\b", re.I), "Taobao"),
    (re.compile(r"^(?:SALES:\s*)?E-?WALLET\b", re.I), "E-wallet"),
    (re.compile(r"^(?:SALES:\s*)?APLPAY\b", re.I), "Apple Pay"),
    (re.compile(r"^(?:SALES:\s*)?GGLPAY\b", re.I), "Google Pay"),
    (re.compile(r"^(?:SALES:\s*)?KPAY\b", re.I), "KPay"),
]

#: Tokens that are place, legal form, or the gateway naming itself again — none
#: of which identify a merchant. NUCC is China's clearing house, not a shop.
_NOT_A_MERCHANT = re.compile(
    r"^(?:\*+|CHN|CN|HK|HKG|HONGKONG|HONG|KONG|SHANGHAI|SHENZHEN|BEIJING|CHINA|"
    r"MACAU|MO|TW|SG|LIMITED|LIMI|LTD|CO|INC|NUCC|ALIPAY|WECHAT|PAY|MERCHANT)$",
    re.I)


def payment_gateway(description_raw: str) -> tuple[str, str] | None:
    """The gateway a charge was routed through, and the merchant it disclosed.

    Returns (gateway, merchant) with merchant "" when the acquirer passed no
    merchant through. Reads the raw description on purpose: normalisation drops
    the "*" that separates a gateway from the merchant behind it, which is the
    one character that distinguishes the two cases.
    """
    for rx, name in _GATEWAYS:
        m = rx.match(description_raw.strip())
        if m is None:
            continue
        tokens = description_raw.strip()[m.end():].replace("*", " ").split()
        # Trim place and legal-form tokens from the end only. Dropping them
        # wherever they appear would turn "Ichiran Hong K" into "Ichiran K".
        while tokens and _NOT_A_MERCHANT.match(tokens[-1]):
            tokens.pop()
        while tokens and _NOT_A_MERCHANT.match(tokens[0]):
            tokens.pop(0)
        return name, " ".join(tokens)
    return None
