# Finto

Personal finance ledger. Ingests statements from multiple banks and card
issuers, deduplicates them, links transfers between accounts you own, and gives
you one queryable ledger.

**No financial data is in this repo.** The database, raw statements, and real
account config are gitignored. Only code and synthetic fixtures are committed.

---

## Why this exists

Five institutions across two countries produce statements that overlap,
disagree on date format, disagree on sign convention, and double-count every
transfer between your own accounts. Naively concatenating them gives you a
number that is wrong in three separate ways:

1. **Overlapping periods** — the same charge appears in two statements.
2. **Internal transfers** — HKD 20,000 moved from HSBC to Mox looks like
   HKD 20,000 of spending *and* HKD 20,000 of income. It is neither.
3. **Supplementary cards** — charges may appear on both a card-level and an
   account-level statement.

Finto's whole job is to make those three problems go away without silently
throwing away real transactions.

---

## Scope

| | |
|---|---|
| **Institutions** | AMEX US, AMEX HK, HSBC HK, Wise, Mox |
| **Period** | 2025-01-01 onward |
| **Currency** | Native + booked stored separately; conversion on demand |
| **Storage** | Local SQLite. Never leaves your machine. |

---

## Design decisions

**Money is integer minor units, never floats.** `Money(amount=-123456,
currency="HKD")` is HKD -1,234.56. Currencies with 0 or 3 decimals (JPY, KWD)
are handled by an exponent table.

**One sign convention everywhere: negative = money out.** This holds for credit
cards too — a purchase is negative, a payment received is positive. AMEX writes
the opposite and its parser flips it at the boundary. The benefit is that you
can `SUM(amount_booked)` across any mix of account types and get a meaningful
number.

**Two currency pairs per transaction.** `native` is what the merchant charged
(¥12,000). `booked` is what your account actually moved ($78.20). Both are
kept; neither is derived from the other at write time. FX conversion happens at
read time via the `fx_rate` table, so a rate correction never rewrites history.

**Raw rows are immutable and kept forever.** `raw_record` stores the source row
verbatim as JSON. When a parser improves you re-derive transactions from stored
raw rows instead of hunting down the original download.

**Parsers only extract.** A parser reads a row, gets the money and dates right,
and stops. Normalisation, dedup keys, categorisation and transfer linking all
happen downstream. Adding an institution means writing only the genuinely
institution-specific part.

**Nothing ambiguous is auto-merged.** Exact key collisions merge silently.
Everything fuzzy goes to a review queue. A wrongly-merged transaction is far
harder to notice six months later than a wrongly-kept one.

---

## How dedup works

Duplicates come from four different places and need different handling:

| Source | Detection |
|---|---|
| Same file imported twice | `file_sha256` on `statement_file` — refuses at the door |
| Overlapping statement periods | exact `dedup_key` collision → auto-merge |
| Pending row later posted | `dedup_key` excludes `posted_date`/`status`, so they collide by design; date drift is caught by the fuzzy pass |
| Supplementary card on two statements | cross-account pass, scoped to whitelisted account pairs only |

The `dedup_key` prefers the issuer's own reference when one exists (Wise gives
you `TransferWise ID` — authoritative and stable). Otherwise it hashes
`account + date + signed amount + currency + normalised description`.

Normalisation strips exactly the fields that differ between two copies of the
same charge: embedded dates, auth/trace/reference numbers, masked card
fragments. `STARBUCKS HK REF ABC123 03/08` and `Starbucks HK REF ZZZ999 05/08`
both normalise to `STARBUCKS HK`.

The fuzzy pass blocks candidates on `(currency, |amount|)` — two rows can only
be duplicates if the money matches exactly, and amount is the one field
statements never fudge. That keeps it near-linear instead of O(n²).

---

## How transfer linking works

Money you move between your own accounts appears twice. The matcher pairs
outflows with inflows and scores each pair:

| Signal | Weight |
|---|---|
| Amounts equal, same currency | 0.62 |
| Amounts differ by a fee-sized gap | 0.42 |
| Cross-currency, reconciles via FX within 3% | 0.35 |
| Date proximity (0–5 days) | up to 0.20 |
| Inflow lands on a credit card | 0.12 |
| Same balance group (in-Wise conversion) | 0.12 |
| Transfer/payment wording | 0.12 |

At ≥ 0.90 the pair is linked automatically. Between 0.55 and 0.90 it goes to
`transfer_candidate` for review. Below 0.55 it is discarded.

A transaction can only be one leg of one transfer — pairs are taken greedily
best-first, and any later pair reusing a claimed leg is dropped. Without this,
three identical HKD 10,000 movements on the same day produce nine matches.

Groups, not pairs: a Wise FX conversion has an out leg, an in leg, and a fee,
in two different currencies. `transfer_group` + `transfer_leg` models that
directly.

---

## The LLM layer

Optional, off by default, and deliberately fenced in. The ledger is correct
without it — the LLM improves categorisation quality and resolves genuinely
ambiguous matches.

```bash
python -m fin.cli config set llm_enabled 1
export ANTHROPIC_API_KEY=...
python -m fin.cli categorize --dry-run   # see the cost before paying it
python -m fin.cli categorize --promote
python -m fin.cli reconcile --llm
```

### Categorisation — where an LLM genuinely wins

Turning `CTY SPR TST 3 KLN` into "City Super, groceries" is exactly the kind of
fuzzy, context-dependent judgement that regexes lose at and models win at.

- **Merchants, not transactions.** Input is grouped by normalised description,
  so 300 Starbucks charges cost one classification. A year of statements
  typically collapses to a few hundred distinct merchants.
- **Closed taxonomy.** The model must pick from a fixed category/subcategory
  list; anything invented is discarded. Open-ended taxonomies drift — you get
  "Food", "Dining", "Restaurants" and "Eating out" for one thing, and your
  reports lie.
- **Rules always win.** Only transactions no rule matched are ever sent.
- **Abstention is allowed.** Below 0.60 confidence, the transaction is left
  uncategorised. Uncategorised is honest; a wrong category is not.
- **`--promote` is the ratchet.** Confident results seen 3+ times become
  deterministic rules, so next import they're free and identical. Over time the
  model only sees genuinely new merchants.

### Adjudication — where an LLM helps but is kept on a short leash

Deciding whether `SQ *BLUE BOTTLE` and `BLUE BOTTLE COFFEE HK` are the same
shop, or whether `AMEX AUTOPAY` on HSBC pairs with `PAYMENT RECEIVED` on AMEX,
needs world knowledge that string similarity doesn't have.

But a wrong answer here silently deletes or fabricates money, so:

| Constraint | Effect |
|---|---|
| Only the middle confidence band is sent | 0.70–0.97 for duplicates, 0.55–0.90 for transfers. Confident cases never reach it. |
| It cannot merge anything | Its verdict adjusts a score by at most ±0.20. The deterministic threshold still decides. |
| Amount mismatches are filtered first | If two rows differ in amount, no textual plausibility makes them the same transaction. |
| `"unsure"` is encouraged | Routes to human review, which is the correct outcome for genuine ambiguity. |
| Every decision is stored | Model, prompt version, input and reasoning, in `llm_decision`. |

### Why decisions are cached

Not primarily cost. A ledger whose numbers shift because a model was updated
underneath it is not a ledger. Cached decisions freeze the answer until you
explicitly bump the prompt version or run `llm clear`. It also means re-running
`reconcile` doesn't re-bill you, and a bad model version can be revoked by
deleting its rows without touching anything deterministic.

```bash
python -m fin.cli llm stats     # cache hit counts and average confidence
python -m fin.cli llm audit     # what the model saw and what it said
python -m fin.cli llm clear --task categorize
```

**What the LLM is never allowed to do:** change an amount, currency, date or
account; merge or unmerge transactions directly; override a rule you wrote or a
decision you made by hand; invent a category outside the taxonomy. Every
LLM-set field is recorded in `txn_annotation` with `source='llm'`, so you can
always tell exactly what the model touched — and undo all of it with one
`DELETE`.

---

## Integrity checking

The most important question this system answers is not "are there duplicates?"
but **"did I capture every transaction?"** Dedup and linking can both be
perfect while the ledger is wrong, because a parser silently skipped rows.

Statements print a running balance. That number is the bank's, independent of
our parsing. `balance_assertion` stores it, and `fin.cli check` verifies that
the transactions we hold reproduce the balance movement between any two dates.

```
$ python -m fin.cli check
!! hsbc_hk_current 2025-01-08 -> 2025-01-15:
   expected -13,345.20  actual -12,500.00  diff 845.20 HKD
```

That is a dropped row, located to the day and the cent. Without this check it
would be invisible forever. `check` also runs seven structural invariants
(orphaned transfer groups, one-legged transfers, duplicate chains, currency
mismatches) that should always return clean.

See `SCHEMA_REVIEW.md` for the full design review.

---

## Getting started

```bash
pip install -e .

python -m fin.cli init                        # create finto.db
cp accounts.example.yaml accounts.yaml        # then edit with your real accounts
python -m fin.cli accounts load accounts.yaml
```

**Before importing anything, sniff each new export:**

```bash
python -m fin.cli sniff ~/Downloads/amex.csv --institution amex_us --currency USD
```

This prints the detected parser, the real column header, and the first five
parsed transactions — without writing to the database. It is how you verify and
correct the column mappings in `fin/parsers/institutions.py`. The mappings for
HSBC HK and Mox are informed guesses; Wise and AMEX are on firmer ground.

Then:

```bash
python -m fin.cli import inbox/ --institution hsbc_hk --account hsbc_hk_current
python -m fin.cli reconcile          # dedup + link transfers across the whole ledger
python -m fin.cli review transfers   # work the review queue
python -m fin.cli resolve transfers <id> accept
python -m fin.cli stats
python -m fin.cli export ledger.csv
```

`reconcile` always runs over the full ledger, not just the newest file — a
duplicate or a transfer counterpart usually lives in a different file from a
different institution.

---

## Layout

```
fin/
  models.py               Pydantic DTOs, Money, normalisation, dedup key
  schema.sql              SQLite DDL
  db.py                   Persistence. Thin, explicit, no ORM.
  ingest.py               file -> parser -> Txn -> SQLite, then reconcile
  dedup.py                exact + fuzzy duplicate detection
  transfers.py            transfer/payment/FX-conversion matching
  integrity.py            balance reconciliation + structural invariants
  cli.py                  command line interface
  parsers/
    base.py               parser contract, registry, amount/date helpers
    institutions.py       AMEX, HSBC HK, Wise, Mox, generic fallback
  llm/                    optional, off by default
    provider.py           Anthropic / Null / Echo(test) providers
    cache.py              decision cache + audit trail
    categorize.py         merchant categorisation, closed taxonomy
    adjudicate.py         ambiguous duplicate/transfer adjudication
tests/
  test_pipeline.py            parsing, dedup, transfers
  test_llm_and_integrity.py   LLM guardrails, balance checks
  fixtures/                   invented statements — no real data
accounts.example.yaml     template; copy to accounts.yaml (gitignored)
```

## Adding an institution

Subclass `StatementParser`, implement `sniff` and `parse`, decorate with
`@register`. Return `ParsedTxn` objects. Nothing else in the pipeline changes.

```python
@register
class MyBankParser(StatementParser):
    parser_id = "mybank_csv"
    version = "0.1.0"
    institution_id = "mybank"

    def sniff(self, ctx, sample): ...   # 0.0-1.0 confidence
    def parse(self, ctx): ...           # -> ParseResult
```

## Status

Working end to end on synthetic fixtures: 50 tests passing. Parser column
mappings need one verification pass against real exports — see `sniff` above.

Not built yet: PDF extraction, FX rate fetching, refund→purchase linking,
budgets and reporting.
