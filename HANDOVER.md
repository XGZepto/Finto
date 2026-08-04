# Finto — Handover

Date: 2026-08-04
Repo: `XGZepto/Finto`, branch `main`
Goal: ingest the owner's real financial data (AMEX HK/US, HSBC HK cards+savings+MPF, Mox, Chase, Wise) perfectly, then polish an Angular frontend.

---

## 1. What this project is

A personal finance ledger. Key design rules (do not violate):

- **Integer money everywhere** (`Money.amount` is minor-unit int). Never floats.
- **Schema is rebuilt, not migrated.** `fin/db.py::init_db` just runs `fin/schema.sql`. No migration machinery — that was deliberately removed. If the schema changes, re-import from statements.
- **Failed PDF verification refuses import.** Templates are the only deterministic extraction path; a statement that doesn't reconcile against its own printed balances is rejected, never silently imported.
- **Strict code quality**: `ruff check fin tests scripts --select E,F,I,UP` must pass. All tests must pass.

## 2. Current state (verified)

- **Tests: 260 passing** (`pytest tests/ --ignore=tests/test_pdf_corpus.py`).
- **Lint: clean.**
- **Corpus eval (last clean run, `scripts/corpus_eval.py`):**
  - **192 files imported, 0 errors, 5,988 transactions**, 26 empty.
  - All Chase, Mox (credit+jpy), Wise USD/GBP, HSBC Pulse HKD/CNY reconcile fully.
  - Remaining reconciliation gaps on: Amex cards (18 each), HSBC savings CNY (12), Mox HKD (22), HSBC EveryMile (10), Amex savings (2), HSBC savings HKD (1), Amex HK Essential (1). See §5.
- Committed and pushed through `5e79b8a` + the uncommitted-then-committed period/income work (see §4).

## 3. Architecture map

| Path | Role |
|---|---|
| `fin/models.py` | Pydantic models (`Txn`, `Money`, `Account`, `Card`, …) + `normalize_description`/`normalize_alias` |
| `fin/schema.sql` | Single source of truth for the DB |
| `fin/db.py` | SQLite CRUD; `busy_timeout` set; loaders incl. `load_account_alias_index` |
| `fin/ingest.py` | `ingest_file` (parse→route→insert→balance) + `reconcile` (dedup→transfers→installments→refunds→income) |
| `fin/parsers/institutions.py` | CSV parsers: Amex, HSBC, Wise, Mox |
| `fin/parsers/pdf.py` | PDF parser wrapper → template engine + LLM fallback |
| `fin/pdf/template.py` | Declarative template engine (columns, balance rules, CR markers, continuation) |
| `fin/pdf/layout.py` | Column geometry (anchors / fractions) |
| `fin/pdf/extract.py` | pdfplumber extraction (word coordinates) |
| `fin/pdf/templates/*.json` | Issuer templates: mox_bank, mox_credit, chase_us, hsbc_hk_card/savings, amex_hk_card, amex_us_card, amex_us_savings |
| `fin/dedup.py` `fin/transfers.py` `fin/refunds.py` `fin/installments.py` | Cross-source dedup, transfer/payment linking, refunds, installment plans |
| `fin/income.py` | **NEW** — regular income cadence detection |
| `fin/investment.py` | HSBC MPF XLSX → investment_* tables |
| `fin/integrity.py` | Balance reconciliation `check_account`, structural violations |
| `fin/cli.py` | `finto` CLI |
| `fin/api/` | FastAPI backend (Angular frontend proxies to it) |
| `scripts/corpus_eval.py` | **The acceptance harness** — see §6 |
| `web/` | Angular frontend |
| `accounts.example.yaml` | The owner's real account map (accounts, cards, parties, aliases) |

## 4. What I did in this session (the last batch, commits `01fa2a1` and `5e79b8a` plus the last one)

1. **Amex HK templates now verify 32/32 statements.**
   - `fin/pdf/template.py`: added `continuation="below"` (FX detail lines attach to the row above); generalized `cr_on_following_line` so a CR ending a continuation line (`UNITED STATES DOLLAR CR`) flips the emitted row; added `BalanceRule.cr_following_line` so a lone CR word under a balance column flips the figure's sign only when it shares the column x-range. Wired `continuation` through `_section_from_dict`/`_section_to_dict`.
2. **Mox `RecursionError`** — root cause: pydantic `validate_assignment=True` + `Txn._derive` assigning `description_norm` re-triggered the validator; a CJK-only description (阿貓的貓) normalized to `""` and recursed forever. Fix: `object.__setattr__` in `_derive`, and `normalize_description` now keeps CJK (`㐀-䶿一-鿿`) so it never collapses to `""`.
3. **CSV↔PDF dedup** — Amex PDFs were picking up footer/FX noise into descriptions (`TURKISH AIRLINES … 5.420,95 the Important Information…`), so the same charge never matched the clean CSV row. Fixes: exclude boilerplate lines in `amex_hk_card.json`; keep `fx` out of the description but fold non-money FX-zone words back in (`_describe`); strip HSBC's CSV-only `SALES:` prefix in `normalize_description`; `dedup._score_duplicate` now boosts same-day same-account restatements with a shared description prefix.
4. **Consolidated statements route rows to per-currency accounts.** `ingest_file` resolves each row's `account_hint` via `load_account_alias_index`; balances carry the hint end-to-end (tuple is now `(as_of, Money, hint, source)`). Chase/HSBC One/Mox bank PDFs import with no `--account`. Idle Chase months route via balance-hint aliases.
5. **Balance assertions:** `INSERT OR REPLACE` → `INSERT OR IGNORE` (first write wins). HSBC/Wise export newest-first, so the first running-balance of a date is the end-of-day figure; same-day mid-day snapshots were inventing phantom deltas. CSV parsers and the PDF path now tag closings `statement_closing` vs `statement_running`.
6. **Card period-start parsing** — `fin/pdf/template.py::_find_period` now parses `"From February 9 to March 8, 2025"` (start has no year) so openings land on period_start, not on statement_date. Before this, opening+closing collided on statement_date and the integrity check compared the wrong period.
7. **CSV parsers:** Amex dates are MM/DD in every market (HK was being day-shifted — **this silently corrupted dates in earlier imports; re-import is required**); HSBC card/savings exports read `Billing amount/currency` + `Credit/Debit`; empty-but-parseable exports import via `ParseResult.allow_empty` instead of erroring.
8. **Income detection** — new `fin/income.py`: credits with ≥3 occurrences, 25–35-day gaps, amounts within 2% are labelled `TxnKind.INCOME` and tagged `income_stream` in `details`. Wired into `reconcile`; tests in `tests/test_income.py`.

## 5. Known gaps / next steps

**CRITICAL — run the final corpus eval.** The last full `scripts/corpus_eval.py` run hung (>6 min, had to be killed) after the period/income changes, which suggests a reconcile path now scales badly somewhere (possibly the new income detection bucketing, or the prefix-similarity in dedup on a larger graph). Before anything else:

```bash
cd /Users/zepto/projects/Finto
source .venv/bin/activate
python scripts/corpus_eval.py --db /tmp/finto_corpus.db
```

If it hangs, profile: ingest is fast (per-file), so it's `reconcile(conn)` — bisect by running `reconcile` after only the Amex Explorer account, then add accounts. `detect_regular_income` and `_score_duplicate` are the suspects.

Then:

1. **Re-import everything from scratch** (schema rebuilt anyway) — Amex date-shift bug means any DB built before commit `01fa2a1` has corrupted Amex CSV dates.
2. **Close remaining reconciliation gaps** (§2 list). Amex cards: opening/closing balance handling for card statements — these are single closing-balance documents (no opening), so `check_account` may need a "card" mode that anchors on the previous statement's closing rather than a paired opening+closing.
3. **Unlinked transfer candidates** (~267 before the last fixes) — review `transfer_candidate` scoring in `fin/transfers.py`; many are same-name FPS moves.
4. **Frontend polish** — Angular app in `web/`; MPF/investment positions page is the main missing view; cardholder breakdown exists via `group_by=cardholder`.

## 6. How to validate (the acceptance harness)

`scripts/corpus_eval.py` imports every file under `~/Documents/Finto-Data` into a scratch DB, runs `reconcile`, and prints per-account transaction counts + balance reconciliation coverage. "0 errors and all balances reconcile" is the bar. `account_for` maps corpus paths → account ids; consolidated Chase/HSBC One/Mox files return `ROUTED` (empty string) and route per-row.

Quick issuer check without a full ingest: `python scripts/pdf_probe.py <file-or-dir>`.

## 7. Things not to do

- Do not reintroduce DB migrations.
- Do not let an unverified PDF import.
- Do not use floats for money.
- Do not weaken `check_account` or the verification gate to make numbers go away — fix the extraction or the balance anchoring instead.
- The `fin/llm/` path exists (categorize/adjudicate/query) but is optional and off by default; keep it that way unless the owner asks.
