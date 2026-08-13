# Mobile UI handoff

State after the 2026-08 mobile experience uplift. The backlog in §3 is closed;
new work should start from the principles and QA matrix in
[`MOBILE_UX.md`](MOBILE_UX.md).

Two rounds of work are merged. This document is what the next person needs to
carry on: how to get a populated app running, what the design system now
guarantees, what is deliberately still open, and the traps that have already
cost time once.

---

## 1. Get a running app with data in it

Empty panels hide every layout problem worth finding. Do not do UI work against
an empty ledger.

```bash
export LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8      # see §5, this is not optional

initdb -D /tmp/finto-pg --no-locale --encoding=UTF8
pg_ctl -D /tmp/finto-pg -o "-p 55432" -l /tmp/finto-pg.log start

export DATABASE_URL='postgresql://127.0.0.1:55432/postgres'
.venv/bin/python scripts/seed_demo.py           # ~1,270 invented transactions

FINTO_AUTH_PASSWORD='local-dev' .venv/bin/finto users bootstrap \
  --username owner --email owner@example.com
```

Two terminals:

```bash
export DATABASE_URL='postgresql://127.0.0.1:55432/postgres'
export FINTO_SESSION_SECRET='any-long-local-value'
.venv/bin/uvicorn fin.api.app:app --port 8000
```

```bash
npm --prefix web start                          # :4200, proxies /api to :8000
```

Sign in as `owner` / `local-dev`. The seed leaves ~50 uncategorised rows so the
triage queue and the swipe gesture have something to act on.

**Screenshots.** `npm --prefix web run capture:mobile` drives system Chrome at
390×844 @2x, validates compact and desktop overflow/touch targets, checks the
reduced-motion path, and refreshes `docs/design/mobile/*-after.png`.

---

## 2. What the design system now guarantees

All of it lives in `web/src/styles.css`, with the reasoning in comments. Read
that file before changing anything visual.

| System | Rule |
|---|---|
| **Breakpoints** | Exactly two: `880px` (compact) and `520px` (narrow, a refinement *inside* compact). Grep for `880px` before inventing a width. |
| **Type** | Ten steps, `--t-micro` → `--t-hero`. A literal px font-size is a bug; there are currently zero in the app. Compact bumps the six reading sizes one notch for phone viewing distance. |
| **Colour** | Four foreground steps, each ≥4.5:1 on `bg`/`panel`/`panel-2` in both themes. `--line-strong` carries interactive boundaries at 3:1 (WCAG 1.4.11); `--line` is decorative hairline only. |
| **Motion** | Three durations (`--motion-fast/-/-slow`), two curves (`--ease-out` entering, `--ease` symmetric). Nineteen ad-hoc timings were removed; do not add a twentieth. |
| **Layers** | Seven `--z-*` tokens, band → auth. **A token only competes inside its stacking context** — see §5. |
| **Loading** | `.skeleton` (via `<finto-skeleton>`) when the shape is known, `.spinner` when it is not. The rule is written above `.spinner`. |

Verification helpers worth re-running after palette changes: the contrast maths
in this repo's history (commit `456bb00` message) checks all four fg steps
against all three surfaces in both themes.

---

## 3. Completed uplift backlog

All eleven items are complete. The notes remain as rationale for future work.

### 3.1 Complete — stop painting spending red
Every outflow renders `--neg`, which on a spending ledger is ~90% of rows, so
red carries no information and the screen reads as one continuous alarm. Copilot
(Apple Design Award finalist) spends red only on budget status, never on a
transaction for being an expense.

Spending → `--fg`. Keep `--pos` for income and refunds; keep `--neg` for the
genuinely exceptional. Mostly `blotter.html` and `summary.html`.

### 3.2 Complete — labels to sentence-case sans
`styles.css` says mono is "reserved for what has to align in a column", then
sets `SEARCH`, `OUT`, `IN`, `NET`, `TOTALS` and every form label in tracked-out
uppercase mono. Labels are not data. This is the strongest internal-tool signal
on the screen and the system already forbids it. Change the global `label` rule
and the `.scope-item .metric small` / `.aggregation-label` variants; keep mono
on figures, codes, dates and keys.

### 3.3 Complete — human dates, slimmer bands, drop the repeated currency
`2026-08-26` is machine-readable, in a full-height band with its own rule and
subtotal. At 1–2 transactions a day that is ~40% of the list. Go relative for
the recent past (Today / Yesterday / Wed 26 Aug), one line, no rule, subtotal
only when the day has more than one row. Separately: print the currency code
only when it differs from the account's own, so a foreign charge becomes
visually distinct for free.

`ShortDatePipe` in `core/money.pipe.ts`, `dayGroups()` in `blotter.ts`,
`.daygroup` in `blotter.css`.

### 3.4 Complete — fewer borders; separate with surface, not line
Every element is a 1px rectangle: card, pill, select, input, segmented control.
On a monitor that reads as precision; on a phone, nested rectangles read as a
form. **Keep square corners and hairlines as the thesis** — change the mechanism:
use `--panel` against `--bg` for separation and drop the border at the innermost
nesting level. Same look, one less rectangle per level.

This is the largest single visual change available and the highest risk of
breaking the aesthetic. Do it in one pass, screenshot before and after.

### 3.5 Complete — row hierarchy
Merchant, category, amount, currency, sometimes a native amount and flag tags —
all at similar weight, so nothing tells you what to read first. Target: one
dominant line (merchant), one quiet line (category), one right-aligned figure.
Everything else earns its place by exception or moves to the drawer.

### 3.6 Complete — expose the classifier; lead the picker with its guess
`fin/llm/categorize.py` classifies uncategorised transactions with Haiku and has
**no HTTP endpoint**, so the phone cannot ask. The category picker
(`blotter.html`, `.picker`) currently lists categories alphabetically. Add a
suggestion endpoint and lead with the prediction — that turns 52 decisions into
52 confirmations, which is the difference between a chore and a swipe session.

### 3.7 Complete — render the review state
`review_state` is on the model (`core/models.ts:80`), comes down the wire, and
appears in no template. Surfacing it gives Review a denominator — "52 to look
at, 11 done" — which is what makes a queue feel finishable.

### 3.8 Complete — rethink the home hero
Summary opens on `-63,076.99` under a line falling to the right, because net
worth nets card liabilities against two bank accounts. Arithmetically fine;
a strange daily greeting, and not actionable. "Spent this month vs last" is
both. Net worth belongs on Accounts. The hero now compares month-to-date spend
with the same elapsed days last month, avoiding a misleading partial-month to
full-month comparison.

### 3.9 Complete — animated charts
`finto-timeseries`, `finto-bars`, `finto-viz` (donut/share bar) and the summary
trend SVG all render statically. Line draw via `stroke-dasharray`, bars growing
from baseline, donut sweep. Use the motion tokens; gate everything on
`prefers-reduced-motion`, which the app already honours globally.

### 3.10 Complete — animated routing
Route changes are instant cuts. A short shared-axis transition would help
orientation on a phone. Angular's `@angular/animations` route transitions, or
the View Transitions API via `withViewTransitions()` in `app.config.ts` — the
latter is far less code and is the modern answer, but check Safari support for
the target iOS version first.

### 3.11 Complete — compact controls use bottom sheets
`finto-select` is a custom dropdown; `finto-date` is a calendar popover. Both
are desktop patterns scaled up. On compact they should become bottom sheets
(the `.picker` in `blotter.css` is a working reference for the pattern). The
segmented control is fine. This is the last structurally desktop thing left.

---

## 4. Conventions

- **Comments explain why, never what.** No "without this…" strawmen, no history,
  no restating the code. Match the density already in `styles.css`.
- **UI copy states, never teaches.** Labels and messages report; they do not
  justify or instruct.
- **Fix bugs you find** rather than reporting them — the owner's standing
  preference. Report the result, not the diagnosis.
- Money is integer minor units; negative is out, positive is in; native and
  booked currency stay separate. Never sum across currencies without an explicit
  reporting currency.

---

## 5. Traps that have already cost time

**CSS override blocks must come last.** The compact `@media (max-width: 880px)`
block sat *above* the rules it overrides. CSS breaks that tie on source order,
so every override in it silently lost — including 44px touch targets that
appeared correct in the source and never applied. It is now the last block in
`styles.css`. Keep it there.

**A z-index only competes inside its stacking context.** The filter sheet
(`z-index: 40`) rendered *under* the tab bar (`30`) because its parent
`.filter-bar` is `position: sticky; z-index: 20` and traps it. Raising the child
does nothing; the host has to move. `.filter-bar.sheet-open` takes
`--z-overlay` for exactly this reason. The same bug hit the pull-to-refresh
indicator. If an overlay renders under something it should cover, look at its
ancestors before touching its own z-index.

**pytest needs `LC_ALL` set.** Without it, `pg_ctl start` fails with *"postmaster
became multithreaded during startup"* and roughly half the suite errors at fixture
setup, looking exactly like a missing PostgreSQL. It is not.
`LC_ALL=en_US.UTF-8 .venv/bin/pytest -q` → 334 passed in ~6–8 min. Stream it or
background it; piping to `tail` hides progress for several minutes.

**Pushing.** SSH fails in this environment even with `gh` authenticated. Use:
```bash
git -c credential.helper='!gh auth git-credential' \
  push https://github.com/XGZepto/Finto.git main
```

**Vercel.** The project is under scope `cimu`, not the CLI default. `vercel
inspect` needs `--scope cimu`; `vercel deploy --prod --yes` does not. Root `/`
302s to `/login` behind auth middleware, so verify deployed HTML by fetching
`/login`.

**Angular style encapsulation.** A parent's stylesheet cannot reach into a child
component. `reports.css` carried `.pill` rules that never applied for this
reason. If a style on a child component "does nothing", check whether it is
someone else's component.

---

## 6. Known-good verification loop

```bash
npm --prefix web run build                       # must be clean, no NG warnings
LC_ALL=en_US.UTF-8 .venv/bin/pytest -q
npm --prefix web run test:mobile-ux              # requires the local API + web app
```

For gestures, synthetic `TouchEvent`s in the page console work and are how
pull-to-refresh and swipe were verified — dispatch `touchstart`/`touchmove`
with a delay between them so Angular's change detection runs, or every DOM read
comes back stale.

---

## 7. Context worth reading

- `docs/design/mobile/*.png` — before/after captures from the last round.
- `docs/design/MOBILE_UX.md` — product hierarchy, platform references, QA
  matrix, and the current screenshot gallery.
- Commit messages on `456bb00`, `b3362cc` and `e4bcbaa` carry the reasoning for
  the design system, the session-cookie fix and the mobile rework respectively.
  They are long on purpose.
