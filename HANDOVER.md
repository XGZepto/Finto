# Finto — Handover

Date: 2026-08-04
Repo: `XGZepto/Finto`, branch `main`
Goal: ingest the owner's real financial data (AMEX HK/US, HSBC HK cards+savings+MPF, Mox, Chase, Wise) perfectly, then polish an Angular frontend.

---

## 1. What this project is

A personal finance ledger. Key design rules (do not violate):

- **Integer money everywhere** (`Money.amount` is minor-unit int). Never floats.
- **Schema is rebuilt, not migrated.** `fin/db.py::init_db` just runs `fin/schema.sql`. No migration machinery. If the schema changes, re-import from statements.
- **Failed PDF verification refuses import.** A statement that doesn't reconcile against its own printed balances is rejected, never silently imported.
- **Strict code quality**: `ruff check fin tests scripts --select E,F,I,UP` must pass. All tests must pass.

## 2. Current state (verified 2026-08-04)

- **Tests: 283 passing.** Lint clean.
- **Extraction is exact.** Every statement that prints an opening and a closing reproduces them from the rows we pulled out of it — 226/226 across AMEX HK (32), AMEX US (38), AMEX US savings (19), Chase (38), HSBC cards (49), HSBC One (19), Mox Bank (31). Mox Credit prints no opening; its 19 statements chain closing-to-closing.
- **Corpus: 192 files imported, 0 errors, 5,494 transactions.**
- **Classification: 5,102/5,494 have a kind; 3,301 have a category.**
- **Reconciliation: 425 checks, 0 discrepancies. 0 structural violations.** `GET /api/integrity` reports `healthy: true`.
- Two accounts are reported *unverified* rather than healthy — Wise HKD and NZD have a single balance assertion each, and one figure cannot verify anything. That is the correct answer, not a gap.

## 3. The two checks, and why they differ

`scripts/corpus_eval.py` is the end-to-end harness. But when it reports a discrepancy, the question is always *which layer*:

- **Extraction** — did we read the statement correctly? Check with the per-statement sweep (no DB, no dedup). This is currently perfect, so any failure is downstream.
- **Ledger** — after CSV and PDF copies of the same charge are merged, do the numbers still hold? This is where the remaining 100 discrepancies live.

Keep them separate when debugging. Conflating them is what made the previous handover attribute extraction quality to reconciliation failures that were really dedup.

## 4. What changed in this session

1. **Reconciliation model rewritten** (`fin/integrity.py`). Card issuers assign a charge to a statement by *posting* date, so a charge dated inside a period is routinely billed on the next one. Walking balance assertions by date therefore disagreed with the bank even when nothing was missing. Statements that print an opening and a closing are now checked against *their own rows* (followed through dedup); passbook-style running balances keep the date walk. `balance_assertion.source` became `kind` (`opening`/`closing`/`running`/`manual`).
2. **Structured detail capture** (`fin/pdf/template.py`). New `DetailRule` — a regex plus the names its capture groups are stored under. Sections declare `detail` (facts about a transaction, on its own line or the lines beneath) and `markers` (facts about a block of rows, `scope: following|preceding`). This replaced a shape heuristic that guessed which continuation lines were FX detail and glued "Payment Advice" onto merchant names, breaking CSV↔PDF dedup.
3. **Foreign currency is now real data.** The printed foreign amount, its currency and the issuer's rate land in `amount_native`/`currency_native`/`fx_rate`. 1,317 transactions carry a foreign amount, 823 the issuer's own rate.
4. **`parse_amount` is currency-aware.** AMEX bills a foreign charge in the foreign market's convention, so EUR arrives as "13,04". Which of `.`/`,` is the decimal point is decided by the currency's minor-unit count, not by guessing.
5. **Cardholder attribution.** AMEX HK closes each cardholder's block with its total, AMEX US opens one with `Card Ending 6-63019`, HSBC heads one with the card number and name. All three are markers now. Name matching compares sorted name parts, because the same person is "HO CHING LEUNG" in the account map and "LEUNG HO CHING" on the statement.
6. **Cross-source dedup was removed; the statement is the truth.** An export
   row is suppressed only when a statement carries the same account, date,
   signed amount and currency — matched by count, so two identical rides
   suppress two rows and a third survives to be noticed. Nothing is scored.
   This alone took the ledger from 100 discrepancies to 0. Statements never
   take part in fuzzy matching, and two identical rows inside one file are two
   movements, never one.
7. **Rows land on the account that settles their currency.** A dual-currency
   card is one statement over two balances; `balance_group` already modelled
   that, and `_settle_in_currency` now uses it for both transactions and
   balance figures. This moved 1,325 CNY charges off the HKD Pulse account.
8. **`compute_dedup_key` no longer keys on `external_ref`.** The key exists to make the same charge collide across sources, and a reference printed on a statement is absent from the same issuer's CSV — keying on it made the copies differ exactly where they had to match. Dedup still *scores* on the reference.
9. **`wraps`** per section: whether a description can run over several lines. Mox centres a long merchant name on its figures row so parts sit above *and* below; each stray line goes to whichever transaction it is nearest. AMEX gives every charge one line, and there a stray line is detail.
10. **Category rules are loadable** (`rules:` in `accounts.example.yaml`, `dbm.upsert_category_rule`). Salary, rent, fees, interest, rewards, card payments and instalments are labelled from what the statements themselves stamp.
11. **Classification closed as far as the statements allow.** AMEX prints the
    merchant's own category under each charge; a `column`-scoped detail rule
    captures it (566 rows) and `category_rule.match_field='merchant_category'`
    maps its vocabulary onto the ledger's — a rename, not an inference. Named
    merchants (Uber, MTR, Didi, foodpanda, Vercel) are ordinary rules. A card
    row left unlabelled after every other pass is a purchase when money left
    and a refund when it came back, because a card carries nothing else.
    Income detection no longer runs on cards, where a monthly AMEX rebate was
    being counted as earnings.
12. **Payment gateways are a category, not a blank.** A charge routed through
    Alipay, WeChat Pay, UnionPay, Apple/Google Pay or KPay reaches the card
    under the gateway's name. `enrich.payment_gateway` reads the *raw*
    description — normalisation drops the "\*" that separates a gateway from
    the merchant behind it, which is the one character telling the two cases
    apart. Where the gateway named the merchant, 309 merchants were recovered
    that the ledger had as NULL (DIDI Taxi, Ichiran, NYCT PAYGO). Where it did
    not, 1,114 rows are categorised `proxy_payment` with the gateway as
    subcategory and `merchant.disclosed=no`, so the commonest line in the
    ledger reads as a known state of affairs rather than an unread row. The
    blotter shows "Merchant not disclosed / via Alipay" and the drawer explains
    that the name is only recoverable from the gateway's own history.
13. **`update_txn_links` persists what reconcile computes.** It wrote links
    only, so gateway labels, recovered merchants and `income_stream` tags were
    recomputed on every run and thrown away. It now writes category,
    subcategory, merchant and the detail rows as well; every pass already
    declines to overwrite a value that is present, so manual corrections
    survive.
14. **PDF extraction is cached** (`fin/pdf/extract.py`). Every import read each PDF twice — once to recognise it, once to parse it. Halves import time.

## 5. Known gaps / next steps

1. **2,193 long-tail merchants** are uncategorised. These *are* the LLM's job — a name a model recognises and a rule-writer would not bother with. Run `python -m fin.cli categorize --db … --apply` with `ANTHROPIC_API_KEY` in the environment. Decisions are cached in `llm_decision` and recorded in `txn_annotation`, so `DELETE FROM txn_annotation WHERE source='llm'` is a complete undo. `promote_to_rules` turns confident, repeated answers into ordinary `category_rule` rows, so the model is paid for once.
2. **392 bank rows have no kind, on purpose.** A debit on a current account is either spending or half of a transfer nothing has matched yet. `unknown` says so; a guess would hide the transfer.
3. **Transfer candidates** — ~290 unlinked, mostly same-name FPS moves between the owner's own accounts. Clearing these would also resolve most of item 3.
4. **Frontend** — Angular app in `web/`. `/api/investments` now exists; the positions page does not.

## 6. Things not to do

- Do not reintroduce DB migrations.
- Do not let an unverified PDF import.
- Do not use floats for money.
- Do not weaken `check_account` to make numbers go away — fix the extraction or the anchoring.
- Do not put an issuer reference back into `compute_dedup_key` (see §4.6).
- Do not merge two identical rows that came from the same file. The key cannot
  tell two HK$18 MTR rides apart from one ride listed twice; the source can.
- Do not reintroduce similarity matching between a statement and an export.
- Do not let a template `exclude` swallow a line that carries data; that is what `detail` is for.
