import { useCallback, useEffect, useRef, useState } from 'react'
import { AdminApiError, cancelTask, controlUpgrade, createProjectWorktree, createTask, createUpgrade, getMachineConfig, issueEnrollment, listAudit, listAuditPage, listMachines, listMachinesPage, listProjects, listProjectsPage, listReleases, listTasks, listTasksPage, listUpgrades, listUpgradesPage, purgeAudit, readTaskArtifact, readTaskOutput, registerProject, removeProject, removeProjectWorktree, retryUpgradeTarget, rollbackMachineConfig, runProjectGit, updateMachineConfig, waitForAdminChange, type AgentConfig, type AuditEvent, type Machine, type PageResult, type Project, type ReleaseCatalog, type Task, type UpgradeCampaign } from './api'

type Page = 'overview' | 'machines' | 'projects' | 'tasks' | 'audit' | 'enrollment' | 'upgrades' | 'settings'

const nav: Array<{ id: Page; label: string; icon: string; group: string }> = [
  { id: 'overview', label: '总览', icon: '⌂', group: '工作台' },
  { id: 'machines', label: '机器与 Agent', icon: '▦', group: '资源管理' },
  { id: 'projects', label: '项目与 Worktree', icon: '⌘', group: '资源管理' },
  { id: 'tasks', label: '任务记录', icon: '≡', group: '执行记录' },
  { id: 'audit', label: '审计日志', icon: '▤', group: '执行记录' },
  { id: 'enrollment', label: '注册令牌', icon: '♢', group: '安全' },
  { id: 'upgrades', label: '升级编排', icon: '↗', group: '运维中心' },
  { id: 'settings', label: '系统设置', icon: '⚙', group: '安全' },
]

const demoMachines = [
  { name: 'edge-lab-01', host: 'edge-lab-01', role: 'command', os: 'Linux · amd64', state: '在线', tone: 'good' },
  { name: 'desktop-lab-01', host: 'edge-lab-01', role: 'desktop', os: 'Windows · amd64', state: '用户会话', tone: 'accent' },
  { name: 'browser-lab-01', host: 'edge-lab-01', role: 'browser', os: 'Linux · arm64', state: '待接入', tone: 'muted' },
]

type DemoMachine = typeof demoMachines[number]

function matchesMachine(machine: Machine | DemoMachine, query: string): boolean {
  const needle = query.trim().toLocaleLowerCase()
  if (!needle) return true
  const searchable = 'id' in machine
    ? [machine.id, machine.name, machine.hostId, machine.hostname, machine.os, machine.arch, machine.version, machine.scopeMode, machine.workspaceRoot, ...machine.capabilities]
    : [machine.name, machine.host, machine.role, machine.os, machine.state]
  return searchable.filter(Boolean).some((value) => String(value).toLocaleLowerCase().includes(needle))
}

/**
 * Keep the initial dashboard projection small and fetch later pages only on
 * explicit user intent. The server remains the source of truth for
 * `has_more`; a full refresh resets the tail so newly-created rows cannot be
 * hidden behind a stale offset.
 */
function usePagedTail<T>(rows: T[] | null, pageSize: number, loadPage: (offset: number, limit: number) => Promise<PageResult<T>>) {
  const [tail, setTail] = useState<T[]>([])
  const [hasMore, setHasMore] = useState(false)
  const [loadingMore, setLoadingMore] = useState(false)
  const [loadError, setLoadError] = useState('')
  // A live event can replace the first page while a user-triggered tail
  // request is still in flight.  Keep a local generation so an old response
  // can never append rows from the previous projection to the refreshed one.
  const generation = useRef(0)
  useEffect(() => {
    generation.current += 1
    setTail([])
    setHasMore(Boolean(rows && rows.length >= pageSize))
    setLoadingMore(false)
    setLoadError('')
  }, [rows, pageSize])
  const loadMore = async () => {
    if (!rows || loadingMore || !hasMore) return
    const requestGeneration = generation.current
    setLoadingMore(true)
    setLoadError('')
    try {
      const page = await loadPage(rows.length + tail.length, pageSize)
      if (generation.current !== requestGeneration) return
      setTail((current) => [...current, ...page.items])
      setHasMore(page.hasMore)
    } catch (error) {
      if (generation.current !== requestGeneration) return
      setLoadError(error instanceof Error ? error.message : '下一页加载失败')
    } finally {
      if (generation.current === requestGeneration) setLoadingMore(false)
    }
  }
  return { rows: rows ? [...rows, ...tail] : null, hasMore, loadingMore, loadError, loadMore }
}

function App() {
  const [page, setPage] = useState<Page>('overview')
  const [search, setSearch] = useState('')
  // The token lives only in React memory. It is never written to localStorage
  // or bundled into the static console.
  const [adminToken, setAdminToken] = useState('')
  const [liveMachines, setLiveMachines] = useState<Machine[] | null>(null)
  const [liveProjects, setLiveProjects] = useState<Project[] | null>(null)
  const [liveTasks, setLiveTasks] = useState<Task[] | null>(null)
  const [liveAudit, setLiveAudit] = useState<AuditEvent[] | null>(null)
  const [liveUpgrades, setLiveUpgrades] = useState<UpgradeCampaign[] | null>(null)
  const [liveReleases, setLiveReleases] = useState<ReleaseCatalog | null>(null)
  const [apiMessage, setApiMessage] = useState('')
  const [loading, setLoading] = useState(false)
  const refreshInFlight = useRef<{ token: string; promise: Promise<void> } | null>(null)
  const current = nav.find((item) => item.id === page) ?? nav[0]

  const refresh = useCallback((token = adminToken): Promise<void> => {
    const normalizedToken = token.trim()
    if (refreshInFlight.current?.token === normalizedToken) return refreshInFlight.current.promise
    const operation = (async () => {
      if (!normalizedToken) {
        setLiveMachines(null)
        setLiveProjects(null)
        setLiveTasks(null)
        setLiveAudit(null)
        setLiveUpgrades(null)
        setLiveReleases(null)
        setApiMessage('演示数据：在“系统设置”输入 Admin Token 后加载 Center 实时数据')
        return
      }
      setLoading(true)
      setApiMessage('')
      try {
        // Project APIs were added in the Java Center migration. Keep the
        // console usable against the older Go compatibility Center while the
        // migration is in progress.
        const [machines, projects, tasks, upgrades, releases, audit] = await Promise.all([
          listMachines(normalizedToken),
          listProjects(normalizedToken).catch(() => []),
          listTasks(normalizedToken),
          listUpgrades(normalizedToken),
          listReleases(normalizedToken).catch((): ReleaseCatalog => ({ items: [], stale: true, available: false, warning: '版本目录暂不可用' })),
          listAudit(normalizedToken).catch(() => []),
        ])
        setLiveMachines(machines)
        setLiveProjects(projects)
        setLiveTasks(tasks)
        setLiveUpgrades(upgrades)
        setLiveReleases(releases)
        setLiveAudit(audit)
        setApiMessage(`已连接 Center · ${new Date().toLocaleTimeString()}`)
      } catch (error) {
        const message = error instanceof AdminApiError ? error.message : 'Center 暂时不可达'
        setApiMessage(message)
        setLiveMachines(null)
        setLiveProjects(null)
        setLiveTasks(null)
        setLiveUpgrades(null)
        setLiveReleases(null)
        setLiveAudit(null)
      } finally {
        setLoading(false)
      }
    })()
    const tracked = operation.finally(() => {
      if (refreshInFlight.current?.promise === tracked) refreshInFlight.current = null
    })
    refreshInFlight.current = { token: normalizedToken, promise: tracked }
    return tracked
  }, [adminToken])

  useEffect(() => {
    if (!adminToken.trim()) return
    const controller = new AbortController()
    let stopped = false
    let retryTimer: number | undefined
    const watch = async () => {
      let cursor = 0
      while (!stopped) {
        try {
          const change = await waitForAdminChange(adminToken, cursor, 25_000, controller.signal)
          if (stopped) return
          cursor = change.cursor
          if (change.changed) await refresh(adminToken)
        } catch {
          if (stopped) return
          // Retry only after a transport failure; healthy operation is held
          // by the Center event endpoint rather than a fixed refresh timer.
          await new Promise<void>((resolve) => {
            retryTimer = window.setTimeout(resolve, 1500)
          })
        }
      }
    }
    void refresh(adminToken)
    void watch()
    return () => {
      stopped = true
      controller.abort()
      if (retryTimer !== undefined) window.clearTimeout(retryTimer)
    }
  }, [adminToken, refresh])

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">RC</div>
          <div>
            <strong>Remote Connect</strong>
            <span>MCP Control Center</span>
          </div>
        </div>

        <nav className="nav" aria-label="主导航">
          {Array.from(new Set(nav.map((item) => item.group))).map((group) => (
            <div className="nav-group" key={group}>
              <div className="nav-caption">{group}</div>
              {nav.filter((item) => item.group === group).map((item) => (
                <button className={item.id === page ? 'nav-item active' : 'nav-item'} key={item.id} onClick={() => setPage(item.id)}>
                  <span className="nav-icon" aria-hidden="true">{item.icon}</span>
                  <span>{item.label}</span>
                </button>
              ))}
            </div>
          ))}
        </nav>

        <div className="sidebar-footer">
          <span className="status-dot" />
          <span>Java Center · 迁移候选</span>
          <span className="version">0.1</span>
        </div>
      </aside>

      <main className="main-content">
        <header className="topbar">
          <div>
            <span className="eyebrow">CONTROL CENTER</span>
            <h1>{current.label}</h1>
          </div>
          <div className="topbar-actions">
            <div className="search"><span>⌕</span><input aria-label="全局搜索" value={search} onChange={(event) => setSearch(event.target.value)} placeholder="搜索机器、任务或 ID" /></div>
            <button className="icon-button" title="刷新" aria-label="刷新" onClick={() => void refresh()} disabled={loading}>{loading ? '…' : '↻'}</button>
            <div className="profile"><span className="avatar">Z</span><span>管理员</span><span className="chevron">⌄</span></div>
          </div>
        </header>

        <div className="content">
          {apiMessage && <div className={liveMachines ? 'api-banner good' : 'api-banner'} role="status">{apiMessage}</div>}
          {page === 'overview' && <Overview onNavigate={setPage} rows={liveMachines} tasks={liveTasks} upgrades={liveUpgrades} query={search} />}
          {page === 'machines' && <Machines rows={liveMachines} token={adminToken} onEnroll={() => setPage('enrollment')} query={search} />}
          {page === 'projects' && <Projects rows={liveProjects} machines={liveMachines ?? []} token={adminToken} onRefresh={() => void refresh()} query={search} />}
          {page === 'tasks' && <Tasks rows={liveTasks} machines={liveMachines ?? []} projects={liveProjects ?? []} adminToken={adminToken} onRefresh={() => void refresh()} query={search} />}
          {page === 'audit' && <Audit rows={liveAudit} machines={liveMachines ?? []} token={adminToken} onRefresh={() => void refresh()} query={search} />}
          {page === 'enrollment' && <Enrollment adminToken={adminToken} />}
          {page === 'upgrades' && <Upgrades token={adminToken} rows={liveUpgrades} releases={liveReleases} machines={liveMachines ?? []} onRefresh={() => void refresh()} query={search} />}
          {page === 'settings' && <Settings token={adminToken} machines={liveMachines ?? []} onTokenChange={setAdminToken} onRefresh={() => void refresh()} />}
        </div>
      </main>
    </div>
  )
}

function Overview({ onNavigate, rows, tasks, upgrades, query }: { onNavigate: (page: Page) => void; rows: Machine[] | null; tasks: Task[] | null; upgrades: UpgradeCampaign[] | null; query: string }) {
  const sourceRows: Array<Machine | DemoMachine> = rows ?? demoMachines
  const displayRows = sourceRows.filter((machine) => matchesMachine(machine, query)).slice(0, 8)
  const onlineCount = rows ? rows.filter((machine) => machine.online).length : 9
  const taskCount = tasks ? tasks.filter((task) => ['queued', 'dispatching', 'running', 'cancel_requested'].includes(task.status)).length : 4
  return (
    <>
      <section className="hero-card">
        <div>
          <span className="section-kicker">JAVA MIGRATION · PHASE A</span>
          <h2>让每一台终端都清晰可控</h2>
          <p>Center、Agent 与控制台已拆分为可独立升级的组件。输入 Admin Token 后，页面直接读取 Center 的机器、任务和升级状态；未连接时保留脱敏演示数据。</p>
          <div className="hero-actions"><button className="primary" onClick={() => onNavigate('machines')}>查看 Agent</button><button className="secondary" onClick={() => onNavigate('upgrades')}>查看迁移计划</button></div>
        </div>
        <div className="hero-orbit"><div className="orbit-ring ring-one" /><div className="orbit-ring ring-two" /><div className="orbit-core">RCM</div></div>
      </section>

      <div className="metric-grid">
        <Metric label="已注册 Agent" value={rows ? String(rows.length) : '12'} meta={rows ? 'Center 实时' : '+2 本周'} tone="blue" icon="◇" />
        <Metric label="当前在线" value={String(onlineCount)} meta={rows ? `${Math.round((onlineCount / Math.max(1, rows.length)) * 100)}% 可用率` : '75% 可用率'} tone="green" icon="●" />
        <Metric label="运行中任务" value={String(taskCount)} meta={tasks ? 'Center 实时' : '最长 18m 32s'} tone="amber" icon="◷" />
        <Metric label="待处理升级" value={upgrades ? String(upgrades.filter((upgrade) => ['running', 'paused'].includes(upgrade.status)).length) : '—'} meta={upgrades ? 'Center 实时' : '输入 Token 后加载'} tone="purple" icon="↗" />
      </div>

      <div className="section-heading"><div><span className="eyebrow">RESOURCE MAP</span><h2>终端与 Agent</h2></div><button className="text-button" onClick={() => onNavigate('machines')}>查看全部 <span>→</span></button></div>
      <section className="panel table-panel">
        <div className="table-head"><span>Agent</span><span>物理终端</span><span>能力</span><span>平台</span><span>状态</span></div>
        {displayRows.length > 0 ? displayRows.map((machine) => <MachineRow machine={machine} key={'id' in machine ? machine.id : machine.name} />) : <div className="filtered-empty">没有匹配的机器</div>}
      </section>
    </>
  )
}

function Metric({ label, value, meta, tone, icon }: { label: string; value: string; meta: string; tone: string; icon: string }) {
  return <div className={`metric-card ${tone}`}><div className="metric-icon">{icon}</div><span className="metric-label">{label}</span><strong>{value}</strong><span className="metric-meta">{meta}</span></div>
}

function MachineRow({ machine }: { machine: Machine | DemoMachine }) {
  const live = 'id' in machine
  const role = live ? machine.capabilities[0] ?? 'agent' : machine.role
  const online = live ? machine.online : machine.state !== '待接入'
  const tone = live ? (online ? 'good' : 'muted') : machine.tone
  const state = live ? (online ? '在线' : '离线') : machine.state
  const host = live ? machine.hostId : machine.host
  const os = live ? `${machine.os ?? 'unknown'} · ${machine.arch ?? 'unknown'}` : machine.os
  const runtime = live ? machine.runtime : undefined
  const runtimeLabel = runtime ? `${role} agent · 并发 ${runtime.maxConcurrency} · 进程 ${runtime.maxTotalChildProcesses} · ${runtime.resourceEnforcement} · g${runtime.configGeneration}` : `${role} agent`
  return <div className="table-row"><div className="agent-name"><span className="agent-symbol">{role === 'command' ? '⌁' : role === 'desktop' ? '▣' : '◌'}</span><div><strong>{machine.name}</strong><span>{runtimeLabel}</span></div></div><span className="mono">{host}</span><span className="capability-pill">{role}</span><span>{os}</span><span className={`state ${tone}`}><i />{state}</span></div>
}

function Machines({ rows, token, onEnroll, query }: { rows: Machine[] | null; token: string; onEnroll: () => void; query: string }) {
  const [filter, setFilter] = useState<'all' | 'online' | 'offline'>('all')
  const paged = usePagedTail(rows, 200, (offset, limit) => listMachinesPage(token, offset, limit))
  const sourceRows: Array<Machine | DemoMachine> = paged.rows ?? demoMachines.flatMap((machine) => [machine, { ...machine, name: `${machine.name}-2` }])
  const displayRows = sourceRows.filter((machine) => (filter === 'all' || ('id' in machine ? (filter === 'online' ? machine.online : !machine.online) : (filter === 'online' ? machine.state !== '待接入' : machine.state === '待接入')))).filter((machine) => matchesMachine(machine, query))
  const online = paged.rows ? paged.rows.filter((machine) => machine.online).length : 9
  return <><PageIntro kicker="RESOURCE MANAGEMENT" title="机器与 Agent" action="新增注册令牌" onAction={onEnroll} /><section className="panel table-panel"><div className="filter-bar"><div className="segmented"><button className={filter === 'all' ? 'selected' : ''} onClick={() => setFilter('all')} aria-pressed={filter === 'all'}>全部 <b>{paged.rows?.length ?? 12}</b></button><button className={filter === 'online' ? 'selected' : ''} onClick={() => setFilter('online')} aria-pressed={filter === 'online'}>在线 <b>{online}</b></button><button className={filter === 'offline' ? 'selected' : ''} onClick={() => setFilter('offline')} aria-pressed={filter === 'offline'}>离线 <b>{(paged.rows?.length ?? 12) - online}</b></button></div><span className="filter-summary">显示 {displayRows.length}{paged.rows ? ` / ${paged.rows.length}` : ''} 台</span></div><div className="table-head"><span>Agent</span><span>物理终端</span><span>能力</span><span>平台</span><span>状态</span></div>{displayRows.length > 0 ? displayRows.map((machine, index) => <MachineRow machine={machine} key={'id' in machine ? machine.id : `${machine.name}-${index}`} />) : <div className="filtered-empty">没有匹配的机器</div>}{paged.rows && (paged.hasMore || paged.loadingMore || paged.loadError) && <div className="pagination-bar"><span>{paged.loadError || '列表按页加载，避免大规模终端占用前端内存'}</span><button className="secondary" onClick={() => void paged.loadMore()} disabled={paged.loadingMore || !paged.hasMore}>{paged.loadingMore ? '加载中…' : paged.hasMore ? '加载下一页' : '已加载全部'}</button></div>}</section></>
}

function Projects({ rows, machines, token, onRefresh, query }: { rows: Project[] | null; machines: Machine[]; token: string; onRefresh: () => void; query: string }) {
  const [machineId, setMachineId] = useState('')
  const [name, setName] = useState('')
  const [rootPath, setRootPath] = useState('')
  const [repositoryPath, setRepositoryPath] = useState('')
  const [defaultRef, setDefaultRef] = useState('main')
  const [worktreeRefs, setWorktreeRefs] = useState<Record<string, string>>({})
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState('')
  useEffect(() => {
    if (!machineId && machines.length > 0) setMachineId(machines[0].id)
  }, [machineId, machines])
  const paged = usePagedTail(rows, 200, (offset, limit) => listProjectsPage(token, '', offset, limit))
  const needle = query.trim().toLocaleLowerCase()
  const visible = paged.rows?.filter((project) => !needle || [project.id, project.machineId, project.name, project.rootPath, project.repositoryPath, project.defaultRef, ...project.worktrees.flatMap((worktree) => [worktree.id, worktree.ref, worktree.path, worktree.status])].some((value) => String(value ?? '').toLocaleLowerCase().includes(needle))) ?? null
  const register = async () => {
    if (!machineId || !name.trim() || !rootPath.trim()) { setMessage('请选择 Agent，并填写项目名称和绝对根路径'); return }
    setBusy('register'); setMessage('')
    try {
      await registerProject(token, { machine_id: machineId, name: name.trim(), root_path: rootPath.trim(), repository_path: repositoryPath.trim() || undefined, default_ref: defaultRef.trim() || 'HEAD' })
      setMessage('项目已注册；Center 只保存路径元数据，仓库内容留在 Agent。')
      setName(''); setRootPath(''); setRepositoryPath(''); onRefresh()
    } catch (error) { setMessage(error instanceof Error ? error.message : '项目注册失败') }
    finally { setBusy('') }
  }
  const createWorktree = async (project: Project) => {
    const ref = (worktreeRefs[project.id] || project.defaultRef || 'HEAD').trim()
    if (!ref) { setMessage('请填写 Git ref'); return }
    setBusy(`create-${project.id}`); setMessage('')
    try {
      await createProjectWorktree(token, project.id, { ref, idempotency_key: `${project.id}:${ref}` })
      setMessage(`已为 ${project.name} 排队创建 ${ref} worktree`); onRefresh()
    } catch (error) { setMessage(error instanceof Error ? error.message : '创建 worktree 失败') }
    finally { setBusy('') }
  }
  const removeWorktree = async (project: Project, worktree: Project['worktrees'][number]) => {
    if (!window.confirm(`确认移除 worktree ${worktree.ref}？`)) return
    setBusy(`remove-${worktree.id}`); setMessage('')
    try { await removeProjectWorktree(token, project.id, worktree.id, `${project.id}:remove:${worktree.id}`); setMessage(`已排队移除 ${worktree.ref}`); onRefresh() }
    catch (error) { setMessage(error instanceof Error ? error.message : '移除 worktree 失败') }
    finally { setBusy('') }
  }
  const remove = async (project: Project) => {
    if (project.worktrees.some((worktree) => worktree.status !== 'removed')) {
      setMessage('请先移除该项目的全部 Worktree')
      return
    }
    if (!window.confirm(`确认移除项目 ${project.name} 的注册信息？目标机器上的文件不会被删除。`)) return
    setBusy(`delete-${project.id}`); setMessage('')
    try { await removeProject(token, project.id); setMessage(`已移除项目 ${project.name}`); onRefresh() }
    catch (error) { setMessage(error instanceof Error ? error.message : '移除项目失败') }
    finally { setBusy('') }
  }
  return <><PageIntro kicker="DEVELOPMENT WORKFLOW" title="项目与 Git Worktree" action="注册项目" onAction={() => document.getElementById('project-composer')?.scrollIntoView({ behavior: 'smooth', block: 'start' })} /><section className="panel task-composer" id="project-composer"><div><span className="section-kicker">PROJECT REGISTRY</span><h3>注册 Agent 本地项目</h3><p>Center 只保存不透明 ID 与路径；worktree 在目标 Agent 上通过 Git 异步创建，原始 checkout 不会被直接修改。</p></div><div className="composer-grid"><label>目标 Agent<select value={machineId} onChange={(event) => setMachineId(event.target.value)}><option value="">选择 Agent</option>{machines.filter((machine) => machine.online && machine.capabilities.includes('command')).map((machine) => <option key={machine.id} value={machine.id}>{machine.name} · {machine.id}</option>)}</select></label><label>项目名称<input value={name} onChange={(event) => setName(event.target.value)} placeholder="例如 remote-connect-mcp" /></label><label className="composer-wide">项目根路径<input value={rootPath} onChange={(event) => setRootPath(event.target.value)} placeholder="Linux /srv/project 或 Windows C:\\Work\\project" /></label><label className="composer-wide">仓库路径（可选）<input value={repositoryPath} onChange={(event) => setRepositoryPath(event.target.value)} placeholder="留空使用项目根路径" /></label><label>默认 ref<input value={defaultRef} onChange={(event) => setDefaultRef(event.target.value)} placeholder="main" /></label></div><div className="composer-actions"><button className="primary" onClick={() => void register()} disabled={!token || busy !== ''}>{busy === 'register' ? '注册中…' : '注册项目'}</button>{message && <span className="form-message">{message}</span>}</div></section>{!visible?.length ? <div className="panel empty-ready"><div className="empty-icon">⌘</div><h3>{token ? (paged.rows?.length ? '没有匹配的项目' : '暂无注册项目') : '等待 Center 数据'}</h3><p>{token ? '注册项目后可在这里创建隔离 worktree。' : '在系统设置输入 Admin Token 后加载项目。'}</p></div> : <><section className="project-grid">{visible.map((project) => <article className="panel project-card" key={project.id}><div className="project-card-head"><div><span className="mono">{project.id}</span><h3>{project.name}</h3></div><div className="row"><span className="capability-pill">{machines.find((machine) => machine.id === project.machineId)?.name ?? project.machineId}</span><button className="text-button danger" onClick={() => void remove(project)} disabled={busy !== '' || !token || project.worktrees.some((worktree) => worktree.status !== 'removed')}>{busy === `delete-${project.id}` ? '移除中…' : '移除项目'}</button></div></div><div className="project-path"><span>根目录</span><code>{project.rootPath}</code><span>仓库</span><code>{project.repositoryPath}</code><span>默认 ref</span><code>{project.defaultRef}</code></div><GitActions token={token} project={project} onRefresh={onRefresh} /><div className="worktree-toolbar"><input value={worktreeRefs[project.id] ?? project.defaultRef} onChange={(event) => setWorktreeRefs((previous) => ({ ...previous, [project.id]: event.target.value }))} placeholder="feature/ref" aria-label={`${project.name} Git ref`} /><button className="secondary" onClick={() => void createWorktree(project)} disabled={busy !== '' || !token}>{busy === `create-${project.id}` ? '排队中…' : '创建 Worktree'}</button></div><div className="worktree-list">{project.worktrees.length === 0 ? <span className="muted">暂无 worktree</span> : project.worktrees.map((worktree) => <div className="worktree-row" key={worktree.id}><div><strong>{worktree.ref}</strong><span>{worktree.status} · {worktree.path}</span>{worktree.taskId && <small>任务 {worktree.taskId}</small>}</div>{worktree.status === 'ready' && <button className="text-button danger" onClick={() => void removeWorktree(project, worktree)} disabled={busy !== ''}>移除</button>}</div>)}</div></article>)}</section>{paged.rows && (paged.hasMore || paged.loadingMore) && <div className="pagination-bar"><span>项目列表按页加载，避免一次把全部 Worktree 元数据带入页面</span><button className="secondary" onClick={() => void paged.loadMore()} disabled={paged.loadingMore || !paged.hasMore}>{paged.loadingMore ? '加载中…' : paged.hasMore ? '加载下一页' : '已加载全部'}</button></div>}</>}</>
}

function GitActions({ token, project, onRefresh }: { token: string; project: Project; onRefresh: () => void }) {
  const [worktreeId, setWorktreeId] = useState('')
  const [ref, setRef] = useState('')
  const [commitMessage, setCommitMessage] = useState('')
  const [nonce, setNonce] = useState(() => Math.random().toString(36).slice(2, 10))
  const [busy, setBusy] = useState('')
  const [message, setMessage] = useState('')
  const run = async (operation: string, extra: Record<string, unknown> = {}) => {
    setBusy(operation); setMessage('')
    try {
      const payload = { worktree_id: worktreeId || undefined, mode: operation === 'diff' ? 'stat' : undefined,
        idempotency_key: `console:${project.id}:${operation}:${worktreeId || 'project'}:${nonce}`, ...extra }
      const task = await runProjectGit(token, project.id, operation, payload)
      setMessage(`${operation} 已入队：${task.id}`); setNonce(Math.random().toString(36).slice(2, 10)); onRefresh()
    } catch (error) { setMessage(error instanceof Error ? error.message : `Git ${operation} 失败`) }
    finally { setBusy('') }
  }
  const commit = () => {
    const text = commitMessage.trim()
    if (!text) { setMessage('commit 需要填写提交说明'); return }
    if (window.confirm(`确认在 ${project.name} 提交当前变更？`)) void run('commit', { message: text })
  }
  const merge = () => {
    const target = ref.trim()
    if (!target) { setMessage('merge 需要填写目标 ref'); return }
    if (window.confirm(`确认将 ${target} 合并到选定仓库？`)) void run('merge', { ref: target })
  }
  const abortMerge = () => {
    if (window.confirm('确认终止当前 Git merge？')) void run('merge_abort')
  }
  return <div className="git-actions"><select value={worktreeId} onChange={(event) => setWorktreeId(event.target.value)} disabled={busy !== ''}><option value="">项目仓库</option>{project.worktrees.filter((worktree) => worktree.status === 'ready').map((worktree) => <option key={worktree.id} value={worktree.id}>{worktree.ref}</option>)}</select><button className="secondary" onClick={() => void run('status')} disabled={!token || busy !== ''}>{busy === 'status' ? '排队中…' : '状态'}</button><button className="secondary" onClick={() => void run('diff')} disabled={!token || busy !== ''}>{busy === 'diff' ? '排队中…' : '差异'}</button><button className="secondary" onClick={() => void run('log')} disabled={!token || busy !== ''}>{busy === 'log' ? '排队中…' : '日志'}</button><input value={commitMessage} onChange={(event) => setCommitMessage(event.target.value)} placeholder="commit message" aria-label="Git commit message" disabled={busy !== ''} /><button className="secondary" onClick={commit} disabled={!token || busy !== ''}>{busy === 'commit' ? '排队中…' : '提交'}</button><input value={ref} onChange={(event) => setRef(event.target.value)} placeholder="merge ref" aria-label="Git merge ref" disabled={busy !== ''} /><button className="secondary" onClick={merge} disabled={!token || busy !== ''}>{busy === 'merge' ? '排队中…' : '合并'}</button><button className="secondary" onClick={abortMerge} disabled={!token || busy !== ''}>{busy === 'merge_abort' ? '排队中…' : '终止合并'}</button>{message && <span className="form-message">{message}</span>}</div>
}

function Tasks({ rows, machines, projects, adminToken, onRefresh, query }: { rows: Task[] | null; machines: Machine[]; projects: Project[]; adminToken: string; onRefresh: () => void; query: string }) {
  const [filter, setFilter] = useState<'all' | 'active' | 'terminal'>('all')
  const paged = usePagedTail(rows, 200, (offset, limit) => listTasksPage(adminToken, offset, limit))
  if (!rows) return <><PageIntro kicker="EXECUTION RECORDS" title="任务记录" action="创建任务" /><section className="panel empty-ready"><div className="empty-icon">≡</div><h3>等待 Center 数据</h3><p>在“系统设置”输入 Admin Token 后，这里显示有界分页、状态和输出游标。</p></section></>
  const visibleRows = paged.rows ?? []
  const needle = query.trim().toLocaleLowerCase()
  const filteredRows = visibleRows.filter((task) => {
    const terminal = ['completed', 'failed', 'canceled'].includes(task.status)
    if (filter === 'active' && terminal) return false
    if (filter === 'terminal' && !terminal) return false
    if (!needle) return true
    return [task.id, task.machineId, task.kind, task.command, task.cwd, task.status].filter(Boolean).some((value) => String(value).toLocaleLowerCase().includes(needle))
  })
  return <><PageIntro kicker="EXECUTION RECORDS" title="任务记录" action="创建任务" onAction={() => document.getElementById('task-composer')?.scrollIntoView({ behavior: 'smooth', block: 'start' })} /><TaskComposer machines={machines} projects={projects} token={adminToken} onCreated={onRefresh} /><section className="panel task-list"><div className="task-toolbar"><div className="segmented"><button className={filter === 'all' ? 'selected' : ''} onClick={() => setFilter('all')} aria-pressed={filter === 'all'}>全部 <b>{visibleRows.length}</b></button><button className={filter === 'active' ? 'selected' : ''} onClick={() => setFilter('active')} aria-pressed={filter === 'active'}>活动 <b>{visibleRows.filter((task) => !['completed', 'failed', 'canceled'].includes(task.status)).length}</b></button><button className={filter === 'terminal' ? 'selected' : ''} onClick={() => setFilter('terminal')} aria-pressed={filter === 'terminal'}>已结束 <b>{visibleRows.filter((task) => ['completed', 'failed', 'canceled'].includes(task.status)).length}</b></button></div><div className="task-toolbar-actions"><span>{filteredRows.length} 条任务{paged.hasMore ? `（已加载 ${visibleRows.length}）` : ''}</span><button className="secondary" onClick={onRefresh}>刷新</button></div></div>{filteredRows.length > 0 ? filteredRows.map((task) => <TaskRow key={task.id} task={task} token={adminToken} onRefresh={onRefresh} />) : <div className="filtered-empty">没有匹配的任务</div>}{paged.rows && (paged.hasMore || paged.loadingMore) && <div className="pagination-bar"><span>任务按页加载，日志仍通过独立游标读取</span><button className="secondary" onClick={() => void paged.loadMore()} disabled={paged.loadingMore || !paged.hasMore}>{paged.loadingMore ? '加载中…' : paged.hasMore ? '加载下一页' : '已加载全部'}</button></div>}</section></>
}

function TaskComposer({ machines, projects, token, onCreated }: { machines: Machine[]; projects: Project[]; token: string; onCreated: () => void }) {
  const [kind, setKind] = useState<'command' | 'desktop' | 'browser'>('command')
  const [machineId, setMachineId] = useState('')
  const [command, setCommand] = useState('')
  const [cwd, setCwd] = useState('')
  const [projectId, setProjectId] = useState('')
  const [worktreeId, setWorktreeId] = useState('')
  const [scopeMode, setScopeMode] = useState('auto')
  const [scopeRoot, setScopeRoot] = useState('')
  const [timeout, setTimeoutValue] = useState('0')
  const [risk, setRisk] = useState('low')
  const [elevation, setElevation] = useState(false)
  const [sessionId, setSessionId] = useState('')
  const [idempotencyKey, setIdempotencyKey] = useState(() => `console-task-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`)
  const [desktopOperation, setDesktopOperation] = useState('screenshot')
  const [desktopExecutable, setDesktopExecutable] = useState('')
  const [desktopArgs, setDesktopArgs] = useState('')
  const [desktopText, setDesktopText] = useState('')
  const [desktopX, setDesktopX] = useState('')
  const [desktopY, setDesktopY] = useState('')
  const [desktopX2, setDesktopX2] = useState('')
  const [desktopY2, setDesktopY2] = useState('')
  const [desktopScreen, setDesktopScreen] = useState('')
  const [desktopWindowTitle, setDesktopWindowTitle] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [message, setMessage] = useState('')
  const eligibleMachines = machines.filter((machine) => machine.online && machine.capabilities.includes(kind))
  useEffect(() => {
    if (!eligibleMachines.some((machine) => machine.id === machineId)) {
      setMachineId(eligibleMachines[0]?.id ?? '')
      setProjectId('')
      setWorktreeId('')
    }
  }, [kind, machines, machineId])
  useEffect(() => {
    // A scope selected for one Agent must never silently carry over to a
    // different Agent with a narrower workspace policy.
    setScopeMode('auto')
    setScopeRoot('')
  }, [machineId])
  const selectedMachine = machines.find((machine) => machine.id === machineId)
  const selectedProject = projects.find((project) => project.id === projectId)
  const effectiveScope = worktreeId ? 'worktree' : projectId ? 'project' : (scopeMode === 'auto' ? (selectedMachine?.scopeMode || 'workspace') : scopeMode)
  const parseNumber = (value: string) => {
    const trimmed = value.trim()
    if (!trimmed) return undefined
    const parsed = Number(trimmed)
    return Number.isFinite(parsed) ? parsed : undefined
  }
  const submit = async () => {
    if (!machineId || !command.trim() && kind !== 'desktop') {
      setMessage(kind === 'desktop' ? '请选择在线桌面 Agent' : '请选择在线 Agent 并填写请求内容')
      return
    }
    if (!projectId && !worktreeId && scopeMode === 'auto' && effectiveScope === 'unrestricted') {
      setMessage('该 Agent 仅提供 unrestricted 范围；请选择“Unrestricted（显式）”后再提交')
      return
    }
    if (kind === 'desktop' && desktopOperation === 'launch' && !desktopExecutable.trim()) {
      setMessage('启动桌面应用需要填写可执行文件')
      return
    }
    if (effectiveScope === 'path' && !scopeRoot.trim()) {
      setMessage('path 范围需要填写绝对 scope root')
      return
    }
    setSubmitting(true)
    setMessage('')
    try {
      const base = {
        machine_id: machineId,
        cwd: cwd.trim() || undefined,
        project_id: projectId || undefined,
        worktree_id: worktreeId || undefined,
        scope_mode: effectiveScope,
        scope_root: effectiveScope === 'path' ? scopeRoot.trim() : undefined,
        timeout_seconds: Number(timeout) || 0,
        session_id: sessionId.trim() || undefined,
        risk,
        elevation_required: elevation,
        idempotency_key: idempotencyKey.trim() || undefined,
      }
      const taskCommand = kind === 'desktop'
        ? {
            kind: 'desktop',
            required_capability: 'desktop',
            command: null,
            cwd: cwd.trim() || undefined,
            env: {},
            timeout_seconds: Number(timeout) || 30,
            desktop: {
              operation: desktopOperation,
              executable: desktopOperation === 'launch' ? desktopExecutable.trim() || undefined : undefined,
              args: desktopOperation === 'launch' ? desktopArgs.split(/\r?\n/).map((value) => value.trim()).filter(Boolean) : [],
              cwd: cwd.trim() || undefined,
              text: desktopText || undefined,
              x: parseNumber(desktopX),
              y: parseNumber(desktopY),
              x2: parseNumber(desktopX2),
              y2: parseNumber(desktopY2),
              screen: parseNumber(desktopScreen),
              window_title: desktopWindowTitle.trim() || undefined,
            },
            created_at: null,
          }
        : {
            kind,
            required_capability: kind,
            command: command.trim(),
            cwd: cwd.trim() || undefined,
            env: {},
            timeout_seconds: Number(timeout) || (kind === 'browser' ? 300 : 0),
            desktop: null,
            created_at: null,
          }
      const task = await createTask(token, { ...base, command: kind === 'command' ? command.trim() : taskCommand })
      setMessage(`已创建 ${task.id}（${kind}），任务在 Agent 后台异步执行`)
      setCommand('')
      setIdempotencyKey(`console-task-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`)
      onCreated()
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '创建任务失败')
    } finally {
      setSubmitting(false)
    }
  }
  const projectOptions = projects.filter((project) => project.machineId === machineId)
  return <section className="panel task-composer" id="task-composer"><div><span className="section-kicker">ASYNC DISPATCH</span><h3>创建后台任务</h3><p>提交只写入 Center 队列，页面不会等待完成。Agent 会按项目、worktree、path 或显式 unrestricted 合同执行，并返回有界输出/工件。</p></div><div className="composer-grid"><label>任务类型<select value={kind} onChange={(event) => { setKind(event.target.value as 'command' | 'desktop' | 'browser'); setCommand('') }}><option value="command">Command · 终端</option><option value="desktop">Desktop · 用户会话</option><option value="browser">Browser · 本地适配器</option></select></label><label>目标 Agent<select value={machineId} onChange={(event) => { setMachineId(event.target.value); setProjectId(''); setWorktreeId('') }} disabled={eligibleMachines.length === 0}><option value="">选择在线 Agent</option>{eligibleMachines.map((machine) => <option key={machine.id} value={machine.id}>{machine.name} · {machine.os ?? 'unknown'} · {machine.id}</option>)}</select></label><label>项目（可选）<select value={projectId} onChange={(event) => { setProjectId(event.target.value); setWorktreeId('') }} disabled={!machineId}><option value="">Agent 默认目录</option>{projectOptions.map((project) => <option key={project.id} value={project.id}>{project.name}</option>)}</select></label><label>Worktree（可选）<select value={worktreeId} onChange={(event) => setWorktreeId(event.target.value)} disabled={!projectId}><option value="">项目根目录</option>{selectedProject?.worktrees.filter((worktree) => worktree.status === 'ready').map((worktree) => <option key={worktree.id} value={worktree.id}>{worktree.ref}</option>)}</select></label><label>范围<select value={scopeMode} onChange={(event) => setScopeMode(event.target.value)} disabled={Boolean(projectId)}><option value="auto">Agent 默认范围</option><option value="workspace">Workspace</option><option value="path">Path</option><option value="unrestricted">Unrestricted（显式）</option></select></label><label>风险<select value={risk} onChange={(event) => setRisk(event.target.value)}><option value="low">低</option><option value="high">高</option><option value="critical">严重</option></select></label><label>超时（秒）<input type="number" min="0" max="2592000" value={timeout} onChange={(event) => setTimeoutValue(event.target.value)} /></label><label>会话 ID（可选）<input value={sessionId} onChange={(event) => setSessionId(event.target.value)} placeholder="用于桌面/浏览器复用上下文" /></label><label className="checkbox-label"><input type="checkbox" checked={elevation} onChange={(event) => setElevation(event.target.checked)} /> 显式请求提权</label><label className="composer-wide">工作目录（可选）<input value={cwd} onChange={(event) => setCwd(event.target.value)} placeholder="项目/worktree 内的相对目录；留空使用选定根目录" /></label>{effectiveScope === 'path' && <label className="composer-wide">Path scope root<input value={scopeRoot} onChange={(event) => setScopeRoot(event.target.value)} placeholder="目标 Agent 上的绝对目录" /></label>}{kind === 'desktop' ? <><label>桌面操作<select value={desktopOperation} onChange={(event) => setDesktopOperation(event.target.value)}><option value="screenshot">截图</option><option value="screens">显示器列表</option><option value="launch">启动应用</option><option value="click">点击</option><option value="drag">拖拽</option><option value="key">按键</option><option value="type">输入文本</option><option value="clipboard_read">读取剪贴板</option><option value="clipboard_write">写入剪贴板</option><option value="focus">聚焦窗口</option></select></label>{desktopOperation === 'launch' && <><label>可执行文件<input value={desktopExecutable} onChange={(event) => setDesktopExecutable(event.target.value)} placeholder="例如 notepad.exe" /></label><label className="composer-wide">启动参数（每行一个）<textarea value={desktopArgs} onChange={(event) => setDesktopArgs(event.target.value)} rows={2} /></label></>}{['type', 'clipboard_write'].includes(desktopOperation) && <label className="composer-wide">文本<textarea value={desktopText} onChange={(event) => setDesktopText(event.target.value)} rows={2} /></label>}{['click', 'drag'].includes(desktopOperation) && <><label>X<input type="number" value={desktopX} onChange={(event) => setDesktopX(event.target.value)} /></label><label>Y<input type="number" value={desktopY} onChange={(event) => setDesktopY(event.target.value)} /></label></>}{desktopOperation === 'drag' && <><label>X2<input type="number" value={desktopX2} onChange={(event) => setDesktopX2(event.target.value)} /></label><label>Y2<input type="number" value={desktopY2} onChange={(event) => setDesktopY2(event.target.value)} /></label></>}{desktopOperation === 'screenshot' && <label>屏幕编号（可选）<input type="number" min="0" max="32" value={desktopScreen} onChange={(event) => setDesktopScreen(event.target.value)} /></label>}{desktopOperation === 'focus' && <label className="composer-wide">窗口标题<input value={desktopWindowTitle} onChange={(event) => setDesktopWindowTitle(event.target.value)} /></label>}{desktopOperation === 'key' && <label>按键<input value={desktopText} onChange={(event) => setDesktopText(event.target.value)} placeholder="例如 CTRL+L" /></label>}</> : <label className="composer-wide">{kind === 'browser' ? 'Browser adapter 请求' : 'Shell 命令'}<textarea value={command} onChange={(event) => setCommand(event.target.value)} placeholder={kind === 'browser' ? '{"operation":"snapshot"}' : '例如：git status --short'} rows={3} /></label>}</div><div className="composer-actions"><label className="idempotency-field">幂等键<input value={idempotencyKey} maxLength={256} onChange={(event) => setIdempotencyKey(event.target.value)} /></label><button className="primary" onClick={() => void submit()} disabled={submitting || !token || eligibleMachines.length === 0}>{submitting ? '提交中…' : '立即入队'}</button>{message && <span className="form-message">{message}</span>}</div></section>
}

function TaskRow({ task, token, onRefresh }: { task: Task; token: string; onRefresh: () => void }) {
  const [canceling, setCanceling] = useState(false)
  const [expanded, setExpanded] = useState(false)
  const [output, setOutput] = useState('')
  const [cursor, setCursor] = useState(0)
  const [more, setMore] = useState(false)
  const [loadingOutput, setLoadingOutput] = useState(false)
  const [outputError, setOutputError] = useState('')
  const [artifactUrl, setArtifactUrl] = useState('')
  const [artifactError, setArtifactError] = useState('')
  const [loadingArtifact, setLoadingArtifact] = useState(false)
  const terminal = ['completed', 'failed', 'canceled'].includes(task.status)
  useEffect(() => () => {
    if (artifactUrl) URL.revokeObjectURL(artifactUrl)
  }, [artifactUrl])
  const loadOutput = async (reset: boolean) => {
    setLoadingOutput(true)
    setOutputError('')
    try {
      const page = await readTaskOutput(token, task.id, reset ? 0 : cursor, 16 * 1024)
      setOutput((previous) => reset ? page.text : previous + page.text)
      setCursor(page.nextCursor)
      setMore(page.more)
      setExpanded(true)
    } catch (error) {
      setOutputError(error instanceof Error ? error.message : '读取任务输出失败')
    } finally {
      setLoadingOutput(false)
    }
  }
  const loadArtifact = async () => {
    setLoadingArtifact(true)
    setArtifactError('')
    try {
      const result = await readTaskArtifact(token, task.id)
      const nextUrl = URL.createObjectURL(result.blob)
      setArtifactUrl((previous) => {
        if (previous) URL.revokeObjectURL(previous)
        return nextUrl
      })
      setExpanded(true)
    } catch (error) {
      setArtifactError(error instanceof Error ? error.message : '读取工件失败')
    } finally {
      setLoadingArtifact(false)
    }
  }
  const cancel = async () => {
    setCanceling(true)
    try {
      await cancelTask(token, task.id)
      onRefresh()
    } finally {
      setCanceling(false)
    }
  }
  return <>
    <div className="task-row">
      <div><strong>{task.id}</strong><span>{task.command || task.kind} · {task.machineId} · {task.scopeMode || 'workspace'}{task.projectId ? ` · ${task.projectId}` : ''}{task.worktreeId ? ` / ${task.worktreeId}` : ''}</span></div>
      <span className={`state ${terminal ? 'muted' : 'accent'}`}><i />{task.status}</span>
      <span className="mono">第 {task.attempt} 次投递</span>
      <span className="mono">{task.outputBytes} B</span>
      <div className="task-row-actions">
        <button className="secondary" onClick={() => expanded ? setExpanded(false) : void loadOutput(true)} disabled={loadingOutput} aria-expanded={expanded}>
          {loadingOutput ? '读取中…' : expanded ? '收起输出' : '查看输出'}
        </button>
        {!terminal && <button className="secondary" onClick={() => void cancel()} disabled={canceling}>{canceling ? '取消中…' : '取消'}</button>}
        {task.artifactBytes > 0 && <button className="secondary" onClick={() => void loadArtifact()} disabled={loadingArtifact}>{loadingArtifact ? '读取中…' : artifactUrl ? '重新读取工件' : '查看工件'}</button>}
      </div>
    </div>
    {expanded && <div className="task-output-row">
      {outputError ? <div className="output-error" role="alert">{outputError}</div> : <pre className="task-output-view">{output || '暂无输出'}</pre>}
      <div className="task-output-actions">
        {more && <button className="secondary" onClick={() => void loadOutput(false)} disabled={loadingOutput}>{loadingOutput ? '读取中…' : '加载下一页'}</button>}
        <span className="mono">已读取 {cursor} B{task.outputTruncated ? ' · 输出已被 Agent 有界截断' : ''}</span>
      </div>
      {artifactError && <div className="output-error" role="alert">{artifactError}</div>}
      {artifactUrl && <div className="task-artifact-preview">
        {task.artifactMime?.toLowerCase().startsWith('image/') && <img src={artifactUrl} alt={`任务 ${task.id} 工件预览`} />}
        <a className="secondary" href={artifactUrl} download={`${task.id}-artifact`}>下载工件</a>
        {task.artifactSha256 && <span className="mono">SHA-256 {task.artifactSha256}</span>}
      </div>}
    </div>}
  </>
}
function Enrollment({ adminToken }: { adminToken: string }) {
  const [name, setName] = useState('')
  const [lifetime, setLifetime] = useState('86400')
  const [issued, setIssued] = useState<{ tokenId: string; token: string; expiresAt: string } | null>(null)
  const [message, setMessage] = useState('')
  const generate = async () => {
    setMessage('')
    try {
      const result = await issueEnrollment(adminToken, name, Number(lifetime))
      setIssued(result)
      setMessage('令牌只显示本次页面生命周期，请立即复制或下载。')
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '生成失败')
    }
  }
  const copy = async () => {
    if (issued) await navigator.clipboard.writeText(issued.token)
    setMessage('令牌已复制')
  }
  const download = () => {
    if (!issued) return
    const content = `REMOTE_CONNECT_MCP_AGENT_ENROLLMENT_TOKEN=${issued.token}\nREMOTE_CONNECT_MCP_AGENT_NAME=${name}\n`
    const url = URL.createObjectURL(new Blob([content], { type: 'text/plain;charset=utf-8' }))
    const link = document.createElement('a')
    link.href = url
    link.download = `remote-connect-mcp-enrollment-${name || 'agent'}.env`
    link.click()
    URL.revokeObjectURL(url)
  }
  return <><PageIntro kicker="SECURITY" title="注册令牌" action="生成一次性令牌" /><section className="split-grid"><div className="panel form-panel"><span className="section-kicker">ONE-TIME ENROLLMENT</span><h3>为新 Agent 生成令牌</h3><p>令牌与目标 Agent 名称绑定，注册成功一次后立即失效。</p><label>Agent 名称<input value={name} onChange={(event) => setName(event.target.value)} placeholder="例如 desktop-lab-02" /></label><label>有效期<select value={lifetime} onChange={(event) => setLifetime(event.target.value)}><option value="3600">1 小时</option><option value="21600">6 小时</option><option value="86400">1 天</option><option value="604800">7 天</option><option value="2592000">30 天</option></select></label><button className="primary full" onClick={() => void generate()} disabled={!adminToken || !name.trim()}>生成令牌</button>{issued && <div className="issued-token"><code>{issued.token}</code><div><button className="secondary" onClick={() => void copy()}>复制</button><button className="secondary" onClick={download}>下载 env</button></div></div>}{message && <p className="form-message">{message}</p>}</div><div className="panel info-panel"><span className="section-kicker">POLICY</span><h3>身份边界</h3><div className="policy-item"><span>⌁</span><div><strong>独立 Agent 身份</strong><p>同一 host_id 下的 command、desktop、browser 不共享 Token。</p></div></div><div className="policy-item"><span>◈</span><div><strong>一次性注册</strong><p>注册成功立即失效，日常通信换用独立 Agent Token。</p></div></div></div></section></>
}
function Upgrades({ token, rows, releases, machines, onRefresh, query }: { token: string; rows: UpgradeCampaign[] | null; releases: ReleaseCatalog | null; machines: Machine[]; onRefresh: () => void; query: string }) {
  const [version, setVersion] = useState('')
  const [canary, setCanary] = useState('1')
  const [batch, setBatch] = useState('3')
  const [submitting, setSubmitting] = useState(false)
  const [message, setMessage] = useState('')
  const paged = usePagedTail(rows, 100, (offset, limit) => listUpgradesPage(token, offset, limit))
  useEffect(() => {
    if (version || !releases?.items.length) return
    const preferred = releases.items.find((release) => !release.prerelease && release.assets.some((asset) => asset.available && asset.checksumAvailable))
      ?? releases.items.find((release) => release.assets.some((asset) => asset.available && asset.checksumAvailable))
      ?? releases.items[0]
    if (preferred) setVersion(preferred.version)
  }, [releases, version])
  const names = Object.fromEntries(machines.map((machine) => [machine.id, machine.name]))
  const needle = query.trim().toLocaleLowerCase()
  const filteredRows = paged.rows?.filter((campaign) => {
    if (!needle) return true
    const targetText = campaign.targets.flatMap((target) => [target.machineId, names[target.machineId], target.status, target.error]).filter(Boolean)
    return [campaign.id, campaign.version, campaign.status, ...targetText].some((value) => String(value).toLocaleLowerCase().includes(needle))
  }) ?? null
  const submit = async () => {
    if (!version.trim()) { setMessage('请选择一个已发布 Release 版本'); return }
    setSubmitting(true); setMessage('')
    try {
      await createUpgrade(token, { version: version.trim(), canary_count: Number(canary) || 1, batch_size: Number(batch) || 3 })
      setMessage('升级活动已创建，Agent 将在下一次心跳中领取任务。')
      setVersion(''); onRefresh()
    } catch (error) { setMessage(error instanceof Error ? error.message : '创建升级活动失败') }
    finally { setSubmitting(false) }
  }
  const action = async (id: string, value: 'resume' | 'cancel') => {
    try { await controlUpgrade(token, id, value); onRefresh() }
    catch (error) { setMessage(error instanceof Error ? error.message : '升级活动操作失败') }
  }
  const retryTarget = async (campaign: UpgradeCampaign, machineId: string) => {
    if (!window.confirm('确认只重新排队该失败 Agent？已成功的目标不会重复升级。')) return
    try { await retryUpgradeTarget(token, campaign.id, machineId); setMessage(`已重新排队 ${names[machineId] ?? machineId}`); onRefresh() }
    catch (error) { setMessage(error instanceof Error ? error.message : '目标重排队失败') }
  }
  return (
    <>
      <PageIntro kicker="OPERATIONS" title="升级编排" action="创建升级活动" onAction={() => document.getElementById('upgrade-composer')?.scrollIntoView({ behavior: 'smooth', block: 'start' })} />
      <section className="panel task-composer" id="upgrade-composer">
        <div><span className="section-kicker">CANARY RELEASE</span><h3>选择并发布 Agent 版本</h3><p>GitHub Actions 发布的新版本会出现在目录中。Center 按目标机器平台重新校验资产与 SHA-256，先 canary，成功后按批次推进。</p></div>
        <div className="release-catalog-row"><label>目标 Release<select value={version} onChange={(event) => setVersion(event.target.value)} disabled={!releases?.items.length}><option value="">{releases?.items.length ? '请选择版本' : '等待版本目录'}</option>{releases?.items.map((release) => { const ready = release.assets.filter((asset) => asset.available && asset.checksumAvailable).length; return <option key={release.version} value={release.version}>{release.version}{release.prerelease ? ' · 预发布' : ''} · {ready}/{release.assets.length} 平台资产</option> })}</select></label><button className="secondary release-refresh" onClick={onRefresh} disabled={!token}>刷新目录</button></div>
        <div className="release-catalog-meta">{releases?.refreshedAt && <span>目录刷新：{new Date(releases.refreshedAt).toLocaleString()}</span>}{releases?.stale && <span className="warning">{releases.warning || '目录为缓存数据'}</span>}{releases && !releases.items.length && <span>暂无可选 Java Release；也可以在下方手动填写已知版本。</span>}</div>
        <details className="manual-release"><summary>高级：手动填写版本</summary><input value={version} onChange={(event) => setVersion(event.target.value)} placeholder="例如 v0.1.15" /></details>
        <div className="composer-grid"><label>首批 canary<input type="number" min="1" value={canary} onChange={(event) => setCanary(event.target.value)} /></label><label>后续批次<input type="number" min="1" value={batch} onChange={(event) => setBatch(event.target.value)} /></label></div>
        <div className="composer-actions"><button className="primary" onClick={() => void submit()} disabled={submitting || !token || !version.trim()}>{submitting ? '创建中…' : '开始升级'}</button>{message && <span className="form-message">{message}</span>}</div>
      </section>
      <section className="upgrade-list">
        {!filteredRows?.length ? <div className="panel empty-ready"><div className="empty-icon">↗</div><h3>{token ? (paged.rows?.length ? '没有匹配的升级活动' : '暂无升级活动') : '等待 Center 数据'}</h3><p>{token ? (paged.rows?.length ? '尝试修改顶部搜索条件。' : '选择 Release 版本即可创建第一批 canary。') : '在系统设置输入 Admin Token 后加载升级活动。'}</p></div> : <>
          {filteredRows.map((campaign) => {
            const done = campaign.targets.filter((target) => target.status === 'completed').length
            const failed = campaign.targets.filter((target) => target.status === 'failed').length
            const percent = campaign.targets.length ? Math.round(done * 100 / campaign.targets.length) : 0
            const retryAllowed = campaign.status !== 'completed' && campaign.status !== 'canceled'
            return <article className="panel upgrade-card" key={campaign.id}>
              <div className="upgrade-card-head"><div><span className="mono">{campaign.id}</span><h3>{campaign.version}</h3></div><div className="row"><span className={`state ${campaign.status === 'completed' ? 'good' : campaign.status === 'paused' ? 'bad' : 'accent'}`}><i />{campaign.status}</span>{campaign.status === 'paused' && <button className="secondary" onClick={() => void action(campaign.id, 'resume')}>恢复</button>}{campaign.status === 'running' && <button className="secondary" onClick={() => void action(campaign.id, 'cancel')}>取消</button>}</div></div>
              <div className="upgrade-meta"><span>目标 <b>{campaign.targets.length}</b> 台</span><span>完成 <b>{done}</b></span>{failed > 0 && <span>失败 <b>{failed}</b></span>}<span>canary {campaign.canaryCount} · 每批 {campaign.batchSize}</span></div>
              <div className="progress-track"><span style={{ width: `${percent}%` }} /></div>
              <div className="task-list">{campaign.targets.map((target) => <div className="task-row" key={target.machineId}><div><strong>{names[target.machineId] ?? target.machineId}</strong><span>{target.status}{target.error ? ` · ${target.error}` : ''}</span></div><div className="row"><span className="mono">{target.attempts} 次</span>{target.status === 'failed' && retryAllowed && <button className="secondary compact-action" onClick={() => void retryTarget(campaign, target.machineId)}>重试该机</button>}</div></div>)}</div>
            </article>
          })}
          {paged.rows && (paged.hasMore || paged.loadingMore || paged.loadError) && <div className="pagination-bar"><span>{paged.loadError || '升级活动按页加载，避免在控制台一次展开历史活动'}</span><button className="secondary" onClick={() => void paged.loadMore()} disabled={paged.loadingMore || !paged.hasMore}>{paged.loadingMore ? '加载中…' : paged.hasMore ? '加载下一页' : '已加载全部'}</button></div>}
        </>}
      </section>
    </>
  )
}
function Audit({ rows, machines, token, onRefresh, query }: { rows: AuditEvent[] | null; machines: Machine[]; token: string; onRefresh: () => void; query: string }) {
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const paged = usePagedTail(rows, 200, (offset, limit) => listAuditPage(token, offset, limit))
  const names = Object.fromEntries(machines.map((machine) => [machine.id, machine.name]))
  const needle = query.trim().toLocaleLowerCase()
  const visible = (paged.rows ?? []).filter((event) => !needle || [event.eventType, event.actor, event.agentId, event.taskId, event.scopeMode, event.risk, event.outcome, event.detail].some((value) => String(value ?? '').toLocaleLowerCase().includes(needle)))
  const purge = async () => {
    if (!window.confirm('确认删除 365 天以前的审计事件？此操作只删除审计记录，不影响任务和工件。')) return
    setBusy(true); setMessage('')
    try { const result = await purgeAudit(token); setMessage(`已清理 ${result.deleted} 条审计记录`); onRefresh() }
    catch (error) { setMessage(error instanceof Error ? error.message : '审计清理失败') }
    finally { setBusy(false) }
  }
  return <><PageIntro kicker="EXECUTION RECORDS" title="审计日志" action="刷新" onAction={onRefresh} /><section className="panel table-panel"><div className="audit-note"><span>只展示有界、脱敏的事件元数据；命令正文、环境变量、Token、Cookie 和工件内容不会进入审计记录。</span><button className="secondary" onClick={() => void purge()} disabled={!token || busy}>{busy ? '清理中…' : '清理一年以前'}</button>{message && <span className="form-message">{message}</span>}</div><div className="table-head audit-head"><span>时间</span><span>事件</span><span>来源</span><span>目标</span><span>结果</span><span>详情</span></div>{visible.length > 0 ? visible.map((event) => <div className="table-row audit-row" key={event.id}><span className="mono">{event.createdAt ? new Date(event.createdAt).toLocaleString() : '—'}</span><span>{event.eventType}</span><span>{event.actor}</span><span className="mono">{event.agentId ? (names[event.agentId] ?? event.agentId) : event.taskId ?? '—'}</span><span className={`state ${event.outcome === 'failed' ? 'bad' : 'good'}`}><i />{event.outcome ?? '—'}</span><span className="audit-detail">{event.detail ?? '—'}</span></div>) : <div className="filtered-empty">{token ? (paged.rows?.length ? '没有匹配的审计事件' : '暂无审计事件') : '在系统设置输入 Admin Token 后加载审计事件'}</div>}{paged.rows && (paged.hasMore || paged.loadingMore) && <div className="pagination-bar"><span>审计记录只按页加载，服务端仍保留完整总数</span><button className="secondary" onClick={() => void paged.loadMore()} disabled={paged.loadingMore || !paged.hasMore}>{paged.loadingMore ? '加载中…' : paged.hasMore ? '加载下一页' : '已加载全部'}</button></div>}</section></>
}

function Settings({ token, machines, onTokenChange, onRefresh }: { token: string; machines: Machine[]; onTokenChange: (value: string) => void; onRefresh: () => void }) {
  const [machineId, setMachineId] = useState('')
  const [config, setConfig] = useState<AgentConfig | null>(null)
  const [poll, setPoll] = useState('5000')
  const [concurrency, setConcurrency] = useState('1')
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  useEffect(() => {
    if (!machineId && machines.length > 0) setMachineId(machines[0].id)
  }, [machineId, machines])
  useEffect(() => {
    if (!token || !machineId) { setConfig(null); return }
    setBusy(true); setMessage('')
    void getMachineConfig(token, machineId).then((value) => { setConfig(value); setPoll(String(value.pollIntervalMs)); setConcurrency(String(value.maxConcurrency)) })
      .catch((error) => setMessage(error instanceof Error ? error.message : '读取 Agent 配置失败'))
      .finally(() => setBusy(false))
  }, [token, machineId])
  const save = async () => {
    if (!config || !machineId) return
    setBusy(true); setMessage('')
    try {
      const value = await updateMachineConfig(token, machineId, { poll_interval_ms: Number(poll), max_concurrency: Number(concurrency), expected_generation: config.generation })
      setConfig(value); setPoll(String(value.pollIntervalMs)); setConcurrency(String(value.maxConcurrency)); setMessage(`配置已发布，generation ${value.generation}`); onRefresh()
    } catch (error) { setMessage(error instanceof Error ? error.message : '保存 Agent 配置失败') }
    finally { setBusy(false) }
  }
  const rollback = async () => {
    if (!config || !machineId || !window.confirm('确认回滚到上一份 Agent 配置？回滚会发布新的 generation。')) return
    setBusy(true); setMessage('')
    try { const value = await rollbackMachineConfig(token, machineId); setConfig(value); setPoll(String(value.pollIntervalMs)); setConcurrency(String(value.maxConcurrency)); setMessage(`已回滚并发布 generation ${value.generation}`); onRefresh() }
    catch (error) { setMessage(error instanceof Error ? error.message : '回滚 Agent 配置失败') }
    finally { setBusy(false) }
  }
  return <><PageIntro kicker="SECURITY & POLICY" title="系统设置" action="保存变更" /><section className="panel token-panel"><span className="section-kicker">CENTER SESSION</span><h3>连接控制台 API</h3><p>令牌只保存在当前浏览器标签页内存，刷新页面后自动清除，不写入 localStorage。</p><label>Admin Token<input type="password" value={token} onChange={(event) => onTokenChange(event.target.value)} placeholder="粘贴 Center Admin Token" autoComplete="off" /></label><button className="primary" onClick={onRefresh}>验证并加载</button></section><section className="panel token-panel"><span className="section-kicker">AGENT RUNTIME</span><h3>热更新运行参数</h3><p>配置通过 generation CAS 发布；Agent 在下一次心跳收到后原子落盘。输出、Token 和工作区边界不能在这里修改。</p><div className="composer-grid"><label>目标 Agent<select value={machineId} onChange={(event) => setMachineId(event.target.value)} disabled={!machines.length || busy}><option value="">选择 Agent</option>{machines.map((machine) => <option key={machine.id} value={machine.id}>{machine.name} · {machine.online ? '在线' : '离线'}</option>)}</select></label><label>长轮询等待（毫秒）<input type="number" min="250" max="60000" value={poll} onChange={(event) => setPoll(event.target.value)} disabled={!config || busy} /></label><label>最大并发槽位<input type="number" min="1" max="32" value={concurrency} onChange={(event) => setConcurrency(event.target.value)} disabled={!config || busy} /></label></div><div className="composer-actions"><button className="primary" onClick={() => void save()} disabled={!token || !config || busy}>{busy ? '处理中…' : '发布配置'}</button><button className="secondary" onClick={() => void rollback()} disabled={!token || !config || busy}>回滚上一代</button>{config && <span className="mono">当前 generation {config.generation}</span>}{message && <span className="form-message">{message}</span>}</div></section><section className="settings-grid"><div className="panel setting-card"><span className="setting-icon">⌁</span><div><h3>连接策略</h3><p>控制台通过事件长连接获取变更，异常时才重试；Agent 使用长轮询/WebSocket 唤醒。</p></div><span className="toggle on" /></div><div className="panel setting-card"><span className="setting-icon">◈</span><div><h3>令牌存储</h3><p>仅保存摘要；前端不将 Admin Token 写入 localStorage。</p></div><span className="toggle on" /></div><div className="panel setting-card"><span className="setting-icon">▣</span><div><h3>工具上下文</h3><p>维持精简 MCP 工具面，结果分页且有界。</p></div><span className="toggle on" /></div></section></> }

function PageIntro({ kicker, title, action, onAction }: { kicker: string; title: string; action: string; onAction?: () => void }) { return <div className="page-intro"><div><span className="eyebrow">{kicker}</span><p>统一管理多 Agent 终端、任务和版本状态</p></div><button className="primary" onClick={onAction} disabled={!onAction}>{action} <span>＋</span></button></div> }

export default App
