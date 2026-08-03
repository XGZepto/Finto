# Schema review

You asked two direct questions. Short answers first, then what the review
actually turned up.

## "Did you track accounts?"

Yes — `account`, plus `institution` above it and `card` below it.

```
institution  amex_us, amex_hk, hsbc_hk, wise, mox
  └── account         id, type, primary_currency, balance_group, is_own_account
        └── card      cardholder_name, last4, is_supplementary
```

Three things that design buys you:

**`is_own_account`** is what makes transfer detection possible at all. Without
a flag saying "this account is mine", there's no way to distinguish moving your
own money from paying someone else.

**`balance_group`** handles Wise and Mox. They hold several currency balances
under one login. Each balance is its own `account` row (so each has a coherent
single currency), tied together by `balance_group`. That's what lets the matcher
recognise a Wise HKD→USD conversion as a transfer rather than as spending plus
income.

**`card` is separate from `account`** because supplementary charges post to the
*parent account's* statement. They aren't a separate account — they're a
separate attribution within one. Keeping them separate means you can ask "what
did the supplementary cardholder spend?" without it distorting balances.

Verified on fixtures: a charge on card `-11009` was correctly attributed to the
supplementary holder while posting to `amex_us_main`.

## "If it's an internal transfer, do you link those properly?"

Yes, and via a group rather than a pair — which matters more than it sounds.

```sql
transfer_group   kind, match_method, confidence, fee, is_confirmed
transfer_leg     (group_id, txn_id, role)   role ∈ out | in | fee
```

A pair table would have been the obvious choice and would have been wrong. A
Wise FX conversion has an out leg in HKD, an in leg in USD, and a fee — three
rows, two currencies, one economic event. `transfer_leg` models that directly.

Verified on fixtures: the HSBC→Wise HKD 20,000 movement linked at 0.94
confidence with both legs correctly roled.

---

## What the review actually found

Reviewing my own work, five real gaps. All now fixed.

### 1. No integrity check — the serious one

The original schema had no way to answer *"did I capture every transaction?"*

Dedup can be perfect and transfer linking can be perfect while the ledger is
still quietly wrong, because a parser skipped four rows it couldn't read. I was
*parsing* HSBC's `Balance` column and throwing it away. That number comes from
the bank, independent of my parsing — it's the only external check available.

Added `balance_assertion` and `reconciliation_check`, and parsers now capture
balances. `fin.cli check` walks consecutive assertions and compares the bank's
balance movement against the sum of transactions held.

Tested by deliberately corrupting one row of a fixture so the parser skipped it:

```
!! hsbc_hk_current 2025-01-08 -> 2025-01-15:
   expected -13,345.20  actual -12,500.00  diff 845.20 HKD
```

It pinpointed the interval and the exact missing amount. Without this, that
transaction is gone forever and nothing ever tells you.

### 2. Duplicate chains

`duplicate_of_id` could form A→B→C across repeated `reconcile` runs. Any query
joining one level deep would silently miss rows. Added
`resolve_duplicate_chains()`, plus a `find_violations()` check that fails if a
chain ever reappears.

### 3. No structural invariant checking

Nothing verified that transfer groups have both an inflow and an outflow, that
suppressed duplicates aren't still linked into transfers, or that a transaction
isn't marked a duplicate of itself. `find_violations()` now runs seven such
checks and returns an empty list on a healthy ledger.

### 4. Missing indexes

`external_ref` is the fast path for dedup when an issuer supplies an ID (Wise
always does), but there was no index on it — every lookup was a table scan.
Added indexes on `(account_id, external_ref)`, `card_id`, `description_norm`.

### 5. My own comment was wrong about supplementary cards

`dedup.py` claimed the cross-account pass handled supplementary-card
duplicates. It doesn't, and doesn't need to: supplementary charges share the
parent's `account_id`, so ordinary same-account dedup covers them. The
cross-account whitelist is really for `balance_group` members. Comment and
function name corrected — a misleading comment about dedup logic is a bug
waiting to happen.

---

## Still not addressed

Being straight about what's missing:

- **No refund→purchase linking.** A refund is currently just a positive
  transaction. Tying it to the original charge would make per-merchant
  net-spend accurate.
- **No budgets or reporting tables.** Deliberate — get ingest correct first.
- **`txn.transfer_group_id` duplicates `transfer_leg`.** It's a denormalised
  cache for query speed. Two sources of truth that could drift;
  `find_violations` checks for orphans, but the cleaner fix is a view.
- **No FX rate fetching.** The table and lookup exist; nothing populates them
  except Wise's own supplied rates. Cross-currency transfer matching stays
  weak until you load real rates.
- **PDF extraction.** If HSBC or Mox only give you PDFs, that work is still
  ahead.
