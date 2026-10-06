import { test, expect } from '@playwright/test'

const machines = Array.from({ length: 50 }, (_, i) => ({ id: `machine-${i}`, name: `机器 ${String(i).padStart(2, '0')}`,
  hostname: `host-${i}`, os: 'linux', arch: 'amd64', version: 'test', default_cwd: '/srv', capabilities: ['files', 'file_transfer'], online: true }))
const entries = Array.from({ length: 55 }, (_, i) => ({ name: `中文${String(i).padStart(2, '0')}.txt`, path: `/srv/中文${String(i).padStart(2, '0')}.txt`,
  type: 'file', bytes: 100, modified_at: '2026-10-06T05:00:00Z', readable: true, writable: true }))

async function setup(page) {
  const calls = [], tasks = new Map(), requests = new Map(); let sequence = 0, loseResponse = false
  const complete = (id, result) => ({ task: { id, machine_id: 'machine-0', kind: 'files', status: 'completed', change_sequence: 2, attempt: 1, output_bytes: 0 }, result })
  await page.route('**/api/v1/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname
    const json = (body, status = 200) => route.fulfill({ status, json: body }).catch(() => {})
    if (path.endsWith('/events')) { await new Promise(resolve => setTimeout(resolve, 1000)); return json({ cursor: 1, changed: false }) }
    if (path === '/api/v1/admin/machines') return json({ items: machines, has_more: false, total: 50 })
    if (path === '/api/v1/admin/releases') return json({ items: [], available: true })
    if (/^\/api\/v1\/admin\/(tasks|upgrades|audit)$/.test(path)) return json({ items: [], has_more: false })
    const recovered = path.match(/\/files\/requests\/([^/]+)$/)
    if (recovered) return requests.has(recovered[1]) ? json(tasks.get(requests.get(recovered[1]))) : json({ error: 'task not found' }, 404)
    const observed = path.match(/\/tasks\/([^/]+)\/files$/)
    if (observed) return json(tasks.get(observed[1]))
    if (path.endsWith('/files') && request.method() === 'POST') {
      const body = request.postDataJSON(), action = body.request; calls.push(action)
      const id = `task-${++sequence}`; let result = { operation: action.operation, path: action.path }
      if (action.operation === 'list' || action.operation === 'search') {
        const offset = action.offset ?? 0, limit = action.limit ?? 25
        result = { ...result, entries: entries.slice(offset, offset + limit), offset, next_offset: Math.min(entries.length, offset + limit),
          has_more: offset + limit < entries.length, scanned: entries.length, pattern: action.pattern }
      } else if (action.operation === 'read') result = { ...result, text: '中文内容😀', encoding: 'UTF-16LE', bom: true, has_more: false, next_cursor: 20, sha256: 'a'.repeat(64) }
      else if (action.operation === 'stat') result = { ...result, entry: entries.find(item => item.path === action.path) }
      const response = complete(id, result); tasks.set(id, response); requests.set(body.idempotency_key, id)
      if (loseResponse && action.operation === 'mkdir') { loseResponse = false; return route.abort('failed') }
      return json(response)
    }
    return json({ items: [] })
  })
  const login = async () => {
    await page.locator('#admin-token').fill('ui-test')
    await page.getByRole('button', { name: '登录并连接 Center' }).click()
    await page.getByRole('button', { name: '文件与工件', exact: true }).click()
  }
  await page.goto('/'); await login(); await expect(page.locator('.file-table tbody tr')).toHaveCount(25)
  return { calls, tasks, login, loseNextCreation() { loseResponse = true } }
}

test('Chinese directory pagination, machine selection and batch copy stay compact', async ({ page }, testInfo) => {
  const fixture = await setup(page)
  await expect(page.getByLabel('选择文件管理机器').locator('option')).toHaveCount(51)
  await page.getByRole('button', { name: '下一页', exact: true }).click()
  await expect(page.getByRole('button', { name: '中文25.txt', exact: true })).toBeVisible()
  await page.getByLabel('选择 中文25.txt', { exact: true }).check()
  await page.getByLabel('选择 中文26.txt', { exact: true }).check()
  await page.getByRole('button', { name: '复制', exact: true }).click()
  await page.getByLabel('目标目录', { exact: true }).fill('/srv/备份')
  await page.getByRole('button', { name: '执行', exact: true }).click()
  await expect(page.locator('dialog')).toHaveCount(0)
  const copies = fixture.calls.filter(item => item.operation === 'copy')
  expect(copies.map(item => item.destination_path)).toEqual(['/srv/备份/中文25.txt', '/srv/备份/中文26.txt'])
  await page.screenshot({ path: testInfo.outputPath('files-desktop.png'), fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.locator('.sidebar')).not.toBeInViewport()
  await expect(page.getByRole('button', { name: '上一级', exact: true })).toBeInViewport()
  await page.screenshot({ path: testInfo.outputPath('files-mobile.png'), fullPage: true })
})

test('text editor preserves BOM, encoding and the version conflict guard', async ({ page }) => {
  const fixture = await setup(page)
  await page.locator('.file-table tbody tr').filter({ hasText: '中文00.txt' }).getByRole('button', { name: '文本', exact: true }).click()
  await expect(page.getByLabel('保留 BOM')).toBeChecked()
  await expect(page.getByLabel('文件内容')).toHaveValue('中文内容😀')
  await page.getByLabel('文件内容').fill('编辑完成😀')
  page.once('dialog', dialog => dialog.accept())
  await page.getByRole('button', { name: '保存文件' }).click()
  await expect(page.locator('dialog')).toHaveCount(0)
  const write = fixture.calls.find(item => item.operation === 'write')
  expect(write).toMatchObject({ encoding: 'UTF-16LE', bom: true, expected_sha256: 'a'.repeat(64), content: '编辑完成😀', overwrite: true })
})

test('lost creation response is recovered after reload without repeating the mutation', async ({ page }) => {
  const fixture = await setup(page); fixture.loseNextCreation()
  await page.getByRole('button', { name: '新建目录', exact: true }).click()
  await page.getByLabel('完整目标路径', { exact: true }).fill('/srv/新建目录')
  await page.getByRole('button', { name: '执行', exact: true }).click()
  await expect(page.getByRole('button', { name: '核对原任务' })).toBeVisible()
  await page.reload(); await fixture.login()
  await expect(page.locator('.file-table tbody tr')).toHaveCount(25)
  expect(fixture.calls.filter(item => item.operation === 'mkdir')).toHaveLength(1)
  await expect(page.getByRole('button', { name: '核对原任务' })).toHaveCount(0)
})
