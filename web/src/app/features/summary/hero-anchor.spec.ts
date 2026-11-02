import assert from 'node:assert/strict';
import test from 'node:test';
import { anchorDate } from './hero-anchor.ts';

const today = new Date(2026, 10, 2); // Nov 2

test('a ledger that stops last month anchors on its final day', () => {
  const anchor = anchorDate('2026-10-23', [], today);
  assert.equal(anchor.lagging, true);
  assert.equal(anchor.date.getMonth(), 9);
  assert.equal(anchor.date.getDate(), 23);
});

test('a ledger current to today keeps the wall clock', () => {
  assert.equal(anchorDate('2026-11-02', [], today).lagging, false);
  assert.equal(anchorDate('2026-11-05', [], today).date, today);
});

test('without ledger_through the last month with rows stands in', () => {
  const anchor = anchorDate(undefined, [
    { bucket: '2026-09', txn_count: 4 }, { bucket: '2026-10', txn_count: 2 },
    { bucket: '2026-11', txn_count: 0 },
  ], today);
  assert.equal(anchor.lagging, true);
  assert.equal(anchor.date.getMonth(), 9);
  assert.equal(anchor.date.getDate(), 31);
});

test('no data at all falls back to today', () => {
  assert.equal(anchorDate(null, [], today).lagging, false);
});

test('a month holding only income does not count as current', () => {
  const anchor = anchorDate(undefined, [
    { bucket: '2026-10', txn_count: 9, spend: { amount: -500 } },
    { bucket: '2026-11', txn_count: 1, spend: { amount: 0 } },
  ], today);
  assert.equal(anchor.lagging, true);
  assert.equal(anchor.date.getMonth(), 9);
});
