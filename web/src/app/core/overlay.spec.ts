import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { placeOverlay } from './overlay.ts';

function rect(x: number, y: number, width: number, height: number): DOMRect {
  return { left: x, right: x + width, top: y, bottom: y + height, width, height } as DOMRect;
}

describe('placeOverlay', () => {
  it('grows a narrow trigger and flips left before the viewport edge', () => {
    const previous = 'window' in globalThis ? window : undefined;
    Object.defineProperty(globalThis, 'window', {
      configurable: true,
      value: {
        innerWidth: 1440,
        innerHeight: 900,
        matchMedia: (query: string) => ({ matches: false, media: query }),
      },
    });
    const placed = placeOverlay(rect(1340, 120, 78, 36), { minWidth: 260, maxWidth: 360 });
    assert.ok(placed);
    assert.equal(placed.style['width'], '260px');
    assert.equal(placed.style['position'], 'fixed');
    const left = Number.parseInt(placed.style['left'], 10);
    assert.equal(left + 260 <= 1432, true);
    assert.equal(placed.dropUp, false);
    if (previous === undefined) delete (globalThis as { window?: unknown }).window;
    else Object.defineProperty(globalThis, 'window', { configurable: true, value: previous });
  });
});
