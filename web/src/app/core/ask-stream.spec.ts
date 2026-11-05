import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { SseParser } from './ask-stream.ts';

describe('SseParser', () => {
  it('reassembles events split across network chunks and skips comments', () => {
    const parser = new SseParser();
    assert.deepEqual(parser.push(': open\n\nevent: text\ndata: {"del'), []);
    assert.deepEqual(parser.push('ta":"Hi"}\n\nevent: done\r\ndata: {"ok":true}\r\n\r\n'), [
      { event: 'text', data: { delta: 'Hi' } },
      { event: 'done', data: { ok: true } },
    ]);
  });

  it('drops a malformed event without losing the next one', () => {
    const parser = new SseParser();
    assert.deepEqual(parser.push('event: text\ndata: {oops\n\nevent: done\ndata: {}\n\n'), [
      { event: 'done', data: {} },
    ]);
  });
});
