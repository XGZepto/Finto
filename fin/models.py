"""Pydantic DTOs for the personal finance ledger.

Design notes
------------
* Money is stored as integer minor units (cents) plus an ISO-4217 code.
  Never floats. `Money.from_decimal` handles currencies with 0 or 3 decimals
  (JPY, KRW, BHD) via a small exponent table.
* Every transaction carries BOTH a native pair (what the merchant charged) and
  a booked pair (what your account actually moved). For a plain HKD purchase on
  an HKD card these are identical and native_* is left None.
* Sign convention is uniform across account types: negative = money out.
"""

from __future__ import annotations

from datetime import date, datetime, timezone
from decimal import Decimal
from enum import Enum
from typing import Any, Literal
import hashlib
import re
import uuid

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


# ---------------------------------------------------------------------------
# Money
# ---------------------------------------------------------------------------

# Currencies whose minor unit is not 1/100.
_EXPONENTS: dict[str, int] = {
    "JPY": 0, "KRW": 0, "VND": 0, "CLP": 0, "ISK": 0,
    "BHD": 3, "KWD": 3, "JOD": 3, "OMR": 3, "TND": 3,
}


def minor_exponent(currency: str) -> int:
    return _EXPONENTS.get(currency.upper(), 2)


class Money(BaseModel):
    """A signed amount in integer minor units."""

    model_config = ConfigDict(frozen=True)

    amount: int  # minor units, signed. Negative = outflow.
    currency: str = Field(min_length=3, max_length=3)

    @field_validator("currency")
    @classmethod
    def _upper(cls, v: str) -> str:
        return v.upper()

    @classmethod
    def from_decimal(cls, value: Decimal | str | float, currency: str) -> "Money":
        exp = minor_exponent(currency)
        d = Decimal(str(value)).scaleb(exp).quantize(Decimal(1))
        return cls(amount=int(d), currency=currency.upper())

    def to_decimal(self) -> Decimal:
        return Decimal(self.amount).scaleb(-minor_exponent(self.currency))

    def __str__(self) -> str:
        return f"{self.to_decimal():,} {self.currency}"


# ---------------------------------------------------------------------------
# Enums
# ---------------------------------------------------------------------------

class AccountType(str, Enum):
    CREDIT_CARD = "credit_card"
    CHARGE_CARD = "charge_card"
    CHECKING = "checking"
    SAVINGS = "savings"
    MULTI_CURRENCY = "multi_currency"
    INVESTMENT = "investment"
    LOAN = "loan"


class TxnKind(str, Enum):
    PURCHASE = "purchase"
    REFUND = "refund"
    FEE = "fee"
    INTEREST = "interest"
    REWARD = "reward"
    CC_PAYMENT = "cc_payment"        # paying a card off from a bank account
    TRANSFER = "transfer"            # between accounts you own
    ATM = "atm"
    FX_CONVERSION = "fx_conversion"  # Wise/Mox in-account currency swap
    INCOME = "income"
    ADJUSTMENT = "adjustment"
    INSTALLMENT = "installment"      # one monthly charge of a plan
    # The full-amount purchase in statements that book the whole charge and then
    # reverse it. Cash-neutral once paired with its reversal.
    INSTALLMENT_ORIGINATION = "installment_origination"
    UNKNOWN = "unknown"


class TxnStatus(str, Enum):
    PENDING = "pending"
    POSTED = "posted"
    VOID = "void"


class TransferKind(str, Enum):
    INTERNAL_TRANSFER = "internal_transfer"
    CC_PAYMENT = "cc_payment"
    FX_CONVERSION = "fx_conversion"
    ATM_WITHDRAWAL = "atm_withdrawal"
    # Full charge + its reversal, when a plan is booked gross then backed out.
    INSTALLMENT_ORIGINATION = "installment_origination"


class PlanStatus(str, Enum):
    ACTIVE = "active"
    COMPLETED = "completed"
    CANCELLED = "cancelled"


class FileFormat(str, Enum):
    CSV = "csv"
    OFX = "ofx"
    QFX = "qfx"
    XLSX = "xlsx"
    PDF = "pdf"
    JSON = "json"
    MANUAL = "manual"


# ---------------------------------------------------------------------------
# Reference entities
# ---------------------------------------------------------------------------

class Institution(BaseModel):
    id: str
    display_name: str
    country: str
    timezone: str = "Asia/Hong_Kong"


class Account(BaseModel):
    id: str
    institution_id: str
    display_name: str
    account_type: AccountType
    primary_currency: str
    # Every currency this account can settle in. A single-currency card lists
    # one; a multi-currency card or Wise-style account lists several, and each
    # is a separate position that is never added to the others. Defaults to
    # [primary_currency] when unspecified.
    settlement_currencies: list[str] = Field(default_factory=list)
    balance_group: str | None = None   # groups Wise/Mox per-currency balances
    masked_number: str | None = None
    is_own_account: bool = True
    opened_on: date | None = None
    closed_on: date | None = None
    notes: str | None = None

    @model_validator(mode="after")
    def _default_currencies(self) -> "Account":
        ccys = [c.upper() for c in self.settlement_currencies]
        primary = self.primary_currency.upper()
        if primary not in ccys:
            ccys.insert(0, primary)
        object.__setattr__(self, "settlement_currencies", ccys)
        object.__setattr__(self, "primary_currency", primary)
        return self


class Card(BaseModel):
    id: str
    account_id: str
    cardholder_name: str
    last4: str | None = None
    is_supplementary: bool = False
    issued_on: date | None = None
    closed_on: date | None = None
    # Set when this card was issued to replace another (lost, expired,
    # compromised). Same physical cardholder, new number — chaining them keeps
    # per-card history intact across the renumbering.
    replaces_card_id: str | None = None


class StatementFile(BaseModel):
    id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    source_path: str
    file_sha256: str
    institution_id: str
    account_id: str | None = None
    file_format: FileFormat
    parser_id: str
    parser_version: str
    period_start: date | None = None
    period_end: date | None = None
    statement_date: date | None = None
    imported_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    row_count: int = 0


class RawRecord(BaseModel):
    id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    statement_file_id: str
    line_no: int
    payload: dict[str, Any]
    row_sha256: str = ""

    @model_validator(mode="after")
    def _hash(self) -> "RawRecord":
        if not self.row_sha256:
            blob = repr(sorted(self.payload.items())).encode()
            object.__setattr__(self, "row_sha256", hashlib.sha256(blob).hexdigest())
        return self


# ---------------------------------------------------------------------------
# Parser output — the narrow contract every parser must satisfy
# ---------------------------------------------------------------------------

class ParsedTxn(BaseModel):
    """What a parser emits. Deliberately smaller than `Txn`.

    Parsers do extraction only: read the source row, get the money and dates
    right, and stop. Normalisation, dedup keys, categorisation and transfer
    linking all happen downstream, so adding a new institution means writing
    only the part that is genuinely institution-specific.
    """

    txn_date: date
    posted_date: date | None = None
    booked: Money
    native: Money | None = None
    fx_rate: Decimal | None = None
    fx_fee: Money | None = None
    description_raw: str
    merchant: str | None = None
    counterparty: str | None = None
    external_ref: str | None = None
    cardholder_hint: str | None = None   # supplementary card attribution
    card_last4: str | None = None
    status: TxnStatus = TxnStatus.POSTED
    kind_hint: TxnKind | None = None
    line_no: int = 0
    # (sequence, term) for a plan instalment, e.g. (3, 12) for "INSTALMENT 03/12".
    # Captured here because normalize_description strips dd/mm patterns as noise
    # and would erase the marker before anything downstream could read it.
    installment_hint: tuple[int, int] | None = None
    # Structured facts extracted from the source row: flight routing, passenger
    # name, merchant address. Namespaced keys — see enrich.py.
    details: dict[str, str] = Field(default_factory=dict)
    extra: dict[str, Any] = Field(default_factory=dict)

    @model_validator(mode="after")
    def _check_currency_consistency(self) -> "ParsedTxn":
        if self.native is not None and self.native.currency == self.booked.currency:
            # Same currency both sides — native is redundant noise.
            object.__setattr__(self, "native", None)
        if self.native is not None and (self.native.amount < 0) != (self.booked.amount < 0):
            raise ValueError("native and booked amounts must share a sign")
        return self


# ---------------------------------------------------------------------------
# Canonical transaction
# ---------------------------------------------------------------------------

_NOISE = re.compile(
    r"\b(\d{2}/\d{2}(/\d{2,4})?|REF\s?\w{6,}|AUTH\s?\d+|TRACE\s?\d+|"
    r"XX+\d{2,}|\*{2,}\d+|#\d{4,})\b",
    re.IGNORECASE,
)
_SPACE = re.compile(r"\s+")


def normalize_description(raw: str) -> str:
    """Strip per-statement noise so the same merchant collapses to one string.

    Removes embedded dates, auth/trace/reference numbers and masked card
    fragments — the fields that differ between a pending and a posted copy of
    the same charge, and between two statements that both cover it.
    """
    s = raw.upper()
    s = _NOISE.sub(" ", s)
    s = re.sub(r"[^A-Z0-9 &./-]", " ", s)
    return _SPACE.sub(" ", s).strip()


class Txn(BaseModel):
    model_config = ConfigDict(validate_assignment=True)

    id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    account_id: str
    card_id: str | None = None

    txn_date: date
    posted_date: date | None = None
    status: TxnStatus = TxnStatus.POSTED

    booked: Money
    native: Money | None = None
    fx_rate: Decimal | None = None
    fx_fee: Money | None = None

    description_raw: str
    description_norm: str = ""
    merchant: str | None = None
    counterparty: str | None = None
    external_ref: str | None = None

    kind: TxnKind = TxnKind.UNKNOWN
    category: str | None = None
    subcategory: str | None = None

    transfer_group_id: str | None = None
    duplicate_of_id: str | None = None
    installment_plan_id: str | None = None
    installment_seq: int | None = None
    refund_of_id: str | None = None
    dedup_key: str = ""
    statement_file_id: str
    raw_record_id: str | None = None
    details: dict[str, str] = Field(default_factory=dict)

    review_state: Literal["unreviewed", "confirmed", "flagged"] = "unreviewed"
    notes: str | None = None
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    updated_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))

    @model_validator(mode="after")
    def _derive(self) -> "Txn":
        if not self.description_norm:
            self.description_norm = normalize_description(self.description_raw)
        if not self.dedup_key:
            self.dedup_key = self.compute_dedup_key()
        return self

    def compute_dedup_key(self) -> str:
        """Deterministic natural key for exact-duplicate detection.

        Uses the issuer's own reference when one exists — that is authoritative
        and survives re-statements. Otherwise falls back to the tuple that
        identifies a charge in practice: account, date, signed amount, currency
        and normalised description. Deliberately excludes posted_date and
        status, so a pending row and its posted twin collide by design.
        """
        if self.external_ref:
            basis = f"{self.account_id}|ref|{self.external_ref}"
        else:
            basis = "|".join([
                self.account_id,
                self.txn_date.isoformat(),
                str(self.booked.amount),
                self.booked.currency,
                self.description_norm,
            ])
        return hashlib.sha256(basis.encode()).hexdigest()[:32]

    @property
    def is_outflow(self) -> bool:
        return self.booked.amount < 0

    @property
    def abs_amount(self) -> int:
        return abs(self.booked.amount)


# ---------------------------------------------------------------------------
# Transfers
# ---------------------------------------------------------------------------

class TransferLeg(BaseModel):
    txn_id: str
    role: Literal["out", "in", "fee"]


class TransferGroup(BaseModel):
    id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    kind: TransferKind
    match_method: Literal["auto", "manual", "rule"] = "auto"
    confidence: float = 1.0
    legs: list[TransferLeg] = Field(default_factory=list)
    fee: Money | None = None
    is_confirmed: bool = False
    notes: str | None = None
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class TransferCandidate(BaseModel):
    id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    out_txn_id: str
    in_txn_id: str
    score: float
    date_delta: int
    amount_delta: int
    reasons: list[str] = Field(default_factory=list)
    resolution: Literal["open", "accepted", "rejected"] = "open"
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class DuplicateCandidate(BaseModel):
    id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    keep_txn_id: str
    dupe_txn_id: str
    score: float
    reasons: list[str] = Field(default_factory=list)
    resolution: Literal["open", "accepted", "rejected"] = "open"
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class InstallmentPlan(BaseModel):
    """A card instalment plan: one purchase, N monthly charges.

    `principal` is the full committed amount (negative, like any outflow). The
    individual charges live as ordinary cash-basis transactions pointing back
    here via installment_plan_id, so the balance-assertion check still works.
    """

    id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    account_id: str
    card_id: str | None = None
    merchant: str | None = None
    description: str
    principal: Money
    term_months: int = Field(ge=2)
    start_date: date
    fee_total: Money | None = None
    apr: Decimal | None = None
    external_ref: str | None = None
    status: PlanStatus = PlanStatus.ACTIVE
    match_method: Literal["auto", "manual", "rule"] = "auto"
    confidence: float = 1.0
    is_confirmed: bool = False
    notes: str | None = None
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class InstallmentCandidate(BaseModel):
    id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    account_id: str
    description: str
    txn_ids: list[str]
    term_months: int
    score: float
    reasons: list[str] = Field(default_factory=list)
    resolution: Literal["open", "accepted", "rejected"] = "open"
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class FxRate(BaseModel):
    rate_date: date
    base: str
    quote: str
    rate: Decimal
    source: str = "manual"
