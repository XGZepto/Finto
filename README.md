# Finto

Finto is a PostgreSQL-backed personal finance ledger for bank, credit-card,
multi-currency, and investment statements. It includes statement ingestion,
reconciliation, reporting, account-level access control, a responsive web
application, and a native Android app.

No financial data or production credentials are stored in this repository.

## Live demo

Open [demo.finto.zepto.page](https://demo.finto.zepto.page) and sign in with:

- Username: `demo`
- Password: `finto-demo-2026`

The account is read-only and contains invented transactions. Its isolated data
is regenerated daily and whenever the seed code changes on `main`.

## Capabilities

- Registry-driven CSV and PDF transaction-statement ingestion
- XLSX investment-position snapshots through the investment CLI
- Duplicate detection and statement-source precedence
- Internal transfer, credit-card payment, refund, and instalment linkage
- Account, product, subaccount, cardholder, and investment views
- Native-currency reporting and configurable reporting-currency conversion
- PostgreSQL row-level security with viewer, editor, and owner account roles
- Password sessions and revocable per-user API keys
- Installable PWA with desktop and mobile layouts
- Native Android app (Kotlin, Jetpack Compose, Material 3) with the web
  app's features: summary, blotter and editing, reports, accounts, recurring,
  Ask, import, instalments, investments, ledger health and settings
- Read-only LLM analysis with allowlisted ledger tools and prompt caching
- Vercel deployment in the Tokyo region

The Import page lists every active format directly from the parser and PDF
template registries. See the wiki's [statement-format guide](https://github.com/XGZepto/Finto/wiki/Statement-Formats)
to add or request a format.

## Architecture

```text
Angular web application      Android app
              │                  │
              ▼                  ▼
            FastAPI application
                     │
                     ▼
                PostgreSQL
```

This repository holds all three. The Python API and the Angular app deploy
together to Vercel. The Android app is a separate Gradle project in
[`android/`](android/README.md) that uses the same HTTP API and session
cookie, and has its own CI workflow.

Money is stored as integer minor units. Every transaction uses one sign
convention: negative is money out and positive is money in. Native and booked
currency values remain separate. Converted totals require an explicit reporting
currency and dated FX rates.

## Requirements

- Python 3.12
- Node.js 22
- PostgreSQL 17 or a compatible managed PostgreSQL service
- `psql` for direct database administration
- JDK 21 and the Android SDK, only for the Android app

## Local setup

```bash
python -m venv .venv
.venv/bin/pip install -e ".[dev,pdf,xlsx,api]"
npm --prefix web ci

export DATABASE_URL='postgresql://...'
.venv/bin/finto init

cp accounts.example.yaml accounts.yaml
FINTO_AUTH_PASSWORD='use-a-unique-password' \
  .venv/bin/finto users bootstrap \
  --username owner \
  --email owner@example.com
.venv/bin/finto accounts load accounts.yaml --owner owner
```

Finto has no registration route. Create users from the CLI and grant account
access explicitly.

Run the API and web development server in separate terminals:

```bash
export DATABASE_URL='postgresql://...'
export FINTO_SESSION_SECRET='use-a-long-random-value'
.venv/bin/uvicorn fin.api.app:app --reload --port 8000
```

```bash
npm --prefix web start
```

The Angular development server is available at `http://localhost:4200` and
proxies API requests to `http://localhost:8000`.

## Import workflow

Inspect a new statement format without changing the database:

```bash
.venv/bin/finto sniff statement.csv \
  --institution hsbc_hk \
  --currency HKD
```

Import, reconcile, and verify the ledger:

```bash
.venv/bin/finto import statement.csv \
  --institution hsbc_hk \
  --account hsbc_hk_current
.venv/bin/finto reconcile
.venv/bin/finto check
```

In the web app, a preview also checks the statement against the ledger
(already imported, account, rows already present, gaps and overlaps with
earlier statements, dates, currencies, signs) and, with the LLM layer on, asks
Claude for a go-ahead. After the import, a review categorises, names and tags
the new rows, checks which payments found their other leg and summarises what
is left to do. Agents can run the same review with
`POST /api/agent/imports/{statement_file_id}/review`.

An empty statement is retained as evidence that the period was checked. It does
not make an inactive account overdue or create synthetic transactions.

## PostgreSQL administration

The canonical schema is [`fin/schema.sql`](fin/schema.sql). `scripts/psql.sh`
uses the first available variable in this order:

1. `POSTGRES_URL_NON_POOLING`
2. `POSTGRES_URL`
3. `DATABASE_URL`

```bash
scripts/psql.sh --single-transaction -f fin/schema.sql
scripts/psql.sh -c 'select count(*) from v_ledger'
```

The one-time SQLite migration tool copies supported tables, verifies row counts
and content digests, and exits without committing if verification fails:

```bash
export POSTGRES_URL_NON_POOLING='postgresql://...'
python scripts/migrate_sqlite_to_postgres.py /path/to/legacy.sqlite --reset
```

After a successful migration, use PostgreSQL only. Runtime code has no SQLite
fallback.

See the wiki's [operations guide](https://github.com/XGZepto/Finto/wiki/Operations)
for user administration, backups, maintenance, and deployment.

The compact interaction and visual rules, QA matrix, and screenshot gallery are
in the wiki's [mobile experience guide](https://github.com/XGZepto/Finto/wiki/Mobile-Experience).
With the populated local app running, generate local responsive evidence with:

```bash
npm --prefix web run capture:mobile
npm --prefix web run capture:sanity
```

## Taxonomy maintenance

Categories, tags, merchants, and their aliases are stored in managed taxonomy
tables. Audit proposed changes before applying them:

```bash
.venv/bin/finto taxonomy audit --user owner
.venv/bin/finto taxonomy audit --user owner --apply
```

Users can mint and revoke maintenance API keys in Settings. A generated key is
shown once and stored only as a SHA-256 digest.

```bash
export FINTO_URL='https://finto.example.com'
export FINTO_API_KEY='finto_...'

curl -fsS \
  -H "Authorization: Bearer $FINTO_API_KEY" \
  "$FINTO_URL/api/agent/taxonomy/audit"

curl -fsS -X POST \
  -H "Authorization: Bearer $FINTO_API_KEY" \
  -H 'X-Finto-Confirm: apply-taxonomy' \
  "$FINTO_URL/api/agent/taxonomy/apply"

curl -fsS -X POST \
  -H "Authorization: Bearer $FINTO_API_KEY" \
  "$FINTO_URL/api/agent/ledger/rebuild-transfers?month=2026-07&start_day=24&end_day=24"
```

Statement agents use `/api/agent/imports/preview` and
`/api/agent/imports/confirm`: confirm re-uploads the same PDF with the preview's
`sha256`. MPF/eMPF bundles use `/api/agent/investments/imports/preview` and
`/api/agent/investments/imports/confirm`. Their multipart `analysis_mode` may be
`auto` (deterministic parser with validated LLM fallback), `deterministic`, or
`llm`. Every mode must reconcile the aggregate, all subaccounts, and all fund
holdings exactly before confirm can write.

Agents can verify committed state through:

```text
GET /api/agent/imports/history
GET /api/agent/investments
GET /api/agent/investments/{snapshot_id}
GET /api/agent/investments/activities
GET /api/agent/integrity
```

Each maintenance operation is recorded in PostgreSQL. API keys are scoped to
their owner and can be revoked without changing the login password.

## LLM analysis

The optional Ask page uses a bounded set of read-only reporting tools. The
model cannot issue SQL or modify the ledger. Tool results supply all financial
figures; the generated answer and executed filters are returned together.

Anthropic prompt-prefix caching covers the tool definitions, system
instructions, and current ledger vocabulary. PostgreSQL decision caching remains
separate and is used for deterministic categorisation and query audit records.

Classification (merchant names, categories and tags) defaults to Claude
Sonnet 5.5 at low effort; analysis (Ask and the import review) defaults to
Claude Opus 5.5 at medium effort. Both use the server-side refusal fallback,
so a false-positive decline on a statement line is retried on another model
inside the same request. No sampling parameters are sent: current models accept
only the defaults and the anthropic 2.x SDK rejects them. Complete answers are
reused while the authenticated user's ledger revision is unchanged. The web
client renders the answer as structured text, not raw Markdown. Each role can be
configured independently:

```bash
finto config set llm_enabled 1
finto config set llm_model claude-sonnet-5-5
finto config set llm_agent_model claude-opus-5-5
```

Deployments can use `FINTO_LLM_ENABLED`, `FINTO_LLM_MODEL`, and
`FINTO_LLM_AGENT_MODEL` instead of database settings.

## Tests

```bash
.venv/bin/pytest -q
npm --prefix web test
npm --prefix web run build
```

```bash
cd android && ./gradlew testDebugUnitTest lintDebug
```

## Deployment

The production build is configured by [`vercel.json`](vercel.json). It builds
the Angular application, routes `/api/*` to FastAPI, serves the PWA assets, and
runs the function in Vercel's `hnd1` region.

Required production variables:

- `POSTGRES_URL` or `DATABASE_URL`
- `POSTGRES_URL_NON_POOLING` for schema administration
- `FINTO_SESSION_SECRET`
- `FINTO_AUTH_USERNAME`, `FINTO_AUTH_EMAIL`, and `FINTO_AUTH_PASSWORD` for the
  initial owner bootstrap when the database has no users

Pull requests and non-production branch pushes continue to create Vercel
previews. Pushing a semantic release tag such as `v0.3.1` runs
`.github/workflows/release-production.yml`, builds with the Vercel production
environment, and deploys the tagged commit to Vercel Production.

The workflow requires the `VERCEL_TOKEN`, `VERCEL_ORG_ID`, and
`VERCEL_PROJECT_ID` repository secrets. A manual production deployment remains
available with `vercel deploy --prod`.

Every push to `main` also deploys to the `finto` project's `demo` custom
environment, which tracks `main` and serves `demo.finto.zepto.page`. Its own
variables pin every connection to the guarded `finto_demo` schema
(`PGOPTIONS=-c search_path=finto_demo`) and enable the demo seed, so it never
reads the live ledger. Run `.github/workflows/demo-check.yml` to verify the
public demo login and account data.

Apply database migrations before deploying code that depends on them. Do not
place database URLs or API keys in committed files.

## Repository layout

```text
api/                     Vercel FastAPI entry point
fin/                     ledger, parsers, reconciliation, reporting, API
fin/api/routers/         HTTP route modules
fin/pdf/templates/       declarative PDF statement templates
scripts/                 PostgreSQL and migration utilities
tests/                   unit, integration, and regression tests
web/                     Angular application and PWA assets
android/                 native Android app (Gradle project; see android/README.md)
accounts.example.yaml    account configuration example
vercel.json              production build and routing configuration
```

Release history is in [`CHANGELOG.md`](CHANGELOG.md). HTTP authentication and
maintenance endpoints are documented in the wiki's [API guide](https://github.com/XGZepto/Finto/wiki/API).
