import { defineConfig } from '@playwright/test'

// Formal browser validation runs against the Actions-built Console only.
if (process.env.GITHUB_ACTIONS !== 'true') throw new Error('Console browser tests run in GitHub Actions')
export default defineConfig({
  testDir: './e2e', workers: 1, timeout: 30000, retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: { baseURL: 'http://127.0.0.1:4173', browserName: 'chromium', viewport: { width: 1440, height: 1000 }, trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  webServer: { command: 'pnpm exec vite preview --host 127.0.0.1 --port 4173 --strictPort', url: 'http://127.0.0.1:4173', timeout: 15000, reuseExistingServer: false },
})
