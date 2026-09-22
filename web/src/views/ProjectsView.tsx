import { useState } from 'react'
import { FolderGitIcon, PlusIcon, TrashIcon } from '../icons/Icons'
import { CopyButton } from '../components/CopyButton'
import { EmptyState } from '../components/EmptyState'
import {
  createProjectWorktree,
  registerProject,
  removeProject,
  removeProjectWorktree,
  runProjectGit,
  type Machine,
  type Project,
} from '../api'

interface ProjectsViewProps {
  rows: Project[] | null
  machines: Machine[]
  token: string
  onRefresh: () => void
  query: string
}

export function ProjectsView({ rows, machines, token, onRefresh, query }: ProjectsViewProps) {
  const [showCreate, setShowCreate] = useState(false)
  const [machineId, setMachineId] = useState(machines[0]?.id ?? '')
  const [name, setName] = useState('')
  const [rootPath, setRootPath] = useState('')
  const [repositoryPath, setRepositoryPath] = useState('')
  const [defaultRef, setDefaultRef] = useState('main')
  const [submitting, setSubmitting] = useState(false)
  const [actionMessage, setActionMessage] = useState('')

  const handleRegister = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!token.trim()) {
      setActionMessage('未配置 Admin Token，无法注册项目')
      return
    }
    setSubmitting(true)
    setActionMessage('')
    try {
      await registerProject(token, {
        machine_id: machineId,
        name: name.trim(),
        root_path: rootPath.trim(),
        repository_path: repositoryPath.trim(),
        default_ref: defaultRef.trim() || 'main',
      })
      setName('')
      setRootPath('')
      setRepositoryPath('')
      setShowCreate(false)
      onRefresh()
      setActionMessage('项目注册成功')
    } catch (err) {
      setActionMessage(err instanceof Error ? err.message : '注册失败')
    } finally {
      setSubmitting(false)
    }
  }

  const filteredProjects = (rows ?? []).filter((p) => {
    if (!query.trim()) return true
    const q = query.toLowerCase()
    return (
      p.name.toLowerCase().includes(q) ||
      p.rootPath.toLowerCase().includes(q) ||
      p.repositoryPath.toLowerCase().includes(q) ||
      p.machineId.toLowerCase().includes(q)
    )
  })

  return (
    <div>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '20px', flexWrap: 'wrap', gap: '14px' }}>
        <div>
          <h2 style={{ margin: 0, fontSize: '20px', fontWeight: 800, color: '#fff', letterSpacing: '-0.02em' }}>
            代码项目与 Worktree
          </h2>
          <p style={{ margin: '4px 0 0', fontSize: '13px', color: 'var(--text-secondary)' }}>
            关联宿主机上的 Git 仓库与多分支工作区，支持远程触发分支同步与工作树隔离。
          </p>
        </div>

        <button
          type="button"
          className="btn btn-primary btn-sm"
          onClick={() => setShowCreate(!showCreate)}
        >
          <PlusIcon size={14} />
          <span>{showCreate ? '取消创建' : '注册新项目'}</span>
        </button>
      </div>

      {actionMessage && (
        <div className={`toast-bar ${actionMessage.includes('成功') ? 'success' : 'warning'}`}>
          {actionMessage}
        </div>
      )}

      {/* Register Project Drawer/Form */}
      {showCreate && (
        <div className="card" style={{ padding: '24px', marginBottom: '24px', background: 'var(--bg-elevated)' }}>
          <h3 style={{ margin: '0 0 16px', fontSize: '16px', fontWeight: 700, color: '#fff' }}>
            注册宿主机 Git 仓库
          </h3>
          <form onSubmit={handleRegister}>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: '16px' }}>
              <div className="form-group">
                <label className="form-label">挂载目标机器</label>
                <select
                  className="form-select"
                  value={machineId}
                  onChange={(e) => setMachineId(e.target.value)}
                  required
                >
                  <option value="" disabled>请选择机器</option>
                  {machines.map((m) => (
                    <option key={m.id} value={m.id}>
                      {m.name} ({m.hostId})
                    </option>
                  ))}
                </select>
              </div>

              <div className="form-group">
                <label className="form-label">项目识别名</label>
                <input
                  type="text"
                  className="form-input"
                  placeholder="例如: remote-connect-mcp"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  required
                />
              </div>

              <div className="form-group">
                <label className="form-label">项目根路径 (root_path)</label>
                <input
                  type="text"
                  className="form-input"
                  placeholder="例如: /home/user/work/my-project"
                  value={rootPath}
                  onChange={(e) => setRootPath(e.target.value)}
                  required
                />
              </div>

              <div className="form-group">
                <label className="form-label">Git 仓库路径 (repository_path)</label>
                <input
                  type="text"
                  className="form-input"
                  placeholder="例如: /home/user/work/my-project/.git"
                  value={repositoryPath}
                  onChange={(e) => setRepositoryPath(e.target.value)}
                  required
                />
              </div>

              <div className="form-group">
                <label className="form-label">默认分支 (default_ref)</label>
                <input
                  type="text"
                  className="form-input"
                  placeholder="main"
                  value={defaultRef}
                  onChange={(e) => setDefaultRef(e.target.value)}
                />
              </div>
            </div>

            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '10px', marginTop: '16px' }}>
              <button
                type="button"
                className="btn btn-secondary btn-sm"
                onClick={() => setShowCreate(false)}
              >
                取消
              </button>
              <button
                type="submit"
                className="btn btn-primary btn-sm"
                disabled={submitting}
              >
                {submitting ? '提交注册中...' : '立即注册'}
              </button>
            </div>
          </form>
        </div>
      )}

      {/* Projects Grid */}
      {filteredProjects.length > 0 ? (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(min(100%, 320px), 1fr))', gap: '20px' }}>
          {filteredProjects.map((project) => (
            <ProjectCard
              key={project.id}
              project={project}
              token={token}
              onRefresh={onRefresh}
            />
          ))}
        </div>
      ) : (
        <EmptyState
          title="尚未登记 Git 项目"
          description="注册宿主机上的代码仓库后，可通过控制台直接管理多 Worktree 及分支操作"
          action={{ label: '注册第一个项目', onClick: () => setShowCreate(true) }}
        />
      )}
    </div>
  )
}

function ProjectCard({
  project,
  token,
  onRefresh,
}: {
  project: Project
  token: string
  onRefresh: () => void
}) {
  const [subCommand, setSubCommand] = useState<string>('fetch')
  const [branchRef, setBranchRef] = useState('')
  const [gitOutput, setGitOutput] = useState('')
  const [runningGit, setRunningGit] = useState(false)
  const [newWorktreePath, setNewWorktreePath] = useState('')
  const [newWorktreeRef, setNewWorktreeRef] = useState('')
  const [addingWorktree, setAddingWorktree] = useState(false)

  const handleRunGit = async () => {
    if (!token.trim()) return
    setRunningGit(true)
    setGitOutput('')
    try {
      const task = await runProjectGit(token, project.id, subCommand, {
        ref: branchRef || undefined,
        branch: branchRef || undefined,
      })
      setGitOutput(`已创建异步 Git 任务 [${task.id}] · 状态: ${task.status}`)
      onRefresh()
    } catch (err) {
      setGitOutput(`执行出错: ${err instanceof Error ? err.message : String(err)}`)
    } finally {
      setRunningGit(false)
    }
  }

  const handleCreateWorktree = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!token.trim() || !newWorktreePath.trim()) return
    setAddingWorktree(true)
    try {
      await createProjectWorktree(token, project.id, {
        path: newWorktreePath.trim(),
        ref: newWorktreeRef.trim() || undefined,
      })
      setNewWorktreePath('')
      setNewWorktreeRef('')
      onRefresh()
    } catch (err) {
      alert(`创建 Worktree 失败: ${err instanceof Error ? err.message : String(err)}`)
    } finally {
      setAddingWorktree(false)
    }
  }

  const handleRemoveProject = async () => {
    if (!window.confirm(`确认解除项目 [${project.name}] 的关联吗？不会删除磁盘代码。`)) return
    try {
      await removeProject(token, project.id)
      onRefresh()
    } catch (err) {
      alert(`删除失败: ${err instanceof Error ? err.message : String(err)}`)
    }
  }

  const handleRemoveWorktree = async (wtId: string, path: string) => {
    if (!window.confirm(`确认注销 Worktree [${path}] 吗？`)) return
    try {
      await removeProjectWorktree(token, project.id, wtId)
      onRefresh()
    } catch (err) {
      alert(`删除 Worktree 失败: ${err instanceof Error ? err.message : String(err)}`)
    }
  }

  return (
    <div className="card" style={{ padding: '22px' }}>
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: '14px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <div
            style={{
              width: '36px',
              height: '36px',
              borderRadius: 'var(--radius-md)',
              background: 'rgba(99, 102, 241, 0.12)',
              border: '1px solid rgba(99, 102, 241, 0.25)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              color: 'var(--accent-primary)',
            }}
          >
            <FolderGitIcon size={18} />
          </div>
          <div>
            <h3 style={{ margin: 0, fontSize: '15px', fontWeight: 700, color: '#fff' }}>{project.name}</h3>
            <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>主分支: {project.defaultRef}</span>
          </div>
        </div>

        <button
          type="button"
          className="btn btn-ghost btn-sm"
          onClick={handleRemoveProject}
          title="移除项目关联"
          style={{ color: 'var(--accent-rose)' }}
        >
          <TrashIcon size={14} />
        </button>
      </div>

      <div style={{ background: 'var(--bg-subtle)', padding: '12px', borderRadius: 'var(--radius-md)', border: '1px solid var(--border-subtle)', marginBottom: '16px' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '11px', color: 'var(--text-tertiary)', marginBottom: '4px' }}>
          <span>仓库路径 (repositoryPath)</span>
          <CopyButton text={project.repositoryPath} label="" size="sm" />
        </div>
        <div className="font-mono" style={{ fontSize: '12px', color: '#e2e8f0', wordBreak: 'break-all' }}>
          {project.repositoryPath}
        </div>
      </div>

      {/* Git Actions Section */}
      <div style={{ marginBottom: '16px' }}>
        <div style={{ fontSize: '12px', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: '8px' }}>
          Git 操作触发器
        </div>
        <div style={{ display: 'flex', gap: '8px' }}>
          <select
            className="form-select"
            style={{ width: '130px', padding: '6px 10px', fontSize: '12px' }}
            value={subCommand}
            onChange={(e) => setSubCommand(e.target.value)}
          >
            <option value="fetch">git fetch</option>
            <option value="checkout">checkout</option>
            <option value="branch">branch</option>
            <option value="worktree_list">wt list</option>
          </select>

          {subCommand === 'checkout' || subCommand === 'branch' ? (
            <input
              type="text"
              className="form-input"
              style={{ flex: 1, padding: '6px 10px', fontSize: '12px' }}
              placeholder="目标分支或 Tag"
              value={branchRef}
              onChange={(e) => setBranchRef(e.target.value)}
            />
          ) : null}

          <button
            type="button"
            className="btn btn-secondary btn-sm"
            onClick={handleRunGit}
            disabled={runningGit}
          >
            {runningGit ? '执行中...' : '运行'}
          </button>
        </div>

        {gitOutput && (
          <pre
            className="font-mono"
            style={{
              marginTop: '10px',
              padding: '10px',
              borderRadius: 'var(--radius-sm)',
              background: '#07090e',
              border: '1px solid var(--border-subtle)',
              fontSize: '11px',
              color: '#94a3b8',
              maxHeight: '120px',
              overflowY: 'auto',
              whiteSpace: 'pre-wrap',
            }}
          >
            {gitOutput}
          </pre>
        )}
      </div>

      {/* Worktrees Section */}
      <div>
        <div style={{ fontSize: '12px', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: '8px' }}>
          活动 Worktree ({project.worktrees?.length ?? 0})
        </div>

        {project.worktrees && project.worktrees.length > 0 ? (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '6px', marginBottom: '12px' }}>
            {project.worktrees.map((wt) => (
              <div
                key={wt.id}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  padding: '8px 10px',
                  borderRadius: 'var(--radius-sm)',
                  background: 'rgba(255, 255, 255, 0.02)',
                  border: '1px solid var(--border-subtle)',
                  fontSize: '12px',
                }}
              >
                <div style={{ minWidth: 0, flex: 1 }}>
                  <div style={{ color: '#fff', fontWeight: 500, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {wt.path}
                  </div>
                  {wt.ref && (
                    <span className="tag-badge" style={{ marginTop: '3px' }}>
                      {wt.ref}
                    </span>
                  )}
                </div>
                <button
                  type="button"
                  className="btn btn-ghost btn-sm"
                  style={{ color: 'var(--accent-rose)', padding: '4px' }}
                  onClick={() => handleRemoveWorktree(wt.id, wt.path)}
                  title="注销此 Worktree"
                >
                  <TrashIcon size={13} />
                </button>
              </div>
            ))}
          </div>
        ) : (
          <div style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '10px' }}>
            暂未建立独立工作树
          </div>
        )}

        {/* Add Worktree Inline Form */}
        <form onSubmit={handleCreateWorktree} style={{ display: 'flex', gap: '6px' }}>
          <input
            type="text"
            className="form-input"
            style={{ flex: 1, padding: '6px 9px', fontSize: '11px' }}
            placeholder="Worktree 目录路径"
            value={newWorktreePath}
            onChange={(e) => setNewWorktreePath(e.target.value)}
            required
          />
          <input
            type="text"
            className="form-input"
            style={{ width: '100px', padding: '6px 9px', fontSize: '11px' }}
            placeholder="ref(可选)"
            value={newWorktreeRef}
            onChange={(e) => setNewWorktreeRef(e.target.value)}
          />
          <button
            type="submit"
            className="btn btn-secondary btn-sm"
            disabled={addingWorktree}
          >
            {addingWorktree ? '创建中...' : '新建'}
          </button>
        </form>
      </div>
    </div>
  )
}
