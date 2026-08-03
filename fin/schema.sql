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
CREATE TABLE IF NOT EXISTS card (
    id               TEXT PRIMARY KEY,
    account_id       TEXT NOT NULL REFERENCES account(id),
    cardholder_name  TEXT NOT NULL,
    last4            TEXT,
    is_supplementary INTEGER NOT NULL DEFAULT 0,
    issued_on        TEXT,
    closed_on        TEXT
) STRICT;

CREATE INDEX IF NOT EXISTS idx_card_account ON card(account_id);

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
                         'income','adjustment','unknown')),
    category          TEXT,
    subcategory       TEXT,

    -- Links & lineage
    transfer_group_id TEXT REFERENCES transfer_group(id),
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

-- The ledger you actually query: duplicates filtered out.
CREATE VIEW IF NOT EXISTS v_ledger AS
SELECT t.*, a.display_name AS account_name, a.institution_id
FROM txn t JOIN account a ON a.id = t.account_id
WHERE t.duplicate_of_id IS NULL AND t.status <> 'void';

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
                    ('internal_transfer','cc_payment','fx_conversion','atm_withdrawal')),
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
    match_field TEXT NOT NULL CHECK (match_field IN
                  ('description_norm','merchant','counterparty','external_ref')),
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

CREATE TABLE IF NOT EXISTS balance_assertion (
    id                TEXT PRIMARY KEY,
    account_id        TEXT NOT NULL REFERENCES account(id),
    as_of_date        TEXT NOT NULL,
    balance           INTEGER NOT NULL,     -- minor units, signed
    currency          TEXT NOT NULL,
    source            TEXT NOT NULL CHECK (source IN
                        ('statement_closing','statement_running','manual')),
    statement_file_id TEXT REFERENCES statement_file(id),
    UNIQUE (account_id, as_of_date, source, currency)
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
                                                 'adjudicate_transfer','normalize_merchant')),
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
-- 9. Settings
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
-- 10. Import audit log
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
