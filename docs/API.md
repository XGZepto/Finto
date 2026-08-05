# HTTP API

All application routes use the `/api` prefix. `/api/health` and login are
public; ledger routes require authentication.

## Browser sessions

`POST /api/auth/login` accepts a username or email and a password. A successful
login sets an HTTP-only signed cookie. The web application checks the session
with `GET /api/auth/me` and ends it with `POST /api/auth/logout`.

Session cookies use `Secure` in production and persist across browser restarts.
The server validates the user on each authenticated request.

## API keys

Authenticated users manage their own keys with:

| Method | Route | Action |
|---|---|---|
| `GET` | `/api/auth/api-keys` | List active keys and metadata |
| `POST` | `/api/auth/api-keys` | Create a key; plaintext is returned once |
| `DELETE` | `/api/auth/api-keys/{key_id}` | Revoke a key |

Send a maintenance key as a bearer token:

```http
Authorization: Bearer finto_...
```

Maintenance routes:

| Method | Route | Scope | Additional header |
|---|---|---|---|
| `GET` | `/api/agent/taxonomy/audit` | `taxonomy:read` | — |
| `POST` | `/api/agent/taxonomy/apply` | `taxonomy:write` | `X-Finto-Confirm: apply-taxonomy` |
| `GET` | `/api/agent/ledger/transactions` | `ledger:read` | — |
| `POST` | `/api/agent/ledger/rebuild-transfers` | `ledger:write` | — |

Transfer rebuilds require `month=YYYY-MM`; optional `start_day` and `end_day`
bound the work within that month. Ledger reads require `date_from` and `date_to`
and accept ranges up to 31 days. Every write records its user, key, action,
outcome, and timestamp.

## Access control

Browser requests set the authenticated user context on the PostgreSQL
connection. PostgreSQL row-level security restricts account-linked records to
accounts granted through `account_acl`. Write routes also check the user's ACL
role.

Maintenance keys inherit their owner's account scope. They do not bypass row-
level security.

## Main route groups

| Route group | Content |
|---|---|
| `/api/summary` | Aggregated positions, flow, and freshness data |
| `/api/accounts` | Products, subaccounts, positions, and account detail |
| `/api/transactions` | Ledger rows, filters, details, and edits |
| `/api/imports` | Statement import status and history |
| `/api/review` | Duplicate and transfer review queues |
| `/api/installments` | Instalment plans and payment state |
| `/api/investments` | Position snapshots and holdings |
| `/api/integrity` | Reconciliation and structural checks |

The generated OpenAPI document is available at `/openapi.json` when the API is
running.
