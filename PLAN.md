# Finto — current plan

Living checklist for what is done and what still matters for *this* ledger
(AMEX HK/US, HSBC HK cards+savings+MPF, Mox, Chase, Wise).

## Done

- [x] Cash ledger: accounts, cards (supplementary + reissue), txns, dedup,
      transfers, refunds, installments, integrity
- [x] HTTP API + Angular frontend (summary, blotter, import, review,
      installments, integrity, ask)
- [x] PDF layout-template engine + issuer templates
      (Mox, Chase, HSBC savings/cards, Amex US/HK)
- [x] Parties + account aliases so self-transfers and P2P are distinguished
- [x] Investment / MPF position snapshots
      (`investment_*` tables + `finto investments import`)
- [x] Full `accounts.example.yaml` covering the real account map
- [x] PDF ingest wired through template engine with LLM fallback; failed
      verification refuses import (templates are the only deterministic path)
- [x] Repo reorganised: docs/ for design reviews, lint clean, 253 tests passing

## Next (in priority order)

1. **Full-corpus ingest evaluation** — import every file under
   `~/Documents/Finto-Data` into a scratch database, run `reconcile` and
   `check`, and report per-account ingestion and balance coverage.
2. **Amex HK PDF residuals** — a small number of statements still fail
   verification; fix template or use LLM-assisted extraction with the verify
   gate.
3. **Regular income detection** — salary cadence on top of `TxnKind.INCOME`.
4. **Frontend**: investment / MPF positions page; cardholder breakdown is
   already exposed via `group_by=cardholder`.

## Non-goals

- Cloud sync, multi-user, OCR of scanned statements.
- Accrual accounting for instalments (cash basis is forced by integrity checks).
