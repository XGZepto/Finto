"""Request/response models.

`LedgerFilter` is the important one: the blotter, the summary and the natural
language query all speak it. One filter type is what makes drill-down work
(clicking a summary row pushes that dimension onto the blotter) and what makes
the NL query feature small — the model translates a question into this, and the
database does the rest.
"""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field


class Money(BaseModel):
    """Integer minor units plus a currency. Never a decimal."""

    amount: int
    currency: str


class LedgerFilter(BaseModel):
    date_from: str | None = Field(default=None, alias="from")
    date_to: str | None = Field(default=None, alias="to")
    accounts: list[str] | None = None
    cards: list[str] | None = None
    institutions: list[str] | None = None
    categories: list[str] | None = None
    kinds: list[str] | None = None
    currency: str | None = None
    minAmount: int | None = None
    maxAmount: int | None = None
    q: str | None = None
    # Transfers are money moved between your own accounts. Counting them as
    # spending is the specific error transfer linking exists to prevent, so the
    # caller has to opt in.
    includeTransfers: bool = False
    includeDuplicates: bool = False
    uncategorisedOnly: bool = False
    installmentsOnly: bool = False

    model_config = {"populate_by_name": True}

    def to_query(self) -> dict[str, Any]:
        d = self.model_dump(exclude_none=True, by_alias=True)
        return d


class SummaryRequest(BaseModel):
    group_by: str = "month"
    filter: LedgerFilter = Field(default_factory=LedgerFilter)
    # Optional presentation-only normalisation. The native figures are always
    # returned regardless; this adds a converted companion field.
    convert_to: str | None = None


class TransactionPatch(BaseModel):
    category: str | None = None
    subcategory: str | None = None
    notes: str | None = None
    review_state: Literal["unreviewed", "confirmed", "flagged"] | None = None
    merchant: str | None = None


class ResolveRequest(BaseModel):
    action: Literal["accept", "reject"]


class ImportRequest(BaseModel):
    institution_id: str | None = None
    account_id: str | None = None
    currency: str | None = None


class QueryRequest(BaseModel):
    question: str
    convert_to: str | None = None
