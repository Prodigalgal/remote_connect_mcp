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
  workspacePolicy?: string
  laneMode?: string
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
  componentStatuses?: Record<string, string>
}

export type UpgradeCampaign = {
  id: string
  version: string
  status: string
  canaryCount: number
  batchSize: number
  activeLimit: number
  componentPlans?: Record<string, Array<{ component: string; version: string; os: string; arch: string; restartPolicy?: string }>>
  targets: UpgradeTarget[]
  createdAt?: string
  updatedAt?: string
  finishedAt?: string
}

export type UpgradeComponentPlan = {
  component: string
  version: string
  os: string
  arch: string
  url: string
  sha256: string
  bytes?: number
  restartPolicy?: string
}

export type UpgradeComponentCatalog = {
  version: string
  components: Record<string, UpgradeComponentPlan[]>
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
    workspacePolicy: item.workspace_policy as string | undefined,
    laneMode: item.lane_mode as string | undefined,
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
    componentStatuses: item.component_statuses && typeof item.component_statuses === 'object' ? item.component_statuses as Record<string, string> : undefined,
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
    componentPlans: (item.component_plans && typeof item.component_plans === 'object') ? item.component_plans as UpgradeCampaign['componentPlans'] : undefined,
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

export async function listReleases(token: string, includePrerelease = true, refresh = false): Promise<ReleaseCatalog> {
  const response = await request<Record<string, unknown>>(
    `/api/v1/admin/releases?limit=50&include_prerelease=${includePrerelease ? 'true' : 'false'}&refresh=${refresh ? 'true' : 'false'}`,
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

export async function listReleaseComponents(token: string, version: string): Promise<UpgradeComponentCatalog> {
  const body = await request<Record<string, unknown>>(`/api/v1/admin/releases/${encodeURIComponent(version)}/components`, token)
  const raw = body.components && typeof body.components === 'object' ? body.components as Record<string, unknown> : {}
  const components: Record<string, UpgradeComponentPlan[]> = {}
  Object.entries(raw).forEach(([platform, value]) => {
    if (!Array.isArray(value)) return
    components[platform] = value
      .filter((item): item is Record<string, unknown> => Boolean(item && typeof item === 'object'))
      .map((item) => ({
        component: String(item.component ?? ''),
        version: String(item.version ?? ''),
        os: String(item.os ?? ''),
        arch: String(item.arch ?? ''),
        url: String(item.url ?? ''),
        sha256: String(item.sha256 ?? ''),
        bytes: item.bytes == null ? undefined : Number(item.bytes),
        restartPolicy: item.restart_policy as string | undefined,
      }))
  })
  return { version: String(body.version ?? version), components }
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

export type McpToken = {
  tokenId: string
  principalId: string
  displayName: string
  scopes: string[]
  expiresAt?: string
  revokedAt?: string
  createdAt?: string
}

export type MachineGrant = {
  principalId: string
  machineId: string
  scopes: string[]
  expiresAt?: string
}

export type ProjectMember = {
  principalId: string
  projectId: string
  role: string
  scopes: string[]
  expiresAt?: string
}

export type ExecutionSession = {
  principalId: string
  sessionId: string
  conversationId: string
  machineId: string
  status: string
  lastSeenAt?: string
  expiresAt?: string
  capability?: string
  workspacePolicy?: string
  laneMode?: string
}

export type Quota = {
  principalId: string
  activeTasks: number
  maxActiveTasks: number
  queuedTasks: number
  maxQueuedTasks: number
  activeSessions: number
  maxSessions: number
  transferBytes: number
  maxTransferBytes: number
  reservedTransferBytes: number
  transferredTransferBytes: number
}

export type ArtifactAdmin = {
  artifactId: string
  transferId?: string
  taskId?: string
  principalId: string
  machineId?: string
  fileName: string
  mimeType?: string
  bytes: number
  bytesTransferred: number
  status: string
  transferStatus: string
  createdAt?: string
  expiresAt?: string
  sha256?: string
  sessionId?: string
  retentionPolicy?: string
  pinned: boolean
}

function mapArtifactAdmin(item: Record<string, unknown>): ArtifactAdmin {
  return {
    artifactId: String(item.artifact_id ?? ''), transferId: item.transfer_id as string | undefined,
    taskId: item.task_id as string | undefined, principalId: String(item.principal_id ?? ''),
    machineId: item.machine_id as string | undefined, fileName: String(item.file_name ?? ''),
    mimeType: item.mime_type as string | undefined, bytes: Number(item.bytes ?? 0),
    bytesTransferred: Number(item.bytes_transferred ?? 0), status: String(item.status ?? ''),
    transferStatus: String(item.transfer_status ?? item.status ?? ''), createdAt: item.created_at as string | undefined,
    expiresAt: item.expires_at as string | undefined, sha256: item.sha256 as string | undefined,
    sessionId: item.session_id as string | undefined, retentionPolicy: item.retention_policy as string | undefined,
    pinned: Boolean(item.pinned),
  }
}

export async function listArtifacts(token: string, filters: { principalId?: string; machineId?: string; sessionId?: string } = {}, offset = 0, limit = 50): Promise<PageResult<ArtifactAdmin>> {
  const params = new URLSearchParams({ offset: String(Math.max(0, Math.trunc(offset))), limit: String(Math.min(200, Math.max(1, Math.trunc(limit)))) })
  if (filters.principalId?.trim()) params.set('principalId', filters.principalId.trim())
  if (filters.machineId?.trim()) params.set('machineId', filters.machineId.trim())
  if (filters.sessionId?.trim()) params.set('sessionId', filters.sessionId.trim())
  const body = await request<{ items?: Array<Record<string, unknown>>; offset?: number; limit?: number; total?: number; has_more?: boolean }>(`/api/v1/admin/artifacts?${params.toString()}`, token)
  return pageResult((body.items ?? []).map(mapArtifactAdmin), body, offset, limit)
}

export async function deleteArtifact(token: string, artifactId: string, principalId = ''): Promise<void> {
  const suffix = principalId.trim() ? `?principalId=${encodeURIComponent(principalId.trim())}` : ''
  await request(`/api/v1/admin/artifacts/${encodeURIComponent(artifactId)}${suffix}`, token, { method: 'DELETE' })
}

export async function extendArtifactRetention(token: string, artifactId: string, extensionSeconds: number, principalId = '', policy = 'task-bound', pinned = false): Promise<void> {
  const params = new URLSearchParams({ extensionSeconds: String(Math.max(1, Math.trunc(extensionSeconds))) })
  if (principalId.trim()) params.set('principalId', principalId.trim())
  params.set('policy', policy)
  params.set('pinned', String(pinned))
  await request(`/api/v1/admin/artifacts/${encodeURIComponent(artifactId)}/retention?${params.toString()}`, token, { method: 'POST' })
}

function mapToken(item: Record<string, unknown>): McpToken {
  return { tokenId: String(item.token_id ?? ''), principalId: String(item.principal_id ?? ''), displayName: String(item.display_name ?? ''), scopes: Array.isArray(item.scopes) ? item.scopes.map(String) : [], expiresAt: item.expires_at as string | undefined, revokedAt: item.revoked_at as string | undefined, createdAt: item.created_at as string | undefined }
}

export async function listMcpTokens(token: string, offset = 0, limit = 200): Promise<PageResult<McpToken>> {
  const boundedOffset = Math.max(0, Math.trunc(offset)); const boundedLimit = Math.min(200, Math.max(1, Math.trunc(limit)))
  const body = await request<{ items?: Array<Record<string, unknown>>; offset?: number; limit?: number; total?: number; has_more?: boolean }>(`/api/v1/admin/mcp-tokens?offset=${boundedOffset}&limit=${boundedLimit}`, token)
  return pageResult((body.items ?? []).map(mapToken), body, boundedOffset, boundedLimit)
}

export async function issueMcpToken(token: string, payload: { principal_id?: string; display_name?: string; expires_in_seconds?: number; scopes?: string[] }): Promise<McpToken & { token: string }> {
  const body = await request<Record<string, unknown>>('/api/v1/admin/mcp-tokens', token, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) })
  return { ...mapToken(body), token: String(body.token ?? '') }
}

export async function revokeMcpToken(token: string, tokenId: string): Promise<void> {
  await request(`/api/v1/admin/mcp-tokens/${encodeURIComponent(tokenId)}/revoke`, token, { method: 'POST' })
}

function mapMachineGrant(item: Record<string, unknown>): MachineGrant { return { principalId: String(item.principal_id ?? ''), machineId: String(item.machine_id ?? item.agent_id ?? ''), scopes: Array.isArray(item.scopes) ? item.scopes.map(String) : [], expiresAt: item.expires_at as string | undefined } }
function mapProjectMember(item: Record<string, unknown>): ProjectMember { return { principalId: String(item.principal_id ?? ''), projectId: String(item.project_id ?? ''), role: String(item.role ?? ''), scopes: Array.isArray(item.scopes) ? item.scopes.map(String) : [], expiresAt: item.expires_at as string | undefined } }
function mapSession(item: Record<string, unknown>): ExecutionSession { return { principalId: String(item.principal_id ?? ''), sessionId: String(item.session_id ?? ''), conversationId: String(item.conversation_id ?? ''), machineId: String(item.machine_id ?? ''), status: String(item.status ?? ''), lastSeenAt: item.last_seen_at as string | undefined, expiresAt: item.expires_at as string | undefined, capability: item.capability as string | undefined, workspacePolicy: item.workspace_policy as string | undefined, laneMode: item.lane_mode as string | undefined } }

export async function listMachineGrants(token: string, principalId = ''): Promise<MachineGrant[]> { const suffix = principalId.trim() ? `&principalId=${encodeURIComponent(principalId.trim())}` : ''; const body = await request<{ items?: Array<Record<string, unknown>> }>(`/api/v1/admin/access/machines?offset=0&limit=200${suffix}`, token); return (body.items ?? []).map(mapMachineGrant) }
export async function listProjectMembers(token: string, principalId = ''): Promise<ProjectMember[]> { const suffix = principalId.trim() ? `&principalId=${encodeURIComponent(principalId.trim())}` : ''; const body = await request<{ items?: Array<Record<string, unknown>> }>(`/api/v1/admin/access/projects?offset=0&limit=200${suffix}`, token); return (body.items ?? []).map(mapProjectMember) }
export async function grantMachine(token: string, payload: unknown): Promise<MachineGrant> { return mapMachineGrant(await request<Record<string, unknown>>('/api/v1/admin/access/machines', token, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) })) }
export async function grantProject(token: string, payload: unknown): Promise<ProjectMember> { return mapProjectMember(await request<Record<string, unknown>>('/api/v1/admin/access/projects', token, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) })) }
export async function revokeMachine(token: string, payload: unknown): Promise<void> { await request('/api/v1/admin/access/machines', token, { method: 'DELETE', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) }) }
export async function revokeProject(token: string, payload: unknown): Promise<void> { await request('/api/v1/admin/access/projects', token, { method: 'DELETE', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) }) }
export async function listExecutionSessions(token: string, principalId = ''): Promise<ExecutionSession[]> { const suffix = principalId.trim() ? `&principalId=${encodeURIComponent(principalId.trim())}` : ''; const body = await request<{ items?: Array<Record<string, unknown>> }>(`/api/v1/admin/execution-sessions?offset=0&limit=200${suffix}`, token); return (body.items ?? []).map(mapSession) }
export async function closeExecutionSession(token: string, principalId: string, sessionId: string): Promise<void> { await request('/api/v1/admin/execution-sessions/close', token, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ principal_id: principalId, session_id: sessionId }) }) }
export async function getQuota(token: string, principalId: string): Promise<Quota> { const body = await request<Record<string, unknown>>(`/api/v1/admin/quotas/${encodeURIComponent(principalId)}`, token); return { principalId: String(body.principal_id ?? principalId), activeTasks: Number(body.active_tasks ?? 0), maxActiveTasks: Number(body.max_active_tasks ?? 0), queuedTasks: Number(body.queued_tasks ?? 0), maxQueuedTasks: Number(body.max_queued_tasks ?? 0), activeSessions: Number(body.active_sessions ?? 0), maxSessions: Number(body.max_sessions ?? 0), transferBytes: Number(body.transfer_bytes ?? body.reserved_transfer_bytes ?? 0), maxTransferBytes: Number(body.max_transfer_bytes ?? 0), reservedTransferBytes: Number(body.reserved_transfer_bytes ?? body.transfer_bytes ?? 0), transferredTransferBytes: Number(body.transferred_transfer_bytes ?? 0) } }
