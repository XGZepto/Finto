# Finto — Handover

Date: 2026-08-04
Repo: `XGZepto/Finto`, branch `main` (all work pushed; tree clean)
Owner data: `~/Documents/Finto-Data` (golden bank PDFs + CSVs, 2025-01 on)

**Do not trust prose in this repo over the code and the data.** Earlier docs
(including the old README) editorialised and drifted. Verify against `fin/` and
a fresh corpus run.

---

## 1. What it is

A personal-finance ledger. Ingests statements → canonical `txn` rows →
reconciles against the banks' own printed balances → serves a FastAPI backend →
Angular frontend in `web/`.

Design rules that must not be broken:
- **Integer minor units for money.** Never floats.
- **The statement is authoritative.** A PDF statement prints an
  opening/closing balance we can reconcile against; a CSV export is the same
  movements reworded, with no balance. Where both cover a period the statement
  wins and the export rows are suppressed (`dedup.supersede_with_statements`).
- **Schema is rebuilt, not migrated.** `db.init_db` runs `schema.sql`
  (idempotent `CREATE ... IF NOT EXISTS`). No migration machinery. Adding a
  table = add it to `schema.sql`; `init_db` picks it up on any existing DB.
- **Extraction is deterministic and verified.** Templates own the money;
  `pdf/verify.py` refuses an import that doesn't reconcile. This is how a
  dropped row is caught. Do not replace it with an LLM parse (see §6.1).

## 2. State (verified 2026-08-04)

- **Tests: 290 passing** (`pytest tests/ --ignore=tests/test_pdf_corpus.py`). Lint clean (`ruff check fin tests scripts --select E,F,I,UP`).
- **Corpus: 192 files, 0 errors, 5,494 txns, 0 reconciliation discrepancies, 0 structural violations.**
- **Extraction reconciles 226/226 statement documents** against their own printed opening/closing.
- **Classification: 82%** (rules + one LLM pass). ~949 uncategorised — mostly payment-rail rows (Alipay/WeChat/UnionPay) where the merchant is genuinely absent, plus low-confidence abstentions.
- Open review candidates on a clean rebuild: **12 transfer, 3 duplicate, 0 installment.**

## 3. Running it

```bash
source .venv/bin/activate
# Backend (localhost only, no auth):
FINTO_DB=/tmp/finto_ui.db python -m uvicorn fin.api.app:app --port 8000
# Frontend (proxies /api → :8000):
cd web && npx ng serve --port 4201 --proxy-config proxy.conf.json
```

**Databases:** `/tmp/finto_ui.db` is the demo DB the running API/UI use — it has
the LLM categories + tags applied. A clean pipeline rebuild
(`python scripts/corpus_eval.py --db /tmp/finto_fresh.db`) produces the same
ledger **minus** the LLM pass (a separate opt-in step, §5).

## 4. Architecture map (heavily changed this session)

| Path | Role |
|---|---|
| `fin/pdf/template.py` | Declarative engine. `DetailRule` (regex→named captures, `column`/`on` scoping), `markers` (block-level facts e.g. cardholder), `wraps` per section. Unknown template keys now rejected. |
| `fin/pdf/templates/*.json` | Per-issuer templates. All money reconciles. |
| `fin/enrich.py` | Detail parsing — labelled fields only, no guessing. `payment_gateway()` splits Alipay/WeChat/etc into gateway + (maybe) merchant. |
| `fin/dedup.py` | Statement supersession (exact account+date+amount+currency, counted) + within-source exact + fuzzy between exports only. |
| `fin/transfers.py` | `_score_pair` **gates on evidence** (payment wording / name linking legs / shared balance_group / FX match), not amount+date alone. Killed coincidental-pair flood (262→12). |
| `fin/integrity.py` | Per-statement reconciliation (opening→closing vs that statement's rows) + running-balance walk. |
| `fin/reporting.py` | `rollup()` = single-currency normalised aggregation (used everywhere). `flows()`, `coverage()`, `composition()`. `build_where` filters incl `tags`, `cardholders`, `detail`. `group_by=tag` fans out. |
| `fin/ingest.py` | `reconcile()`: dedup→transfers→installments→refunds→income→gateway labels→default kinds→**FX harvest**. `merge_duplicate_details` gives a survivor every fact its suppressed copies held. |
| `fin/db.py` | Tag CRUD, `merge_duplicate_details`, `statement_txn_ids`. `update_txn_links` persists category/merchant/details, not just links. |
| `fin/llm/categorize.py` | Taxonomy aligned to the rule scheme. Suggests brand tags. `apply_to_ledger`, `promote_to_rules`. |
| `fin/api/routers/*` | `+/flows +/coverage +/composition +/investments +/details +/tags`, tag add/remove, `convert_to`/`as_of`/`cardholders`/`tags` params. |
| `web/src/app/features/` | `summary` (normalised headline + net worth + in/out flow chart), `timeline` (composition + coverage band), `accounts` (cards/lineage/flow), `investments` (MPF), tags in blotter. |

## 5. The LLM layer (key now available)

- `ANTHROPIC_API_KEY` is in the owner's `~/.zshrc`. The harness shell does **not**
  source it; run LLM commands via a subshell that does, and never echo the key:
  ```bash
  zsh -c 'source ~/.zshrc >/dev/null 2>&1; cd ~/projects/Finto; source .venv/bin/activate; python -m fin.cli --db DB categorize --promote'
  ```
- `anthropic` SDK is installed and optional in `pyproject.toml`. Model: `claude-haiku-4-5`.
- Enable per DB: `python -m fin.cli --db DB config set llm_enabled 1` (`--db` is a **global** flag, *before* the subcommand).
- One categorize pass done: 1,241 classified, 55 promoted to rules, 756 abstained (low confidence, left uncategorised by design). Cached in `llm_decision`, annotated `source='llm'`; `DELETE FROM txn_annotation WHERE source='llm'` undoes it.

## 6. Open threads / next steps

1. **LLM enrichment pass (offered, NOT built).** Owner asked "AI parse the PDFs".
   Do **not** replace templates — that forfeits the reconciliation guarantee (an
   LLM can silently drop a row). Right shape: templates keep the money + balance
   proof; an LLM pass reads the PDF and fills the *detail* fields a template
   missed (merchant category, itinerary, `raw.*`), **forbidden from changing any
   reconciled amount**. Stub exists: `fin/parsers/pdf.py::_try_llm_extract`.
   Left for owner to greenlight.
2. **Account→sub-account hierarchy (NOT built).** `balance_group` already groups
   per-currency accounts (`hsbc_pulse`, `wise_personal`, `mox_personal`,
   `hsbc_one`). Expose `group_by='account_group'` and roll positions up so
   Wise/Pulse/Mox show one parent with sub-account rows. Data exists; presentation only.
3. **Hosting on Vercel (owner wants it; NOT started — needs a plan, not a flag):**
   - SQLite-as-a-file doesn't survive serverless → hosted DB (Postgres/Turso/libSQL); schema is STRICT SQLite, port carefully.
   - **No auth exists.** API binds localhost, wide open; public exposure hands over the whole financial history. Auth is mandatory first.
   - Secrets → platform env vars.
   - "local · offline-only" sidebar label + offline framing become false; update once direction is fixed.
4. **Payment-rail rows (~1,300) uncategorised on purpose.** "ALIPAY CHN CN" names
   the gateway, not the merchant. Categorised `proxy_payment` (subcategory =
   gateway); merchant only recoverable from the Alipay/WeChat apps' own exports.
5. **Transfer/duplicate candidates (12/3 open).** Matchers are conservative now.
   `fin/llm/adjudicate.py` hooks exist to triage the rest; not run this session.

## 7. Things not to do

- Don't reintroduce DB migrations, floats for money, or let an unverified PDF import.
- Don't put `external_ref` back in `compute_dedup_key` (statement prints it, CSV omits it — breaks cross-source collision).
- Don't merge two identical rows from the *same* file (two movements).
- Don't loosen `transfers._score_pair`'s evidence gate (reopens the coincidental-pair flood).
- Don't parse PDFs with an LLM as the primary path (§6.1).
- Don't add explanatory/justifying UI copy or comments — labels and state only.

## 8. Session log (commits, newest first)

- `cfe4126` normalised net headline; cardholders clickable
- `ebceaa0` merge duplicate detail; harvest rates in reconcile; cardholder filter; LLM backfill
- `9807b25` transaction tags
- `789fbf4` provenance mislink + coincidental transfer candidates + rollup
- `0f64a48` cut filler UI copy
- `90c294a` timeline (composition + coverage)
- `4a16641` accounts view + money-flow reporting
- `f75a249` scope totals + two-series trend + as-at positions
- `4dd2d23` ranked breakdown + cardholder + copy cuts
- `d32b5b1` reject unknown template keys; fix stale docs
- `9f741d3` trim comments; two branch fixes
- `abeafdc` statement-as-truth; capture all detail (the big one)
