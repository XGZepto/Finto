-- ============================================================================
-- Personal finance ledger — canonical schema
-- SQLite 3.35+ (uses STRICT tables and generated columns)
-- Scope: 2025-01-01 onward. Multi-currency: native + booked.
-- ============================================================================

PRAGMA foreign_keys = ON;

-- ---------------------------------------------------------------------------
-- 1. Institutions & accounts
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS institution (
    id           TEXT PRIMARY KEY,          -- 'amex_us', 'amex_hk', 'hsbc_hk', 'wise', 'mox'
    display_name TEXT NOT NULL,
    country      TEXT NOT NULL,             -- ISO-3166 alpha-2: 'US', 'HK'
    timezone     TEXT NOT NULL DEFAULT 'Asia/Hong_Kong'
) STRICT;

CREATE TABLE IF NOT EXISTS account (
    id                TEXT PRIMARY KEY,     -- stable slug, e.g. 'hsbc_hk_savings_hkd'
    institution_id    TEXT NOT NULL REFERENCES institution(id),
    display_name      TEXT NOT NULL,
    account_type      TEXT NOT NULL CHECK (account_type IN
                        ('credit_card','charge_card','checking','savings',
                         'multi_currency','investment','loan')),
    primary_currency  TEXT NOT NULL,        -- ISO-4217
    -- Wise/Mox hold several currency balances under one login. Model each
    -- balance as its own account row and group them via balance_group.
    balance_group     TEXT,
    masked_number     TEXT,                 -- '****1007' — never store the full PAN
    is_own_account    INTEGER NOT NULL DEFAULT 1,   -- drives transfer matching
    opened_on         TEXT,
    closed_on         TEXT,
    notes             TEXT
) STRICT;

-- Supplementary cards. Charges post to the parent account's statement but
-- must stay attributable to the cardholder.
--
-- Card identity is not stable over time: a lost/expired/compromised card is
-- reissued with a new number, but it is the same card in every sense that
-- matters for reporting. The account_id does not change, so ledger totals,
-- dedup and balance continuity are all unaffected — but per-card attribution
-- would silently split in two. replaces_card_id chains the reissue back to what
-- it replaced, so a card's history survives renumbering.
CREATE TABLE IF NOT EXISTS card (
    id               TEXT PRIMARY KEY,
    account_id       TEXT NOT NULL REFERENCES account(id),
    cardholder_name  TEXT NOT NULL,
    last4            TEXT,
    is_supplementary INTEGER NOT NULL DEFAULT 0,
    issued_on        TEXT,
    closed_on        TEXT,
    replaces_card_id TEXT REFERENCES card(id)
) STRICT;

CREATE INDEX IF NOT EXISTS idx_card_account ON card(account_id);
CREATE INDEX IF NOT EXISTS idx_card_replaces ON card(replaces_card_id);

-- Which currencies an account actually settles in.
--
-- A position is only meaningful in a currency the account can hold. Some cards
-- settle in exactly one currency; multi-currency cards and Wise-style accounts
-- settle in several, and each is a separate position that must never be added
-- to the others. Summing HKD and USD into one number is not a balance, it is a
-- category error — so the ledger computes and stores positions per currency and
-- leaves any normalised "total in USD" view to the presentation layer, where it
-- can be labelled as converted and dated.
--
-- account.primary_currency remains the default for parsing and display order;
-- this table is the authority on what the account may hold.
CREATE TABLE IF NOT EXISTS account_currency (
    account_id TEXT NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    currency   TEXT NOT NULL,              -- ISO-4217
    is_primary INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (account_id, currency)
) STRICT;

-- ---------------------------------------------------------------------------
-- 2. Provenance — every canonical row traces back to a file and a raw line
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS statement_file (
    id             TEXT PRIMARY KEY,
    source_path    TEXT NOT NULL,
    file_sha256    TEXT NOT NULL UNIQUE,    -- re-importing the same file is a no-op
    institution_id TEXT NOT NULL REFERENCES institution(id),
    account_id     TEXT REFERENCES account(id),  -- NULL if the file spans accounts
    file_format    TEXT NOT NULL CHECK (file_format IN
                     ('csv','ofx','qfx','xlsx','pdf','json','manual')),
    parser_id      TEXT NOT NULL,           -- which parser handled it, for reprocessing
    parser_version TEXT NOT NULL,
    period_start   TEXT,
    period_end     TEXT,
    statement_date TEXT,
    imported_at    TEXT NOT NULL,
    row_count      INTEGER NOT NULL DEFAULT 0
) STRICT;

-- Verbatim source rows. Never edited. Lets you re-derive transactions when a
-- parser improves, without going back to the original download.
CREATE TABLE IF NOT EXISTS raw_record (
    id                TEXT PRIMARY KEY,
    statement_file_id TEXT NOT NULL REFERENCES statement_file(id) ON DELETE CASCADE,
    line_no           INTEGER NOT NULL,
    payload           TEXT NOT NULL,        -- JSON of the source row, keys as-issued
    row_sha256        TEXT NOT NULL,
    UNIQUE (statement_file_id, line_no)
) STRICT;

-- ---------------------------------------------------------------------------
-- 3. Canonical transaction
-- ---------------------------------------------------------------------------
-- Sign convention: amount_booked < 0 = money leaves the account,
--                  amount_booked > 0 = money enters. Applies to credit cards
--                  too (a purchase is negative; a payment received is positive),
--                  so balances sum consistently across account types.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS txn (
    id                TEXT PRIMARY KEY,
    account_id        TEXT NOT NULL REFERENCES account(id),
    card_id           TEXT REFERENCES card(id),      -- set for supplementary charges

    txn_date          TEXT NOT NULL,                 -- when it happened (ISO date)
    posted_date       TEXT,                          -- when it hit the statement
    status            TEXT NOT NULL DEFAULT 'posted'
                        CHECK (status IN ('pending','posted','void')),

    -- Money. Two pairs: what the merchant charged, and what the account moved.
    amount_booked     INTEGER NOT NULL,              -- minor units, signed
    currency_booked   TEXT NOT NULL,
    amount_native     INTEGER,                       -- NULL when same as booked
    currency_native   TEXT,
    fx_rate           TEXT,                          -- decimal string, native->booked
    fx_fee_booked     INTEGER,                       -- FX/markup fee if itemised

    description_raw   TEXT NOT NULL,
    description_norm  TEXT NOT NULL,                 -- uppercased, noise stripped
    merchant          TEXT,
    counterparty      TEXT,                          -- for transfers/payments
    external_ref      TEXT,                          -- issuer's own txn id, when given

    kind              TEXT NOT NULL DEFAULT 'purchase' CHECK (kind IN
                        ('purchase','refund','fee','interest','reward',
                         'cc_payment','transfer','atm','fx_conversion',
                         'income','adjustment','installment',
                         'installment_origination','unknown')),
    category          TEXT,
    subcategory       TEXT,

    -- Links & lineage
    transfer_group_id TEXT REFERENCES transfer_group(id),
    -- Instalment plan membership: which plan, and which of its N charges.
    installment_plan_id TEXT REFERENCES installment_plan(id),
    installment_seq   INTEGER,
    -- A refund points at the purchase it reverses, when we can identify it.
    refund_of_id      TEXT REFERENCES txn(id),
    duplicate_of_id   TEXT REFERENCES txn(id),       -- non-NULL = suppressed dupe
    dedup_key         TEXT NOT NULL,                 -- deterministic natural key
    statement_file_id TEXT NOT NULL REFERENCES statement_file(id),
    raw_record_id     TEXT REFERENCES raw_record(id),

    review_state      TEXT NOT NULL DEFAULT 'unreviewed'
                        CHECK (review_state IN ('unreviewed','confirmed','flagged')),
    notes             TEXT,
    created_at        TEXT NOT NULL,
    updated_at        TEXT NOT NULL
) STRICT;

CREATE INDEX IF NOT EXISTS idx_txn_account_date ON txn(account_id, txn_date);
CREATE INDEX IF NOT EXISTS idx_txn_dedup        ON txn(dedup_key);
CREATE INDEX IF NOT EXISTS idx_txn_amount_date  ON txn(amount_booked, txn_date);
CREATE INDEX IF NOT EXISTS idx_txn_group        ON txn(transfer_group_id);
CREATE INDEX IF NOT EXISTS idx_txn_dupe         ON txn(duplicate_of_id);
-- external_ref is the fast path for dedup when the issuer supplies an id.
CREATE INDEX IF NOT EXISTS idx_txn_extref       ON txn(account_id, external_ref);
CREATE INDEX IF NOT EXISTS idx_txn_card         ON txn(card_id);
CREATE INDEX IF NOT EXISTS idx_txn_merchant     ON txn(description_norm);
CREATE INDEX IF NOT EXISTS idx_txn_plan         ON txn(installment_plan_id);
CREATE INDEX IF NOT EXISTS idx_txn_refund       ON txn(refund_of_id);
CREATE INDEX IF NOT EXISTS idx_txn_date         ON txn(txn_date);
CREATE INDEX IF NOT EXISTS idx_txn_category     ON txn(category, subcategory);

-- The ledger you actually query: duplicates filtered out.
CREATE VIEW IF NOT EXISTS v_ledger AS
SELECT t.*, a.display_name AS account_name, a.institution_id
FROM txn t JOIN account a ON a.id = t.account_id
WHERE t.duplicate_of_id IS NULL AND t.status <> 'void';

-- Net movement per (account, currency). One row per currency an account has
-- actually transacted in — never a cross-currency total, which would be
-- meaningless. This is movement, not balance: a true balance also needs an
-- opening figure, which comes from balance_assertion (see reporting.positions).
CREATE VIEW IF NOT EXISTS v_position AS
SELECT t.account_id,
       a.display_name    AS account_name,
       a.institution_id,
       a.account_type,
       t.currency_booked AS currency,
       COUNT(*)          AS txn_count,
       SUM(t.amount_booked) AS net_minor,
       SUM(CASE WHEN t.amount_booked < 0 THEN t.amount_booked ELSE 0 END) AS outflow_minor,
       SUM(CASE WHEN t.amount_booked > 0 THEN t.amount_booked ELSE 0 END) AS inflow_minor,
       MIN(t.txn_date)   AS first_txn_date,
       MAX(t.txn_date)   AS last_txn_date
FROM txn t JOIN account a ON a.id = t.account_id
WHERE t.duplicate_of_id IS NULL AND t.status <> 'void'
GROUP BY t.account_id, t.currency_booked;

-- ---------------------------------------------------------------------------
-- 3b. Structured transaction detail
-- ---------------------------------------------------------------------------
-- Statements carry far more than date/amount/description. AMEX's Extended
-- Details field describes air travel down to the passenger name, carrier,
-- routing and ticket number; most issuers include the merchant's address, city
-- and country.
--
-- raw_record already keeps the source row verbatim, but a JSON blob is not
-- queryable — you cannot ask "every flight I booked for a given passenger" of
-- it. This table holds the extracted, namespaced facts so you can.
--
-- Keys are namespaced by domain: 'travel.passenger_name', 'travel.carrier',
-- 'merchant.city', 'issuer.reference'. Unknown-but-present detail lines are
-- kept under 'raw.*' rather than discarded — a parser that improves later can
-- re-derive from raw_record, but only if we noticed the field existed.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS txn_detail (
    txn_id  TEXT NOT NULL REFERENCES txn(id) ON DELETE CASCADE,
    key     TEXT NOT NULL,
    value   TEXT NOT NULL,
    source  TEXT NOT NULL DEFAULT 'parser'
              CHECK (source IN ('parser','rule','llm','manual')),
    PRIMARY KEY (txn_id, key)
) STRICT;

CREATE INDEX IF NOT EXISTS idx_detail_key   ON txn_detail(key, value);
CREATE INDEX IF NOT EXISTS idx_detail_value ON txn_detail(value);

-- ---------------------------------------------------------------------------
-- 4. Transfers between accounts you own
-- ---------------------------------------------------------------------------
-- A group, not a pair: Wise FX conversions and CC payments can involve 2-3
-- legs, and legs may differ in amount (fees) and currency.
-- Net effect on net worth should be ~0 aside from fees.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS transfer_group (
    id            TEXT PRIMARY KEY,
    kind          TEXT NOT NULL CHECK (kind IN
                    ('internal_transfer','cc_payment','fx_conversion','atm_withdrawal',
                     'installment_origination')),
    match_method  TEXT NOT NULL CHECK (match_method IN ('auto','manual','rule')),
    confidence    REAL NOT NULL DEFAULT 1.0,
    fee_booked    INTEGER,                  -- leakage: outflow + inflow != 0
    fee_currency  TEXT,
    is_confirmed  INTEGER NOT NULL DEFAULT 0,
    notes         TEXT,
    created_at    TEXT NOT NULL
) STRICT;

-- Explicit membership: which txn plays which role in the group.
CREATE TABLE IF NOT EXISTS transfer_leg (
    transfer_group_id TEXT NOT NULL REFERENCES transfer_group(id) ON DELETE CASCADE,
    txn_id            TEXT NOT NULL REFERENCES txn(id),
    role              TEXT NOT NULL CHECK (role IN ('out','in','fee')),
    PRIMARY KEY (transfer_group_id, txn_id)
) STRICT;

-- Candidate matches awaiting your review. Populated by the matcher, drained by
-- the confirm/reject CLI. Keeps low-confidence guesses out of the ledger.
CREATE TABLE IF NOT EXISTS transfer_candidate (
    id            TEXT PRIMARY KEY,
    out_txn_id    TEXT NOT NULL REFERENCES txn(id),
    in_txn_id     TEXT NOT NULL REFERENCES txn(id),
    score         REAL NOT NULL,
    date_delta    INTEGER NOT NULL,         -- days between legs
    amount_delta  INTEGER NOT NULL,         -- minor units, after FX normalisation
    reasons       TEXT NOT NULL,            -- JSON array of scoring signals
    resolution    TEXT NOT NULL DEFAULT 'open'
                    CHECK (resolution IN ('open','accepted','rejected')),
    created_at    TEXT NOT NULL,
    UNIQUE (out_txn_id, in_txn_id)
) STRICT;

-- Near-duplicate pairs the dedup engine wasn't confident enough to auto-merge.
CREATE TABLE IF NOT EXISTS duplicate_candidate (
    id           TEXT PRIMARY KEY,
    keep_txn_id  TEXT NOT NULL REFERENCES txn(id),
    dupe_txn_id  TEXT NOT NULL REFERENCES txn(id),
    score        REAL NOT NULL,
    reasons      TEXT NOT NULL,
    resolution   TEXT NOT NULL DEFAULT 'open'
                   CHECK (resolution IN ('open','accepted','rejected')),
    created_at   TEXT NOT NULL,
    UNIQUE (keep_txn_id, dupe_txn_id)
) STRICT;

-- ---------------------------------------------------------------------------
-- 4b. Installment plans
-- ---------------------------------------------------------------------------
-- A card instalment plan turns one purchase into N monthly charges. Statements
-- present this two ways:
--
--   (a) amortised only  — each statement shows one "INSTALMENT 03/12" charge.
--   (b) charge+reversal — month 1 shows the full amount, a credit reversing all
--       but the first instalment, then the first instalment.
--
-- Transactions stay CASH BASIS — what actually hit the account. That is forced:
-- integrity.check_account proves we captured every row by reproducing the
-- bank's own running balance, and an accrual-basis row would break it. The
-- economic view (one HKD 12,000 event in January, not twelve of HKD 1,000) is a
-- projection over these rows, never the stored form.
--
-- Shape (b)'s origination and its reversal net to zero and are linked as a
-- transfer_group with kind='installment_origination', reusing machinery that
-- already nets to zero in reports.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS installment_plan (
    id            TEXT PRIMARY KEY,
    account_id    TEXT NOT NULL REFERENCES account(id),
    card_id       TEXT REFERENCES card(id),
    merchant      TEXT,
    description   TEXT NOT NULL,
    principal     INTEGER NOT NULL,        -- minor units, signed (negative = owed)
    currency      TEXT NOT NULL,
    term_months   INTEGER NOT NULL,
    start_date    TEXT NOT NULL,
    fee_total     INTEGER,                 -- handling fee, when itemised
    apr           TEXT,                    -- decimal string; NULL = interest free
    external_ref  TEXT,                    -- issuer's plan id, when supplied
    status        TEXT NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active','completed','cancelled')),
    match_method  TEXT NOT NULL DEFAULT 'auto'
                    CHECK (match_method IN ('auto','manual','rule')),
    confidence    REAL NOT NULL DEFAULT 1.0,
    is_confirmed  INTEGER NOT NULL DEFAULT 0,
    notes         TEXT,
    created_at    TEXT NOT NULL
) STRICT;

CREATE INDEX IF NOT EXISTS idx_plan_account ON installment_plan(account_id, status);

-- Plans the matcher proposed but wasn't confident enough to create.
CREATE TABLE IF NOT EXISTS installment_candidate (
    id           TEXT PRIMARY KEY,
    account_id   TEXT NOT NULL REFERENCES account(id),
    description  TEXT NOT NULL,
    txn_ids      TEXT NOT NULL,            -- JSON array
    term_months  INTEGER NOT NULL,
    score        REAL NOT NULL,
    reasons      TEXT NOT NULL,            -- JSON array
    resolution   TEXT NOT NULL DEFAULT 'open'
                   CHECK (resolution IN ('open','accepted','rejected')),
    created_at   TEXT NOT NULL,
    UNIQUE (account_id, description, term_months)
) STRICT;

-- ---------------------------------------------------------------------------
-- 5. FX rates — convert on demand, never destructively
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS fx_rate (
    rate_date  TEXT NOT NULL,
    base       TEXT NOT NULL,
    quote      TEXT NOT NULL,
    rate       TEXT NOT NULL,               -- decimal string; 1 base = rate quote
    source     TEXT NOT NULL,               -- 'statement','ecb','manual'
    PRIMARY KEY (rate_date, base, quote, source)
) STRICT;

-- ---------------------------------------------------------------------------
-- 6. Categorisation rules
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS category_rule (
    id          TEXT PRIMARY KEY,
    priority    INTEGER NOT NULL DEFAULT 100,   -- lower runs first
    -- 'merchant_category' matches what the issuer itself called the merchant
    -- (AMEX prints "TAXICAB & LIMOUSINE" under the charge). That is a fact
    -- from the statement, so mapping it to a ledger category is a rename, not
    -- a guess — unlike inferring one from a merchant's name.
    match_field TEXT NOT NULL CHECK (match_field IN
                  ('description_norm','merchant','counterparty','external_ref',
                   'merchant_category')),
    match_type  TEXT NOT NULL CHECK (match_type IN ('contains','regex','exact')),
    pattern     TEXT NOT NULL,
    account_id  TEXT REFERENCES account(id),     -- optional scoping
    set_kind    TEXT,
    set_category    TEXT,
    set_subcategory TEXT,
    enabled     INTEGER NOT NULL DEFAULT 1
) STRICT;

-- ---------------------------------------------------------------------------
-- 7. Balance assertions — the integrity check that catches dropped rows
-- ---------------------------------------------------------------------------
-- Statements print a running/closing balance. That number is authoritative and
-- independent of our parsing. If the transactions we ingested for a period
-- don't reproduce the balance delta, we dropped a row, double-counted one, or
-- got a sign wrong — and this is the only way to find out.
--
-- Without this, a silently-dropped transaction is invisible forever.
-- ---------------------------------------------------------------------------

-- `kind` is what the issuer said the figure was, not where we got it. A
-- statement's opening and closing bracket exactly the rows that statement
-- listed, which is the only sound way to check a card: the issuer bills by
-- posting date, so a charge made on the last day of a period routinely appears
-- on the next statement. Comparing "everything dated inside the period" against
-- those two figures therefore disagrees with the bank even when nothing is
-- missing. A `running` balance is the per-row figure a passbook-style statement
-- prints, checkable in sequence instead.
CREATE TABLE IF NOT EXISTS balance_assertion (
    id                TEXT PRIMARY KEY,
    account_id        TEXT NOT NULL REFERENCES account(id),
    as_of_date        TEXT NOT NULL,
    balance           INTEGER NOT NULL,     -- minor units, signed
    currency          TEXT NOT NULL,
    kind              TEXT NOT NULL CHECK (kind IN
                        ('opening','closing','running','manual')),
    statement_file_id TEXT REFERENCES statement_file(id),
    UNIQUE (account_id, as_of_date, kind, currency)
) STRICT;

CREATE INDEX IF NOT EXISTS idx_balance_account ON balance_assertion(account_id, as_of_date);

-- Result of checking ingested transactions against consecutive assertions.
CREATE TABLE IF NOT EXISTS reconciliation_check (
    id            TEXT PRIMARY KEY,
    account_id    TEXT NOT NULL REFERENCES account(id),
    period_start  TEXT NOT NULL,
    period_end    TEXT NOT NULL,
    expected_delta INTEGER NOT NULL,   -- from balance assertions
    actual_delta   INTEGER NOT NULL,   -- from summed transactions
    discrepancy    INTEGER NOT NULL,
    currency       TEXT NOT NULL,
    status         TEXT NOT NULL CHECK (status IN ('ok','discrepancy','insufficient_data')),
    checked_at     TEXT NOT NULL
) STRICT;

-- ---------------------------------------------------------------------------
-- 8. LLM decisions — cache and audit trail
-- ---------------------------------------------------------------------------
-- Every LLM call is cached by a hash of its input and recorded permanently.
-- Three reasons this table exists rather than calling the model inline:
--   1. Cost. The same merchant string recurs hundreds of times.
--   2. Reproducibility. A ledger that changes because a model was updated is
--      not a ledger. Cached decisions freeze the answer.
--   3. Revocability. If a model version turns out to be bad, you can delete
--      its rows by prompt_version and re-derive, without touching anything the
--      deterministic layer decided.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS llm_decision (
    id             TEXT PRIMARY KEY,
    task           TEXT NOT NULL CHECK (task IN ('categorize','adjudicate_duplicate',
                                                 'adjudicate_transfer','normalize_merchant',
                                                 'query')),
    input_hash     TEXT NOT NULL,        -- sha256 of the canonical input
    input_summary  TEXT NOT NULL,        -- human-readable, for auditing
    output         TEXT NOT NULL,        -- JSON
    confidence     REAL,
    model          TEXT NOT NULL,
    prompt_version TEXT NOT NULL,
    applied        INTEGER NOT NULL DEFAULT 0,
    created_at     TEXT NOT NULL,
    UNIQUE (task, input_hash, prompt_version)
) STRICT;

CREATE INDEX IF NOT EXISTS idx_llm_lookup ON llm_decision(task, input_hash);

-- Records which layer set a transaction's category, so LLM output is always
-- distinguishable from a rule you wrote or a choice you made by hand.
CREATE TABLE IF NOT EXISTS txn_annotation (
    txn_id      TEXT NOT NULL REFERENCES txn(id) ON DELETE CASCADE,
    field       TEXT NOT NULL,          -- 'category', 'merchant', 'kind'
    value       TEXT,
    source      TEXT NOT NULL CHECK (source IN ('parser','rule','llm','manual')),
    confidence  REAL,
    decision_id TEXT REFERENCES llm_decision(id),
    created_at  TEXT NOT NULL,
    PRIMARY KEY (txn_id, field)
) STRICT;

-- ---------------------------------------------------------------------------
-- 9. Parties & aliases — who money moves between
-- ---------------------------------------------------------------------------
-- Transfers between *your* accounts are netted out of spend/income. That only
-- works when the matcher can recognise both legs as yours. Institutions write
-- your name differently (YIXIANG ZHOU vs ZEPTO ZHOU YIXIANG vs FPS aliases), and
-- they write the counterparty on one leg only. party + party_alias is the
-- shared dictionary:
--
--   kind='self'     every name you go by — used to boost self-transfer scores
--                   and to stop a payment to yourself being treated as income
--   kind='person'   people you send money to / receive from (P2P). Their
--                   transfers are REAL spend/income; we label them, we do not
--                   net them against another of your accounts.
--
-- account_alias maps description tokens ("MOX", "AMEX PLATINUM") onto the
-- destination account so HSBC→Mox FPS can link even when amounts alone are
-- ambiguous across several same-day movements.

CREATE TABLE IF NOT EXISTS party (
    id           TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    kind         TEXT NOT NULL CHECK (kind IN ('self','person','merchant','institution')),
    notes        TEXT
) STRICT;

CREATE TABLE IF NOT EXISTS party_alias (
    party_id TEXT NOT NULL REFERENCES party(id) ON DELETE CASCADE,
    alias    TEXT NOT NULL,               -- already normalised (upper, alnum)
    PRIMARY KEY (party_id, alias)
) STRICT;

CREATE INDEX IF NOT EXISTS idx_party_alias ON party_alias(alias);

CREATE TABLE IF NOT EXISTS account_alias (
    account_id TEXT NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    alias      TEXT NOT NULL,
    PRIMARY KEY (account_id, alias)
) STRICT;

CREATE INDEX IF NOT EXISTS idx_account_alias ON account_alias(alias);

-- ---------------------------------------------------------------------------
-- 10. Investment / MPF positions
-- ---------------------------------------------------------------------------
-- An investment account is still an `account` row (account_type='investment').
-- Cash contributions that leave a bank account stay cash-basis txns and can
-- link as transfers into the investment account. What this section adds is the
-- *unit* ledger: fund holdings and valuations that do not move cash and must
-- never be fed into integrity.check_account.
--
-- HSBC MPF specifically: three member sub-accounts (Regular Employee, Personal,
-- TDVC) plus an aggregate fund breakdown. Each sub-account is its own account
-- row under balance_group='hsbc_mpf'; a snapshot records the valuation date;
-- subaccount_balance and holding rows hang off the snapshot.

CREATE TABLE IF NOT EXISTS investment_snapshot (
    id                TEXT PRIMARY KEY,
    as_of_date        TEXT NOT NULL,
    scheme            TEXT NOT NULL,          -- 'hsbc_mpf'
    currency          TEXT NOT NULL,
    total_value       INTEGER NOT NULL,       -- minor units
    source            TEXT NOT NULL,          -- 'xlsx','manual','statement'
    statement_file_id TEXT REFERENCES statement_file(id),
    notes             TEXT,
    created_at        TEXT NOT NULL,
    UNIQUE (scheme, as_of_date, source)
) STRICT;

CREATE TABLE IF NOT EXISTS investment_subaccount_balance (
    snapshot_id  TEXT NOT NULL REFERENCES investment_snapshot(id) ON DELETE CASCADE,
    account_id   TEXT NOT NULL REFERENCES account(id),
    member_no    TEXT,                        -- issuer member account number
    balance      INTEGER NOT NULL,            -- minor units
    currency     TEXT NOT NULL,
    allocation   TEXT,                        -- decimal string fraction of total
    PRIMARY KEY (snapshot_id, account_id)
) STRICT;

CREATE TABLE IF NOT EXISTS investment_holding (
    id            TEXT PRIMARY KEY,
    snapshot_id   TEXT NOT NULL REFERENCES investment_snapshot(id) ON DELETE CASCADE,
    instrument    TEXT NOT NULL,              -- constituent fund name
    units         TEXT,                       -- decimal string
    unit_price    TEXT,                       -- decimal string, in currency
    market_value  INTEGER NOT NULL,           -- minor units
    currency      TEXT NOT NULL,
    allocation    TEXT,                       -- decimal string fraction of total
    UNIQUE (snapshot_id, instrument)
) STRICT;

CREATE INDEX IF NOT EXISTS idx_holding_snapshot ON investment_holding(snapshot_id);
CREATE INDEX IF NOT EXISTS idx_inv_sub_account ON investment_subaccount_balance(account_id);

-- ---------------------------------------------------------------------------
-- 11. Settings
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS setting (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
) STRICT;

INSERT OR IGNORE INTO setting (key, value) VALUES
    ('base_currency', 'HKD'),
    ('period_start',  '2025-01-01'),
    ('llm_enabled',   '0'),
    ('llm_model',     'claude-haiku-4-5-20251001');

-- ---------------------------------------------------------------------------
-- 12. Import audit log
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS import_run (
    id              TEXT PRIMARY KEY,
    started_at      TEXT NOT NULL,
    finished_at     TEXT,
    files_seen      INTEGER NOT NULL DEFAULT 0,
    files_imported  INTEGER NOT NULL DEFAULT 0,
    files_skipped   INTEGER NOT NULL DEFAULT 0,
    txns_inserted   INTEGER NOT NULL DEFAULT 0,
    txns_deduped    INTEGER NOT NULL DEFAULT 0,
    errors          TEXT
) STRICT;
