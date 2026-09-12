import { spawnSync } from 'node:child_process'

if (process.env.GITHUB_ACTIONS !== 'true') {
  console.error('Local React compilation is disabled. Push a branch or java-vX.Y.Z tag and let GitHub Actions build it.')
  process.exit(2)
}

const command = process.platform === 'win32' ? 'pnpm.cmd' : 'pnpm'
for (const args of [['exec', 'tsc', '-b'], ['exec', 'vite', 'build']]) {
  const result = spawnSync(command, args, { stdio: 'inherit', shell: false })
  if (result.error) {
    console.error(result.error.message)
    process.exit(1)
  }
  if (result.status !== 0) process.exit(result.status ?? 1)
}
