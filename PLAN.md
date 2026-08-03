# Finto — installments, API, and web frontend

Design for the next three pieces of work:

1. **Installment plans** — a gap in the ledger model, not just a missing view.
2. **HTTP API** — a second entry point over the existing `fin` package.
3. **Angular frontend** — summary, blotter, digest, search, AI query.

Written in dependency order: (1) changes the schema, (2) exposes it, (3) consumes it.

---

## 1. Installment plans

### Current state: unsupported

There is no `TxnKind.INSTALLMENT`, no plan table, and no linking pass. Today a
12-month plan lands as 12 unrelated purchases. Three consequences:

- **Outstanding liability is unanswerable.** "How much do I still owe on plans?"
  has no query. This is the number that actually matters for a plan.
- **The economic event is invisible.** A HKD 12,000 TV shows up as HKD 1,000 in
  each of twelve months. Monthly spend looks smooth and the purchase decision is
  untraceable.
- **Presentation (b) below double-counts** unless handled explicitly.

Dedup is *not* currently broken by this, which is worth stating: the fuzzy pass
blocks on `(currency, |amount|)` and twelve identical amounts would collide —
but `FUZZY_DATE_WINDOW_DAYS = 4` and instalments are ~30 days apart, so they
never get compared. That is luck rather than design, and it is worth a test.

### The two statement shapes

**(a) Amortised only.** Each statement shows one instalment:

```
2025-03-15  INSTALMENT 03/12 BEST BUY TST     -1,000.00
```

**(b) Full charge, then reversal.** Month 1 shows all three of:

```
2025-01-15  BEST BUY TST                     -12,000.00
2025-01-15  INSTALMENT PLAN CREDIT            +11,000.00
2025-01-15  INSTALMENT 01/12 BEST BUY TST     -1,000.00
```

Both occur in HK. AMEX HK and HSBC HK differ, and can change format.

### Storage basis: cash, always

**Rows stay cash-basis — what actually hit the account.** This is not a
preference, it is forced: `integrity.check_account` verifies that summed
transactions reproduce the bank's own running balance. Storing an accrual-basis
row would break the one check that proves we captured every transaction.

The economic view is therefore a *projection*, never a storage format.

### Schema

```sql
CREATE TABLE installment_plan (
    id             TEXT PRIMARY KEY,
    account_id     TEXT NOT NULL REFERENCES account(id),
    card_id        TEXT REFERENCES card(id),
    merchant       TEXT,
    description    TEXT NOT NULL,
    principal      INTEGER NOT NULL,      -- minor units, signed (negative)
    currency       TEXT NOT NULL,
    term_months    INTEGER NOT NULL,
    start_date     TEXT NOT NULL,
    fee_total      INTEGER,               -- handling fee, if itemised
    apr            TEXT,                  -- decimal string; NULL = interest-free
    external_ref   TEXT,                  -- issuer's plan id when supplied
    status         TEXT NOT NULL DEFAULT 'active'
                     CHECK (status IN ('active','completed','cancelled')),
    match_method   TEXT NOT NULL CHECK (match_method IN ('auto','manual','rule')),
    confidence     REAL NOT NULL DEFAULT 1.0,
    is_confirmed   INTEGER NOT NULL DEFAULT 0,
    created_at     TEXT NOT NULL,
    UNIQUE (account_id, external_ref)
) STRICT;

ALTER TABLE txn ADD COLUMN installment_plan_id TEXT REFERENCES installment_plan(id);
ALTER TABLE txn ADD COLUMN installment_seq     INTEGER;   -- 1..term_months
```

New `TxnKind` values: `installment` (a monthly charge) and
`installment_origination` (the full-amount row in shape (b)).

Shape (b)'s origination and its reversal net to zero in cash terms. Model them
as a **transfer group** with `kind='installment_origination'` — the mechanism
already exists, already nets to zero in reports, and already has a review queue.
No new concept needed.

### Detection

Parser level — cheap and high precision. Add to `ParsedTxn`:

```python
installment_hint: tuple[int, int] | None = None   # (seq, term)
```

Populated by a shared regex covering the formats seen in practice:

```
INSTALMENT 03/12    INSTALLMENT 3 OF 12    MTHLY INSTAL 03/12    分期 03/12
```

Linking pass — `fin/installments.py`, mirroring `transfers.py`:

- Group candidate rows by `(account_id, description_norm, |amount|, currency)`.
- Require ~monthly spacing (25–35 days) and a consistent `term`.
- A plan needs `seq` values forming a prefix of `1..term` with no duplicates.
- Score it; auto-create above a high bar, queue the rest in
  `installment_candidate` for review.

Same philosophy as everywhere else in this codebase: **nothing ambiguous is
auto-linked.** A wrongly-grouped plan misstates your liabilities.

Note `normalize_description` already strips `\d{2}/\d{2}` as noise, which erases
the `03/12` sequence marker. The parser must capture `installment_hint`
**before** normalisation — worth a comment in `models.normalize_description`
pointing at it, since it is a live footgun.

### What this unlocks

- `GET /api/installments` — active plans, paid-to-date, remaining, next charge.
- **Outstanding liability**: `SUM(principal) - SUM(paid instalments)` per plan.
- Accrual projection: replace *N* instalment rows with one event at `start_date`.
  A report flag, never the stored form.
- Forward cash-flow: remaining instalments are *known* future outflows — the
  only genuinely predictable part of a spending forecast.

### Open decision

Whether a plan's fee should book as a separate `fee` txn or fold into
`fee_total`. Recommendation: both — book what the statement actually shows
(cash basis rule), and set `fee_total` as the plan-level rollup for reporting.

---

## 2. HTTP API

FastAPI over the existing `fin` package. The CLI stays; it becomes a peer of the
API, not a dependency of it.

```
fin/
  ...                     unchanged domain code
  reporting.py            NEW — queries currently inline in cli.py
  jobs.py                 NEW — background job runner
  api/
    __init__.py
    app.py                FastAPI app, CORS for the dev server only
    deps.py               connection-per-request
    routers/
      transactions.py  summary.py  imports.py
      review.py  integrity.py  accounts.py  query.py
web/                      NEW — Angular workspace
```

### Prerequisite refactor

`cmd_stats` and `cmd_check` build SQL and format output inline
([cli.py:243](fin/cli.py:243), [cli.py:156](fin/cli.py:156)). Move the queries
into `fin/reporting.py` returning plain dicts; CLI and API both render them.
`ingest_file` and `reconcile` already return dicts and need no change — the
existing separation is good, these two commands are the exception.

### Non-negotiables

**Money crosses the wire as integers.**

```json
{ "amount": -123456, "currency": "HKD" }
```

Never `-1234.56`. The whole schema is built on integer minor units precisely to
avoid float error; emitting a JSON number hands that bug to JavaScript, where
`0.1 + 0.2 !== 0.3` and every total is quietly wrong in the last cent.
Formatting is the frontend's job.

**Writes are serialised.** SQLite allows one writer. `import` and `reconcile`
are long, full-ledger operations and must not run in a request handler — a UI
button makes concurrent invocation easy in a way the CLI never did. Route both
through a single-worker job queue.

**Bind to `127.0.0.1`.** There is no auth, and the project's core promise is
that data never leaves the machine. A dev server on `0.0.0.0` silently breaks
that. If remote access is ever wanted, that is a real auth design, not a flag.

### Endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/summary` | aggregates; `group_by=month\|quarter\|category\|subcategory\|merchant\|account\|institution\|card` |
| `GET` | `/api/transactions` | blotter; filters + sort + cursor pagination |
| `GET` | `/api/transactions/{id}` | detail, incl. `raw_record` provenance |
| `PATCH` | `/api/transactions/{id}` | category / notes / review_state → writes `txn_annotation` with `source='manual'` |
| `GET` | `/api/facets` | distinct categories, merchants, accounts, kinds — populates filter controls |
| `POST` | `/api/imports` | multipart upload → job id |
| `GET` | `/api/imports/{id}/preview` | `sniff` output: parser, header, first rows |
| `POST` | `/api/imports/{id}/confirm` | commit the staged import |
| `POST` | `/api/reconcile` | → job id |
| `GET` | `/api/jobs/{id}` | status + result (SSE for progress) |
| `GET` | `/api/review/{duplicates\|transfers\|installments}` | queues |
| `POST` | `/api/review/{queue}/{id}` | accept / reject |
| `GET` | `/api/integrity` | `check` output |
| `GET` | `/api/installments` | plans + outstanding liability |
| `POST` | `/api/query` | natural-language query (§4) |

Filter model shared by `/transactions`, `/summary` and `/query`:

```ts
interface LedgerFilter {
  from?: string; to?: string;              // ISO dates
  accounts?: string[]; cards?: string[];
  institutions?: string[];
  categories?: string[]; kinds?: TxnKind[];
  currency?: string;
  minAmount?: number; maxAmount?: number;  // minor units
  q?: string;                              // free text
  includeTransfers?: boolean;              // default false
  includeDuplicates?: boolean;             // default false
  uncategorisedOnly?: boolean;
}
```

One filter type across all three surfaces is what makes the AI query feature
cheap to build — see §4.

`includeTransfers` defaulting to **false** matters: internal transfers are not
spending, and the entire point of `transfers.py` is to stop them being counted
as such. The default must be the correct answer.

---

## 3. Angular frontend

Angular 17+ standalone components with signals. No NgModules.

```
web/src/app/
  core/        api client, money pipe, filter serialisation
  features/
    summary/   blotter/  import/  review/  integrity/  ask/
  shared/      filter-bar, money-cell, account-picker, date-range
```

### Cross-cutting

**`MoneyPipe`** — the only place minor units become a display string. Takes
`{amount, currency}`, uses the currency's exponent (JPY has none). If any
component calls `parseFloat` on a money value, that is a bug.

**Filter state lives in the URL.** `/blotter?from=2025-01-01&accounts=hsbc_hk_current&categories=dining`
Every view is bookmarkable and shareable, back/forward works, and "different
filtering views" needs no separate saved-state machinery to start. Saved views
become a thin `saved_view` table later.

### Summary

KPI row (net, inflow, outflow, uncategorised count, outstanding instalment
liability), a trend chart, and a breakdown table.

The breakdown pivots on `group_by` — the same endpoint drives *all* the
aggregation levels, so month / category / merchant / account / card are one
control, not five screens. **Clicking any row pushes that dimension onto the
blotter filter and navigates** — that is the drill-down, and it is why summary
and blotter must share `LedgerFilter`.

Multi-currency: never sum across currencies without conversion. Either group by
currency or convert to `base_currency` via `fx_rate` and label it as converted.
Silently adding HKD to USD is the kind of error this project exists to prevent.

### Blotter

Dense virtualised table (`cdk-virtual-scroll`) — several years of statements is
comfortably 10k+ rows.

Columns: date, account, card, description, merchant, category, kind, amount,
currency, flags. Amounts right-aligned, tabular figures, colour by sign.

Row affordances that reflect the domain:
- **Duplicate/transfer badges** with the counterpart linked. A transfer leg
  should visibly pair with its other leg.
- **Instalment badge** showing `3/12` and linking to the plan.
- **Provenance drawer** — the raw source row from `raw_record`. This is the
  feature that makes the ledger trustworthy: every number traces to a line in a
  file you downloaded.
- Inline category edit, writing `source='manual'` so it outranks rules and LLM.

### Digest / import page

Drag-drop, then a **preview-before-commit** flow — this is the important part.
The README already insists you `sniff` every new export before importing,
because the column mappings for HSBC and Mox are informed guesses. The UI should
make that the default path rather than an optional discipline:

1. Drop files. Each shows detected institution, parser, and confidence.
2. Preview: real header row, plus the first ~10 parsed transactions with dates
   and signs rendered as they'd be stored. **This is where a wrong column
   mapping becomes visible** — a dd/mm vs mm/dd error is obvious here and nearly
   invisible later.
3. Warnings surface inline: unparseable rows, and the new unattributed-card
   warning.
4. Confirm → import → auto-run reconcile → show what changed (new txns,
   duplicates merged, transfers linked, new review items).

Refusals must be legible, not silent: "no parser matched" on a PDF should say
*PDF extraction isn't supported yet* rather than showing a generic failure.

### Review & integrity

Review is a two-pane diff: candidate pair side by side, scoring reasons listed,
accept/reject. Keyboard-driven — these queues are worked in batches.

Integrity page renders `check`: balance reconciliation per account with
discrepancies highlighted, and the structural invariants. A discrepancy should
deep-link to the blotter filtered to that account and date range, since the next
question is always "which row is missing?"

Also surface accounts with **no** balance assertions — currently `check` just
prints nothing for them, which reads as "fine" when it means "unverified".

### Search

Two tiers:
- **Filter bar** — structured, instant, covers most needs.
- **Text search** — `q` against `description_raw`, `description_norm`,
  `merchant`, `counterparty`. Start with `LIKE` on the indexed `description_norm`;
  move to FTS5 only if it gets slow. A personal ledger is small.

---

## 4. AI-powered query

### Recommendation: natural language → filter DSL, not SQL

The model translates the question into the **same `LedgerFilter` JSON** the
blotter uses, plus a `group_by` and `metric`. The backend executes it
deterministically.

```
"how much did I spend on dining last quarter, excluding transfers"

  → { from: "2025-04-01", to: "2025-06-30",
      categories: ["dining"], includeTransfers: false,
      groupBy: "month", metric: "sum" }
```

Why not text-to-SQL:

- **No arbitrary execution** against the financial database.
- **The result is inspectable.** The filter renders as editable chips — "dining,
  Apr–Jun, excluding transfers" — so a misreading is visible and correctable
  rather than a wrong number with a confident sentence attached.
- **Reproducible.** Same filter, same answer, forever. A number that changes
  because a model was updated is exactly what `llm_decision` caching exists to
  prevent — and this reuses that table (add a `query` task type).
- **Degrades well.** A partial parse still produces a useful filter.

The honest limitation: questions outside the DSL's expressiveness ("which
merchants did I spend more at this year than last?") can't be answered. Handle
that by *saying so* and offering the nearest filter, not by silently answering a
different question. If it becomes a real constraint, add a read-only SQL escape
hatch — separate read-only connection, restricted to `v_ledger`, hard `LIMIT`,
statement timeout, off by default behind a setting. Same posture as the existing
`llm_enabled` flag.

### Guardrails, consistent with the existing LLM layer

Reuse what `fin/llm/` already establishes: the model may not change an amount,
currency, date or account; every decision is cached and auditable; and the
answer must render the filter it used. Add one rule specific to this feature:
**the model never produces the number.** It produces the query; the database
produces the number.

---

## 5. Sequencing

| Phase | Work | Ships |
|---|---|---|
| 0 | `reporting.py` extraction, `jobs.py`, installment schema + linking pass | CLI gains `installments`; nothing breaks |
| 1 | FastAPI read endpoints; Angular shell, blotter, summary | Browsable ledger |
| 2 | Import job + digest page | Drag-drop replaces the CLI for daily use |
| 3 | Review queues + integrity page | Full CLI parity |
| 4 | Filter→NL query | AI query |

Phase 0 is deliberately backend-only. Installments change the schema, and
changing the schema after the UI reads it is more expensive than doing it first.

### Risks

- **Installment detection accuracy.** Mitigated by the review queue — but the
  detection regexes need real statements. Same caveat the README already makes
  about HSBC/Mox column mappings.
- **SQLite write contention** once a UI can trigger jobs. Mitigated by the
  single-worker queue; revisit only if it actually bites.
- **Scope.** The blotter and summary alone cover most daily use. Ship phase 1
  and live with it before building phases 2–4.
