import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  COLLAPSED_PANE_PX, REMOUNT_AFTER_MS, isDeadChunkError, isHashedAssetRequest, recoverAction,
} from './pwa-lifecycle.ts';

describe('recoverAction', () => {
  it('reloads when contentHeight is below COLLAPSED_PANE_PX', () => {
    assert.equal(recoverAction({
      contentHeight: COLLAPSED_PANE_PX - 1,
      outletActivated: true,
      hiddenMs: 100,
    }), 'reload');
  });

  it('remounts when the outlet is inactive', () => {
    assert.equal(recoverAction({
      contentHeight: 600,
      outletActivated: false,
      hiddenMs: 100,
    }), 'remount');
  });

  it('remounts when hiddenMs exceeds REMOUNT_AFTER_MS', () => {
    assert.equal(recoverAction({
      contentHeight: 600,
      outletActivated: true,
      hiddenMs: REMOUNT_AFTER_MS + 1,
    }), 'remount');
  });

  it('refreshes when the outlet is active and hiddenMs is short', () => {
    assert.equal(recoverAction({
      contentHeight: 600,
      outletActivated: true,
      hiddenMs: 500,
    }), 'refresh');
    assert.equal(recoverAction({
      contentHeight: 600,
      outletActivated: null,
      hiddenMs: 500,
    }), 'refresh');
  });
});

describe('isDeadChunkError', () => {
  it('matches lazy-import failures', () => {
    assert.equal(isDeadChunkError(new Error('Failed to fetch dynamically imported module')), true);
    assert.equal(isDeadChunkError(new Error('Loading chunk 17 failed')), true);
    assert.equal(isDeadChunkError(new Error('Loading module /chunk.js failed')), true);
    assert.equal(isDeadChunkError(new Error('NG04002: Cannot match any routes')), false);
    assert.equal(isDeadChunkError(undefined), false);
  });
});

describe('isHashedAssetRequest', () => {
  it('matches .js/.css/.woff2 paths with an empty destination', () => {
    assert.equal(isHashedAssetRequest('', '/chunk-OMJ4Q2G2.js'), true);
    assert.equal(isHashedAssetRequest('script', '/main-HIU3YEL5.js'), true);
    assert.equal(isHashedAssetRequest('style', '/styles-BYDBHGVK.css'), true);
    assert.equal(isHashedAssetRequest('font', '/ibm-plex.woff2'), true);
    assert.equal(isHashedAssetRequest('', '/api/summary'), false);
    assert.equal(isHashedAssetRequest('document', '/summary'), false);
  });
});
