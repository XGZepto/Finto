# Changelog

## Unreleased

## 0.4.34 — 2026-11-05

- Import checks a statement against the ledger before anything is written:
  already-imported files or content, which account the rows land in, rows
  already present, gaps or overlaps with the account's earlier statements,
  dates outside the period or in the future, currencies the account does not
  settle in, one-sided signs and parser warnings. Claude reads the checks and
  a sample of rows and says go ahead, review first or hold.
- After an import, Claude categorises, names and tags the new rows, card
  payments filed as spending go back under transfers, in-band transfer and
  duplicate candidates are adjudicated, and a summary lists what is left:
  payments without their other leg, rows still uncategorised, suggestions to
  decide, balance checks that disagree. Each figure opens the matching rows.
  Reviews are stored; Imported files shows each one's status and can re-run it.
- `POST /api/imports/{id}/review` (and the API-key `/api/agent/...` form),
  `GET /api/imports/{id}/review`, `review=true` on preview, and a
  `statements` ledger filter for the rows one import produced.
- Statements can be shared to the installed app from another app's share
  sheet (Android); they open on Import for the same preview.
- Claude categorisation works again. The deployment installs anthropic 2.x,
  which no longer accepts `temperature`, and every classification call had
  failed. No sampling parameters are sent now.
- Classification defaults to Claude Sonnet 5.5 and analysis (Ask, import
  review) to Claude Opus 5.5, with effort set explicitly, structured outputs
  for verdicts and summaries, refusals handled, and the server-side refusal
  fallback on. Installations still on an earlier default model move to these.
- A merchant rule can no longer file a card payment or currency swap under a
  spending category.

## 0.4.33 — 2026-11-02

- The Summary hero anchors on the last day anything was spent. An interest
  credit posted on the 1st had made an empty month look current.

## 0.4.32 — 2026-11-02

- An account's Top spending list is ordered by amount. It had been
  alphabetical, so the account's actual top category could be missing from
  its own "top" list.
- Category, month and cardholder rows on an account page open the blotter on
  the rows that make up the figure; internal transfers name the other
  account and link to it; recent activity uses the blotter's cleaned-up
  titles.
- A product's combined balance leaves empty currency pockets out of the
  headline and counts them underneath.
- Reports shows one breakdown instead of the same categories twice, with a
  share strip over the ranked rows. The monthly average is positive, like
  Out, and leaves the month in progress out; the four headline figures line
  up.
- Matching suggestions opens on the first queue with work in it, and each
  side names its account and opens in the blotter instead of printing ids.

## 0.4.31 — 2026-11-02

- The ledger's trigram search index names pg_trgm's own schema, so a schema
  whose search_path is only itself builds where the extension lives
  elsewhere. The demo had been failing to seed on every deploy with
  `operator class "gin_trgm_ops" does not exist`.

## 0.4.30 — 2026-11-02

- A transaction's sheet links onward: the account name opens the account,
  and rows below jump to everything at the same merchant, the same category,
  or the same account. Transfer and linked legs name their account instead
  of its storage id, and the sheet title matches the row's cleaned-up title.
- An account name in a blotter row filters the blotter to that account.
- Leaving the sheet for another page (an account, instalment plans) now
  lands there; the back-step that closes the sheet had been cancelling it.

## 0.4.29 — 2026-11-02

- Summary, Reports and Timeline count spending only. Card payments, FX
  conversions and transfer-categorised rows stay out even before the linker
  pairs their legs; they had put a 290k "Fees" bucket at the top of the
  ranking. The API takes this as `spendingOnly=true`, and drilling into the
  blotter carries the same flag so its total matches the figure tapped.
- `/api/summary` returns `ledger_through`, the latest transaction date in
  scope. The Summary hero follows it: when statements have not arrived for
  the new month it shows the last month with data ("Spent in October,
  through Oct 23") instead of "0.00, 100% less".
- Timeline opens in the reporting currency, uses readable labels and eight
  distinct series colours, no longer lists "other" twice, and labels every
  few months in full instead of truncating each one.
- The Summary ranking takes the donut's colours, so the donut no longer
  repeats it as a legend.
- Controls (buttons, inputs, selects, segmented controls) are set in the
  proportional face; mono stays with figures, codes and table headers.
- Haptic feedback in the installed app for tab and period changes, toggles,
  and pull-to-refresh arming and completion. It uses vibration on Android and
  the system switch tick on iOS, and can be turned off in Settings.
- Page changes cross-fade with a short rise. Filter changes within a page do
  not animate, and reduced motion turns the effect off.
- The More tab stays selected on every page it leads to.
- Recurring lists active subscriptions first. Lapsed ones move to a
  collapsed Inactive group.
- The blotter no longer repeats a description that only differs from the
  title in case, and phone Accounts read as one divided list.

## 0.4.28 — 2026-11-02

- A statement's balance counts through the statement date, so an idle
  section that prints only its opening balance no longer holds net worth's
  complete day back by a month.

## 0.4.27 — 2026-11-02

- A transaction can be voided when it never happened, such as a payment's
  funding leg booked to a Wise balance it did not pass through. Voided rows
  leave every total, match and link.

## 0.4.26 — 2026-11-02

- A balance only moves forward with rows from statements that print their
  own balances, and an account counts as covered up to its last printed
  balance. Balance-less exports had pushed Wise far negative because they
  hold the money going out but not the top-ups.

## 0.4.25 — 2026-11-02

- Net worth is stated as of the last day every watched account has a
  statement for, and names the account it is waiting on. Today's figure is
  shown beside it, marked incomplete, and the series is dashed past that day.
- An account's balance is its last statement balance plus what moved after
  it, so accounts with different closing days are taken to the same day.
  Wise balances no longer stop at the last PDF statement.

## 0.4.24 — 2026-11-02

- Confirming a statement that prints no period re-links only the weeks its
  rows cover. It used to rebuild the whole ledger, ran past the 300 s limit
  and blocked other requests while it ran.
- Clearing automatic links is saved together with the rebuild, so an
  interrupted run keeps the old links instead of leaving none.

## 0.4.23 — 2026-11-02

- HSBC One's current-account lines stay on the existing HKD savings account.
  An account created by mistake can be removed once its rows have moved.

## 0.4.22 — 2026-11-02

- Read an HSBC One HKD Current ledger as its own account. It was being
  folded into HKD Savings, which made a balanced statement look short by
  the opening balance and the import was refused.
- Keep the currency named on an AMEX credit line. A mainland Alipay charge
  with no currency printed is renminbi, and no longer raises a warning.
  A transaction can also be given the foreign amount the statement printed
  when an earlier import dropped it.

## 0.4.21 — 2026-10-22

- Stop caching the statement-freshness response. A stale copy kept the
  overdue banner up for a day after the statement was already imported.
- A blotter page that fails or comes back empty no longer leaves the tail
  spinning. The retry control works without reloading the page.

## 0.4.20 — 2026-10-22

- Leave transfers out of the home summary pie and ranking. Moving money
  between your own accounts stays in the blotter.

## 0.4.19 — 2026-10-22

- Add an account from the API, including its primary card, so a new card
  statement can be imported without a database session.

## 0.4.18 — 2026-10-12

- Keep the blotter loading after the first page on a phone. The tail was
  driven only by an IntersectionObserver; when it stays silent the list sat
  at 50 rows with an empty sentinel. The pane's scroll position now drives
  the same check, the sentinel shows a "Load more" button while idle, and a
  page request that never answers times out after 30 s into the retry state.

## 0.4.17 — 2026-10-12

- Fix the phone blotter layout. 0.4.14's desktop-only overflow rule also
  applied on phones and made the table wrap a scroll container, so day bands
  pinned inside it and landed on top of the first rows, leaving a gap under
  the totals. The rule is now desktop-only, and bands pin under the filter
  bar again.
- A page that fails to load no longer ends the blotter silently. The tail
  shows "Couldn't load more · Retry" instead of an empty sentinel that never
  fires again.

## 0.4.16 — 2026-09-25

- Import an HSBC MPF print that lists the three account balances on Member
  Returns, without separate Account Returns PDFs. The snapshot date stays on
  the printed balance date when that page does not show an earlier unit price.

## 0.4.15 — 2026-09-16

- HSBC One wrap keeps a dated counterparty with the next amount, so salary no
  longer leaks onto the previous card payment. Savings rows get a readable
  title (Mox, Pulse, EveryMile, employer) instead of the HC/N-ref line. The
  blotter hides that issuer soup once a title exists, and a description can
  be corrected with PATCH.

## 0.4.14 — 2026-09-14

- Keep the blotter loading the next page after the list paints. A sentinel
  IntersectionObserver watches the tail; after each page Angular re-observes
  in the post-render read phase so a sentinel that stayed on screen still
  fetches. The desktop table wrap no longer becomes a nested vertical
  scroller that eats the wheel.

## 0.4.13 — 2026-09-14

- Instalment progress counts billed sequence, not how many rows happen to be in
  the ledger. Equal monthly charges without a printed 03/12 marker still join
  the plan, so earlier months of a BT loan are no longer treated as unpaid.
- Cross-account payments that name the destination card, IFS/PPS thank-you
  credits, and masked self FPS names auto-link. Card rewards that only print
  the cardholder are no longer proposed as transfers.

## 0.4.12 — 2026-09-14

- Closed and idle accounts no longer appear on the home overdue banner. Mark
  an account closed or “don’t expect statements” from its account page.
  Investment accounts are ignored automatically. CSV imports without a printed
  statement date still count as coverage via their newest row.
- Fill categories that rules never claimed: transfers, card payments, FX,
  interest, fees, and common Hong Kong merchants (MTR, Octopus, DiDi,
  ParknShop). Undisclosed Alipay/Tenpay charges stay `proxy_payment` in the
  taxonomy instead of looking uncategorised.

## 0.4.11 — 2026-09-14

- Keep a CR-suffixed card statement balance as a credit. HSBC prints
  `1,803.34CR` when the account is in credit; applying `negate` on top of
  that flipped the closing figure and refused a complete EveryMile import.

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
