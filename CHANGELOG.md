# Changelog

## Unreleased

## 0.4.10 — 2026-08-25

- Collapse page load into one `status` (`loading` | `ok` | `failed`) instead of
  two booleans that could both be true.
- Ignore stale in-flight responses when a page reloads (currency, filter, queue,
  snapshot, account detail, instalment schedule).
- Recurring fails only when both tag requests fail and no rows arrived; merge
  those requests with `forkJoin` instead of a pending counter.
- Investment snapshot list success no longer becomes a page error if opening
  the first valuation fails.
- Drop overlapping Summary effects; one effect owns currency and refresh.
  Reports and Recurring follow the same rule.
- Parse filter URLs into `LedgerFilter` without `as any`.
- Accounts hierarchy: back from a subaccount goes to the product group;
  the sibling strip names the group and shows balances, not just a currency.
- Recent activity on Accounts (and Recurring charge history) opens the blotter
  on that transaction. The blotter consumes a `txn` query param once.
- Leaf account analysis matches the group page: category spend, type as a
  secondary heading, and the same money format.
- Group and leaf pages show a fail banner when balances or activity cannot load.

## 0.4.9 — 2026-08-24

- Show a banner when a page's API load fails, instead of an empty shell.
- Restore the net-worth time series on Accounts.
- Close remaining compact-layout gaps: tab-bar inset, sticky filters with
  visible chips, stacked import metadata, Review installment groups, and
  44px segment and date targets.
- Mark segmented controls as toggle groups for assistive tech.

## 0.4.8 — 2026-08-24

- Size overlay menus to the trigger, capped at 360px. `min-width: 100%` of
  the overlay host made Account search as wide as the blotter.
- Use one control height: 36px on desktop and 44px under 880px, including
  filter chips and Clear all.
- Put Recurring and Ask in the desktop Money group; More stays the tools
  overflow. Phone tabs are unchanged.
- Use the desktop Summary row: spend beside In/Net/Saved, a taller flow
  chart, and the ranking next to the donut instead of a 760px strip.
- Fill the Reports row the same way: totals across the card, month bars
  that span the plot, and a two-column ranking.
- Open the blotter on the clicked flow month. The chart was sending that
  month as a category, which matched nothing.
- Render Ask answers as structured paragraphs, lists, headings, and emphasis;
  replace the implementation badges with a compact analysis-scope card.
- Reduce Ask latency with Haiku for the bounded tool loop and reuse complete
  answers while the authenticated user's ledger revision is unchanged.

## 0.4.7 — 2026-08-21

- Render select and date menus on a root overlay host (the usual portal
  pattern) so the first click is not clipped by the blotter scroll pane.

## 0.4.6 — 2026-08-21

- Put blotter totals on the same row as Converted/Native and drop the dangling
  Amounts label.

## 0.4.5 — 2026-08-21

- Render filter select and date menus on `document.body` with inline fixed
  positioning so the filter panel cannot clip the option list.

## 0.4.4 — 2026-08-21

- Pin desktop select and date menus to the viewport so the blotter scroll pane
  cannot clip them, and let the content column fill the window so the scrollbar
  sits on the right edge.

## 0.4.3 — 2026-08-21

- Keep blotter infinite scroll moving on phones: paginate transaction ids
  before attaching details, skip repeated aggregates on later pages, and only
  show the tail spinner while a page is actually in flight.

## 0.4.2 — 2026-08-21

- Recreate the ledger view when adding derived transaction columns so existing
  PostgreSQL installations migrate without changing an established view shape.

## 0.4.1 — 2026-08-21

- Materialise non-raw transaction search text so PostgreSQL can use one trigram
  index instead of repeatedly scanning RLS-protected transaction detail rows.
- Serve stable filter vocabularies from canonical catalogs instead of full
  ledger scans.

## 0.4.0 — 2026-08-20

- Add validated LLM fallback and force modes for changing HSBC MPF/eMPF PDF
  layouts while retaining exact balance and holding reconciliation.
- Expose bearer-key import history, investment snapshot/activity reads, and
  integrity verification for end-to-end agent ledger maintenance.
- Accelerate transaction search with PostgreSQL trigram indexes, exclude noisy
  raw PDF lines from user search, and reduce transaction/facet query round trips.
- Reuse currency rates across position conversion and avoid repeated net-worth
  conversion queries during account-page loading.

## 0.3.5 — 2026-08-20

- Bound existing-statement reprocessing to its transaction period so production
  reconciliation completes within a serverless request.

## 0.3.4 — 2026-08-20

- Read card metadata from normalized transaction details when reprocessing
  existing statements, restoring scoped card re-attribution.

## 0.3.3 — 2026-08-20

- Make repeated balance figures within consolidated statements idempotent so
  verified Chase checking/savings PDFs import without a uniqueness failure.

## 0.3.2 — 2026-08-20

- Replace server-local upload staging and background import jobs with stateless
  preview/confirm uploads and bounded synchronous reconciliation.
- Detect semantically identical statements even when their PDF bytes differ.
- Correct HSBC supplementary-card attribution and preserve installment fees
  outside principal plans.
- Import and reconcile HSBC MPF Member Returns, Account Returns, and
  Contribution History PDF bundles, including idempotent investment activity.
- Add direct browser and API-key import flows with MPF bundle previews.

## 0.3.1 — 2026-08-19

- Reload, remount, or refetch the current route when an installed PWA returns
  to the foreground.
- Tighten compact sheet headers, option rows, and the tab bar.

## 0.3.0 — 2026-08-14

- Add exact multi-month report selection with aggregate totals, breakdowns,
  accessible chart state, and matching Blotter drill-down.
- Add investment-specific scheme and member-account pages with valuation
  history, previous-valuation change, and fund allocation.
- Complete the compact mobile experience audit across navigation, selectors,
  transaction detail, account hierarchy, charts, settings, and light mode.
- Standardise mobile account gutters and remove decorative scroll-edge cues.
- Publish responsive mobile and desktop evidence in the project wiki.
- Deploy semantic release tags to Vercel Production through GitHub Actions.

- Complete the compact mobile uplift with surface-led cards, a stronger ledger
  row hierarchy, human date bands, and exception-only currency labels.
- Lead Summary with month-to-date spend versus the same elapsed period last
  month; move net worth and its asset/liability context to Accounts.
- Add confidence-gated category suggestions, one-tap confirmation, review-state
  indicators, and a filtered review-progress denominator.
- Present compact selects and calendars as bottom sheets with 44px targets.
- Animate first-reveal charts and route changes while honoring reduced motion.
- Add repeatable 390×844 screenshot capture plus compact/desktop UI checks.
- Add a spacing and type scale, and draw component padding and rhythm from it.
- Place net worth with its asset/liability context on Accounts.
- Add a net-worth-over-time series evaluated through the positions rollup.
- Show asset and liability composition with amounts, replacing the Positions card.
- Band blotter rows by day, each carrying its own total.
- Separate the time bucket from the entity dimension in the Summary controls.
- Ask the model for transaction tags and apply them to already-categorised rows.
- Bound the LLM backfill per request and step chunks past merchants already ruled on.
- Stop caching non-2xx API responses.
- Keep app chrome fixed while only content scrolls.
- Preserve blotter scroll position when the inspector closes.
- Remove storage-engine and session-status copy from the interface.

## 0.2.7 — 2026-08-05

- Generate Import format support from the parser and PDF-template registries.
- Use active PostgreSQL PDF templates during preview and import.
- Add format contribution documentation and request links.
- Include investment valuations in aggregate positions and net worth.
- Correct short-page mobile layout, navigation grouping, and shared control sizing.
- Fix Ask on Claude 5 and return specific analysis-service errors.

## 0.2.6 — 2026-08-05

- Add bounded ledger read and audited transfer-maintenance routes for user-minted API keys.

## 0.2.5 — 2026-08-05

- Link exact near-date transfers when the receiving leg, rather than the debit,
  carries a recognised owner name.
- Remove a stale transaction-specific lookup from bounded transfer maintenance.

## 0.2.4 — 2026-08-05

- Separated the public connectivity check from the authenticated review-count
  request so the login shell no longer reports an expected 401 as an outage.

## 0.2.3 — 2026-08-05

- Removed the authenticated database dependency from the public health handler.

## 0.2.2 — 2026-08-05

- Made the data-free health endpoint available before login so the login shell
  reports API availability correctly.

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
