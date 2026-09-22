import { useCallback, useEffect, useRef, useState } from 'react'
import {
  AdminApiError,
  listAudit,
  listMachines,
  listProjects,
  listReleases,
  listTasks,
  listUpgrades,
  waitForAdminChange,
  type AuditEvent,
  type Machine,
  type Project,
  type ReleaseCatalog,
  type Task,
  type UpgradeCampaign,
} from './api'
import { PageId, Sidebar } from './components/Sidebar'
import { Topbar } from './components/Topbar'
import { OverviewView } from './views/OverviewView'
import { MachinesView } from './views/MachinesView'
import { ProjectsView } from './views/ProjectsView'
import { TasksView } from './views/TasksView'
import { ArtifactsView } from './views/ArtifactsView'
import { AuditView } from './views/AuditView'
import { EnrollmentView } from './views/EnrollmentView'
import { AccessControlView } from './views/AccessControlView'
import { UpgradesView } from './views/UpgradesView'
import { SettingsView } from './views/SettingsView'

export default function App() {
  const [page, setPage] = useState<PageId>('overview')
  const [mobileNavOpen, setMobileNavOpen] = useState(false)
  const [search, setSearch] = useState('')
  const [adminToken, setAdminToken] = useState('')
  const [liveMachines, setLiveMachines] = useState<Machine[] | null>(null)
  const [liveProjects, setLiveProjects] = useState<Project[] | null>(null)
  const [liveTasks, setLiveTasks] = useState<Task[] | null>(null)
  const [liveAudit, setLiveAudit] = useState<AuditEvent[] | null>(null)
  const [liveUpgrades, setLiveUpgrades] = useState<UpgradeCampaign[] | null>(null)
  const [liveReleases, setLiveReleases] = useState<ReleaseCatalog | null>(null)
  const [apiMessage, setApiMessage] = useState('')
  const [loading, setLoading] = useState(false)
  const refreshInFlight = useRef<{ key: string; promise: Promise<void> } | null>(null)

  const navigate = useCallback((next: PageId) => {
    setPage(next)
    setMobileNavOpen(false)
  }, [])

  const connectionState: 'online' | 'connecting' | 'offline' | 'idle' = loading
    ? 'connecting'
    : liveMachines
    ? 'online'
    : adminToken.trim()
    ? 'offline'
    : 'idle'

  const connectionLabel = loading
    ? '正在连接 Center...'
    : liveMachines
    ? 'Center 实时在线'
    : adminToken.trim()
    ? 'Center 无法连通'
    : '演示脱敏模式'

  // Scroll to top on page change
  useEffect(() => {
    window.scrollTo({ top: 0, left: 0, behavior: 'auto' })
  }, [page])

  // Refresh handler
  const refresh = useCallback(
    (token = adminToken, forceReleases = false): Promise<void> => {
      const normalizedToken = token.trim()
      const key = `${normalizedToken}\u0000${forceReleases ? 'refresh' : 'cached'}`
      if (refreshInFlight.current?.key === key) return refreshInFlight.current.promise

      const operation = (async () => {
        if (!normalizedToken) {
          setLiveMachines(null)
          setLiveProjects(null)
          setLiveTasks(null)
          setLiveAudit(null)
          setLiveUpgrades(null)
          setLiveReleases(null)
          setApiMessage('脱敏演示模式：在“系统设置”输入 Admin Token 后加载 Center 实时集群数据')
          return
        }

        setLoading(true)
        setApiMessage('')
        try {
          const [machines, projects, tasks, upgrades, releases, audit] = await Promise.all([
            listMachines(normalizedToken),
            listProjects(normalizedToken).catch(() => []),
            listTasks(normalizedToken),
            listUpgrades(normalizedToken),
            listReleases(normalizedToken, true, forceReleases).catch(
              (): ReleaseCatalog => ({
                items: [],
                stale: true,
                available: false,
                warning: '版本目录暂不可用',
              })
            ),
            listAudit(normalizedToken).catch(() => []),
          ])
          setLiveMachines(machines)
          setLiveProjects(projects)
          setLiveTasks(tasks)
          setLiveUpgrades(upgrades)
          setLiveReleases(releases)
          setLiveAudit(audit)
          setApiMessage(`已接入 Center · 活跃同步中 (${new Date().toLocaleTimeString()})`)
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
      refreshInFlight.current = { key, promise: tracked }
      return tracked
    },
    [adminToken]
  )

  // Real-time long polling loop
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
          await new Promise<void>((resolve) => {
            retryTimer = window.setTimeout(resolve, 2000)
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

  // Keyboard shortcut (⌘K or / to focus search)
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      const isModifierK = (e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k'
      const isSlash = e.key === '/' && !['INPUT', 'TEXTAREA', 'SELECT'].includes((document.activeElement as HTMLElement)?.tagName)
      if (isModifierK || isSlash) {
        e.preventDefault()
        const input = document.querySelector<HTMLInputElement>('.search-box input')
        input?.focus()
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [])

  return (
    <div className="app-shell">
      <Sidebar
        currentPage={page}
        onNavigate={navigate}
        isOpen={mobileNavOpen}
        onCloseMobile={() => setMobileNavOpen(false)}
        connectionState={connectionState}
        connectionLabel={connectionLabel}
      />

      <main className="main-content">
        <Topbar
          currentPage={page}
          search={search}
          onSearchChange={setSearch}
          onToggleMobileNav={() => setMobileNavOpen((open) => !open)}
          connectionState={connectionState}
          connectionLabel={connectionLabel}
          loading={loading}
          onRefresh={() => void refresh(adminToken, true)}
        />

        <div className="page-container">
          {apiMessage && (
            <div
              className={`toast-bar ${
                liveMachines ? 'success' : adminToken.trim() ? 'warning' : 'info'
              }`}
            >
              <span>{apiMessage}</span>
            </div>
          )}

          {page === 'overview' && (
            <OverviewView
              onNavigate={navigate}
              rows={liveMachines}
              tasks={liveTasks}
              upgrades={liveUpgrades}
              query={search}
            />
          )}

          {page === 'machines' && (
            <MachinesView
              rows={liveMachines}
              token={adminToken}
              onEnroll={() => navigate('enrollment')}
              query={search}
            />
          )}

          {page === 'projects' && (
            <ProjectsView
              rows={liveProjects}
              machines={liveMachines ?? []}
              token={adminToken}
              onRefresh={() => void refresh()}
              query={search}
            />
          )}

          {page === 'tasks' && (
            <TasksView
              rows={liveTasks}
              machines={liveMachines ?? []}
              projects={liveProjects ?? []}
              adminToken={adminToken}
              onRefresh={() => void refresh()}
              query={search}
            />
          )}

          {page === 'artifacts' && (
            <ArtifactsView
              token={adminToken}
              machines={liveMachines ?? []}
              query={search}
            />
          )}

          {page === 'audit' && (
            <AuditView
              rows={liveAudit}
              machines={liveMachines ?? []}
              token={adminToken}
              onRefresh={() => void refresh()}
              query={search}
            />
          )}

          {page === 'enrollment' && (
            <EnrollmentView
              adminToken={adminToken}
              releases={liveReleases}
            />
          )}

          {page === 'access' && (
            <AccessControlView
              token={adminToken}
              machines={liveMachines ?? []}
              projects={liveProjects ?? []}
            />
          )}

          {page === 'upgrades' && (
            <UpgradesView
              token={adminToken}
              rows={liveUpgrades}
              releases={liveReleases}
              machines={liveMachines ?? []}
              onRefresh={() => void refresh()}
              onRefreshReleases={() => void refresh(adminToken, true)}
              query={search}
            />
          )}

          {page === 'settings' && (
            <SettingsView
              token={adminToken}
              machines={liveMachines ?? []}
              onTokenChange={setAdminToken}
              onRefresh={() => void refresh()}
            />
          )}
        </div>
      </main>
    </div>
  )
}
