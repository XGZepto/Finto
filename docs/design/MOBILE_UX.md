# Mobile experience

Finto's compact layout is a financial ledger shaped for a thumb, not a desktop
table squeezed into 390 pixels. It keeps the square, data-dense visual thesis
while using surface, hierarchy, and progressive disclosure to reduce chrome.

## Product hierarchy

- **Summary answers the daily question.** The primary figure is spend this
  month compared with the same elapsed period last month. Income, net, and
  savings rate support it; longer-range flow stays below.
- **Accounts owns financial position.** Net worth sits with the assets and
  liabilities that explain it, ahead of the account hierarchy.
- **Blotter is a reviewable queue.** Merchant is the dominant line, category is
  quiet, the amount is right-aligned, and currency appears only for exceptions.
  A progress line supplies the denominator.
- **Classification assists; it does not decide.** The picker leads with a
  confidence-gated cached/model suggestion. The ledger changes only after a
  tap, which records a manual confirmation.

## Interaction rules

- Compact controls have at least 44px height. Selects and calendars become
  bottom sheets with a scrim, explicit close target, safe-area padding, and
  keyboard dismissal.
- Recent dates use Today, Yesterday, and localized weekday/month labels. The ISO
  date remains available as a title for precision.
- Ordinary spending is neutral. Green denotes money in or improvement; red is
  reserved for exceptional negative state. Status also has text or shape so
  colour is never the only signal.
- Chart and route motion uses the system duration/easing tokens and is disabled
  under `prefers-reduced-motion`.

These choices follow Apple's guidance on [layout](https://developer.apple.com/design/human-interface-guidelines/layout),
[lists and tables](https://developer.apple.com/design/human-interface-guidelines/lists-and-tables),
[writing](https://developer.apple.com/design/human-interface-guidelines/writing),
[sheets](https://developer.apple.com/design/human-interface-guidelines/sheets),
[charts](https://developer.apple.com/design/human-interface-guidelines/charts), and
[motion](https://developer.apple.com/design/human-interface-guidelines/motion).
Colour is backed by the WCAG rule that it must not be the sole visual means of
conveying information.

## Visual QA

`web/scripts/capture-mobile.mjs` is the repeatable acceptance check. Against a
populated local ledger it:

1. signs in and captures the core views at 390×844 @2x;
2. fails on page-level horizontal overflow or visible controls below 44px;
3. verifies select and date sheets attach to the viewport bottom;
4. repeats overflow checks at 1280×800; and
5. confirms chart animation is disabled for reduced-motion users.

Run `npm --prefix web run capture:mobile` to refresh the images, or
`npm --prefix web run test:mobile-ux` for assertions without file writes.

## Current screens

| Summary | Reviewable ledger |
|---|---|
| ![Summary](mobile/summary-after.png) | ![Blotter](mobile/blotter-after.png) |

| Suggested category | Bottom-sheet control |
|---|---|
| ![Category suggestion](mobile/blotter-category-suggestion-after.png) | ![Reporting currency sheet](mobile/summary-currency-sheet-after.png) |

| Filters | Accounts and net worth |
|---|---|
| ![Filters](mobile/blotter-filters-after.png) | ![Accounts](mobile/accounts-after.png) |

| Calendar sheet |
|---|
| ![Date sheet](mobile/blotter-date-sheet-after.png) |
