-- ============================================================================
-- Personal finance ledger — canonical PostgreSQL schema
-- Scope: 2025-01-01 onward. Multi-currency: native + booked.
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------------------------------------------------------------------------
-- 1. Users, institutions & accounts
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS app_user (
    id             TEXT PRIMARY KEY,
    username       TEXT NOT NULL,
    email          TEXT NOT NULL,
    password_hash  TEXT NOT NULL,
    password_salt  TEXT NOT NULL,
    preferences    JSONB NOT NULL DEFAULT '{}'::jsonb,
    is_active      BIGINT NOT NULL DEFAULT 1,
    created_at     TEXT NOT NULL,
    updated_at     TEXT NOT NULL,
    last_login_at  TEXT
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_app_user_username
    ON app_user (lower(username));
CREATE UNIQUE INDEX IF NOT EXISTS idx_app_user_email
    ON app_user (lower(email));

-- The bootstrap row makes account ownership non-null during first-run schema
-- creation and upgrades. `finto users bootstrap` replaces its unusable
-- credential before the application can authenticate it.
INSERT INTO app_user
    (id, username, email, password_hash, password_salt, preferences,
     is_active, created_at, updated_at)
VALUES
    ('owner', 'owner', 'owner@localhost', '!', '!', '{}'::jsonb,
     1, CURRENT_TIMESTAMP::text, CURRENT_TIMESTAMP::text)
ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS institution (
    id           TEXT PRIMARY KEY,          -- 'amex_us', 'amex_hk', 'hsbc_hk', 'wise', 'mox'
    display_name TEXT NOT NULL,
    country      TEXT NOT NULL,             -- ISO-3166 alpha-2: 'US', 'HK'
    timezone     TEXT NOT NULL DEFAULT 'Asia/Hong_Kong'
);

CREATE TABLE IF NOT EXISTS account (
    id                TEXT PRIMARY KEY,     -- stable slug, e.g. 'hsbc_hk_savings_hkd'
    user_id           TEXT NOT NULL DEFAULT 'owner'
                      CONSTRAINT account_user_fk REFERENCES app_user(id),
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
    is_own_account    BIGINT NOT NULL DEFAULT 1,   -- drives transfer matching
    opened_on         TEXT,
    closed_on         TEXT,
    notes             TEXT
);

CREATE TABLE IF NOT EXISTS account_acl (
    account_id  TEXT NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    user_id     TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    access_role TEXT NOT NULL CHECK (access_role IN ('viewer','editor','owner')),
    granted_at  TEXT NOT NULL,
    granted_by  TEXT REFERENCES app_user(id),
    PRIMARY KEY (account_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_account_acl_user ON account_acl(user_id, account_id);

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
    is_supplementary BIGINT NOT NULL DEFAULT 0,
    issued_on        TEXT,
    closed_on        TEXT,
    replaces_card_id TEXT REFERENCES card(id)
);

CREATE INDEX IF NOT EXISTS idx_card_account ON card(account_id);
CREATE INDEX IF NOT EXISTS idx_card_replaces ON card(replaces_card_id);
CREATE TABLE IF NOT EXISTS auth_session (
    id          TEXT PRIMARY KEY,
    user_id     TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash  TEXT NOT NULL UNIQUE,
    created_at  TEXT NOT NULL,
    expires_at  TEXT NOT NULL,
    revoked_at  TEXT,
    user_agent  TEXT
);

CREATE INDEX IF NOT EXISTS idx_auth_session_user ON auth_session(user_id, expires_at);

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
    is_primary BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (account_id, currency)
);

-- ---------------------------------------------------------------------------
-- 2. Provenance — every canonical row traces back to a file and a raw line
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS statement_file (
    id             TEXT PRIMARY KEY,
    user_id        TEXT NOT NULL DEFAULT
                   COALESCE(NULLIF(current_setting('finto.user_id', true), ''), 'owner')
                   CONSTRAINT statement_file_user_fk REFERENCES app_user(id),
    source_path    TEXT NOT NULL,
    file_sha256    TEXT NOT NULL UNIQUE,    -- re-importing the same file is a no-op
    content_fingerprint TEXT,
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
    row_count      BIGINT NOT NULL DEFAULT 0
);
ALTER TABLE statement_file ADD COLUMN IF NOT EXISTS content_fingerprint TEXT;

-- Verbatim source rows. Never edited. Lets you re-derive transactions when a
-- parser improves, without going back to the original download.
CREATE TABLE IF NOT EXISTS raw_record (
    id                TEXT PRIMARY KEY,
    statement_file_id TEXT NOT NULL REFERENCES statement_file(id) ON DELETE CASCADE,
    line_no           BIGINT NOT NULL,
    payload           TEXT NOT NULL,        -- JSON of the source row, keys as-issued
    row_sha256        TEXT NOT NULL,
    UNIQUE (statement_file_id, line_no)
);

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
    amount_booked     BIGINT NOT NULL,              -- minor units, signed
    currency_booked   TEXT NOT NULL,
    amount_native     BIGINT,                       -- NULL when same as booked
    currency_native   TEXT,
    fx_rate           TEXT,                          -- decimal string, native->booked
    fx_fee_booked     BIGINT,                       -- FX/markup fee if itemised

    description_raw   TEXT NOT NULL,
    description_norm  TEXT NOT NULL,                 -- uppercased, noise stripped
    merchant          TEXT,
    counterparty      TEXT,                          -- for transfers/payments
    external_ref      TEXT,                          -- issuer's own txn id, when given
    search_text       TEXT NOT NULL DEFAULT '',      -- derived non-raw searchable facts

    kind              TEXT NOT NULL DEFAULT 'purchase' CHECK (kind IN
                        ('purchase','refund','fee','interest','reward',
                         'cc_payment','transfer','atm','fx_conversion',
                         'income','adjustment','installment',
                         'installment_origination','unknown')),
    category          TEXT,
    subcategory       TEXT,

    -- Links & lineage
    transfer_group_id TEXT,
    -- Instalment plan membership: which plan, and which of its N charges.
    installment_plan_id TEXT,
    installment_seq   BIGINT,
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
);

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
CREATE INDEX IF NOT EXISTS idx_txn_live_date
    ON txn(txn_date DESC, id)
    WHERE duplicate_of_id IS NULL AND status <> 'void';
ALTER TABLE txn ADD COLUMN IF NOT EXISTS search_text TEXT NOT NULL DEFAULT '';
CREATE INDEX IF NOT EXISTS idx_txn_search_text_trgm
    ON txn USING gin (search_text gin_trgm_ops);
DROP INDEX IF EXISTS idx_txn_description_raw_trgm;
DROP INDEX IF EXISTS idx_txn_description_norm_trgm;
DROP INDEX IF EXISTS idx_txn_merchant_trgm;
DROP INDEX IF EXISTS idx_txn_counterparty_trgm;

-- The ledger you actually query: duplicates filtered out.
CREATE OR REPLACE VIEW v_ledger AS
SELECT t.*, a.display_name AS account_name, a.institution_id
FROM txn t JOIN account a ON a.id = t.account_id
WHERE t.duplicate_of_id IS NULL AND t.status <> 'void';

-- Net movement per (account, currency). One row per currency an account has
-- actually transacted in — never a cross-currency total, which would be
-- meaningless. This is movement, not balance: a true balance also needs an
-- opening figure, which comes from balance_assertion (see reporting.positions).
CREATE OR REPLACE VIEW v_position AS
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
GROUP BY t.account_id, t.currency_booked,
         a.display_name, a.institution_id, a.account_type;

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

-- User tags: free labels for slicing spend the categories don't capture — a
-- trip ("Marriott"), a project, a person reimbursing. Many per transaction,
-- and orthogonal to category, which is one value chosen from a taxonomy.
CREATE TABLE IF NOT EXISTS txn_tag (
    txn_id     TEXT NOT NULL REFERENCES txn(id) ON DELETE CASCADE,
    tag        TEXT NOT NULL,
    source     TEXT NOT NULL DEFAULT 'manual'
                 CHECK (source IN ('manual','rule','llm')),
    created_at TEXT NOT NULL,
    PRIMARY KEY (txn_id, tag)
);

CREATE INDEX IF NOT EXISTS idx_txn_tag_tag ON txn_tag(tag);

-- Canonical pools. Transaction columns remain denormalised for reporting, but
-- every value written by the application is resolved through these tables.
CREATE TABLE IF NOT EXISTS category_definition (
    category          TEXT NOT NULL,
    subcategory       TEXT NOT NULL,
    category_label    TEXT NOT NULL,
    subcategory_label TEXT NOT NULL,
    source            TEXT NOT NULL CHECK (source IN ('builtin','manual')),
    active            BIGINT NOT NULL DEFAULT 1,
    created_at        TEXT NOT NULL,
    PRIMARY KEY (category, subcategory)
);

CREATE TABLE IF NOT EXISTS tag_definition (
    id           TEXT PRIMARY KEY,
    user_id      TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    slug         TEXT NOT NULL,
    display_name TEXT NOT NULL,
    source       TEXT NOT NULL CHECK (source IN ('observed','manual','rule','llm')),
    active       BIGINT NOT NULL DEFAULT 1,
    created_at   TEXT NOT NULL,
    UNIQUE (user_id, slug)
);

CREATE TABLE IF NOT EXISTS tag_alias (
    user_id   TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    alias_key TEXT NOT NULL,
    tag_id    TEXT NOT NULL REFERENCES tag_definition(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, alias_key)
);

CREATE TABLE IF NOT EXISTS merchant_definition (
    id           TEXT PRIMARY KEY,
    user_id      TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    name_key     TEXT NOT NULL,
    display_name TEXT NOT NULL,
    category     TEXT,
    subcategory  TEXT,
    source       TEXT NOT NULL CHECK (source IN ('observed','manual','rule','llm')),
    active       BIGINT NOT NULL DEFAULT 1,
    created_at   TEXT NOT NULL,
    UNIQUE (user_id, name_key)
);

CREATE TABLE IF NOT EXISTS merchant_alias (
    user_id     TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    alias_key   TEXT NOT NULL,
    merchant_id TEXT NOT NULL REFERENCES merchant_definition(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, alias_key)
);

CREATE TABLE IF NOT EXISTS agent_operation (
    id          TEXT PRIMARY KEY,
    subject     TEXT NOT NULL,
    action      TEXT NOT NULL,
    user_id     TEXT,
    applied     BIGINT NOT NULL DEFAULT 0 CHECK (applied IN (0,1)),
    result      JSONB NOT NULL,
    created_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS user_api_key (
    id            TEXT PRIMARY KEY,
    user_id       TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    name          TEXT NOT NULL,
    key_prefix    TEXT NOT NULL,
    key_hash      TEXT NOT NULL UNIQUE,
    scopes        JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at    TEXT NOT NULL,
    last_used_at  TEXT,
    revoked_at    TEXT
);
CREATE INDEX IF NOT EXISTS idx_user_api_key_owner ON user_api_key(user_id, revoked_at);
UPDATE user_api_key
SET scopes = scopes || '["imports:write"]'::jsonb
WHERE scopes @> '["ledger:write"]'::jsonb
  AND NOT scopes @> '["imports:write"]'::jsonb;

CREATE TABLE IF NOT EXISTS txn_detail (
    txn_id  TEXT NOT NULL REFERENCES txn(id) ON DELETE CASCADE,
    key     TEXT NOT NULL,
    value   TEXT NOT NULL,
    source  TEXT NOT NULL DEFAULT 'parser'
              CHECK (source IN ('parser','rule','llm','manual')),
    PRIMARY KEY (txn_id, key)
);

CREATE INDEX IF NOT EXISTS idx_detail_key   ON txn_detail(key, value);
CREATE INDEX IF NOT EXISTS idx_detail_value ON txn_detail(value);
DROP INDEX IF EXISTS idx_detail_value_trgm;

CREATE TABLE IF NOT EXISTS detail_key_catalog (
    key TEXT PRIMARY KEY
);

INSERT INTO detail_key_catalog (key)
SELECT DISTINCT key FROM txn_detail WHERE key NOT LIKE 'raw.%'
ON CONFLICT (key) DO NOTHING;

CREATE OR REPLACE FUNCTION finto_set_txn_search_text()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.search_text := concat_ws(
        ' ',
        NEW.description_raw,
        NEW.description_norm,
        NEW.merchant,
        NEW.counterparty,
        (
            SELECT string_agg(value, ' ' ORDER BY key)
            FROM txn_detail
            WHERE txn_id=NEW.id AND key NOT LIKE 'raw.%'
        )
    );
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS finto_txn_search_fields ON txn;
CREATE TRIGGER finto_txn_search_fields
BEFORE INSERT OR UPDATE OF description_raw,description_norm,merchant,counterparty
ON txn
FOR EACH ROW EXECUTE FUNCTION finto_set_txn_search_text();

CREATE OR REPLACE FUNCTION finto_refresh_txn_search_from_detail()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    target_txn TEXT;
    relevant BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE' THEN
        target_txn := OLD.txn_id;
        relevant := OLD.key NOT LIKE 'raw.%';
    ELSIF TG_OP = 'INSERT' THEN
        target_txn := NEW.txn_id;
        relevant := NEW.key NOT LIKE 'raw.%';
    ELSE
        target_txn := NEW.txn_id;
        relevant := NEW.key NOT LIKE 'raw.%' OR OLD.key NOT LIKE 'raw.%';
    END IF;
    IF TG_OP <> 'DELETE' AND NEW.key NOT LIKE 'raw.%' THEN
        INSERT INTO detail_key_catalog (key) VALUES (NEW.key)
        ON CONFLICT (key) DO NOTHING;
    END IF;
    IF relevant THEN
        UPDATE txn
        SET search_text=concat_ws(
            ' ',
            description_raw,
            description_norm,
            merchant,
            counterparty,
            (
                SELECT string_agg(value, ' ' ORDER BY key)
                FROM txn_detail
                WHERE txn_id=target_txn AND key NOT LIKE 'raw.%'
            )
        )
        WHERE id=target_txn;
    END IF;
    RETURN NULL;
END
$$;

DROP TRIGGER IF EXISTS finto_txn_detail_search ON txn_detail;
CREATE TRIGGER finto_txn_detail_search
AFTER INSERT OR UPDATE OR DELETE ON txn_detail
FOR EACH ROW EXECUTE FUNCTION finto_refresh_txn_search_from_detail();

UPDATE txn
SET search_text=concat_ws(
    ' ',
    description_raw,
    description_norm,
    merchant,
    counterparty,
    (
        SELECT string_agg(value, ' ' ORDER BY key)
        FROM txn_detail
        WHERE txn_id=txn.id AND key NOT LIKE 'raw.%'
    )
)
WHERE search_text='';

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
    confidence    DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    fee_booked    BIGINT,                  -- leakage: outflow + inflow != 0
    fee_currency  TEXT,
    is_confirmed  BIGINT NOT NULL DEFAULT 0,
    notes         TEXT,
    created_at    TEXT NOT NULL
);

-- Explicit membership: which txn plays which role in the group.
CREATE TABLE IF NOT EXISTS transfer_leg (
    transfer_group_id TEXT NOT NULL REFERENCES transfer_group(id) ON DELETE CASCADE,
    txn_id            TEXT NOT NULL REFERENCES txn(id),
    role              TEXT NOT NULL CHECK (role IN ('out','in','fee')),
    PRIMARY KEY (transfer_group_id, txn_id)
);

-- Candidate matches awaiting your review. Populated by the matcher, drained by
-- the confirm/reject CLI. Keeps low-confidence guesses out of the ledger.
CREATE TABLE IF NOT EXISTS transfer_candidate (
    id            TEXT PRIMARY KEY,
    out_txn_id    TEXT NOT NULL REFERENCES txn(id),
    in_txn_id     TEXT NOT NULL REFERENCES txn(id),
    score         DOUBLE PRECISION NOT NULL,
    date_delta    BIGINT NOT NULL,         -- days between legs
    amount_delta  BIGINT NOT NULL,         -- minor units, after FX normalisation
    reasons       TEXT NOT NULL,            -- JSON array of scoring signals
    resolution    TEXT NOT NULL DEFAULT 'open'
                    CHECK (resolution IN ('open','accepted','rejected')),
    created_at    TEXT NOT NULL,
    UNIQUE (out_txn_id, in_txn_id)
);

-- Near-duplicate pairs the dedup engine wasn't confident enough to auto-merge.
CREATE TABLE IF NOT EXISTS duplicate_candidate (
    id           TEXT PRIMARY KEY,
    keep_txn_id  TEXT NOT NULL REFERENCES txn(id),
    dupe_txn_id  TEXT NOT NULL REFERENCES txn(id),
    score        DOUBLE PRECISION NOT NULL,
    reasons      TEXT NOT NULL,
    resolution   TEXT NOT NULL DEFAULT 'open'
                   CHECK (resolution IN ('open','accepted','rejected')),
    created_at   TEXT NOT NULL,
    UNIQUE (keep_txn_id, dupe_txn_id)
);

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
    principal     BIGINT NOT NULL,        -- minor units, signed (negative = owed)
    currency      TEXT NOT NULL,
    term_months   BIGINT NOT NULL,
    start_date    TEXT NOT NULL,
    fee_total     BIGINT,                 -- handling fee, when itemised
    apr           TEXT,                    -- decimal string; NULL = interest free
    external_ref  TEXT,                    -- issuer's plan id, when supplied
    status        TEXT NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active','completed','cancelled')),
    match_method  TEXT NOT NULL DEFAULT 'auto'
                    CHECK (match_method IN ('auto','manual','rule')),
    confidence    DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    is_confirmed  BIGINT NOT NULL DEFAULT 0,
    notes         TEXT,
    created_at    TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_plan_account ON installment_plan(account_id, status);

-- Plans the matcher proposed but wasn't confident enough to create.
CREATE TABLE IF NOT EXISTS installment_candidate (
    id           TEXT PRIMARY KEY,
    account_id   TEXT NOT NULL REFERENCES account(id),
    description  TEXT NOT NULL,
    txn_ids      TEXT NOT NULL,            -- JSON array
    term_months  BIGINT NOT NULL,
    score        DOUBLE PRECISION NOT NULL,
    reasons      TEXT NOT NULL,            -- JSON array
    resolution   TEXT NOT NULL DEFAULT 'open'
                   CHECK (resolution IN ('open','accepted','rejected')),
    created_at   TEXT NOT NULL,
    UNIQUE (account_id, description, term_months)
);

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
);

-- ---------------------------------------------------------------------------
-- 6. Categorisation rules
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS category_rule (
    id          TEXT PRIMARY KEY,
    priority    BIGINT NOT NULL DEFAULT 100,   -- lower runs first
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
    enabled     BIGINT NOT NULL DEFAULT 1
);

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
    balance           BIGINT NOT NULL,     -- minor units, signed
    currency          TEXT NOT NULL,
    kind              TEXT NOT NULL CHECK (kind IN
                        ('opening','closing','running','manual')),
    statement_file_id TEXT REFERENCES statement_file(id),
    UNIQUE (account_id, as_of_date, kind, currency)
);

CREATE INDEX IF NOT EXISTS idx_balance_account ON balance_assertion(account_id, as_of_date);

-- Result of checking ingested transactions against consecutive assertions.
CREATE TABLE IF NOT EXISTS reconciliation_check (
    id            TEXT PRIMARY KEY,
    account_id    TEXT NOT NULL REFERENCES account(id),
    period_start  TEXT NOT NULL,
    period_end    TEXT NOT NULL,
    expected_delta BIGINT NOT NULL,   -- from balance assertions
    actual_delta   BIGINT NOT NULL,   -- from summed transactions
    discrepancy    BIGINT NOT NULL,
    currency       TEXT NOT NULL,
    status         TEXT NOT NULL CHECK (status IN ('ok','discrepancy','insufficient_data')),
    checked_at     TEXT NOT NULL
);

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
    confidence     DOUBLE PRECISION,
    model          TEXT NOT NULL,
    prompt_version TEXT NOT NULL,
    applied        BIGINT NOT NULL DEFAULT 0,
    created_at     TEXT NOT NULL,
    UNIQUE (task, input_hash, prompt_version)
);

CREATE INDEX IF NOT EXISTS idx_llm_lookup ON llm_decision(task, input_hash);

-- Records which layer set a transaction's category, so LLM output is always
-- distinguishable from a rule you wrote or a choice you made by hand.
CREATE TABLE IF NOT EXISTS txn_annotation (
    txn_id      TEXT NOT NULL REFERENCES txn(id) ON DELETE CASCADE,
    field       TEXT NOT NULL,          -- 'category', 'merchant', 'kind'
    value       TEXT,
    source      TEXT NOT NULL CHECK (source IN ('parser','rule','llm','manual')),
    confidence  DOUBLE PRECISION,
    decision_id TEXT REFERENCES llm_decision(id),
    created_at  TEXT NOT NULL,
    PRIMARY KEY (txn_id, field)
);

-- ---------------------------------------------------------------------------
-- 9. Parties & aliases — who money moves between
-- ---------------------------------------------------------------------------
-- Transfers between *your* accounts are netted out of spend/income. That only
-- works when the matcher can recognise both legs as yours. Institutions write
-- your name differently (ALEX EXAMPLE vs EXAMPLE ALEX vs FPS aliases), and
-- they write the counterparty on one leg only. party + party_alias is the
-- shared dictionary:
--
--   kind='self'     every name you go by — used to boost self-transfer scores
--                   and to stop a payment to yourself being treated as income
--   kind='person'   people you send money to / receive from (P2P). Their
--                   transfers are spend/income; we label them, we do not
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
);

CREATE TABLE IF NOT EXISTS party_alias (
    party_id TEXT NOT NULL REFERENCES party(id) ON DELETE CASCADE,
    alias    TEXT NOT NULL,               -- already normalised (upper, alnum)
    PRIMARY KEY (party_id, alias)
);

CREATE INDEX IF NOT EXISTS idx_party_alias ON party_alias(alias);

CREATE TABLE IF NOT EXISTS account_alias (
    account_id TEXT NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    alias      TEXT NOT NULL,
    PRIMARY KEY (account_id, alias)
);

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
    user_id           TEXT NOT NULL DEFAULT
                      COALESCE(NULLIF(current_setting('finto.user_id', true), ''), 'owner')
                      REFERENCES app_user(id),
    as_of_date        TEXT NOT NULL,
    scheme            TEXT NOT NULL,          -- 'hsbc_mpf'
    currency          TEXT NOT NULL,
    total_value       BIGINT NOT NULL,       -- minor units
    source            TEXT NOT NULL,          -- 'xlsx','manual','statement'
    statement_file_id TEXT REFERENCES statement_file(id),
    notes             TEXT,
    created_at        TEXT NOT NULL,
    UNIQUE (scheme, as_of_date, source)
);

CREATE TABLE IF NOT EXISTS investment_subaccount_balance (
    snapshot_id  TEXT NOT NULL REFERENCES investment_snapshot(id) ON DELETE CASCADE,
    account_id   TEXT NOT NULL REFERENCES account(id),
    member_no    TEXT,                        -- issuer member account number
    balance      BIGINT NOT NULL,            -- minor units
    currency     TEXT NOT NULL,
    allocation   TEXT,                        -- decimal string fraction of total
    PRIMARY KEY (snapshot_id, account_id)
);

CREATE TABLE IF NOT EXISTS investment_holding (
    id            TEXT PRIMARY KEY,
    snapshot_id   TEXT NOT NULL REFERENCES investment_snapshot(id) ON DELETE CASCADE,
    instrument    TEXT NOT NULL,              -- constituent fund name
    units         TEXT,                       -- decimal string
    unit_price    TEXT,                       -- decimal string, in currency
    market_value  BIGINT NOT NULL,           -- minor units
    currency      TEXT NOT NULL,
    allocation    TEXT,                       -- decimal string fraction of total
    UNIQUE (snapshot_id, instrument)
);

CREATE INDEX IF NOT EXISTS idx_holding_snapshot ON investment_holding(snapshot_id);
CREATE INDEX IF NOT EXISTS idx_inv_sub_account ON investment_subaccount_balance(account_id);

CREATE TABLE IF NOT EXISTS investment_activity (
    id                TEXT PRIMARY KEY,
    account_id        TEXT NOT NULL REFERENCES account(id),
    member_no         TEXT,
    activity_date     TEXT NOT NULL,
    contribution_type TEXT NOT NULL,
    activity_type     TEXT NOT NULL CHECK (activity_type IN
                          ('regular_contribution','transfer_in','rebate')),
    amount            BIGINT NOT NULL,
    currency          TEXT NOT NULL,
    source_hash       TEXT NOT NULL,
    created_at        TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_inv_activity_account_date
    ON investment_activity(account_id, activity_date DESC);

CREATE TABLE IF NOT EXISTS pdf_template (
    template_id   TEXT PRIMARY KEY,
    institution_id TEXT NOT NULL,
    version       TEXT NOT NULL,
    source        TEXT NOT NULL,
    note          TEXT,
    body          TEXT NOT NULL,
    active        BIGINT NOT NULL DEFAULT 1,
    created_at    TEXT NOT NULL
);

-- ---------------------------------------------------------------------------
-- 11. Settings
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS setting (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

-- ---------------------------------------------------------------------------
-- 12. Import audit log
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS import_run (
    id              TEXT PRIMARY KEY,
    started_at      TEXT NOT NULL,
    finished_at     TEXT,
    files_seen      BIGINT NOT NULL DEFAULT 0,
    files_imported  BIGINT NOT NULL DEFAULT 0,
    files_skipped   BIGINT NOT NULL DEFAULT 0,
    txns_inserted   BIGINT NOT NULL DEFAULT 0,
    txns_deduped    BIGINT NOT NULL DEFAULT 0,
    errors          TEXT
);

-- These two references point to tables declared after txn, so add them once
-- all relations exist. The guards keep schema initialization idempotent.
DO $$
BEGIN
    ALTER TABLE account ADD COLUMN IF NOT EXISTS user_id TEXT;
    UPDATE account SET user_id = 'owner' WHERE user_id IS NULL;
    ALTER TABLE account ALTER COLUMN user_id SET DEFAULT 'owner';
    ALTER TABLE account ALTER COLUMN user_id SET NOT NULL;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'account_user_fk') THEN
        ALTER TABLE account ADD CONSTRAINT account_user_fk
            FOREIGN KEY (user_id) REFERENCES app_user(id);
    END IF;
    ALTER TABLE statement_file ADD COLUMN IF NOT EXISTS user_id TEXT;
    UPDATE statement_file SET user_id='owner' WHERE user_id IS NULL;
    ALTER TABLE statement_file ALTER COLUMN user_id SET DEFAULT
        COALESCE(NULLIF(current_setting('finto.user_id', true), ''), 'owner');
    ALTER TABLE statement_file ALTER COLUMN user_id SET NOT NULL;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'statement_file_user_fk') THEN
        ALTER TABLE statement_file ADD CONSTRAINT statement_file_user_fk
            FOREIGN KEY (user_id) REFERENCES app_user(id);
    END IF;
    ALTER TABLE investment_snapshot ADD COLUMN IF NOT EXISTS user_id TEXT;
    UPDATE investment_snapshot SET user_id='owner' WHERE user_id IS NULL;
    ALTER TABLE investment_snapshot ALTER COLUMN user_id SET DEFAULT
        COALESCE(NULLIF(current_setting('finto.user_id', true), ''), 'owner');
    ALTER TABLE investment_snapshot ALTER COLUMN user_id SET NOT NULL;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'investment_snapshot_user_fk') THEN
        ALTER TABLE investment_snapshot ADD CONSTRAINT investment_snapshot_user_fk
            FOREIGN KEY (user_id) REFERENCES app_user(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'txn_transfer_group_fk') THEN
        ALTER TABLE txn ADD CONSTRAINT txn_transfer_group_fk
            FOREIGN KEY (transfer_group_id) REFERENCES transfer_group(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'txn_installment_plan_fk') THEN
        ALTER TABLE txn ADD CONSTRAINT txn_installment_plan_fk
            FOREIGN KEY (installment_plan_id) REFERENCES installment_plan(id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_account_user ON account(user_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_statement_content_fingerprint
    ON statement_file(user_id, content_fingerprint)
    WHERE content_fingerprint IS NOT NULL;

-- Account ACLs are enforced in PostgreSQL, including for queries that access a
-- child table directly. CLI/admin connections explicitly enable the bypass;
-- API connections clear it and set finto.user_id from the signed session.
INSERT INTO account_acl (account_id, user_id, access_role, granted_at, granted_by)
SELECT id, user_id, 'owner', CURRENT_TIMESTAMP::text, user_id FROM account
ON CONFLICT (account_id, user_id) DO UPDATE SET access_role='owner';

CREATE OR REPLACE FUNCTION finto_account_access(target_account TEXT, required_role TEXT)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
AS $$
    SELECT current_setting('finto.bypass_acl', true) = '1'
       OR EXISTS (
            SELECT 1 FROM account_acl acl
             WHERE acl.account_id = target_account
               AND acl.user_id = current_setting('finto.user_id', true)
               AND CASE acl.access_role WHEN 'owner' THEN 3 WHEN 'editor' THEN 2 ELSE 1 END
                   >= CASE required_role WHEN 'owner' THEN 3 WHEN 'editor' THEN 2 ELSE 1 END
       )
$$;

CREATE OR REPLACE FUNCTION finto_txn_access(target_txn TEXT, required_role TEXT)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
AS $$
    SELECT current_setting('finto.bypass_acl', true) = '1'
       OR EXISTS (SELECT 1 FROM txn WHERE id=target_txn
                  AND finto_account_access(account_id, required_role))
$$;

CREATE OR REPLACE FUNCTION finto_statement_access(target_statement TEXT, required_role TEXT)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
AS $$
    SELECT current_setting('finto.bypass_acl', true) = '1'
       OR EXISTS (
            SELECT 1 FROM statement_file sf
             WHERE sf.id=target_statement
               AND (finto_account_access(sf.account_id, required_role)
                    OR EXISTS (SELECT 1 FROM txn t WHERE t.statement_file_id=sf.id
                               AND finto_account_access(t.account_id, required_role))
                    OR EXISTS (SELECT 1 FROM balance_assertion b WHERE b.statement_file_id=sf.id
                               AND finto_account_access(b.account_id, required_role)))
       )
$$;

ALTER TABLE account ENABLE ROW LEVEL SECURITY;
ALTER TABLE account FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS finto_acl ON account;
CREATE POLICY finto_acl ON account
    USING (finto_account_access(id, 'viewer'))
    WITH CHECK (finto_account_access(id, 'owner'));

ALTER TABLE txn ENABLE ROW LEVEL SECURITY;
ALTER TABLE txn FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS finto_acl ON txn;
CREATE POLICY finto_acl ON txn
    USING (finto_account_access(account_id, 'viewer'))
    WITH CHECK (finto_account_access(account_id, 'editor'));

ALTER TABLE card ENABLE ROW LEVEL SECURITY;
ALTER TABLE card FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS finto_acl ON card;
CREATE POLICY finto_acl ON card
    USING (finto_account_access(account_id, 'viewer'))
    WITH CHECK (finto_account_access(account_id, 'editor'));

ALTER TABLE account_currency ENABLE ROW LEVEL SECURITY;
ALTER TABLE account_currency FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS finto_acl ON account_currency;
CREATE POLICY finto_acl ON account_currency
    USING (finto_account_access(account_id, 'viewer'))
    WITH CHECK (finto_account_access(account_id, 'editor'));

ALTER TABLE statement_file ENABLE ROW LEVEL SECURITY;
ALTER TABLE statement_file FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS finto_acl ON statement_file;
CREATE POLICY finto_acl ON statement_file
    USING (current_setting('finto.bypass_acl', true) = '1'
           OR user_id=current_setting('finto.user_id', true)
           OR finto_account_access(account_id, 'viewer'))
    WITH CHECK (current_setting('finto.bypass_acl', true) = '1'
                OR (user_id=current_setting('finto.user_id', true)
                    AND (account_id IS NULL OR finto_account_access(account_id, 'editor'))));

ALTER TABLE raw_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE raw_record FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS finto_acl ON raw_record;
CREATE POLICY finto_acl ON raw_record
    USING (finto_statement_access(statement_file_id, 'viewer'))
    WITH CHECK (finto_statement_access(statement_file_id, 'editor'));

ALTER TABLE txn_tag ENABLE ROW LEVEL SECURITY;
ALTER TABLE txn_tag FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS finto_acl ON txn_tag;
CREATE POLICY finto_acl ON txn_tag
    USING (finto_txn_access(txn_id, 'viewer'))
    WITH CHECK (finto_txn_access(txn_id, 'editor'));

ALTER TABLE tag_definition ENABLE ROW LEVEL SECURITY;
ALTER TABLE tag_definition FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS finto_acl ON tag_definition;
CREATE POLICY finto_acl ON tag_definition
    USING (current_setting('finto.bypass_acl', true)='1'
           OR user_id=current_setting('finto.user_id', true))
    WITH CHECK (current_setting('finto.bypass_acl', true)='1'
                OR user_id=current_setting('finto.user_id', true));

ALTER TABLE tag_alias ENABLE ROW LEVEL SECURITY;
ALTER TABLE tag_alias FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS finto_acl ON tag_alias;
CREATE POLICY finto_acl ON tag_alias
    USING (current_setting('finto.bypass_acl', true)='1'
           OR user_id=current_setting('finto.user_id', true))
    WITH CHECK (current_setting('finto.bypass_acl', true)='1'
                OR user_id=current_setting('finto.user_id', true));

ALTER TABLE merchant_definition ENABLE ROW LEVEL SECURITY;
ALTER TABLE merchant_definition FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS finto_acl ON merchant_definition;
CREATE POLICY finto_acl ON merchant_definition
    USING (current_setting('finto.bypass_acl', true)='1'
           OR user_id=current_setting('finto.user_id', true))
    WITH CHECK (current_setting('finto.bypass_acl', true)='1'
                OR user_id=current_setting('finto.user_id', true));

ALTER TABLE merchant_alias ENABLE ROW LEVEL SECURITY;
ALTER TABLE merchant_alias FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS finto_acl ON merchant_alias;
CREATE POLICY finto_acl ON merchant_alias
    USING (current_setting('finto.bypass_acl', true)='1'
           OR user_id=current_setting('finto.user_id', true))
    WITH CHECK (current_setting('finto.bypass_acl', true)='1'
                OR user_id=current_setting('finto.user_id', true));

ALTER TABLE txn_detail ENABLE ROW LEVEL SECURITY;
ALTER TABLE txn_detail FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS finto_acl ON txn_detail;
CREATE POLICY finto_acl ON txn_detail
    USING (finto_txn_access(txn_id, 'viewer'))
    WITH CHECK (finto_txn_access(txn_id, 'editor'));

ALTER TABLE txn_annotation ENABLE ROW LEVEL SECURITY;
ALTER TABLE txn_annotation FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS finto_acl ON txn_annotation;
CREATE POLICY finto_acl ON txn_annotation
    USING (finto_txn_access(txn_id, 'viewer'))
    WITH CHECK (finto_txn_access(txn_id, 'editor'));

ALTER TABLE installment_plan ENABLE ROW LEVEL SECURITY;
ALTER TABLE installment_plan FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS finto_acl ON installment_plan;
CREATE POLICY finto_acl ON installment_plan
    USING (finto_account_access(account_id, 'viewer'))
    WITH CHECK (finto_account_access(account_id, 'editor'));

ALTER TABLE installment_candidate ENABLE ROW LEVEL SECURITY;
ALTER TABLE installment_candidate FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS finto_acl ON installment_candidate;
CREATE POLICY finto_acl ON installment_candidate
    USING (finto_account_access(account_id, 'viewer'))
    WITH CHECK (finto_account_access(account_id, 'editor'));

ALTER TABLE balance_assertion ENABLE ROW LEVEL SECURITY;
ALTER TABLE balance_assertion FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS finto_acl ON balance_assertion;
CREATE POLICY finto_acl ON balance_assertion
    USING (finto_account_access(account_id, 'viewer'))
    WITH CHECK (finto_account_access(account_id, 'editor'));

ALTER TABLE reconciliation_check ENABLE ROW LEVEL SECURITY;
ALTER TABLE reconciliation_check FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS finto_acl ON reconciliation_check;
CREATE POLICY finto_acl ON reconciliation_check
    USING (finto_account_access(account_id, 'viewer'))
    WITH CHECK (finto_account_access(account_id, 'editor'));

ALTER TABLE account_alias ENABLE ROW LEVEL SECURITY;
ALTER TABLE account_alias FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS finto_acl ON account_alias;
CREATE POLICY finto_acl ON account_alias
    USING (finto_account_access(account_id, 'viewer'))
    WITH CHECK (finto_account_access(account_id, 'editor'));

ALTER TABLE investment_subaccount_balance ENABLE ROW LEVEL SECURITY;
ALTER TABLE investment_subaccount_balance FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS finto_acl ON investment_subaccount_balance;
CREATE POLICY finto_acl ON investment_subaccount_balance
    USING (finto_account_access(account_id, 'viewer'))
    WITH CHECK (finto_account_access(account_id, 'editor'));

ALTER TABLE transfer_leg ENABLE ROW LEVEL SECURITY;
ALTER TABLE transfer_leg FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS finto_acl ON transfer_leg;
CREATE POLICY finto_acl ON transfer_leg
    USING (finto_txn_access(txn_id, 'viewer'))
    WITH CHECK (finto_txn_access(txn_id, 'editor'));

ALTER TABLE transfer_candidate ENABLE ROW LEVEL SECURITY;
ALTER TABLE transfer_candidate FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS finto_acl ON transfer_candidate;
CREATE POLICY finto_acl ON transfer_candidate
    USING (finto_txn_access(out_txn_id, 'viewer')
           AND finto_txn_access(in_txn_id, 'viewer'))
    WITH CHECK (finto_txn_access(out_txn_id, 'editor')
                AND finto_txn_access(in_txn_id, 'editor'));

ALTER TABLE duplicate_candidate ENABLE ROW LEVEL SECURITY;
ALTER TABLE duplicate_candidate FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS finto_acl ON duplicate_candidate;
CREATE POLICY finto_acl ON duplicate_candidate
    USING (finto_txn_access(keep_txn_id, 'viewer')
           AND finto_txn_access(dupe_txn_id, 'viewer'))
    WITH CHECK (finto_txn_access(keep_txn_id, 'editor')
                AND finto_txn_access(dupe_txn_id, 'editor'));

ALTER TABLE investment_snapshot ENABLE ROW LEVEL SECURITY;
ALTER TABLE investment_snapshot FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS finto_acl_select ON investment_snapshot;
CREATE POLICY finto_acl_select ON investment_snapshot FOR SELECT
    USING (current_setting('finto.bypass_acl', true) = '1'
           OR user_id=current_setting('finto.user_id', true)
           OR EXISTS (
        SELECT 1 FROM investment_subaccount_balance b
         WHERE b.snapshot_id=investment_snapshot.id
           AND finto_account_access(b.account_id, 'viewer')
    ));
DROP POLICY IF EXISTS finto_acl_write ON investment_snapshot;
CREATE POLICY finto_acl_write ON investment_snapshot
    FOR ALL
    USING (current_setting('finto.bypass_acl', true) = '1'
           OR user_id=current_setting('finto.user_id', true))
    WITH CHECK (current_setting('finto.bypass_acl', true) = '1'
                OR user_id=current_setting('finto.user_id', true));

ALTER TABLE investment_holding ENABLE ROW LEVEL SECURITY;
ALTER TABLE investment_holding FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS finto_acl_select ON investment_holding;
CREATE POLICY finto_acl_select ON investment_holding FOR SELECT
    USING (current_setting('finto.bypass_acl', true) = '1' OR EXISTS (
        SELECT 1 FROM investment_subaccount_balance b
         WHERE b.snapshot_id=investment_holding.snapshot_id
           AND finto_account_access(b.account_id, 'viewer')
    ));
DROP POLICY IF EXISTS finto_acl_write ON investment_holding;
CREATE POLICY finto_acl_write ON investment_holding
    FOR ALL
    USING (current_setting('finto.bypass_acl', true) = '1' OR EXISTS (
        SELECT 1 FROM investment_snapshot s
         WHERE s.id=investment_holding.snapshot_id
           AND s.user_id=current_setting('finto.user_id', true)
    ))
    WITH CHECK (current_setting('finto.bypass_acl', true) = '1' OR EXISTS (
        SELECT 1 FROM investment_snapshot s
         WHERE s.id=investment_holding.snapshot_id
           AND s.user_id=current_setting('finto.user_id', true)
    ));

ALTER TABLE investment_activity ENABLE ROW LEVEL SECURITY;
ALTER TABLE investment_activity FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS finto_acl ON investment_activity;
CREATE POLICY finto_acl ON investment_activity
    USING (finto_account_access(account_id, 'viewer'))
    WITH CHECK (finto_account_access(account_id, 'editor'));

-- The database owner (and test superusers) can bypass RLS. Web requests switch
-- to this non-login role so the policies remain authoritative in every hosting
-- environment. The provisioning role retains direct maintenance access.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='finto_api_rls') THEN
        CREATE ROLE finto_api_rls NOLOGIN NOBYPASSRLS;
    END IF;
    EXECUTE format('GRANT finto_api_rls TO %I', current_user);
    EXECUTE format('GRANT USAGE ON SCHEMA %I TO finto_api_rls', current_schema());
    EXECUTE format(
        'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO finto_api_rls',
        current_schema()
    );
    EXECUTE format(
        'GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %I TO finto_api_rls',
        current_schema()
    );
    EXECUTE format(
        'GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA %I TO finto_api_rls',
        current_schema()
    );
END $$;
