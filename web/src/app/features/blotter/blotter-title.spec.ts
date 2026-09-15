import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { showRawDescription } from './blotter-title.ts';

describe('showRawDescription', () => {
  it('hides an HSBC ref line once a readable merchant is set', () => {
    assert.equal(
      showRawDescription({
        merchant: 'EveryMile',
        description: 'N82079482392(20AUG26) QUBE R & T HK LTD',
      }),
      false,
    );
  });

  it('keeps a distinct subtitle that is not issuer noise', () => {
    assert.equal(
      showRawDescription({
        merchant: 'Qube R & T',
        description: 'Bonus / 13th month',
      }),
      true,
    );
  });

  it('does not emit a subtitle when merchant and description match', () => {
    assert.equal(
      showRawDescription({ merchant: 'Mox', description: 'Mox' }),
      false,
    );
  });
});
