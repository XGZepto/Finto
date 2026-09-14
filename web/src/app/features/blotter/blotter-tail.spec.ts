import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { blotterTailDue } from './blotter-tail.ts';

describe('blotterTailDue', () => {
  const base = {
    hasMore: true,
    busy: false,
    sentinelTop: 2000,
    rootBottom: 800,
    scrollRemain: 900,
  };

  it('does not load when the list is complete or a page is in flight', () => {
    assert.equal(blotterTailDue({ ...base, hasMore: false, scrollRemain: 0 }), false);
    assert.equal(blotterTailDue({ ...base, busy: true, scrollRemain: 0 }), false);
  });

  it('loads when the sentinel sits in the prefetch zone', () => {
    assert.equal(blotterTailDue({ ...base, sentinelTop: 900, scrollRemain: 900 }), true);
  });

  it('loads when the pane is at the end even if sentinel geometry is missing', () => {
    assert.equal(blotterTailDue({ ...base, sentinelTop: null, scrollRemain: 40 }), true);
  });

  it('waits when the tail is still well below the fold', () => {
    assert.equal(blotterTailDue(base), false);
  });
});
