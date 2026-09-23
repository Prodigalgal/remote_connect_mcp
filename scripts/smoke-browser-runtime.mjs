import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

const server = createServer((_request, response) => {
  response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  response.end('<!doctype html><title>RCM browser smoke</title><h1>Ready</h1>');
});
const temporary = await mkdtemp(path.join(tmpdir(), 'rcm-browser-smoke-'));
try {
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const address = server.address();
  if (!address || typeof address === 'string') throw new Error('HTTP listener has no port');
  const request = path.join(temporary, 'request.json');
  const result = path.join(temporary, 'result.json');
  await writeFile(request, JSON.stringify({ command: JSON.stringify({
    action: 'navigate', url: `http://127.0.0.1:${address.port}/`,
  }) }));
  const worker = path.resolve('scripts/browser-runtime/browser-worker.mjs');
  const child = spawn(process.execPath, [worker], {
    env: {
      ...process.env,
      RCM_BROWSER_TASK_REQUEST_FILE: request,
      RCM_BROWSER_RESULT_FILE: result,
      RCM_BROWSER_ARTIFACT_DIR: temporary,
      RCM_BROWSER_HEADLESS: '1',
      RCM_BROWSER_TASK_TIMEOUT_SECONDS: '60',
    },
    stdio: ['ignore', 'ignore', 'pipe'],
  });
  let errorText = '';
  child.stderr.setEncoding('utf8');
  child.stderr.on('data', (chunk) => { errorText = (errorText + chunk).slice(0, 4096); });
  const exitCode = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => { child.kill(); reject(new Error('browser worker timed out')); }, 90_000);
    child.once('error', (error) => { clearTimeout(timer); reject(error); });
    child.once('exit', (code) => { clearTimeout(timer); resolve(code); });
  });
  const output = JSON.parse(await readFile(result, 'utf8'));
  if (exitCode !== 0 || output.status !== 'completed' || !output.output.includes('RCM browser smoke')) {
    throw new Error(`Camoufox navigation failed: ${errorText || output.error || output.status}`);
  }
  process.stdout.write('Camoufox navigation OK\n');
} finally {
  server.close();
  await rm(temporary, { recursive: true, force: true });
}
