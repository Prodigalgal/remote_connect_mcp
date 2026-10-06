import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { runInNewContext } from 'node:vm';
import { test } from 'node:test';

const html = await readFile(new URL('../web/src/artifact-viewer/artifact-viewer-v1.html', import.meta.url), 'utf8');
const bundled = await readFile(new URL('../java/center/src/main/resources/mcp/artifact-viewer-v1.html', import.meta.url), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];

function viewer(openai = {}) {
  const nodes = new Map();
  const listeners = new Map();
  const root = { dataset: {} };
  const element = id => {
    if (!nodes.has(id)) nodes.set(id, { hidden: true, textContent: '', removeAttribute(key) { delete this[key]; } });
    return nodes.get(id);
  };
  const window = { openai, addEventListener(type, callback) { listeners.set(type, callback); } };
  runInNewContext(script, { window, document: { getElementById: element, documentElement: root }, URL,
    fetch() { throw new Error('render must not download files'); } });
  return {
    node: element, root,
    async result(data) {
      listeners.get('message')({ data: { method: 'ui/notifications/tool-result', params: { structuredContent: data } } });
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
