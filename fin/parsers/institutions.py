"""Per-institution parsers.

STATUS: the column mappings below are informed guesses. Each parser is marked
with a CONFIDENCE note. Drop one real export per institution into ./inbox and
run `python -m fin.cli sniff <file>` — it prints the detected header and the
mapping that would be applied, so you can correct these in one pass.

The sniff/parse split means a wrong guess costs you one small edit here and
nothing anywhere else in the pipeline.
"""

from __future__ import annotations

import re
from datetime import date
from decimal import Decimal

from ..enrich import extract_details
from ..installments import parse_installment_marker
from ..models import FileFormat, Money, ParsedTxn, TxnKind
from .base import (
    ParseContext,
    ParseResult,
    StatementParser,
    has_columns,
    parse_amount,
    parse_date,
    pick,
    read_csv_rows,
    register,
)

# ---------------------------------------------------------------------------
# AMEX (US and HK share an export engine; currency and date order differ)
# ---------------------------------------------------------------------------

@register
class AmexCsvParser(StatementParser):
    """AMEX 'Download > CSV' export.

    CONFIDENCE: medium-high. AMEX US exports Date, Description, Card Member,
    Account #, Amount, and optionally Extended Details / Category. AMEX HK
    layout is similar but dd/mm and HKD.

    Two AMEX specifics worth noting:
    * Sign is inverted vs. our convention — AMEX writes a purchase as POSITIVE
      (amount you owe). We flip it so outflow is negative.
    * 'Card Member' is how supplementary cards are attributed. We surface it as
      cardholder_hint and the ingest layer resolves it to a card_id.
    """

    parser_id = "amex_csv"
    version = "0.1.0"
    institution_id = "amex"
    file_format = FileFormat.CSV

    def sniff(self, ctx: ParseContext, sample: bytes) -> float:
        text = sample.decode("utf-8", errors="replace").lower()
        score = 0.0
        if "card member" in text:
            score += 0.5
        if "extended details" in text or "appears on your statement as" in text:
            score += 0.3
        if "amex" in ctx.path.name.lower() or "amex" in (ctx.institution_id or ""):
            score += 0.3
        if "date" in text and "amount" in text:
            score += 0.1
        return min(score, 1.0)

    def parse(self, ctx: ParseContext) -> ParseResult:
        header, rows = read_csv_rows(ctx.path)
        # AMEX's web export uses US-style mm/dd dates in every market, HK
        # included. The account's institution only picks the currency.
        us = (ctx.institution_id or "").endswith("_us")
        ccy = ctx.default_currency or ("USD" if us else "HKD")

        txns, warnings = [], []
        for i, row in enumerate(rows):
            raw_date = pick(row, "Date", "Transaction Date")
            raw_amt = pick(row, "Amount")
            desc = pick(row, "Description", "Appears On Your Statement As")
            if not raw_date or not raw_amt:
                continue
            try:
                d = parse_date(raw_date, dayfirst=False)
                # Flip AMEX's sign convention.
                m = parse_amount(raw_amt, ccy)
                booked = Money(amount=-m.amount, currency=m.currency)
            except ValueError as e:
                warnings.append(f"line {i}: {e}")
                continue

            # Foreign-currency purchases: AMEX puts the original amount in the
            # extended details free-text, e.g. "1,200.00 JPY".
            native, fx = None, None
            ext = pick(row, "Extended Details", "Additional Information")
            fm = re.search(r"([\d,]+\.?\d*)\s*([A-Z]{3})\b", ext)
            if fm and fm.group(2) != ccy:
                try:
                    nat = parse_amount(fm.group(1), fm.group(2))
                    native = Money(
                        amount=-abs(nat.amount) if booked.amount < 0 else abs(nat.amount),
                        currency=fm.group(2),
                    )
                    if native.amount:
                        fx = (booked.to_decimal() / native.to_decimal()).quantize(
                            Decimal("0.000001"))
                except ValueError:
                    pass

            # AMEX's Extended Details carry the richest metadata of any source
            # here — flight routing, passenger name, ticket number, merchant
            # address. Extract it into structured facts rather than losing it.
            details = extract_details(
                extended=ext,
                columns={k: v for k, v in row.items()
                         if k.lower() not in ("date", "amount")},
                description=desc,
            )

            txns.append(ParsedTxn(
                txn_date=d,
                booked=booked,
                native=native,
                fx_rate=fx,
                description_raw=desc or ext or "(no description)",
                cardholder_hint=pick(row, "Card Member") or None,
                card_last4=(pick(row, "Account #", "Card Number") or "")[-4:] or None,
                external_ref=pick(row, "Reference", "Reference ID") or None,
                kind_hint=_amex_kind(desc),
                installment_hint=parse_installment_marker(desc or ext),
                details=details,
                line_no=i,
                extra={"category_hint": pick(row, "Category")},
            ))

        return ParseResult(txns=txns, raw_rows=rows, warnings=warnings,
                           account_id=ctx.account_id, allow_empty=not rows)


# ---------------------------------------------------------------------------
# HSBC HK
# ---------------------------------------------------------------------------

#: HSBC writes its own reference for a transfer into the description on both
#: the statement and the CSV export, but with the payee on the other side of
#: it. Lifting it out is what lets the two copies of one payment recognise each
#: other despite the wording differing.
_HSBC_REF = re.compile(r"\b(HC\d{10,})\b")


def _hsbc_payment_ref(description: str) -> str | None:
    m = _HSBC_REF.search(description)
    return m.group(1) if m else None


@register
class HsbcHkCsvParser(StatementParser):
    """HSBC HK internet banking CSV export.

    CONFIDENCE: medium. HSBC HK typically exports Date, Transaction Details,
    Deposit, Withdrawal, Balance — i.e. separate debit/credit columns rather
    than one signed column. Some exports use a single Amount with a CR/DR
    suffix instead; both shapes are handled.
    """

    parser_id = "hsbc_hk_csv"
    version = "0.1.0"
    institution_id = "hsbc_hk"
    file_format = FileFormat.CSV

    def sniff(self, ctx: ParseContext, sample: bytes) -> float:
        text = sample.decode("utf-8", errors="replace").lower()
        score = 0.0
        if "hsbc" in text or "hsbc" in ctx.path.name.lower():
            score += 0.5
        if "transaction details" in text:
            score += 0.3
        if "withdrawal" in text and "deposit" in text:
            score += 0.3
        return min(score, 1.0)

    def parse(self, ctx: ParseContext) -> ParseResult:
        header, rows = read_csv_rows(ctx.path)
        ccy = ctx.default_currency or "HKD"
        split_cols = has_columns(header, "Deposit") or has_columns(header, "Credit")

        txns, warnings, balances = [], [], []
        for i, row in enumerate(rows):
            raw_date = pick(row, "Date", "Transaction Date", "Post Date")
            desc = pick(row, "Transaction Details", "Description", "Narrative")
            if not raw_date:
                continue
            try:
                d = parse_date(raw_date, dayfirst=True)
                if split_cols:
                    dep = pick(row, "Deposit", "Credit", "Credit Amount")
                    wdr = pick(row, "Withdrawal", "Debit", "Debit Amount")
                    if dep:
                        booked = Money(amount=abs(parse_amount(dep, ccy).amount), currency=ccy)
                    elif wdr:
                        booked = Money(amount=-abs(parse_amount(wdr, ccy).amount), currency=ccy)
                    else:
                        continue
                else:
                    row_ccy = pick(row, "Billing currency") or ccy
                    booked = parse_amount(
                        pick(row, "Billing amount", "Amount", "Transaction amount"),
                        row_ccy,
                    )
                    # Card exports carry a Credit / Debit marker; the written
                    # sign already agrees with it, so this only pins the case
                    # where the sign was dropped from the figures.
                    marker = pick(row, "Credit / Debit").upper()
                    if marker == "DEBIT":
                        booked = Money(amount=-abs(booked.amount), currency=booked.currency)
                    elif marker == "CREDIT":
                        booked = Money(amount=abs(booked.amount), currency=booked.currency)
            except ValueError as e:
                warnings.append(f"line {i}: {e}")
                continue

            txns.append(ParsedTxn(
                txn_date=d,
                posted_date=_maybe_date(pick(row, "Post Date", "Posting Date"), True),
                booked=booked,
                description_raw=desc or "(no description)",
                merchant=pick(row, "Merchant name", "Merchant") or None,
                counterparty=pick(row, "Payee", "Beneficiary") or None,
                external_ref=(pick(row, "Reference", "Transaction Reference")
                              or _hsbc_payment_ref(desc)),
                kind_hint=_hsbc_kind(desc),
                installment_hint=parse_installment_marker(desc),
                details=extract_details(columns=row, description=desc),
                line_no=i,
            ))

            # The running balance is the bank's own figure. Capturing it is what
            # lets integrity.check_account detect rows we failed to parse.
            balances.extend(_balance_at(row, d, booked.currency))

        return ParseResult(txns=txns, raw_rows=rows, warnings=warnings,
                           account_id=ctx.account_id, balances=balances,
                           allow_empty=not rows)


# ---------------------------------------------------------------------------
# Wise
# ---------------------------------------------------------------------------

@register
class WiseCsvParser(StatementParser):
    """Wise statement CSV.

    CONFIDENCE: high — Wise's export is stable and well-documented.
    Columns: TransferWise ID, Date, Amount, Currency, Description,
    Payment Reference, Running Balance, Exchange From, Exchange To,
    Exchange Rate, Payer Name, Payee Name, Merchant, Total fees.

    Wise is the richest source you have: it gives an explicit transaction ID
    (perfect dedup key), an explicit FX rate, and an explicit fee — so FX
    conversions link up cleanly rather than needing fuzzy matching.
    """

    parser_id = "wise_csv"
    version = "0.1.0"
    institution_id = "wise"
    file_format = FileFormat.CSV

    def sniff(self, ctx: ParseContext, sample: bytes) -> float:
        text = sample.decode("utf-8", errors="replace").lower()
        score = 0.0
        if "transferwise id" in text or "wise id" in text:
            score += 0.7
        if "exchange from" in text and "exchange to" in text:
            score += 0.3
        if "wise" in ctx.path.name.lower():
            score += 0.2
        return min(score, 1.0)

    def parse(self, ctx: ParseContext) -> ParseResult:
        header, rows = read_csv_rows(ctx.path)
        txns, warnings, balances = [], [], []
        for i, row in enumerate(rows):
            raw_date = pick(row, "Date")
            raw_amt = pick(row, "Amount")
            ccy = pick(row, "Currency") or ctx.default_currency or "HKD"
            if not raw_date or not raw_amt:
                continue
            try:
                d = parse_date(raw_date, dayfirst=True)
                booked = parse_amount(raw_amt, ccy)
            except ValueError as e:
                warnings.append(f"line {i}: {e}")
                continue

            fx_raw = pick(row, "Exchange Rate")
            fx = None
            if fx_raw:
                try:
                    fx = Decimal(fx_raw.replace(",", ""))
                except Exception:
                    pass

            fee = None
            fee_raw = pick(row, "Total fees", "Fees")
            if fee_raw:
                try:
                    f = parse_amount(fee_raw, ccy)
                    fee = Money(amount=abs(f.amount), currency=ccy)
                except ValueError:
                    pass

            ex_from = pick(row, "Exchange From")
            ex_to = pick(row, "Exchange To")
            kind = TxnKind.FX_CONVERSION if (ex_from and ex_to and ex_from != ex_to) else None

            txns.append(ParsedTxn(
                txn_date=d,
                booked=booked,
                fx_rate=fx,
                fx_fee=fee,
                description_raw=pick(row, "Description") or "(no description)",
                merchant=pick(row, "Merchant") or None,
                counterparty=pick(row, "Payee Name", "Payer Name") or None,
                # Wise's own ID — authoritative, makes dedup exact.
                external_ref=pick(row, "TransferWise ID", "Wise ID", "ID") or None,
                kind_hint=kind,
                details=extract_details(columns=row,
                                        description=pick(row, "Description")),
                line_no=i,
                extra={"exchange_from": ex_from, "exchange_to": ex_to,
                       "reference": pick(row, "Payment Reference")},
            ))

            balances.extend(_balance_at(row, d, ccy))

        return ParseResult(txns=txns, raw_rows=rows, warnings=warnings,
                           account_id=ctx.account_id, balances=balances,
                           allow_empty=not rows)


# ---------------------------------------------------------------------------
# Mox Bank
# ---------------------------------------------------------------------------

@register
class MoxCsvParser(StatementParser):
    """Mox Bank (HK) export.

    CONFIDENCE: low — Mox is app-first and its export format is not well
    documented publicly; you may only get PDF. Treated as a generic dd/mm HKD
    CSV until a real file confirms the columns.
    """

    parser_id = "mox_csv"
    version = "0.1.0"
    institution_id = "mox"
    file_format = FileFormat.CSV

    def sniff(self, ctx: ParseContext, sample: bytes) -> float:
        text = sample.decode("utf-8", errors="replace").lower()
        score = 0.0
        if "mox" in text or "mox" in ctx.path.name.lower():
            score += 0.6
        if "date" in text and "amount" in text:
            score += 0.2
        return min(score, 1.0)

    def parse(self, ctx: ParseContext) -> ParseResult:
        header, rows = read_csv_rows(ctx.path)
        ccy = ctx.default_currency or "HKD"
        txns, warnings, balances = [], [], []
        for i, row in enumerate(rows):
            raw_date = pick(row, "Date", "Transaction Date")
            raw_amt = pick(row, "Amount", "Transaction Amount")
            if not raw_date or not raw_amt:
                continue
            try:
                d = parse_date(raw_date, dayfirst=True)
                booked = parse_amount(raw_amt, ccy)
            except ValueError as e:
                warnings.append(f"line {i}: {e}")
                continue
            txns.append(ParsedTxn(
                txn_date=d,
                booked=booked,
                description_raw=(
                    pick(row, "Description", "Details", "Merchant") or "(no description)"),
                merchant=pick(row, "Merchant") or None,
                external_ref=pick(row, "Reference", "Transaction ID") or None,
                installment_hint=parse_installment_marker(
                    pick(row, "Description", "Details", "Merchant")),
                details=extract_details(
                    columns=row,
                    description=pick(row, "Description", "Details", "Merchant")),
                line_no=i,
            ))
            balances.extend(_balance_at(row, d, ccy))
        return ParseResult(txns=txns, raw_rows=rows, warnings=warnings,
                           account_id=ctx.account_id, balances=balances)


# ---------------------------------------------------------------------------
# Last-resort generic CSV
# ---------------------------------------------------------------------------

@register
class GenericCsvParser(StatementParser):
    """Fallback for any CSV with recognisable date/amount/description columns.

    Always returns low confidence so a specific parser wins when one matches.
    Useful for one-off files and for bootstrapping a new institution.
    """

    parser_id = "generic_csv"
    version = "0.1.0"
    institution_id = "generic"
    file_format = FileFormat.CSV

    def sniff(self, ctx: ParseContext, sample: bytes) -> float:
        if ctx.path.suffix.lower() not in (".csv", ".tsv", ".txt"):
            return 0.0
        text = sample.decode("utf-8", errors="replace").lower()
        return 0.5 if ("date" in text and ("amount" in text or "debit" in text)) else 0.0

    def parse(self, ctx: ParseContext) -> ParseResult:
        header, rows = read_csv_rows(ctx.path)
        ccy = ctx.default_currency or "HKD"
        txns, warnings, balances = [], [], []
        for i, row in enumerate(rows):
            raw_date = pick(row, "Date", "Transaction Date", "Posting Date", "Value Date")
            raw_amt = pick(row, "Amount", "Transaction Amount", "Value")
            if not raw_date:
                continue
            try:
                d = parse_date(raw_date, dayfirst=True)
                if raw_amt:
                    booked = parse_amount(raw_amt, ccy)
                else:
                    dep = pick(row, "Deposit", "Credit")
                    wdr = pick(row, "Withdrawal", "Debit")
                    if dep:
                        booked = Money(amount=abs(parse_amount(dep, ccy).amount), currency=ccy)
                    elif wdr:
                        booked = Money(amount=-abs(parse_amount(wdr, ccy).amount), currency=ccy)
                    else:
                        continue
            except ValueError as e:
                warnings.append(f"line {i}: {e}")
                continue
            txns.append(ParsedTxn(
                txn_date=d,
                booked=booked,
                description_raw=(
                    pick(row, "Description", "Details", "Narrative", "Memo") or "(no description)"),
                installment_hint=parse_installment_marker(
                    pick(row, "Description", "Details", "Narrative", "Memo")),
                details=extract_details(
                    columns=row,
                    description=pick(row, "Description", "Details", "Narrative", "Memo")),
                line_no=i,
            ))
            balances.extend(_balance_at(row, d, ccy))
        return ParseResult(txns=txns, raw_rows=rows, warnings=warnings,
                           account_id=ctx.account_id, balances=balances)


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------

_PAYMENT_WORDS = re.compile(
    r"\b(PAYMENT RECEIVED|AUTOPAY|THANK YOU|DIRECT DEBIT|PAYMENT - THANK|"
    r"AMEX EPAYMENT|ACH PMT)\b", re.IGNORECASE)
_TRANSFER_WORDS = re.compile(
    r"\b(TRANSFER|FPS|FASTER PAYMENT|ATM|CASH WITHDRAWAL|INTERNAL|"
    r"MOVE BETWEEN|OWN ACCOUNT)\b", re.IGNORECASE)
_INCOME_WORDS = re.compile(
    r"\b(SALARY|PAYROLL|WAGES|DIVIDEND|TAX REFUND|GOVERNMENT|"
    r"EMPLOYER|DIRECT CREDIT)\b", re.IGNORECASE)
_INTEREST_WORDS = re.compile(r"\b(INTEREST|INT\.?\s*PAID|CREDIT INTEREST)\b", re.IGNORECASE)
_REWARD_WORDS = re.compile(
    r"\b(CASHBACK|REWARD|STATEMENT CREDIT|MEMBERSHIP REWARDS)\b", re.IGNORECASE)


def _looks_like_payment(desc: str) -> bool:
    return bool(desc and _PAYMENT_WORDS.search(desc))


def _amex_kind(desc: str) -> TxnKind | None:
    if not desc:
        return None
    if _looks_like_payment(desc):
        return TxnKind.CC_PAYMENT
    if parse_installment_marker(desc):
        return TxnKind.INSTALLMENT
    if _INTEREST_WORDS.search(desc):
        return TxnKind.INTEREST
    if _REWARD_WORDS.search(desc):
        return TxnKind.REWARD
    if _INCOME_WORDS.search(desc):
        return TxnKind.INCOME
    return None


def _balance_at(row: dict, d: date, ccy: str) -> list[tuple]:
    """Capture the statement's own running balance when the row carries one.

    This is the independent check that we captured every transaction — see
    integrity.check_account. Any parser reading a source with a balance column
    should call this; without it `check` has nothing to verify the account
    against and a dropped row stays invisible.
    """
    bal = pick(row, "Balance", "Running Balance", "Ledger Balance", "Closing Balance")
    if not bal:
        return []
    try:
        return [(d, parse_amount(bal, ccy), "", "running")]
    except ValueError:
        return []


def _hsbc_kind(desc: str) -> TxnKind | None:
    if not desc:
        return None
    if _PAYMENT_WORDS.search(desc):
        return TxnKind.CC_PAYMENT
    if re.search(r"\b(ATM|CASH WITHDRAWAL)\b", desc, re.IGNORECASE):
        return TxnKind.ATM
    if _INTEREST_WORDS.search(desc):
        return TxnKind.INTEREST
    if _INCOME_WORDS.search(desc):
        return TxnKind.INCOME
    if _TRANSFER_WORDS.search(desc):
        return TxnKind.TRANSFER
    return None


def _maybe_date(value: str, dayfirst: bool) -> date | None:
    if not value:
        return None
    try:
        return parse_date(value, dayfirst=dayfirst)
    except ValueError:
        return None


# ---------------------------------------------------------------------------
# AMEX US High Yield Savings — headerless ISO-date CSV
# ---------------------------------------------------------------------------

@register
class AmexUsSavingsCsvParser(StatementParser):
    """AMEX US High Yield Savings export: no header, `YYYY-MM-DD,desc,amount`.

    Sign convention matches ours (interest positive, card payment negative).
    """

    parser_id = "amex_us_savings_csv"
    version = "0.1.0"
    institution_id = "amex_us"
    file_format = FileFormat.CSV

    def sniff(self, ctx: ParseContext, sample: bytes) -> float:
        text = sample.decode("utf-8", errors="replace")
        first = text.splitlines()[0] if text else ""
        if re.match(r"^\d{4}-\d{2}-\d{2},", first) and "card member" not in text.lower():
            if "amex" in ctx.path.name.lower() or "savings" in ctx.path.name.lower():
                return 0.85
            return 0.4
        return 0.0

    def parse(self, ctx: ParseContext) -> ParseResult:
        import csv
        ccy = ctx.default_currency or "USD"
        txns, warnings = [], []
        with open(ctx.path, newline="", encoding="utf-8", errors="replace") as fh:
            for i, row in enumerate(csv.reader(fh)):
                if len(row) < 3:
                    continue
                try:
                    d = parse_date(row[0], dayfirst=False)
                    booked = parse_amount(row[2], ccy)
                except ValueError as e:
                    warnings.append(f"line {i}: {e}")
                    continue
                desc = row[1].strip()
                txns.append(ParsedTxn(
                    txn_date=d, booked=booked, description_raw=desc,
                    kind_hint=_amex_kind(desc) or (
                        TxnKind.INTEREST if "interest" in desc.lower() else None
                    ),
                    line_no=i,
                ))
        return ParseResult(txns=txns, raw_rows=[], warnings=warnings,
                           account_id=ctx.account_id)
