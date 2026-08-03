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

* **Never invent.** A field is emitted only when the source actually labelled
  it or the format is unambiguous (IATA routing, ticket number check digits).
* **Never discard.** Detail lines we don't recognise are kept under `raw.line_N`.
  A parser that improves later can re-derive from raw_record, but only if we
  noticed the field was there.
* **Namespaced keys.** `travel.*`, `merchant.*`, `issuer.*` — so a UI can group
  them and a query can target one domain.
"""

from __future__ import annotations

import re
from collections.abc import Iterable

# Common IATA codes are 3 uppercase letters; routing is written many ways.
_ROUTE = re.compile(r"\b([A-Z]{3})\s*(?:/|-|>|\bTO\b)\s*([A-Z]{3})\b")
# Airline tickets are 13-14 digits, often with the 3-digit carrier prefix split.
_TICKET = re.compile(r"\b(\d{3}[- ]?\d{10,11})\b")
# AMEX writes passenger names surname-first: SMITH/JOHN MR
_PAX = re.compile(r"\b([A-Z][A-Z'\-]{1,20})\s*/\s*([A-Z][A-Z'\- ]{1,30})\b")
_IATA_CARRIER = re.compile(r"\b(?:CARRIER|AIRLINE)\b[:\s]+([A-Z0-9]{2})\b")

# Label -> namespaced key. Matching is case-insensitive and tolerant of the
# separator (colon, multiple spaces, or nothing).
_LABELS: dict[str, str] = {
    "passenger name": "travel.passenger_name",
    "passenger": "travel.passenger_name",
    "ticket number": "travel.ticket_number",
    "ticket no": "travel.ticket_number",
    "departure date": "travel.departure_date",
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

_LABEL_RE = re.compile(
    r"^\s*(" + "|".join(re.escape(k) for k in sorted(_LABELS, key=len, reverse=True))
    + r")\s*[:\-]?\s{0,}(.+?)\s*$", re.I)

# Labels distinctive enough to split on when several are crammed onto one line.
# CSV quoting collapses the newlines in AMEX's Extended Details, so "PASSENGER
# NAME X TICKET NUMBER Y CARRIER Z" arrives as a single string and a greedy
# label match would swallow the lot into the first field.
#
# Short, common words ("to", "from", "city", "class") are deliberately excluded:
# splitting on those would shred ordinary merchant text.
_SPLIT_LABELS = [
    "passenger name", "ticket number", "ticket no", "departure date",
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

    # Descriptions sometimes carry routing when the detail field does not.
    if "travel.origin" not in details and description:
        route = _ROUTE.search(description.upper())
        if route:
            details["travel.origin"], details["travel.destination"] = route.groups()

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

        m = _LABEL_RE.match(line)
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

        upper = line.upper()
        matched = False

        route = _ROUTE.search(upper)
        if route and "travel.origin" not in details:
            details["travel.origin"], details["travel.destination"] = route.groups()
            matched = True

        ticket = _TICKET.search(upper)
        if ticket and "travel.ticket_number" not in details:
            details["travel.ticket_number"] = ticket.group(1).replace(" ", "").replace("-", "")
            matched = True

        carrier = _IATA_CARRIER.search(upper)
        if carrier and "travel.carrier" not in details:
            details["travel.carrier"] = carrier.group(1)
            matched = True

        # Surname/forename only counts as a passenger when the row looks like
        # travel — otherwise "AMZN/MKTP" would become a person.
        if "travel.passenger_name" not in details and _looks_like_travel(details, upper):
            pax = _PAX.search(upper)
            if pax:
                details["travel.passenger_name"] = f"{pax.group(1)}/{pax.group(2)}".strip()
                matched = True

        if not matched:
            unrecognised.append(line)

    # Keep what we could not classify rather than dropping it.
    for i, line in enumerate(unrecognised[:8]):
        details[f"raw.line_{i}"] = line

    return details


def _looks_like_travel(details: dict[str, str], line: str) -> bool:
    if any(k.startswith("travel.") for k in details):
        return True
    return bool(re.search(
        r"\b(FLIGHT|AIRLINE|AIRWAYS|TICKET|PASSENGER|BOARDING|ITINERARY|AIR)\b", line))


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
