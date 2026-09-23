import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const sourcePath = path.join(root, 'java', 'center', 'src', 'main', 'java', 'com', 'prodigalgal', 'remoteconnectmcp', 'center', 'McpConfiguration.java');
const goldenPath = path.join(root, 'docs', 'mcp-tool-surface.json');
const source = await fs.readFile(sourcePath, 'utf8');
const golden = JSON.parse(await fs.readFile(goldenPath, 'utf8'));

const runtimeFiles = [
  'java/center/src/main/java',
  'java/agent/src/main/java',
  'java/desktop/src/main/java',
  'java/protocol/src/main/java',
  'java/center/src/main/resources/mcp',
  'web/src/artifact-viewer',
  'scripts/install-agent.ps1',
  'scripts/install-agent.sh',
  'scripts/first-install-agent.ps1',
  'scripts/first-install-agent.sh',
  'scripts/deploy-desktop-browser.ps1',
  'scripts/browser-worker.mjs',
  'scripts/browser-runtime',
  'scripts/smoke-java.ps1',
  'scripts/smoke-java.sh',
  'scripts/smoke-java-agent.ps1',
  'scripts/smoke-java-agent.sh',
  'scripts/smoke-java-websocket.ps1',
  'scripts/smoke-java-websocket.sh',
  'scripts/build-native.ps1',
  'scripts/build-native.sh',
  'java/agent/build.gradle.kts',
  'java/center/build.gradle.kts',
  'java/desktop/build.gradle.kts',
  'java/browser/build.gradle.kts',
  'java/Dockerfile.agent.native',
  'java/Dockerfile.center.native',
  'java/Dockerfile.desktop.native',
  'java/Dockerfile.browser.native'
];

async function collectFiles(relativePath) {
  const absolutePath = path.join(root, relativePath);
  const stat = await fs.stat(absolutePath);
  if (stat.isFile()) return [absolutePath];
  const entries = await fs.readdir(absolutePath, { withFileTypes: true });
  const nested = await Promise.all(entries.map((entry) => collectFiles(path.join(relativePath, entry.name))));
  return nested.flat();
}

const runtimePaths = (await Promise.all(runtimeFiles.map(collectFiles))).flat();
const runtimeSources = await Promise.all(runtimePaths.map(async (file) => ({
  file,
  source: await fs.readFile(file, 'utf8')
})));

const count = (needle) => source.split(needle).length - 1;
const errors = [];

for (const removedPath of [
  'go.mod',
  'go.sum',
  'cmd/remote-connect-mcp-agent',
  'cmd/remote-connect-mcp-center',
  'internal',
  'Dockerfile.center',
  '.github/workflows/ci.yml',
  '.github/workflows/release.yml',
  'deploy/k8s/center',
  'scripts/build-all.ps1',
  'scripts/build-all.sh',
  'docs/GO_RETIREMENT.md'
]) {
  try {
    await fs.access(path.join(root, removedPath));
    errors.push(`removed Go/runtime path is still present: ${removedPath}`);
  } catch {
    // The path is intentionally absent after the hard switch.
  }
}

for (const name of golden.tools) {
  if (count(`tool("${name}"`) !== 1) errors.push(`expected exactly one public tool registration: ${name}`);
}
for (const name of golden.must_not_register) {
  if (source.includes(`tool("${name}"`)) errors.push(`removed tool is still registered: ${name}`);
}
for (const forbidden of ['legacyToolSpecs', 'rcm.mcp.legacy-tools', 'onToolOutput', 'uploadWholeTransfer', '--import-go']) {
  if (source.includes(forbidden)) errors.push(`forbidden compatibility path remains in Center source: ${forbidden}`);
}
for (const forbidden of [
  'REMOTE_CONNECT_MCP_CENTER_ENROLLMENT_TOKEN',
  'RCM_CENTER_ALLOW_SHARED_ENROLLMENT',
  'RCM_BROWSER_TASK_COMMAND',
  'onToolOutput',
  'onToolResult',
  'openai/widgetCSP',
  'openai/widgetDomain',
  'uploadWholeTransfer',
  '--import-go',
  'config.archive()',
  'boolean archive',
  'executableSuffix()',
  "ArgumentList.Add('-jar')",
  'java -jar',
  'JarPath',
  'centerJar',
  'agentJar',
  'orElse("compatibility")',
  'RCM_NATIVE_MARCH=compatibility'
]) {
  for (const entry of runtimeSources) {
    if (entry.source.includes(forbidden)) {
      errors.push(`removed runtime entry remains in ${path.relative(root, entry.file)}: ${forbidden}`);
    }
  }
}
if (!source.includes('.tools(modelToolSpecs(')) errors.push('Center is not wired to the single model tool surface');
if (count('additionalProperties') < 6) errors.push('strict schema guard count is unexpectedly low');
if (!source.includes('toolBuilder.outputSchema(modelOutputSchema())')) errors.push('all model tools must publish outputSchema');
if (!source.includes('openai/fileParams')) errors.push('artifact tool must declare ChatGPT file input parameters');
if (!source.includes('artifactUi.put("resourceUri"')) errors.push('artifact tool must publish standard MCP Apps ui.resourceUri metadata');
if (source.includes('ui/resourceUri')) errors.push('legacy flat MCP Apps resource metadata remains in Center source');
if (source.includes('openai/outputTemplate')) errors.push('ChatGPT-only output template alias remains in Center source');

if (errors.length) {
  console.error(errors.join('\n'));
  process.exit(1);
}
console.log(`MCP tool surface OK: ${golden.tools.join(', ')}`);
