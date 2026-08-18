import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { Subject, of, throwError } from 'rxjs';
import { ReadCache } from './read-cache.ts';

describe('ReadCache', () => {
  it('reuses a settled value inside the TTL', () => {
    const cache = new ReadCache();
    let calls = 0;
    const factory = () => {
      calls += 1;
      return of({ n: calls });
    };
    const first: unknown[] = [];
    const second: unknown[] = [];
    cache.get('stats', 60_000, factory).subscribe((value) => first.push(value));
    cache.get('stats', 60_000, factory).subscribe((value) => second.push(value));
    assert.equal(calls, 1);
    assert.deepEqual(first, [{ n: 1 }]);
    assert.deepEqual(second, [{ n: 1 }]);
  });

  it('starts a new factory while an earlier request is still open', () => {
    const cache = new ReadCache();
    const pending = new Subject<unknown>();
    let calls = 0;
    const seen: unknown[] = [];
    cache.get('stats', 60_000, () => {
      calls += 1;
      return calls === 1 ? pending : of({ n: 2 });
    }).subscribe();
    cache.get('stats', 60_000, () => {
      calls += 1;
      return calls === 1 ? pending : of({ n: 2 });
    }).subscribe((value) => seen.push(value));
    assert.equal(calls, 2);
    assert.deepEqual(seen, [{ n: 2 }]);
    assert.equal(pending.observed, true);
  });

  it('starts a new factory after a failed read', () => {
    const cache = new ReadCache();
    let calls = 0;
    const errors: unknown[] = [];
    const recovered: unknown[] = [];
    const factory = () => {
      calls += 1;
      return calls === 1 ? throwError(() => new Error('aborted')) : of({ ok: true });
    };
    cache.get('stats', 60_000, factory).subscribe({ error: (error) => errors.push(error.message) });
    cache.get('stats', 60_000, factory).subscribe((value) => recovered.push(value));
    assert.equal(calls, 2);
    assert.deepEqual(errors, ['aborted']);
    assert.deepEqual(recovered, [{ ok: true }]);
  });

  it('emits the stale value then a fresh one after the TTL expires', () => {
    const cache = new ReadCache();
    let now = 1_000;
    const originalNow = Date.now;
    Date.now = () => now;
    try {
      const values: unknown[] = [];
      cache.get('stats', 30_000, () => of({ n: 1 })).subscribe();
      now = 40_000;
      cache.get('stats', 30_000, () => of({ n: 2 })).subscribe((value) => values.push(value));
      assert.deepEqual(values, [{ n: 1 }, { n: 2 }]);
    } finally {
      Date.now = originalNow;
    }
  });
});
