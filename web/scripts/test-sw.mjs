import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const root = dirname(fileURLToPath(import.meta.url));
const source = readFileSync(join(root, '../public/sw.js'), 'utf8');

class FakeResponse {
  constructor(body, init = {}) {
    this.body = body;
    this.status = init.status ?? 200;
    this.ok = this.status >= 200 && this.status < 300;
    this.type = init.type ?? 'basic';
  }
  clone() { return new FakeResponse(this.body, { status: this.status, type: this.type }); }
}

function loadWorker({ network, cacheSeed = new Map() } = {}) {
  const buckets = new Map([['finto-shell-v4', new Map(cacheSeed)]]);
  const fetches = [];
  const listeners = new Map();

  const caches = {
    async open(name) {
      if (!buckets.has(name)) buckets.set(name, new Map());
      const bucket = buckets.get(name);
      const keyOf = (request) => typeof request === 'string' ? request : request.url;
      return {
        async addAll(urls) {
          for (const url of urls) bucket.set(url, new FakeResponse('shell'));
        },
        async match(request) { return bucket.get(keyOf(request)); },
        async put(request, response) { bucket.set(keyOf(request), response); },
      };
    },
    async match(request) {
      const key = typeof request === 'string' ? request : request.url;
      for (const bucket of buckets.values()) {
        if (bucket.has(key)) return bucket.get(key);
      }
      return undefined;
    },
    async keys() { return [...buckets.keys()]; },
    async delete(name) { return buckets.delete(name); },
  };

  const context = {
    CACHE: undefined,
    caches,
    fetch: async (request) => {
      fetches.push(typeof request === 'string' ? request : request.url);
      if (typeof network === 'function') return network(request);
      throw new Error('network down');
    },
    URL,
    self: {
      location: { origin: 'https://finto.app' },
      clients: { claim() {} },
      addEventListener(type, handler) {
        const list = listeners.get(type) ?? [];
        list.push(handler);
        listeners.set(type, list);
      },
    },
  };
  context.self.clients = context.self.clients;
  vm.runInNewContext(source, context, { filename: 'sw.js' });
  return { listeners, fetches, buckets };
}

async function dispatchFetch(worker, request) {
  const event = {
    request,
    response: undefined,
    respondWith(value) { this.response = Promise.resolve(value); },
    waitUntil() {},
  };
  for (const handler of worker.listeners.get('fetch') ?? []) handler(event);
  return event.response ? event.response : undefined;
}

describe('service worker', () => {
  it('serves a cached .js file with an empty destination', async () => {
    const chunk = 'https://finto.app/chunk-OMJ4Q2G2.js';
    const worker = loadWorker({
      cacheSeed: new Map([[chunk, new FakeResponse('lazy-summary')]]),
    });
    const response = await dispatchFetch(worker, {
      method: 'GET',
      mode: 'cors',
      destination: '',
      url: chunk,
    });
    assert.ok(response, 'fetch handler must respond for hashed JS');
    assert.equal(response.body, 'lazy-summary');
    assert.equal(worker.fetches.length, 1);
  });

  it('leaves /api/ requests unhandled', async () => {
    const worker = loadWorker();
    const response = await dispatchFetch(worker, {
      method: 'GET',
      mode: 'cors',
      destination: '',
      url: 'https://finto.app/api/summary?group_by=category',
    });
    assert.equal(response, undefined);
    assert.deepEqual(worker.fetches, []);
  });

  it('falls back to cached index.html when a navigation fetch fails', async () => {
    const worker = loadWorker({
      cacheSeed: new Map([['/index.html', new FakeResponse('<!doctype html>')]]),
    });
    const response = await dispatchFetch(worker, {
      method: 'GET',
      mode: 'navigate',
      destination: 'document',
      url: 'https://finto.app/summary',
    });
    assert.equal(response.body, '<!doctype html>');
  });
});
