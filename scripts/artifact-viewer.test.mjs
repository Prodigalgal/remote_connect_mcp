import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { runInNewContext } from 'node:vm';
import { test } from 'node:test';
import { Blob, File } from 'node:buffer';
import { webcrypto } from 'node:crypto';

const html = await readFile(new URL('../web/src/artifact-viewer/artifact-viewer-v1.html', import.meta.url), 'utf8');
const bundled = await readFile(new URL('../java/center/src/main/resources/mcp/artifact-viewer-v1.html', import.meta.url), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];

function viewer(openai = {}, options = {}) {
  const nodes = new Map();
  const listeners = new Map();
  const root = { dataset: {} };
  const element = id => {
    if (!nodes.has(id)) nodes.set(id, { hidden: true, textContent: '', removeAttribute(key) { delete this[key]; } });
    return nodes.get(id);
  };
  const window = { openai, crypto: webcrypto, parent: {}, addEventListener(type, callback) { listeners.set(type, callback); } };
  runInNewContext(script, { window, document: { getElementById: element, documentElement: root }, URL, Blob, File,
    AbortController, setTimeout, clearTimeout,
    fetch: options.fetch ?? (() => { throw new Error('render must not download files'); }) });
  return {
    node: element, root,
    async result(data, source) {
      listeners.get('message')({ source, data: { method: 'ui/notifications/tool-result', params: { structuredContent: data } } });
      await Promise.resolve();
    }
  };
}

test('canonical and bundled viewers agree', () => assert.equal(bundled, html));

test('ready file is a compact themed link with no automatic upload', async () => {
  let uploads = 0;
  let hostFileReads = 0;
  const ui = viewer({ theme: 'dark', uploadFile() { uploads++; }, getFileDownloadUrl() { hostFileReads++; } });
  await ui.result({ transfer: { status: 'delivered' }, file: { artifact_id: 'rcm-file', file_name: '中文报告.docx',
    bytes: 1234, mime_type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    download_url: 'https://files.example.test/report?signature=test', preview_url: 'https://files.example.test/preview' } });
  assert.equal(ui.root.dataset.theme, 'dark');
  assert.equal(ui.node('name').textContent, '中文报告.docx');
  assert.equal(ui.node('meta').textContent, 'Word · 1,234 字节');
  assert.equal(ui.node('download').hidden, false);
  assert.equal(ui.node('open').href, 'https://files.example.test/preview');
  assert.equal(ui.node('save').hidden, false);
  assert.equal(uploads, 0);
  assert.equal(hostFileReads, 0, 'RCM ids must not be used as host file ids');
});

test('pending and failed results clear previous file actions and show the actual state', async () => {
  const ui = viewer();
  await ui.result({ transfer: { status: 'delivered' }, file: { file_name: 'old.txt', bytes: 1, download_url: 'https://files.example.test/old' } });
  await ui.result({ task: { id: 'task-pending', status: 'queued' }, transfer: { status: 'pending' } });
  assert.equal(ui.node('download').hidden, true);
  assert.equal(ui.node('download').href, undefined);
  assert.match(ui.node('meta').textContent, /task_read.*task-pending/);
  await ui.result({ task: { status: 'failed', error: 'source is not a regular file' }, transfer: { status: 'pending' } });
  assert.equal(ui.node('download').hidden, true);
  assert.equal(ui.node('meta').textContent, 'source is not a regular file');
  await ui.result({ error: { code: 'forbidden', message: 'permission revoked' } });
  assert.equal(ui.node('meta').textContent, 'permission revoked');
});

test('missing links and unsafe URLs cannot produce a download action', async () => {
  const ui = viewer();
  await ui.result({ transfer: { status: 'delivered' }, file: { artifact_id: 'a', download_url: 'javascript:alert(1)' } });
  assert.equal(ui.node('download').hidden, true);
  assert.match(ui.node('meta').textContent, /artifact\(operation=read\)/);
});

const ready = (extra = {}) => ({ transfer: { status: 'delivered' }, file: {
  artifact_id: 'rcm-chinese-file', file_name: '中文报告.xlsx', bytes: 5,
  mime_type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  download_url: 'https://files.example.test/report?signature=private', ...extra } });

test('explicit upload passes a real file and remembers only the actual host file reference', async () => {
  const uploads = [], states = [], requests = [];
  const bridge = { async uploadFile(file) { uploads.push(file); return { fileId: 'file-host-real' }; },
    async setWidgetState(state) { states.push(state); }, notifyIntrinsicHeight() {} };
  const ui = viewer(bridge, { async fetch(url, options) { requests.push({ url, options }); return new Response(new Blob(['hello'])); } });
  await ui.result(ready());
  assert.equal(requests.length, 0);
  await ui.node('save').onclick();
  assert.equal(uploads.length, 1); assert.equal(uploads[0].name, '中文报告.xlsx');
  assert.equal(await uploads[0].text(), 'hello');
  assert.equal(requests[0].options.credentials, 'omit');
  assert.equal(states[0].modelContent.file.file_id, 'file-host-real');
  assert.equal(states[0].privateContent.savedFiles['rcm-chinese-file'].file_id, 'file-host-real');
  assert.equal(JSON.stringify(states).includes('signature=private'), false);
  await ui.result(ready()); await ui.node('save').onclick();
  assert.equal(uploads.length, 1, 'rerendering must not upload the same artifact twice');
  assert.equal(ui.node('save').textContent, '已添加到 ChatGPT');
  const restored = viewer({ ...bridge, widgetState: states[0] });
  await restored.result(ready()); assert.equal(restored.node('save').disabled, true);
});

test('file length and SHA failures cannot upload corrupt data to the host', async () => {
  for (const file of [ready({ bytes: 6 }), ready({ sha256: '0'.repeat(64) })]) {
    let uploads = 0;
    const ui = viewer({ uploadFile() { uploads++; } }, { async fetch() { return new Response('hello'); } });
    await ui.result(file); await ui.node('save').onclick();
    assert.equal(uploads, 0); assert.equal(ui.node('save').disabled, false);
    assert.match(ui.node('save').textContent, /不完整|校验失败/);
  }
});

test('failed host registration remains retryable and cannot invent a file id', async () => {
  let calls = 0, states = 0;
  const ui = viewer({ async uploadFile() { calls++; return {}; }, setWidgetState() { states++; } },
    { async fetch() { return new Response('hello'); } });
  await ui.result(ready()); await ui.node('save').onclick();
  assert.equal(ui.node('save').disabled, false); assert.equal(states, 0);
  assert.match(ui.node('save').textContent, /有效文件标识/);
  await ui.node('save').onclick(); assert.equal(calls, 2);
});

test('state synchronization retries reuse the uploaded file instead of uploading it twice', async () => {
  let uploads = 0, states = 0;
  const ui = viewer({ async uploadFile() { uploads++; return { fileId: 'file-once' }; },
    async setWidgetState(state) { states++; if (states === 1) throw new Error('bridge disconnected'); assert.equal(state.modelContent.file.file_id, 'file-once'); } },
    { async fetch() { return new Response('hello'); } });
  await ui.result(ready()); await ui.node('save').onclick();
  assert.equal(ui.node('save').disabled, false); assert.match(ui.node('save').textContent, /重试保存引用/);
  await ui.result(ready()); await ui.node('save').onclick();
  assert.equal(uploads, 1); assert.equal(states, 2); assert.equal(ui.node('save').disabled, true);
});

test('unrelated windows cannot replace the file result', async () => {
  const ui = viewer(); await ui.result(ready());
  await ui.result(ready({ file_name: 'spoofed.txt' }), {});
  assert.equal(ui.node('name').textContent, '中文报告.xlsx');
});

test('clients without host upload and oversized files retain ordinary download links', async () => {
  for (const [bridge, data] of [[{}, ready()], [{ uploadFile() {} }, ready({ bytes: 33 * 1024 * 1024 })]]) {
    const ui = viewer(bridge); await ui.result(data);
    assert.equal(ui.node('save').hidden, true); assert.equal(ui.node('download').hidden, false);
  }
});
