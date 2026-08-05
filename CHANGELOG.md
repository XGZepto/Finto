# Changelog

## 0.2.1 — 2026-08-05

### LLM analysis

- Added Anthropic prompt-prefix caching with cache usage reporting.
- Added a bounded read-only ledger tool loop for totals, summaries,
  transactions, positions, and money flows.
- Added concise generated answers backed by visible tool filters and audited
  query records.
- Split the fast classification model from the higher-capability analysis
  model and added deployment configuration overrides.

## 0.2.0 — 2026-08-05

### Data and storage

- Replaced the SQLite runtime with PostgreSQL and added a verified one-time
  migration utility.
- Added users, account ownership, account ACLs, and PostgreSQL row-level
  security.
- Added managed category, tag, merchant, and alias taxonomies with audited
  backfill operations.
- Improved transfer, credit-card payment, refund, instalment, statement
  freshness, and empty-statement handling.
- Corrected completed instalment plans that were paid early.
- Added reporting-currency normalization and excluded transfers internal to a
  report scope from aggregate net flow.

### Authentication and operations

- Added the application login screen and persistent cookie sessions.
- Added revocable per-user API keys for taxonomy audit and apply operations.
- Added PostgreSQL administration and migration scripts.
- Added Vercel deployment configuration for the Tokyo region.

### Web application

- Added account hierarchy, account detail reporting, aggregate positions, and
  Sankey flow views.
- Added chart/list switches for mobile flow views and configurable reporting
  currency.
- Reworked navigation, filters, inspectors, controls, masonry account cards,
  loading states, and responsive layouts.
- Added profile and settings pages, theme and language preferences, unified
  branding, and PWA assets.

### Repository

- Replaced development handover and schema-review notes with operator-focused
  documentation.
- Expanded API, data-layer, authentication, taxonomy, and regression tests.
