import { useEffect, useState } from 'react'
import {
  AlertCircleIcon,
  ChevronDownIcon,
  ChevronRightIcon,
  FileCodeIcon,
  PlusIcon,
  TerminalIcon,
} from '../icons/Icons'
import { StatusBadge, StatusTone } from '../components/StatusBadge'
import { CopyButton } from '../components/CopyButton'
import { TerminalOutput } from '../components/TerminalOutput'
import { EmptyState } from '../components/EmptyState'
import {
  cancelTask,
  createTask,
  listTasksPage,
  readTaskArtifact,
  readTaskOutput,
  type Machine,
  type Project,
  type Task,
} from '../api'
import { usePagedTail } from '../utils'

interface TasksViewProps {
  rows: Task[] | null
  machines: Machine[]
  projects: Project[]
  adminToken: string
  onRefresh: () => void
  query: string
}

export function TasksView({
  rows,
  machines,
  projects,
  adminToken,
  onRefresh,
  query,
}: TasksViewProps) {
  const [showComposer, setShowComposer] = useState(false)
  const [statusFilter, setStatusFilter] = useState<string>('all')
  const paged = usePagedTail(rows, 100, (offset, limit) => listTasksPage(adminToken, offset, limit))

  const taskList = paged.rows ?? []
  const filteredTasks = taskList
    .filter((task) => {
      if (statusFilter === 'all') return true
      if (statusFilter === 'active') {
        return ['queued', 'dispatching', 'running', 'cancel_requested'].includes(task.status)
      }
      return task.status === statusFilter
    })
    .filter((task) => {
      if (!query.trim()) return true
      const q = query.toLowerCase()
      return (
        task.id.toLowerCase().includes(q) ||
        task.command?.toLowerCase().includes(q) ||
        task.machineId.toLowerCase().includes(q) ||
        task.kind.toLowerCase().includes(q)
      )
    })

  return (
    <div>
      {/* Top Controls */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '20px', flexWrap: 'wrap', gap: '14px' }}>
        <div>
          <h2 style={{ margin: 0, fontSize: '20px', fontWeight: 800, color: '#fff', letterSpacing: '-0.02em' }}>
            任务记录与编排调度
          </h2>
          <p style={{ margin: '4px 0 0', fontSize: '13px', color: 'var(--text-secondary)' }}>
            下发异步任务至 Agent 节点，追踪实时进度、有界控制台输出及下载生成工件。
          </p>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '12px', flexWrap: 'wrap' }}>
          <div className="segmented-group">
            <button
              type="button"
              className={`segmented-btn ${statusFilter === 'all' ? 'active' : ''}`}
              onClick={() => setStatusFilter('all')}
            >
              <span>全部</span>
              <b>{taskList.length}</b>
            </button>
            <button
              type="button"
              className={`segmented-btn ${statusFilter === 'active' ? 'active' : ''}`}
              onClick={() => setStatusFilter('active')}
            >
              <span>运行中</span>
              <b style={{ color: 'var(--accent-sky)' }}>
                {taskList.filter((t) => ['queued', 'dispatching', 'running', 'cancel_requested'].includes(t.status)).length}
              </b>
            </button>
            <button
              type="button"
              className={`segmented-btn ${statusFilter === 'completed' || statusFilter === 'succeeded' ? 'active' : ''}`}
              onClick={() => setStatusFilter('completed')}
            >
              <span>成功</span>
              <b style={{ color: 'var(--accent-emerald)' }}>
                {taskList.filter((t) => t.status === 'completed' || t.status === 'succeeded').length}
              </b>
            </button>
            <button
              type="button"
              className={`segmented-btn ${statusFilter === 'failed' ? 'active' : ''}`}
              onClick={() => setStatusFilter('failed')}
            >
              <span>失败</span>
              <b style={{ color: 'var(--accent-rose)' }}>
                {taskList.filter((t) => t.status === 'failed').length}
              </b>
            </button>
          </div>

          <button
            type="button"
            className="btn btn-primary btn-sm"
            onClick={() => setShowComposer(!showComposer)}
          >
            <PlusIcon size={14} />
            <span>{showComposer ? '关闭表单' : '创建后台任务'}</span>
          </button>
        </div>
      </div>

      {/* Task Composer Form */}
      {showComposer && (
        <TaskComposer
          machines={machines}
          projects={projects}
          token={adminToken}
          onCreated={() => {
            setShowComposer(false)
            onRefresh()
          }}
        />
      )}

      {/* Task Cards List */}
      {filteredTasks.length > 0 ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
          {filteredTasks.map((task) => (
            <TaskItem
              key={task.id}
              task={task}
              token={adminToken}
              onRefresh={onRefresh}
            />
          ))}
        </div>
      ) : (
        <EmptyState
          title="暂无匹配的任务记录"
          description="任务一旦下发，这里将实时追踪执行进度、耗时与控制台输出"
          action={{ label: '新建执行任务', onClick: () => setShowComposer(true) }}
        />
      )}

      {/* Pagination */}
      {(paged.hasMore || paged.loadingMore || paged.loadError) && (
        <div className="pagination-bar">
          {paged.loadError && (
            <div className="pagination-error">
              <AlertCircleIcon size={14} />
              <span>{paged.loadError}</span>
            </div>
          )}
          {paged.hasMore && (
            <button
              type="button"
              className="btn btn-secondary"
              onClick={paged.loadMore}
              disabled={paged.loadingMore}
            >
              {paged.loadingMore ? '正在加载历史记录...' : '加载更多任务'}
            </button>
          )}
        </div>
      )}
    </div>
  )
}

function TaskComposer({
  machines,
  projects,
  token,
  onCreated,
}: {
  machines: Machine[]
  projects: Project[]
  token: string
  onCreated: () => void
}) {
  const [kind, setKind] = useState<'command' | 'desktop' | 'browser'>('command')
  const eligibleMachines = machines.filter((m) => m.online && m.capabilities.includes(kind))
  const [machineId, setMachineId] = useState(eligibleMachines[0]?.id ?? '')
  const selectedMachineIsEligible = eligibleMachines.some((machine) => machine.id === machineId)

  useEffect(() => {
    if (!selectedMachineIsEligible) {
      setMachineId(eligibleMachines[0]?.id ?? '')
    }
  }, [machineId, selectedMachineIsEligible, eligibleMachines])

  const [command, setCommand] = useState('')
  const [cwd, setCwd] = useState('')
  const [timeout, setTimeoutValue] = useState('120')
  const [projectId, setProjectId] = useState('')
  const [worktreeId, setWorktreeId] = useState('')
  const [scopeMode, setScopeMode] = useState('auto')
  const [scopeRoot, setScopeRoot] = useState('')
  const [workspacePolicy, setWorkspacePolicy] = useState('auto')
  const [laneMode, setLaneMode] = useState('auto')
  const [risk, setRisk] = useState('low')
  const [sessionId, setSessionId] = useState('')
  const [elevation, setElevation] = useState(false)
  const [idempotencyKey, setIdempotencyKey] = useState(`console-task-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`)

  // Desktop specific parameters
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

  const selectedMachine = machines.find((m) => m.id === machineId)
  const projectOptions = projects.filter((project) => project.machineId === machineId)
  const selectedProject = projectOptions.find((p) => p.id === projectId)
  const effectiveScope = worktreeId ? 'worktree' : projectId ? 'project' : (scopeMode === 'auto' ? (selectedMachine?.scopeMode || 'workspace') : scopeMode)

  useEffect(() => {
    // Reset scope options when machine changes
    setScopeMode('auto')
    setScopeRoot('')
    setWorkspacePolicy('auto')
    setLaneMode('auto')
    setProjectId('')
    setWorktreeId('')
  }, [machineId])

  // Reactive constraint: isolated workspace policy requires worktreeId
  useEffect(() => {
    if (workspacePolicy === 'isolated' && !worktreeId) {
      setWorkspacePolicy('auto')
    }
  }, [workspacePolicy, worktreeId])

  // Reactive constraint: host workspace policy requires effectiveScope === 'unrestricted'
  useEffect(() => {
    if (workspacePolicy === 'host' && effectiveScope !== 'unrestricted') {
      setWorkspacePolicy('auto')
    }
  }, [workspacePolicy, effectiveScope])

  const parseNumber = (val: string) => (val !== '' && !isNaN(Number(val)) ? Number(val) : undefined)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!token.trim()) {
      setMessage('请在系统设置中配置 Admin Token')
      return
    }
    if (!machineId || !selectedMachineIsEligible) {
      setMessage('请选择一个在线且具备该能力的 Agent')
      return
    }
    if (!command.trim() && kind !== 'desktop') {
      setMessage('请填写要下发的命令或请求内容')
      return
    }
    if (kind === 'desktop') {
      if (desktopOperation === 'launch' && !desktopExecutable.trim()) {
        setMessage('启动桌面应用需要填写可执行文件路径')
        return
      }
      if (desktopOperation === 'key' && !desktopText.trim()) {
        setMessage('模拟按键操作需要填写键位名称（如 Return, Tab, BackSpace, Control_L+c 等）')
        return
      }
      if (desktopOperation === 'type' && !desktopText) {
        setMessage('文本输入操作需要填写输入文本')
        return
      }
      if (['click', 'double_click', 'right_click', 'move'].includes(desktopOperation) && (desktopX === '' || desktopY === '')) {
        setMessage(`${desktopOperation} 需要填写 X 和 Y 坐标`)
        return
      }
      if (['drag', 'screenshot_region'].includes(desktopOperation) && (desktopX === '' || desktopY === '' || desktopX2 === '' || desktopY2 === '')) {
        setMessage(`${desktopOperation} 需要填写起始 (X, Y) 与目标 (X2, Y2) 坐标`)
        return
      }
      if (desktopOperation === 'focus' && !desktopWindowTitle.trim()) {
        setMessage('激活置顶窗口需要填写窗口匹配标题')
        return
      }
    }
    if (!projectId && !worktreeId && scopeMode === 'auto' && effectiveScope === 'unrestricted') {
      setMessage('该 Agent 仅提供 unrestricted 范围；请在“执行范围”中选择“Unrestricted (显式完全控制)”后再提交')
      return
    }
    if (workspacePolicy === 'host' && effectiveScope !== 'unrestricted') {
      setMessage('host 工作区策略必须同时选择 Unrestricted（显式）范围')
      return
    }
    if (workspacePolicy === 'isolated' && !worktreeId) {
      setMessage('isolated 工作区策略需要先选择项目 Worktree')
      return
    }
    if (effectiveScope === 'path' && !scopeRoot.trim()) {
      setMessage('path 范围需要填写绝对 scope root 路径')
      return
    }

    setSubmitting(true)
    setMessage('')
    try {
      const taskCommand = kind === 'browser' ? command.trim() || '{"operation":"snapshot"}' : command.trim()
      const isKeyOp = desktopOperation === 'key'
      const isTypeOp = ['type', 'clipboard_write'].includes(desktopOperation)

      const basePayload: Record<string, unknown> = {
        machine_id: machineId,
        kind,
        required_capability: kind,
        command: kind === 'command' ? command.trim() : taskCommand,
        cwd: cwd.trim() || undefined,
        env: {},
        timeout_seconds: Number(timeout) || (kind === 'browser' ? 300 : 0),
        project_id: projectId || undefined,
        worktree_id: worktreeId || undefined,
        scope_mode: effectiveScope === 'auto' ? undefined : effectiveScope,
        scope_root: effectiveScope === 'path' ? scopeRoot.trim() || undefined : undefined,
        workspace_policy: workspacePolicy === 'auto' ? undefined : workspacePolicy,
        lane_mode: laneMode === 'auto' ? undefined : laneMode,
        risk,
        session_id: sessionId.trim() || undefined,
        elevation_required: elevation || undefined,
        idempotency_key: idempotencyKey.trim() || undefined,
      }

      if (kind === 'desktop') {
        basePayload.desktop = {
          operation: desktopOperation,
          executable: desktopOperation === 'launch' ? desktopExecutable.trim() || undefined : undefined,
          args: desktopOperation === 'launch' && desktopArgs ? desktopArgs.split('\n').map((item) => item.trim()).filter(Boolean) : [],
          cwd: cwd.trim() || undefined,
          text: isTypeOp ? desktopText || undefined : undefined,
          key: isKeyOp ? desktopText.trim() || undefined : undefined,
          x: parseNumber(desktopX),
          y: parseNumber(desktopY),
          x2: parseNumber(desktopX2),
          y2: parseNumber(desktopY2),
          screen: parseNumber(desktopScreen),
          window_title: desktopWindowTitle.trim() || undefined,
        }
      } else {
        basePayload.desktop = null
      }

      const task = await createTask(token, basePayload)
      setMessage(`已创建任务 ${task.id} (${kind})，已在 Agent 后台异步执行`)
      setCommand('')
      setIdempotencyKey(`console-task-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`)
      onCreated()
    } catch (err) {
      setMessage(err instanceof Error ? err.message : '创建任务失败')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="card" style={{ padding: '24px', marginBottom: '24px', background: 'var(--bg-elevated)' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '16px' }}>
        <div>
          <h3 style={{ margin: 0, fontSize: '16px', fontWeight: 700, color: '#fff' }}>
            创建后台异步任务 (Async Dispatch)
          </h3>
          <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
            提交后写入 Center 队列，Agent 将按项目、Worktree 或指定路径执行并回传有界输出与工件
          </span>
        </div>
      </div>

      {message && (
        <div className={`toast-bar ${message.includes('失败') ? 'error' : 'success'}`}>
          <AlertCircleIcon size={15} />
          <span>{message}</span>
        </div>
      )}

      <form onSubmit={handleSubmit}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 260px), 1fr))', gap: '16px', marginBottom: '16px' }}>
          <div className="form-group">
            <label className="form-label">任务类型</label>
            <select
              className="form-select"
              value={kind}
              onChange={(e) => {
                setKind(e.target.value as any)
                setCommand('')
              }}
            >
              <option value="command">Command · 命令行终端</option>
              <option value="desktop">Desktop · 桌面控制/会话</option>
              <option value="browser">Browser · 本地浏览器适配器</option>
            </select>
          </div>

          <div className="form-group">
            <label className="form-label">目标 Agent</label>
            <select
              className="form-select"
              value={machineId}
              onChange={(e) => {
                setMachineId(e.target.value)
                setProjectId('')
                setWorktreeId('')
              }}
              required
            >
              <option value="">{eligibleMachines.length > 0 ? '选择在线 Agent 节点' : '暂无可用 Agent 节点'}</option>
              {eligibleMachines.map((m) => (
                <option key={m.id} value={m.id}>
                  {m.name} · {m.os ?? 'unknown'} · 在线
                </option>
              ))}
            </select>
          </div>

          <div className="form-group">
            <label className="form-label">代码项目 (可选)</label>
            <select
              className="form-select"
              value={projectId}
              onChange={(e) => {
                setProjectId(e.target.value)
                setWorktreeId('')
              }}
              disabled={!machineId}
            >
              <option value="">Agent 默认目录</option>
              {projectOptions.map((p) => (
                <option key={p.id} value={p.id}>{p.name}</option>
              ))}
            </select>
          </div>

          <div className="form-group">
            <label className="form-label">Worktree (可选)</label>
            <select
              className="form-select"
              value={worktreeId}
              onChange={(e) => setWorktreeId(e.target.value)}
              disabled={!projectId}
            >
              <option value="">项目默认目录</option>
              {selectedProject?.worktrees?.filter((w) => w.status === 'ready').map((w) => (
                <option key={w.id} value={w.id}>{w.ref} ({w.path})</option>
              ))}
            </select>
          </div>

          <div className="form-group">
            <label className="form-label">执行范围</label>
            <select
              className="form-select"
              value={scopeMode}
              onChange={(e) => setScopeMode(e.target.value)}
              disabled={Boolean(projectId)}
            >
              <option value="auto">Agent 默认范围</option>
              <option value="workspace">Workspace</option>
              <option value="path">Path</option>
              <option value="unrestricted">Unrestricted (显式完全控制)</option>
            </select>
          </div>

          <div className="form-group">
            <label className="form-label">工作区隔离策略</label>
            <select
              className="form-select"
              value={workspacePolicy}
              onChange={(e) => setWorkspacePolicy(e.target.value)}
            >
              <option value="auto">自动 (Auto)</option>
              <option value="isolated" disabled={!worktreeId}>
                隔离 Worktree {!worktreeId ? '(需先选择 Worktree)' : ''}
              </option>
              <option value="shared_serial">共享并串行</option>
              <option value="host" disabled={effectiveScope !== 'unrestricted'}>
                整机 Host {effectiveScope !== 'unrestricted' ? '(需选择 Unrestricted 范围)' : ''}
              </option>
            </select>
          </div>

          <div className="form-group">
            <label className="form-label">执行车道 (Lane Mode)</label>
            <select
              className="form-select"
              value={laneMode}
              onChange={(e) => setLaneMode(e.target.value)}
            >
              <option value="auto">自动</option>
              <option value="read">READ · 只读</option>
              <option value="write">WRITE · 写入</option>
              <option value="exclusive">EXCLUSIVE · 独占排他</option>
            </select>
          </div>

          <div className="form-group">
            <label className="form-label">风险评级</label>
            <select
              className="form-select"
              value={risk}
              onChange={(e) => setRisk(e.target.value)}
            >
              <option value="low">低风险 (常规操作)</option>
              <option value="high">高风险 (文件修改)</option>
              <option value="critical">严重风险 (破坏性命令)</option>
            </select>
          </div>

          <div className="form-group">
            <label className="form-label">超时时限 (秒)</label>
            <input
              type="number"
              className="form-input"
              value={timeout}
              onChange={(e) => setTimeoutValue(e.target.value)}
              min={1}
            />
          </div>

          <div className="form-group">
            <label className="form-label">复用会话 ID (可选)</label>
            <input
              type="text"
              className="form-input font-mono"
              placeholder="桌面/浏览器复用上下文"
              value={sessionId}
              onChange={(e) => setSessionId(e.target.value)}
            />
          </div>
        </div>

        <div className="form-group">
          <label style={{ display: 'inline-flex', alignItems: 'center', gap: '8px', cursor: 'pointer', fontSize: '13px', color: '#fff' }}>
            <input
              type="checkbox"
              checked={elevation}
              onChange={(e) => setElevation(e.target.checked)}
              style={{ accentColor: 'var(--accent-primary)' }}
            />
            <span>显式请求系统提权执行 (elevation_required)</span>
          </label>
        </div>

        <div className="form-group">
          <label className="form-label">相对工作目录 (CWD · 可选)</label>
          <input
            type="text"
            className="form-input font-mono"
            placeholder="项目/Worktree 内的相对子目录；留空使用根目录"
            value={cwd}
            onChange={(e) => setCwd(e.target.value)}
          />
        </div>

        {(effectiveScope === 'path' || scopeMode === 'path') && (
          <div className="form-group">
            <label className="form-label" style={{ display: 'flex', justifyContent: 'space-between' }}>
              <span>Scope Root 绝对路径</span>
              <span style={{ color: 'var(--accent-rose)', fontSize: '11px' }}>* 必填</span>
            </label>
            <input
              type="text"
              className="form-input font-mono"
              placeholder="例如 /var/data/workspace 或 C:\workspace"
              value={scopeRoot}
              onChange={(e) => setScopeRoot(e.target.value)}
              required
            />
          </div>
        )}

        {/* Desktop Controls */}
        {kind === 'desktop' ? (
          <div style={{ padding: '16px', borderRadius: 'var(--radius-md)', background: 'var(--bg-subtle)', border: '1px solid var(--border-subtle)', marginBottom: '16px' }}>
            <div className="form-group">
              <label className="form-label">桌面操作类型 (Desktop Operation)</label>
              <select
                className="form-select"
                value={desktopOperation}
                onChange={(e) => setDesktopOperation(e.target.value)}
              >
                <option value="screenshot">screenshot · 屏幕截屏</option>
                <option value="screens">screens · 枚举显示器</option>
                <option value="windows">windows · 枚举顶层窗口</option>
                <option value="launch">launch · 启动应用程序</option>
                <option value="click">click · 鼠标单击</option>
                <option value="drag">drag · 鼠标拖拽</option>
                <option value="key">key · 模拟按键</option>
                <option value="type">type · 键盘文本输入</option>
                <option value="clipboard_read">clipboard_read · 读取剪贴板</option>
                <option value="clipboard_write">clipboard_write · 写入剪贴板</option>
                <option value="focus">focus · 激活置顶窗口</option>
              </select>
            </div>

            {desktopOperation === 'launch' && (
              <>
                <div className="form-group">
                  <label className="form-label">可执行文件绝对路径</label>
                  <input
                    type="text"
                    className="form-input font-mono"
                    placeholder="例如: notepad.exe 或 /usr/bin/gedit"
                    value={desktopExecutable}
                    onChange={(e) => setDesktopExecutable(e.target.value)}
                  />
                </div>
                <div className="form-group">
                  <label className="form-label">启动参数（每行一个）</label>
                  <textarea
                    className="form-textarea font-mono"
                    rows={2}
                    value={desktopArgs}
                    onChange={(e) => setDesktopArgs(e.target.value)}
                  />
                </div>
              </>
            )}

            {['type', 'clipboard_write'].includes(desktopOperation) && (
              <div className="form-group">
                <label className="form-label">输入文本内容</label>
                <textarea
                  className="form-textarea"
                  rows={2}
                  value={desktopText}
                  onChange={(e) => setDesktopText(e.target.value)}
                />
              </div>
            )}

            {['click', 'drag'].includes(desktopOperation) && (
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
                <div className="form-group">
                  <label className="form-label">坐标 X</label>
                  <input type="number" className="form-input" value={desktopX} onChange={(e) => setDesktopX(e.target.value)} />
                </div>
                <div className="form-group">
                  <label className="form-label">坐标 Y</label>
                  <input type="number" className="form-input" value={desktopY} onChange={(e) => setDesktopY(e.target.value)} />
                </div>
              </div>
            )}

            {desktopOperation === 'drag' && (
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
                <div className="form-group">
                  <label className="form-label">目标坐标 X2</label>
                  <input type="number" className="form-input" value={desktopX2} onChange={(e) => setDesktopX2(e.target.value)} />
                </div>
                <div className="form-group">
                  <label className="form-label">目标坐标 Y2</label>
                  <input type="number" className="form-input" value={desktopY2} onChange={(e) => setDesktopY2(e.target.value)} />
                </div>
              </div>
            )}

            {desktopOperation === 'key' && (
              <div className="form-group">
                <label className="form-label" style={{ display: 'flex', justifyContent: 'space-between' }}>
                  <span>按键名称或组合 (Key Name)</span>
                  <span style={{ color: 'var(--accent-rose)', fontSize: '11px' }}>* 必填</span>
                </label>
                <input
                  type="text"
                  className="form-input font-mono"
                  placeholder="例如 Return, Tab, BackSpace, Escape, Control_L+c"
                  value={desktopText}
                  onChange={(e) => setDesktopText(e.target.value)}
                  required
                />
              </div>
            )}

            {desktopOperation === 'focus' && (
              <div className="form-group">
                <label className="form-label">窗口匹配标题</label>
                <input
                  type="text"
                  className="form-input"
                  placeholder="例如 Chrome 或 Terminal"
                  value={desktopWindowTitle}
                  onChange={(e) => setDesktopWindowTitle(e.target.value)}
                />
              </div>
            )}
          </div>
        ) : (
          <div className="form-group">
            <label className="form-label">{kind === 'browser' ? 'Browser Adapter 请求 JSON' : '执行 Shell 命令行'}</label>
            <textarea
              className="form-textarea font-mono"
              rows={3}
              placeholder={kind === 'browser' ? '{"operation":"snapshot"}' : '例如：git status --short 或 pnpm test'}
              value={command}
              onChange={(e) => setCommand(e.target.value)}
              required
            />
          </div>
        )}

        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '12px', flexWrap: 'wrap', marginTop: '16px' }}>
          <div className="form-group" style={{ flex: '1 1 240px', margin: 0 }}>
            <label className="form-label">任务幂等键 (Idempotency Key)</label>
            <input
              type="text"
              className="form-input font-mono"
              style={{ fontSize: '11px' }}
              value={idempotencyKey}
              onChange={(e) => setIdempotencyKey(e.target.value)}
            />
          </div>

          <button
            type="submit"
            className="btn btn-primary"
            style={{ height: '38px', alignSelf: 'flex-end' }}
            disabled={submitting || !token || !selectedMachineIsEligible}
          >
            {submitting ? '提交中...' : '立即入队执行'}
          </button>
        </div>
      </form>
    </div>
  )
}

function TaskItem({
  task,
  token,
  onRefresh,
}: {
  task: Task
  token: string
  onRefresh: () => void
}) {
  const [canceling, setCanceling] = useState(false)
  const [expanded, setExpanded] = useState(false)
  const [output, setOutput] = useState('')
  const [cursor, setCursor] = useState(0)
  const [more, setMore] = useState(false)
  const [loadingOutput, setLoadingOutput] = useState(false)
  const [outputError, setOutputError] = useState('')

  // Artifact Preview States
  const [artifactUrl, setArtifactUrl] = useState('')
  const [artifactMime, setArtifactMime] = useState(task.artifactMime ?? '')
  const [artifactSha256, setArtifactSha256] = useState(task.artifactSha256 ?? '')
  const [artifactBytes, setArtifactBytes] = useState(task.artifactBytes)
  const [artifactText, setArtifactText] = useState<string | null>(null)
  const [artifactError, setArtifactError] = useState('')
  const [loadingArtifact, setLoadingArtifact] = useState(false)

  const terminal = ['completed', 'failed', 'canceled', 'succeeded'].includes(task.status)
  const progressPercent = task.progressPercent == null ? null : Math.max(0, Math.min(100, task.progressPercent))

  useEffect(() => () => {
    if (artifactUrl) URL.revokeObjectURL(artifactUrl)
  }, [artifactUrl])

  const loadOutput = async (reset: boolean) => {
    setLoadingOutput(true)
    setOutputError('')
    try {
      const page = await readTaskOutput(token, task.id, reset ? 0 : cursor, 16 * 1024)
      setOutput((prev) => (reset ? page.text : prev + page.text))
      setCursor(page.nextCursor)
      setMore(page.more)
      setExpanded(true)
    } catch (err) {
      setOutputError(err instanceof Error ? err.message : '读取输出失败')
    } finally {
      setLoadingOutput(false)
    }
  }

  const loadArtifact = async () => {
    setLoadingArtifact(true)
    setArtifactError('')
    try {
      const res = await readTaskArtifact(token, task.id)
      const nextUrl = URL.createObjectURL(res.blob)
      const nextMime = res.mimeType || task.artifactMime || 'application/octet-stream'
      setArtifactMime(nextMime)
      setArtifactSha256(res.sha256 || task.artifactSha256 || '')
      setArtifactBytes(res.blob.size)
      setArtifactText(null)
      if (nextMime.toLowerCase().startsWith('text/') && res.blob.size <= 512 * 1024) {
        setArtifactText(await res.blob.text())
      }
      setArtifactUrl((prev) => {
        if (prev) URL.revokeObjectURL(prev)
        return nextUrl
      })
      setExpanded(true)
    } catch (err) {
      setArtifactError(err instanceof Error ? err.message : '读取工件失败')
    } finally {
      setLoadingArtifact(false)
    }
  }

  const cancel = async () => {
    if (!window.confirm('确认中断并取消该任务吗？')) return
    setCanceling(true)
    try {
      await cancelTask(token, task.id)
      onRefresh()
    } finally {
      setCanceling(false)
    }
  }

  const tone: StatusTone =
    task.status === 'completed' || task.status === 'succeeded'
      ? 'good'
      : task.status === 'failed'
      ? 'danger'
      : !terminal
      ? 'info'
      : 'muted'

  const hasArtifact = task.artifactBytes > 0 || Boolean(task.artifactSha256) || Boolean(task.artifactMime)

  return (
    <div className="card" style={{ padding: 0 }}>
      {/* Summary Header */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '16px 20px',
          cursor: 'pointer',
          background: expanded ? 'rgba(255, 255, 255, 0.02)' : 'transparent',
          borderBottom: expanded ? '1px solid var(--border-subtle)' : 'none',
          flexWrap: 'wrap',
          gap: '12px',
        }}
        onClick={() => {
          if (!expanded && !output) void loadOutput(true)
          setExpanded(!expanded)
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px', minWidth: 0 }}>
          <div style={{ color: 'var(--text-tertiary)' }}>
            {expanded ? <ChevronDownIcon size={16} /> : <ChevronRightIcon size={16} />}
          </div>

          <div
            style={{
              width: '32px',
              height: '32px',
              borderRadius: 'var(--radius-sm)',
              background: 'rgba(99, 102, 241, 0.1)',
              border: '1px solid rgba(99, 102, 241, 0.2)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              color: 'var(--accent-sky)',
            }}
          >
            <TerminalIcon size={15} />
          </div>

          <div style={{ minWidth: 0 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
              <span className="font-mono" style={{ color: '#fff', fontWeight: 600 }}>
                {task.id}
              </span>
              <span className="tag-badge">{task.kind}</span>
              <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                {task.machineId} · {task.scopeMode || 'workspace'} · {task.workspacePolicy || 'shared_serial'} · {task.laneMode || 'write'}
              </span>
            </div>

            <div
              className="font-mono"
              style={{
                fontSize: '11px',
                color: 'var(--text-secondary)',
                marginTop: '3px',
                maxWidth: '600px',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
            >
              {task.command || task.kind}
            </div>

            {/* Real Progress Phase Bar */}
            {task.progressPhase && (
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginTop: '6px', fontSize: '11px', color: 'var(--accent-sky)' }}>
                <span>{task.progressPhase}{task.progressMessage ? ` · ${task.progressMessage}` : ''}{task.progressCurrent != null && task.progressTotal != null ? ` · ${task.progressCurrent}/${task.progressTotal}${task.progressUnit ? ` ${task.progressUnit}` : ''}` : ''}</span>
                {progressPercent != null && (
                  <div style={{ display: 'flex', alignItems: 'center', gap: '6px', width: '120px' }}>
                    <div className="progress-rail" style={{ height: '4px' }}>
                      <div className="progress-fill" style={{ width: `${progressPercent}%` }} />
                    </div>
                    <strong style={{ fontSize: '10px' }}>{progressPercent}%</strong>
                  </div>
                )}
              </div>
            )}
          </div>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }} onClick={(e) => e.stopPropagation()}>
          <StatusBadge label={task.status} tone={tone} pulse={!terminal} />

          <span className="font-mono" style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
            第 {task.attempt} 次投递 · {task.outputBytes} B
          </span>

          <div style={{ display: 'flex', gap: '6px' }}>
            {!terminal && (
              <button
                type="button"
                className="btn btn-danger btn-sm"
                onClick={cancel}
                disabled={canceling}
              >
                {canceling ? '取消中...' : '取消'}
              </button>
            )}

            {hasArtifact && (
              <button
                type="button"
                className="btn btn-secondary btn-sm"
                onClick={() => void loadArtifact()}
                disabled={loadingArtifact}
              >
                {loadingArtifact ? '读取中...' : artifactUrl ? '重新读取工件' : '查看工件'}
              </button>
            )}

            <CopyButton text={task.id} label="" size="sm" />
          </div>
        </div>
      </div>

      {/* Expanded Logs & Artifact Section */}
      {expanded && (
        <div style={{ padding: '16px 20px', background: 'var(--bg-subtle)' }}>
          {/* Output log */}
          <TerminalOutput
            title={`task://${task.id}/output.log`}
            content={output}
            error={outputError}
            loading={loadingOutput}
            onRefresh={() => void loadOutput(true)}
            onLoadMore={() => void loadOutput(false)}
            hasMore={more}
          />

          {task.outputTruncated && (
            <div style={{ fontSize: '11px', color: 'var(--accent-amber)', marginTop: '6px' }}>
              ⚠️ 输出已被 Agent 有界截断，超出大小保护限制。
            </div>
          )}

          {/* Artifact Preview Card */}
          {artifactError && (
            <div className="toast-bar error" style={{ marginTop: '12px' }}>
              {artifactError}
            </div>
          )}

          {artifactUrl && (
            <div className="card" style={{ padding: '16px', marginTop: '16px', background: 'var(--bg-elevated)' }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '12px', flexWrap: 'wrap', gap: '8px' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                  <FileCodeIcon size={16} style={{ color: 'var(--accent-sky)' }} />
                  <strong style={{ color: '#fff', fontSize: '13px' }}>任务生成工件 (Artifact)</strong>
                  <span className="tag-badge">{artifactMime}</span>
                  <span className="font-mono" style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                    {artifactBytes} Bytes
                  </span>
                </div>

                <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                  <a
                    href={artifactUrl}
                    download={`${task.id}-artifact`}
                    className="btn btn-secondary btn-sm"
                  >
                    下载工件
                  </a>
                </div>
              </div>

              {artifactSha256 && (
                <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginBottom: '12px' }}>
                  SHA-256: <code className="font-mono" style={{ color: '#fff' }}>{artifactSha256}</code>
                </div>
              )}

              {/* Media Views */}
              <div style={{ borderRadius: 'var(--radius-md)', overflow: 'hidden', background: '#080a0f', border: '1px solid var(--border-subtle)', padding: '10px' }}>
                {artifactMime.toLowerCase().startsWith('image/') && (
                  <img
                    src={artifactUrl}
                    alt={`任务 ${task.id} 工件`}
                    style={{ maxWidth: '100%', maxHeight: '450px', display: 'block', margin: '0 auto', borderRadius: 'var(--radius-sm)' }}
                  />
                )}

                {artifactMime.toLowerCase() === 'application/pdf' && (
                  <iframe
                    src={artifactUrl}
                    title={`任务 ${task.id} PDF`}
                    style={{ width: '100%', height: '480px', border: 0, borderRadius: 'var(--radius-sm)' }}
                  />
                )}

                {artifactMime.toLowerCase().startsWith('audio/') && (
                  <audio controls src={artifactUrl} style={{ width: '100%', marginTop: '8px' }} />
                )}

                {artifactMime.toLowerCase().startsWith('video/') && (
                  <video controls src={artifactUrl} style={{ maxWidth: '100%', maxHeight: '420px', display: 'block', margin: '0 auto' }} />
                )}

                {artifactText !== null && (
                  <pre
                    className="font-mono"
                    style={{
                      padding: '12px',
                      color: '#cbd5e1',
                      fontSize: '11px',
                      lineHeight: '1.6',
                      maxHeight: '300px',
                      overflowY: 'auto',
                      whiteSpace: 'pre-wrap',
                      margin: 0,
                    }}
                  >
                    {artifactText || '(空文本文件)'}
                  </pre>
                )}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
