import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import type { ChangeFields, ChangeProposal } from '../../core/models.ts';
import { describeChange, describeRowChange, mergeProposals } from './proposal-format.ts';

const fields = (overrides: Partial<ChangeFields> = {}): ChangeFields => ({
  kind: 'purchase', category: 'fees', subcategory: null, merchant: null,
  review_state: 'unreviewed', tags: [], ...overrides,
});

const proposal = (overrides: Partial<ChangeProposal> = {}): ChangeProposal => ({
  id: 'c1', status: 'proposed', title: 'Mark card payments',
  set: { kind: 'cc_payment', category: 'transfers', subcategory: 'card_payment' },
  add_tags: ['bill'], remove_tags: [], rows: 2, unchanged: 0,
  totals: [], spending: [], sample: [], created_at: '2026-11-06T00:00:00Z', ...overrides,
});

describe('proposal format', () => {
  it('describes what a proposal sets', () => {
    assert.equal(describeChange(proposal()), 'Card payment · Transfers › Card payment · +bill');
    assert.equal(describeChange(proposal({ set: { merchant: '' }, add_tags: [] })), 'No merchant');
  });

  it('names only what moves on a row', () => {
    const before = fields();
    const after = fields({ kind: 'cc_payment', category: 'transfers', subcategory: 'card_payment', tags: ['bill'] });
    assert.equal(describeRowChange(before, after), 'Purchase → Card payment · Fees → Transfers › Card payment · +bill');
    assert.equal(describeRowChange(before, before), '');
  });

  it('keeps each streamed proposal once, latest copy winning', () => {
    const first = proposal();
    const second = proposal({ id: 'c2' });
    const merged = mergeProposals([first], [second, { ...first, status: 'applied' }]);
    assert.deepEqual(merged?.map((p) => [p.id, p.status]), [['c1', 'applied'], ['c2', 'proposed']]);
    assert.deepEqual(mergeProposals([first], undefined), [first]);
  });
});
