# Operations

## Environment

| Variable | Purpose |
|---|---|
| `POSTGRES_URL` | Pooled PostgreSQL connection used by the deployed API |
| `POSTGRES_URL_NON_POOLING` | Direct PostgreSQL connection used for schema work |
| `DATABASE_URL` | Local fallback PostgreSQL connection |
| `FINTO_SESSION_SECRET` | Cookie-signing secret; required outside tests |
| `FINTO_AUTH_USERNAME` | Initial owner username when no user exists |
| `FINTO_AUTH_EMAIL` | Initial owner email when no user exists |
| `FINTO_AUTH_PASSWORD` | Initial owner password or CLI password input |
| `ANTHROPIC_API_KEY` | Optional categorisation and adjudication provider |
| `FINTO_LLM_ENABLED` | Deployment override for the optional LLM layer |
| `FINTO_LLM_MODEL` | Optional classification model override |
| `FINTO_LLM_AGENT_MODEL` | Optional Ask analysis model override |

Keep production values in the deployment environment. Do not commit `.env`
files.

## Schema

Initialize an empty database through the application:

```bash
finto init
```

Apply the canonical schema directly:

```bash
export POSTGRES_URL_NON_POOLING='postgresql://...'
scripts/psql.sh --single-transaction -f fin/schema.sql
```

`scripts/psql.sh` enables `ON_ERROR_STOP`. A failed statement terminates the
operation.

## Legacy migration

```bash
export POSTGRES_URL_NON_POOLING='postgresql://...'
python scripts/migrate_sqlite_to_postgres.py /path/to/finto.sqlite --reset
```

The migration creates the PostgreSQL schema, copies tables in dependency order,
adds owner ACL rows, and compares a content digest for every copied table.
`--reset` drops only the selected target schema. Use `--verify-only` to compare
an existing migration without writing.

## Users and access

```bash
FINTO_AUTH_PASSWORD='...' finto users bootstrap \
  --username owner --email owner@example.com

FINTO_AUTH_PASSWORD='...' finto users add \
  --username analyst --email analyst@example.com

finto users grant \
  --user <user-id> \
  --account <account-id> \
  --role viewer

finto users list
```

Roles are `viewer`, `editor`, and `owner`. User creation is not exposed through
the HTTP API.

## Ledger maintenance

```bash
finto import /path/to/statements --dry-run
finto import /path/to/statements
finto reconcile
finto check
finto taxonomy audit --user owner
```

Run the taxonomy audit without `--apply` first. The output separates proposed
updates from conflicts and leaves the database unchanged.

## API keys

Create and revoke API keys from Settings. New keys provide taxonomy and bounded
ledger-maintenance scopes for their owner. The plaintext key is returned once;
PostgreSQL stores its digest and prefix.

Use `GET /api/agent/taxonomy/audit` for a read-only audit. Applying the same
deterministic changes requires `POST /api/agent/taxonomy/apply` and the header
`X-Finto-Confirm: apply-taxonomy`.

Use `POST /api/agent/ledger/rebuild-transfers?month=YYYY-MM&start_day=D&end_day=D`
to recompute automatic links in a narrow date window. The route is audited and
enforces the key owner's account ACL.

Use `GET /api/agent/ledger/transactions?date_from=YYYY-MM-DD&date_to=YYYY-MM-DD`
to verify the affected rows. Reads are limited to 31 days and 100 rows.

## Backup and restore

Create a compressed backup with a direct connection:

```bash
pg_dump "$POSTGRES_URL_NON_POOLING" \
  --format=custom \
  --no-owner \
  --file=finto.dump
```

Restore into an empty target database:

```bash
pg_restore \
  --dbname="$POSTGRES_URL_NON_POOLING" \
  --clean \
  --if-exists \
  --no-owner \
  finto.dump
```

Confirm the target URL before using `--clean`.

## Verification

```bash
pytest -q
npm --prefix web run build
finto check
```

`finto check` validates stored balance assertions and structural ledger
invariants. Test and build commands validate application code only; they do not
replace a production backup or a ledger integrity check.

## Vercel

`vercel.json` pins the serverless function to Tokyo (`hnd1`). Deploy a preview,
check authentication and key routes, then promote a production build:

```bash
vercel deploy
vercel deploy --prod
```

Static assets are cached by Vercel. API routes set their own response caching
policy. The service worker is served with revalidation so client updates are
not held behind a stale worker script.
