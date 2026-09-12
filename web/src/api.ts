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
  createdAt?: string
  lastSeen?: string
  online: boolean
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
  const response = await request<{ items: Array<Record<string, unknown>> }>('/api/v1/admin/machines?offset=0&limit=200', token)
  return (response.items ?? []).map((item) => ({
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
    createdAt: item.created_at as string | undefined,
    lastSeen: item.last_seen as string | undefined,
    online: Boolean(item.online),
  }))
}

export async function listTasks(token: string): Promise<Task[]> {
  const response = await request<{ items: Array<Record<string, unknown>> }>('/api/v1/admin/tasks?offset=0&limit=200', token)
  return (response.items ?? []).map(mapTask)
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
  const response = await request<{ items: Array<Record<string, unknown>> }>('/api/v1/admin/upgrades?offset=0&limit=100', token)
  return (response.items ?? []).map(mapUpgrade)
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
  const suffix = machineId.trim() ? `&machine_id=${encodeURIComponent(machineId.trim())}` : ''
  const response = await request<{ items: Array<Record<string, unknown>> }>(`/api/v1/admin/projects?offset=0&limit=200${suffix}`, token)
  return (response.items ?? []).map(mapProject)
}

export async function registerProject(token: string, payload: unknown): Promise<Project> {
  const body = await request<Record<string, unknown>>('/api/v1/admin/projects', token, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
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
