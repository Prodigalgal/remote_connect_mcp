import assert from 'node:assert/strict';
import test from 'node:test';
import { decodeReference, encodeReference, executeAction, normalizeCommand, serializeOutput, withEventSummary } from './browser-worker.mjs';

function fakePage(snapshotError = false) {
  const state = { clicks: 0, timeout: 0 };
  const target = {
    first() { return this; },
    async click() { state.clicks++; },
    async fill(value) { state.value = value; },
    async ariaSnapshot() { if (snapshotError) throw new Error('observation unavailable'); return '- button "Ready"'; },
    async evaluateAll() { return []; },
  };
  return {
    state,
    setDefaultTimeout(value) { state.timeout = value; },
    locator() { return target; },
    url() { return 'https://example.test/'; },
    async title() { return 'Ready'; },
    async goto(url, options) { state.navigationTimeout = options.timeout; },
    async waitForTimeout(value) { state.wait = value; },
  };
}

test('a long Chinese reference round trips without shortening its identity', () => {
  const descriptor = { kind: 'role', role: 'button', name: '中文'.repeat(60), exact: true, nth: 0 };
  const ref = encodeReference(descriptor);
  assert.ok(ref.length > 256 && ref.length <= 2048);
  assert.deepEqual(decodeReference(ref), descriptor);
  assert.throws(() => normalizeCommand({ action: 'click', ref, selector: 'button' }), /exactly one/);
});

test('action timeout applies to navigation and is capped by task timeout', async () => {
  const page = fakePage();
  await executeAction(page, normalizeCommand({ action: 'navigate', url: page.url(), timeout_ms: 1200 }), 900);
  assert.equal(page.state.timeout, 900);
  assert.equal(page.state.navigationTimeout, 900);
});

test('include_snapshot observes after one click; observation failure does not replay the effect', async () => {
  const page = fakePage();
  const result = await executeAction(page, normalizeCommand({ action: 'click', selector: 'button', include_snapshot: true }));
  assert.equal(page.state.clicks, 1);
  assert.equal(JSON.parse(result.output).snapshot, '- button "Ready"');
  const failing = fakePage(true);
  const observed = await executeAction(failing, normalizeCommand({ action: 'click', selector: 'button', include_snapshot: true }));
  assert.equal(failing.state.clicks, 1);
  assert.match(JSON.parse(observed.output).observation_error, /unavailable/);
});

test('empty fill clears a field and wait duration remains distinct from action timeout', async () => {
  const page = fakePage();
  await executeAction(page, normalizeCommand({ action: 'fill', selector: 'input', text: '' }));
  assert.equal(page.state.value, '');
  await assert.rejects(executeAction(page, normalizeCommand({ action: 'wait', wait_ms: 200, timeout_ms: 100 })), /exceeds/);
  await executeAction(page, normalizeCommand({ action: 'wait', wait_ms: 50, timeout_ms: 100 }));
  assert.equal(page.state.wait, 50);
});

test('bounded output is valid JSON with intact references, Unicode and manifest escaping', () => {
  const refs = Array.from({ length: 64 }, (_, nth) => ({
    ref: encodeReference({ kind: 'role', role: 'button', name: '按钮'.repeat(80), exact: true, nth }),
  }));
  const output = serializeOutput({ operation: 'snapshot', snapshot: '\n"😀中文\\'.repeat(20000), elements: refs }, 16 * 1024);
  const payload = JSON.parse(output);
  assert.equal(payload.truncated, true);
  assert.ok(Buffer.byteLength(output) <= 16 * 1024);
  assert.ok(Buffer.byteLength(JSON.stringify({ status: 'completed', output })) <= 64 * 1024);
  assert.ok(!output.includes('\uFFFD'));
  for (const element of payload.elements) assert.equal(decodeReference(element.ref).role, 'button');
});

test('warnings stay visible while full bounded diagnostics remain separately retrievable', () => {
  const result = withEventSummary({ output: '{"operation":"navigate"}' }, {
    network: [{ type: 'response', status: 200 }, { type: 'response', status: 500 }],
    console: [{ type: 'log', text: 'routine' }, { type: 'warn', text: 'warning' }],
    pageErrors: [{ text: 'page failed' }],
  });
  const payload = JSON.parse(result.output);
  assert.equal(payload.diagnostics.console.length, 2);
  assert.equal(payload.warning_count, 3);
  assert.equal(payload.warnings.length, 3);
});
