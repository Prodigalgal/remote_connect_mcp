export type Machine = {
  id: string
  name: string
  hostId: string
  hostname?: string
  os?: string
  arch?: string
  version?: string
  defaultCwd?: string
  scopeMode?: string
  workspaceRoot?: string
  capabilities: string[]
  runtime?: AgentRuntimeDescriptor
  createdAt?: string
  lastSeen?: string
  online: boolean
}

export type AgentRuntimeDescriptor = {
  schemaVersion: number
  configGeneration: number
  maxConcurrency: number
  maxBrowserWorkers: number
  maxOutputBytes: number
  maxAggregateOutputBytes: number
  maxChildProcesses: number
  maxTotalChildProcesses: number
  maxTaskDurationSeconds: number
  maxRssBytes: number
  maxCpuSeconds: number
  desktopEnabled: boolean
  browserAdapterConfigured: boolean
  resourceEnforcement: string
  scopeMode: string
  desktopSessionAvailable: boolean
  browserSessionAvailable: boolean
}

export type AuditEvent = {
  id: string
  eventType: string
  actor: string
  agentId?: string
  taskId?: string
  scopeMode?: string
  risk?: string
  outcome?: string
  detail?: string
  createdAt?: string
}

export type AgentConfig = {
  generation: number
  pollIntervalMs: number
  maxConcurrency: number
}

export type Task = {
  id: string
  machineId: string
  kind: string
  requiredCapability?: string
  command?: string
  cwd?: string
  timeoutSeconds: number
  status: string
  attempt: number
  exitCode?: number
  error?: string
  outputBytes: number
  outputTruncated: boolean
  createdAt?: string
  dispatchedAt?: string
  startedAt?: string
  finishedAt?: string
  artifactBytes: number
  artifactMime?: string
  artifactSha256?: string
  scopeMode?: string
  projectId?: string
  worktreeId?: string
  scopeRoot?: string
  risk?: string
  contractExpiresAt?: string
}

export type TaskOutputPage = {
  text: string
  cursor: number
  nextCursor: number
  more: boolean
}

export type UpgradeTarget = {
  machineId: string
  status: string
  error?: string
  attempts: number
  updatedAt?: string
  finishedAt?: string
  leaseUntil?: string
}

export type UpgradeCampaign = {
  id: string
  version: string
  status: string
  canaryCount: number
  batchSize: number
  activeLimit: number
  targets: UpgradeTarget[]
  createdAt?: string
  updatedAt?: string
  finishedAt?: string
}

export type ReleaseAsset = {
  os: string
  arch: string
  fileName: string
  available: boolean
  checksumAvailable: boolean
}

export type Release = {
  version: string
  tag: string
  name: string
  publishedAt?: string
  prerelease: boolean
  assets: ReleaseAsset[]
}

export type ReleaseCatalog = {
  items: Release[]
  refreshedAt?: string
  stale: boolean
  available: boolean
  warning?: string
}

/** Bounded list metadata returned by Center admin projections. */
export type PageResult<T> = {
  items: T[]
  offset: number
  limit: number
  total: number
  hasMore: boolean
}

export type Worktree = {
  id: string
  projectId: string
  ref: string
  path: string
  operation: string
  status: string
  taskId?: string
  createdAt?: string
  updatedAt?: string
}

export type Project = {
  id: string
  machineId: string
  name: string
  rootPath: string
  repositoryPath: string
  defaultRef: string
  createdAt?: string
  updatedAt?: string
  worktrees: Worktree[]
}

export class AdminApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

const apiBase = (import.meta.env.VITE_RCM_API_BASE ?? '').replace(/\/$/, '')

async function request<T>(path: string, token: string, init?: RequestInit, timeoutMs = 8000, externalSignal?: AbortSignal): Promise<T> {
  if (!token.trim()) {
    throw new AdminApiError(0, '请输入 Admin Token')
  }
  const controller = new AbortController()
  const abortFromOutside = () => controller.abort()
  externalSignal?.addEventListener('abort', abortFromOutside, { once: true })
  if (externalSignal?.aborted) controller.abort()
  const timeout = window.setTimeout(() => controller.abort(), timeoutMs)
  try {
    const response = await fetch(`${apiBase}${path}`, {
      ...init,
      signal: controller.signal,
      headers: {
        Accept: 'application/json',
        Authorization: `Bearer ${token.trim()}`,
        ...(init?.headers ?? {}),
      },
    })
    const body = await response.json().catch(() => ({}))
    if (!response.ok) {
      throw new AdminApiError(response.status, body.error ?? `请求失败（${response.status}）`)
    }
    return body as T
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new AdminApiError(0, 'Center 请求超时')
    }
    throw error
  } finally {
    window.clearTimeout(timeout)
    externalSignal?.removeEventListener('abort', abortFromOutside)
  }
}

export async function waitForAdminChange(token: string, cursor = 0, waitMs = 25_000, signal?: AbortSignal): Promise<{ cursor: number; changed: boolean }> {
  const boundedWait = Math.min(25_000, Math.max(0, Math.trunc(waitMs)))
  const body = await request<Record<string, unknown>>(
    `/api/v1/admin/events?cursor=${Math.max(0, Math.trunc(cursor))}&wait_ms=${boundedWait}`,
    token,
    undefined,
    boundedWait + 5000,
    signal,
  )
  return {
    cursor: Number(body.cursor ?? cursor),
    changed: Boolean(body.changed),
  }
}

export async function listMachines(token: string): Promise<Machine[]> {
  return (await listMachinesPage(token, 0, 200)).items
}

export async function listMachinesPage(token: string, offset = 0, limit = 200): Promise<PageResult<Machine>> {
  const boundedOffset = Math.max(0, Math.trunc(offset))
  const boundedLimit = Math.min(200, Math.max(1, Math.trunc(limit)))
  const response = await request<{ items: Array<Record<string, unknown>>; offset?: number; limit?: number; total?: number; has_more?: boolean }>(`/api/v1/admin/machines?offset=${boundedOffset}&limit=${boundedLimit}`, token)
  const items = (response.items ?? []).map((item) => ({
    id: String(item.id ?? ''),
    name: String(item.name ?? ''),
    hostId: String(item.host_id ?? ''),
    hostname: item.hostname as string | undefined,
    os: item.os as string | undefined,
    arch: item.arch as string | undefined,
    version: item.version as string | undefined,
    defaultCwd: item.default_cwd as string | undefined,
    scopeMode: item.scope_mode as string | undefined,
    workspaceRoot: item.workspace_root as string | undefined,
    capabilities: Array.isArray(item.capabilities) ? item.capabilities.map(String) : [],
    runtime: mapRuntime(item.runtime),
    createdAt: item.created_at as string | undefined,
    lastSeen: item.last_seen as string | undefined,
    online: Boolean(item.online),
  }))
  return pageResult(items, response, boundedOffset, boundedLimit)
}

function pageResult<T>(items: T[], response: { offset?: number; limit?: number; total?: number; has_more?: boolean }, offset: number, limit: number): PageResult<T> {
  const actualOffset = Number.isFinite(Number(response.offset)) ? Math.max(0, Number(response.offset)) : offset
  const actualLimit = Number.isFinite(Number(response.limit)) ? Math.max(1, Number(response.limit)) : limit
  const total = Number.isFinite(Number(response.total)) ? Math.max(0, Number(response.total)) : actualOffset + items.length
  return {
    items,
    offset: actualOffset,
    limit: actualLimit,
    total,
    hasMore: response.has_more === undefined ? actualOffset + items.length < total : Boolean(response.has_more),
  }
}

function mapRuntime(value: unknown): AgentRuntimeDescriptor | undefined {
  if (!value || typeof value !== 'object') return undefined
  const item = value as Record<string, unknown>
  const maxConcurrency = Number(item.max_concurrency ?? 1)
  const legacyTotal = Math.min(256, Math.max(32, Math.max(1, maxConcurrency) * 32))
  return {
    schemaVersion: Number(item.schema_version ?? 1),
    configGeneration: Number(item.config_generation ?? 0),
    maxConcurrency,
    maxBrowserWorkers: Number(item.max_browser_workers ?? 1),
    maxOutputBytes: Number(item.max_output_bytes ?? 0),
    maxAggregateOutputBytes: Number(item.max_aggregate_output_bytes ?? 0),
    maxChildProcesses: Number(item.max_child_processes ?? 0),
    maxTotalChildProcesses: Number(item.max_total_child_processes ?? legacyTotal),
    maxTaskDurationSeconds: Number(item.max_task_duration_seconds ?? 0),
    maxRssBytes: Number(item.max_rss_bytes ?? 0),
    maxCpuSeconds: Number(item.max_cpu_seconds ?? 0),
    desktopEnabled: Boolean(item.desktop_enabled),
    browserAdapterConfigured: Boolean(item.browser_adapter_configured),
    resourceEnforcement: String(item.resource_enforcement ?? 'process-tree'),
    scopeMode: String(item.scope_mode ?? 'workspace'),
    desktopSessionAvailable: Boolean(item.desktop_session_available),
    browserSessionAvailable: Boolean(item.browser_session_available),
  }
}

export async function listAudit(token: string): Promise<AuditEvent[]> {
  return (await listAuditPage(token, 0, 200)).items
}

export async function listAuditPage(token: string, offset = 0, limit = 200): Promise<PageResult<AuditEvent>> {
  const boundedOffset = Math.max(0, Math.trunc(offset))
  const boundedLimit = Math.min(200, Math.max(1, Math.trunc(limit)))
  const response = await request<{ items: Array<Record<string, unknown>>; offset?: number; limit?: number; total?: number; has_more?: boolean }>(`/api/v1/admin/audit?offset=${boundedOffset}&limit=${boundedLimit}`, token)
  const items = (response.items ?? []).map((item) => ({
    id: String(item.id ?? ''),
    eventType: String(item.event_type ?? ''),
    actor: String(item.actor ?? ''),
    agentId: item.agent_id as string | undefined,
    taskId: item.task_id as string | undefined,
    scopeMode: item.scope_mode as string | undefined,
    risk: item.risk as string | undefined,
    outcome: item.outcome as string | undefined,
    detail: item.detail as string | undefined,
    createdAt: item.created_at as string | undefined,
  }))
  return pageResult(items, response, boundedOffset, boundedLimit)
}

export async function purgeAudit(token: string, retentionDays = 365, limit = 500): Promise<{ deleted: number }> {
  const body = await request<Record<string, unknown>>(
    `/api/v1/admin/audit/gc?retentionDays=${Math.max(1, Math.trunc(retentionDays))}&limit=${Math.min(5000, Math.max(1, Math.trunc(limit)))}`,
    token,
    { method: 'POST' },
  )
  return { deleted: Number(body.deleted ?? 0) }
}

function mapAgentConfig(body: Record<string, unknown>): AgentConfig {
  const generation = Number(body.generation ?? 0)
  const configuredPoll = Number(body.poll_interval_ms ?? 5000)
  const configuredConcurrency = Number(body.max_concurrency ?? 1)
  return {
    generation,
    pollIntervalMs: configuredPoll >= 250 ? configuredPoll : 5000,
    maxConcurrency: configuredConcurrency >= 1 ? configuredConcurrency : 1,
  }
}

export async function getMachineConfig(token: string, machineId: string): Promise<AgentConfig> {
  const body = await request<Record<string, unknown>>(`/api/v1/admin/machines/${encodeURIComponent(machineId)}/config`, token)
  return mapAgentConfig(body)
}

export async function updateMachineConfig(token: string, machineId: string, payload: { poll_interval_ms?: number; max_concurrency?: number; expected_generation?: number }): Promise<AgentConfig> {
  const body = await request<Record<string, unknown>>(`/api/v1/admin/machines/${encodeURIComponent(machineId)}/config`, token, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return mapAgentConfig(body)
}

export async function rollbackMachineConfig(token: string, machineId: string): Promise<AgentConfig> {
  const body = await request<Record<string, unknown>>(`/api/v1/admin/machines/${encodeURIComponent(machineId)}/config/rollback`, token, { method: 'POST' })
  return mapAgentConfig(body)
}

export async function listTasks(token: string): Promise<Task[]> {
  return (await listTasksPage(token, 0, 200)).items
}

export async function listTasksPage(token: string, offset = 0, limit = 200): Promise<PageResult<Task>> {
  const boundedOffset = Math.max(0, Math.trunc(offset))
  const boundedLimit = Math.min(200, Math.max(1, Math.trunc(limit)))
  const response = await request<{ items: Array<Record<string, unknown>>; offset?: number; limit?: number; total?: number; has_more?: boolean }>(`/api/v1/admin/tasks?offset=${boundedOffset}&limit=${boundedLimit}`, token)
  const items = (response.items ?? []).map(mapTask)
  return pageResult(items, response, boundedOffset, boundedLimit)
}

function mapTask(item: Record<string, unknown>): Task {
  return {
    id: String(item.id ?? ''),
    machineId: String(item.machine_id ?? ''),
    kind: String(item.kind ?? ''),
    requiredCapability: item.required_capability as string | undefined,
    command: item.command as string | undefined,
    cwd: item.cwd as string | undefined,
    timeoutSeconds: Number(item.timeout_seconds ?? 0),
    status: String(item.status ?? ''),
    attempt: Number(item.attempt ?? 0),
    exitCode: item.exit_code == null ? undefined : Number(item.exit_code),
    error: item.error as string | undefined,
    outputBytes: Number(item.output_bytes ?? 0),
    outputTruncated: Boolean(item.output_truncated),
    createdAt: item.created_at as string | undefined,
    dispatchedAt: item.dispatched_at as string | undefined,
    startedAt: item.started_at as string | undefined,
    finishedAt: item.finished_at as string | undefined,
    artifactBytes: Number(item.artifact_bytes ?? 0),
    artifactMime: item.artifact_mime as string | undefined,
    artifactSha256: item.artifact_sha256 as string | undefined,
    scopeMode: item.scope_mode as string | undefined,
    projectId: item.project_id as string | undefined,
    worktreeId: item.worktree_id as string | undefined,
    scopeRoot: item.scope_root as string | undefined,
    risk: item.risk as string | undefined,
    contractExpiresAt: item.contract_expires_at as string | undefined,
  }
}

export async function createTask(token: string, payload: unknown): Promise<Task> {
  const body = await request<Record<string, unknown>>('/api/v1/admin/tasks', token, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return mapTask(body)
}

export async function cancelTask(token: string, taskId: string): Promise<Task> {
  const body = await request<Record<string, unknown>>(`/api/v1/admin/tasks/${encodeURIComponent(taskId)}/cancel`, token, { method: 'POST' })
  return mapTask(body)
}

export async function readTaskOutput(token: string, taskId: string, cursor = 0, limit = 16 * 1024): Promise<TaskOutputPage> {
  const body = await request<Record<string, unknown>>(
    `/api/v1/admin/tasks/${encodeURIComponent(taskId)}/output?cursor=${Math.max(0, cursor)}&limit=${Math.min(64 * 1024, Math.max(1, limit))}`,
    token,
    undefined,
    15000,
  )
  return {
    text: String(body.text ?? ''),
    cursor: Number(body.cursor ?? cursor),
    nextCursor: Number(body.next_cursor ?? cursor),
    more: Boolean(body.more),
  }
}

export async function readTaskArtifact(token: string, taskId: string): Promise<{ blob: Blob; mimeType: string; sha256: string }> {
  if (!token.trim()) throw new AdminApiError(0, '请输入 Admin Token')
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), 15000)
  try {
    const response = await fetch(`${apiBase}/api/v1/admin/tasks/${encodeURIComponent(taskId)}/artifact`, {
      signal: controller.signal,
      headers: { Authorization: `Bearer ${token.trim()}` },
    })
    if (!response.ok) {
      const body = await response.text().catch(() => '')
      throw new AdminApiError(response.status, body || (response.status === 404 ? '任务尚未生成工件' : `工件读取失败（${response.status}）`))
    }
    return {
      blob: await response.blob(),
      mimeType: response.headers.get('Content-Type') ?? 'application/octet-stream',
      sha256: response.headers.get('X-Artifact-SHA256') ?? '',
    }
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw new AdminApiError(0, '工件读取超时')
    throw error
  } finally {
    window.clearTimeout(timeout)
  }
}

function mapUpgradeTarget(item: Record<string, unknown>): UpgradeTarget {
  return {
    machineId: String(item.machine_id ?? ''),
    status: String(item.status ?? ''),
    error: item.error as string | undefined,
    attempts: Number(item.attempts ?? 0),
    updatedAt: item.updated_at as string | undefined,
    finishedAt: item.finished_at as string | undefined,
    leaseUntil: item.lease_until as string | undefined,
  }
}

function mapUpgrade(item: Record<string, unknown>): UpgradeCampaign {
  return {
    id: String(item.id ?? ''),
    version: String(item.version ?? ''),
    status: String(item.status ?? ''),
    canaryCount: Number(item.canary_count ?? 0),
    batchSize: Number(item.batch_size ?? 0),
    activeLimit: Number(item.active_limit ?? 0),
    targets: Array.isArray(item.targets) ? item.targets.map((target) => mapUpgradeTarget(target as Record<string, unknown>)) : [],
    createdAt: item.created_at as string | undefined,
    updatedAt: item.updated_at as string | undefined,
    finishedAt: item.finished_at as string | undefined,
  }
}

export async function listUpgrades(token: string): Promise<UpgradeCampaign[]> {
  return (await listUpgradesPage(token, 0, 100)).items
}

export async function listUpgradesPage(token: string, offset = 0, limit = 100): Promise<PageResult<UpgradeCampaign>> {
  const boundedOffset = Math.max(0, Math.trunc(offset))
  const boundedLimit = Math.min(100, Math.max(1, Math.trunc(limit)))
  const response = await request<{ items: Array<Record<string, unknown>>; offset?: number; limit?: number; total?: number; has_more?: boolean }>(`/api/v1/admin/upgrades?offset=${boundedOffset}&limit=${boundedLimit}`, token)
  const items = (response.items ?? []).map(mapUpgrade)
  return pageResult(items, response, boundedOffset, boundedLimit)
}

function mapReleaseAsset(item: Record<string, unknown>): ReleaseAsset {
  return {
    os: String(item.os ?? ''),
    arch: String(item.arch ?? ''),
    fileName: String(item.file_name ?? ''),
    available: Boolean(item.available),
    checksumAvailable: Boolean(item.checksum_available),
  }
}

function mapRelease(item: Record<string, unknown>): Release {
  return {
    version: String(item.version ?? ''),
    tag: String(item.tag ?? ''),
    name: String(item.name ?? ''),
    publishedAt: item.published_at as string | undefined,
    prerelease: Boolean(item.prerelease),
    assets: Array.isArray(item.assets) ? item.assets.map((value) => mapReleaseAsset(value as Record<string, unknown>)) : [],
  }
}

export async function listReleases(token: string, includePrerelease = true): Promise<ReleaseCatalog> {
  const response = await request<Record<string, unknown>>(
    `/api/v1/admin/releases?limit=50&include_prerelease=${includePrerelease ? 'true' : 'false'}`,
    token,
  )
  return {
    items: Array.isArray(response.items) ? response.items.map((item) => mapRelease(item as Record<string, unknown>)) : [],
    refreshedAt: response.refreshed_at as string | undefined,
    stale: Boolean(response.stale),
    available: Boolean(response.available),
    warning: response.warning as string | undefined,
  }
}

function mapWorktree(item: Record<string, unknown>): Worktree {
  return {
    id: String(item.id ?? ''),
    projectId: String(item.project_id ?? ''),
    ref: String(item.ref ?? ''),
    path: String(item.path ?? ''),
    operation: String(item.operation ?? ''),
    status: String(item.status ?? ''),
    taskId: item.task_id as string | undefined,
    createdAt: item.created_at as string | undefined,
    updatedAt: item.updated_at as string | undefined,
  }
}

function mapProject(item: Record<string, unknown>): Project {
  return {
    id: String(item.id ?? ''),
    machineId: String(item.machine_id ?? ''),
    name: String(item.name ?? ''),
    rootPath: String(item.root_path ?? ''),
    repositoryPath: String(item.repository_path ?? ''),
    defaultRef: String(item.default_ref ?? ''),
    createdAt: item.created_at as string | undefined,
    updatedAt: item.updated_at as string | undefined,
    worktrees: Array.isArray(item.worktrees) ? item.worktrees.map((value) => mapWorktree(value as Record<string, unknown>)) : [],
  }
}

export async function listProjects(token: string, machineId = ''): Promise<Project[]> {
  return (await listProjectsPage(token, machineId, 0, 200)).items
}

export async function listProjectsPage(token: string, machineId = '', offset = 0, limit = 200): Promise<PageResult<Project>> {
  const suffix = machineId.trim() ? `&machine_id=${encodeURIComponent(machineId.trim())}` : ''
  const boundedOffset = Math.max(0, Math.trunc(offset))
  const boundedLimit = Math.min(200, Math.max(1, Math.trunc(limit)))
  const response = await request<{ items: Array<Record<string, unknown>>; offset?: number; limit?: number; total?: number; has_more?: boolean }>(`/api/v1/admin/projects?offset=${boundedOffset}&limit=${boundedLimit}${suffix}`, token)
  const items = (response.items ?? []).map(mapProject)
  return pageResult(items, response, boundedOffset, boundedLimit)
}

export async function registerProject(token: string, payload: unknown): Promise<Project> {
  const body = await request<Record<string, unknown>>('/api/v1/admin/projects', token, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return mapProject(body)
}

export async function removeProject(token: string, projectId: string): Promise<Project> {
  const body = await request<Record<string, unknown>>(`/api/v1/admin/projects/${encodeURIComponent(projectId)}`, token, { method: 'DELETE' })
  return mapProject(body)
}

export async function createProjectWorktree(token: string, projectId: string, payload: unknown): Promise<Worktree> {
  const body = await request<Record<string, unknown>>(`/api/v1/admin/projects/${encodeURIComponent(projectId)}/worktrees`, token, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return mapWorktree(body)
}

export async function removeProjectWorktree(token: string, projectId: string, worktreeId: string, idempotencyKey?: string): Promise<Worktree> {
  const suffix = idempotencyKey?.trim() ? `?idempotency_key=${encodeURIComponent(idempotencyKey.trim())}` : ''
  const body = await request<Record<string, unknown>>(`/api/v1/admin/projects/${encodeURIComponent(projectId)}/worktrees/${encodeURIComponent(worktreeId)}${suffix}`, token, { method: 'DELETE' })
  return mapWorktree(body)
}

export async function runProjectGit(token: string, projectId: string, operation: string, payload: unknown = {}): Promise<Task> {
  const body = await request<Record<string, unknown>>(`/api/v1/admin/projects/${encodeURIComponent(projectId)}/git/${encodeURIComponent(operation)}`, token, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return mapTask(body)
}

export async function createUpgrade(token: string, payload: unknown): Promise<UpgradeCampaign> {
  const body = await request<Record<string, unknown>>('/api/v1/admin/upgrades', token, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  }, 120000)
  return mapUpgrade(body)
}

export async function controlUpgrade(token: string, campaignId: string, action: 'resume' | 'cancel'): Promise<UpgradeCampaign> {
  const body = await request<Record<string, unknown>>(`/api/v1/admin/upgrades/${encodeURIComponent(campaignId)}/${action}`, token, { method: 'POST' })
  return mapUpgrade(body)
}

export async function retryUpgradeTarget(token: string, campaignId: string, machineId: string): Promise<UpgradeCampaign> {
  const body = await request<Record<string, unknown>>(`/api/v1/admin/upgrades/${encodeURIComponent(campaignId)}/targets/${encodeURIComponent(machineId)}/retry`, token, { method: 'POST' })
  return mapUpgrade(body)
}

export async function issueEnrollment(token: string, requestedName: string, expiresInSeconds = 86400): Promise<{ tokenId: string; token: string; requestedName: string; expiresAt: string }> {
  const body = await request<Record<string, unknown>>('/api/v1/admin/enrollment-tokens', token, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ requested_name: requestedName, expires_in_seconds: expiresInSeconds }),
  })
  return {
    tokenId: String(body.token_id ?? ''),
    token: String(body.token ?? ''),
    requestedName: String(body.requested_name ?? requestedName),
    expiresAt: String(body.expires_at ?? ''),
  }
}
