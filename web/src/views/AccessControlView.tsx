import { useCallback, useEffect, useState } from 'react'
import { CheckCircleIcon, KeyIcon, LockIcon, PlusIcon, RefreshCwIcon, ShieldIcon, TrashIcon } from '../icons/Icons'
import { CopyButton } from '../components/CopyButton'
import { EmptyState } from '../components/EmptyState'
import {
  closeExecutionSession,
  getQuota,
  grantMachine,
  grantProject,
  issueMcpToken,
  listExecutionSessions,
  listMachineGrants,
  listMcpTokens,
  listProjectMembers,
  revokeMachine,
  revokeMcpToken,
  revokeProject,
  type ExecutionSession,
  type Machine,
  type MachineGrant,
  type McpToken,
  type Project,
  type ProjectMember,
  type Quota,
} from '../api'

interface AccessControlViewProps {
  token: string
  machines: Machine[]
  projects: Project[]
}

export function AccessControlView({ token, machines, projects }: AccessControlViewProps) {
  const [principal, setPrincipal] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [expires, setExpires] = useState('86400')
  const [tokens, setTokens] = useState<McpToken[]>([])
  const [machineGrants, setMachineGrants] = useState<MachineGrant[]>([])
  const [projectMembers, setProjectMembers] = useState<ProjectMember[]>([])
  const [sessions, setSessions] = useState<ExecutionSession[]>([])
  const [quota, setQuota] = useState<Quota | null>(null)
  const [machineId, setMachineId] = useState(machines[0]?.id ?? '')
  const [projectId, setProjectId] = useState(projects[0]?.id ?? '')
  const [issued, setIssued] = useState('')
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (machines.length > 0 && (!machineId || !machines.some((m) => m.id === machineId))) {
      setMachineId(machines[0].id)
    }
  }, [machines, machineId])

  useEffect(() => {
    if (projects.length > 0 && (!projectId || !projects.some((p) => p.id === projectId))) {
      setProjectId(projects[0].id)
    }
  }, [projects, projectId])

  const reload = useCallback(async () => {
    if (!token.trim()) return
    setBusy(true)
    setMessage('')
    try {
      const [tokenPage, machineRows, projectRows, sessionRows] = await Promise.all([
        listMcpTokens(token),
        listMachineGrants(token, principal),
        listProjectMembers(token, principal),
        listExecutionSessions(token, principal),
      ])
      setTokens(tokenPage.items)
      setMachineGrants(machineRows)
      setProjectMembers(projectRows)
      setSessions(sessionRows)

      if (principal.trim()) {
        try {
          const q = await getQuota(token, principal.trim())
          setQuota(q)
        } catch {
          setQuota(null)
        }
      } else {
        setQuota(null)
      }
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '读取访问控制数据失败')
    } finally {
      setBusy(false)
    }
  }, [token, principal])

  useEffect(() => {
    void reload()
  }, [reload])

  const issue = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!token.trim()) return
    setBusy(true)
    setMessage('')
    setIssued('')
    try {
      const result = await issueMcpToken(token, {
        principal_id: principal.trim() || undefined,
        display_name: displayName.trim() || undefined,
        expires_in_seconds: Number(expires) || 0,
        scopes: ['mcp:read', 'mcp:execute', 'mcp:project'],
      })
      setPrincipal(result.principalId)
      setIssued(result.token)
      setMessage('Token 只显示一次，请立即复制保存；Center 仅保存摘要哈希。')
      await reload()
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '生成 MCP Token 失败')
    } finally {
      setBusy(false)
    }
  }

  const grantMachineAccess = async () => {
    if (!principal.trim() || !machineId) return
    setBusy(true)
    try {
      await grantMachine(token, {
        principal_id: principal.trim(),
        machine_id: machineId,
        scopes: ['read', 'execute'],
      })
      setMessage(`已为 ${principal} 授予机器读写执行权限 (read, execute)`)
      await reload()
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '授予机器访问失败')
    } finally {
      setBusy(false)
    }
  }

  const grantProjectAccess = async () => {
    if (!principal.trim() || !projectId) return
    setBusy(true)
    try {
      await grantProject(token, {
        principal_id: principal.trim(),
        project_id: projectId,
        scopes: ['read', 'write'],
      })
      setMessage(`已为 ${principal} 授予项目读写权限 (read, write)`)
      await reload()
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '授予项目访问失败')
    } finally {
      setBusy(false)
    }
  }

  const handleRevokeToken = async (tokenId: string) => {
    if (!window.confirm('确认撤销该 MCP Token 吗？撤销后连接将立即断开。')) return
    try {
      await revokeMcpToken(token, tokenId)
      await reload()
    } catch (err) {
      alert(`撤销失败: ${err instanceof Error ? err.message : String(err)}`)
    }
  }

  const handleCloseSession = async (session: ExecutionSession) => {
    if (!window.confirm(`确认终止会话 [${session.sessionId}] 吗？`)) return
    try {
      await closeExecutionSession(token, session.principalId, session.sessionId)
      await reload()
    } catch (err) {
      alert(`关闭会话失败: ${err instanceof Error ? err.message : String(err)}`)
    }
  }

  return (
    <div>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '20px', flexWrap: 'wrap', gap: '14px' }}>
        <div>
          <h2 style={{ margin: 0, fontSize: '20px', fontWeight: 800, color: '#fff', letterSpacing: '-0.02em' }}>
            访问控制与鉴权策略 (RBAC)
          </h2>
          <p style={{ margin: '4px 0 0', fontSize: '13px', color: 'var(--text-secondary)' }}>
            签发 MCP Client 凭证、对机器与项目显式授权 (read/execute)、监控执行会话与安全配额。
          </p>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <button
            type="button"
            className="btn btn-secondary btn-sm"
            onClick={() => void reload()}
            disabled={busy}
          >
            <RefreshCwIcon size={14} style={{ animation: busy ? 'spin 1s linear infinite' : undefined }} />
            <span>刷新授权</span>
          </button>
        </div>
      </div>

      {message && (
        <div className={`toast-bar ${message.includes('失败') ? 'error' : 'success'}`}>
          <CheckCircleIcon size={15} />
          <span>{message}</span>
        </div>
      )}

      {/* Forms Section */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 320px), 1fr))', gap: '24px', marginBottom: '24px' }}>
        {/* Token Form */}
        <div className="card" style={{ padding: '24px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '16px' }}>
            <div
              style={{
                width: '34px',
                height: '34px',
                borderRadius: 'var(--radius-md)',
                background: 'rgba(99, 102, 241, 0.12)',
                border: '1px solid rgba(99, 102, 241, 0.25)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                color: 'var(--accent-primary)',
              }}
            >
              <KeyIcon size={17} />
            </div>
            <div>
              <h3 style={{ margin: 0, fontSize: '15px', fontWeight: 700, color: '#fff' }}>
                签发用户 Token (MCP Principal)
              </h3>
              <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                客户端连接器的唯一主体身份
              </span>
            </div>
          </div>

          <form onSubmit={issue}>
            <div className="form-group">
              <label className="form-label">Principal ID (主体标识)</label>
              <input
                type="text"
                className="form-input font-mono"
                placeholder="例如: user-alice 或 default"
                value={principal}
                onChange={(e) => setPrincipal(e.target.value)}
              />
            </div>

            <div className="form-group">
              <label className="form-label">显示名称 (Display Name)</label>
              <input
                type="text"
                className="form-input"
                placeholder="例如: Alice's Laptop"
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
              />
            </div>

            <div className="form-group">
              <label className="form-label">有效期</label>
              <select
                className="form-select"
                value={expires}
                onChange={(e) => setExpires(e.target.value)}
              >
                <option value="3600">1 小时</option>
                <option value="86400">1 天</option>
                <option value="2592000">30 天</option>
                <option value="0">不过期（仅受撤销控制）</option>
              </select>
            </div>

            <button
              type="submit"
              className="btn btn-primary"
              style={{ width: '100%', marginTop: '10px' }}
              disabled={!token || busy}
            >
              {busy ? '签发中...' : '签发 Token'}
            </button>

            {issued && (
              <div style={{ marginTop: '16px', padding: '12px', borderRadius: 'var(--radius-md)', background: '#07090f', border: '1px solid var(--border-default)' }}>
                <div style={{ fontSize: '11px', color: 'var(--accent-emerald)', marginBottom: '4px', fontWeight: 600 }}>
                  新 Token 仅显示一次，请妥善保存：
                </div>
                <div className="font-mono" style={{ color: '#fff', wordBreak: 'break-all', fontSize: '11px', marginBottom: '8px' }}>
                  {issued}
                </div>
                <CopyButton text={issued} label="复制明文" size="sm" />
              </div>
            )}
          </form>
        </div>

        {/* Grants Form */}
        <div className="card" style={{ padding: '24px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '16px' }}>
            <div
              style={{
                width: '34px',
                height: '34px',
                borderRadius: 'var(--radius-md)',
                background: 'rgba(56, 189, 248, 0.1)',
                border: '1px solid rgba(56, 189, 248, 0.25)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                color: 'var(--accent-sky)',
              }}
            >
              <ShieldIcon size={17} />
            </div>
            <div>
              <h3 style={{ margin: 0, fontSize: '15px', fontWeight: 700, color: '#fff' }}>
                授予资源范围 (Grants)
              </h3>
              <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                为当前 Principal ID 显式开放机器或项目权限
              </span>
            </div>
          </div>

          <div className="form-group">
            <label className="form-label">当前被授权主体</label>
            <input
              type="text"
              className="form-input font-mono"
              value={principal}
              onChange={(e) => setPrincipal(e.target.value)}
              placeholder="请输入或选择已签发的 Principal ID"
            />
          </div>

          <div style={{ padding: '14px', borderRadius: 'var(--radius-md)', background: 'var(--bg-subtle)', border: '1px solid var(--border-subtle)', marginBottom: '16px' }}>
            <label className="form-label">授予机器 read + execute</label>
            <div style={{ display: 'flex', gap: '8px', marginTop: '6px' }}>
              <select
                className="form-select"
                value={machineId}
                onChange={(e) => setMachineId(e.target.value)}
                style={{ flex: 1 }}
              >
                <option value="">选择机器</option>
                {machines.map((m) => (
                  <option key={m.id} value={m.id}>{m.name} ({m.hostId})</option>
                ))}
              </select>
              <button
                type="button"
                className="btn btn-secondary btn-sm"
                onClick={grantMachineAccess}
                disabled={!principal.trim() || !machineId || busy}
              >
                授权机器
              </button>
            </div>
          </div>

          <div style={{ padding: '14px', borderRadius: 'var(--radius-md)', background: 'var(--bg-subtle)', border: '1px solid var(--border-subtle)' }}>
            <label className="form-label">授予项目 read + write</label>
            <div style={{ display: 'flex', gap: '8px', marginTop: '6px' }}>
              <select
                className="form-select"
                value={projectId}
                onChange={(e) => setProjectId(e.target.value)}
                style={{ flex: 1 }}
              >
                <option value="">选择项目</option>
                {projects.map((p) => (
                  <option key={p.id} value={p.id}>{p.name}</option>
                ))}
              </select>
              <button
                type="button"
                className="btn btn-secondary btn-sm"
                onClick={grantProjectAccess}
                disabled={!principal.trim() || !projectId || busy}
              >
                授权项目
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* Quota Summary Card if Principal queried */}
      {quota && (
        <div className="card" style={{ padding: '20px', marginBottom: '24px', background: 'linear-gradient(135deg, #131726 0%, #1c2132 100%)' }}>
          <div style={{ fontSize: '11px', fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase', color: 'var(--accent-sky)', marginBottom: '8px' }}>
            QUOTA · {quota.principalId}
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: '14px' }}>
            <div>
              <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>活动任务</span>
              <div style={{ fontSize: '16px', fontWeight: 700, color: '#fff' }}>{quota.activeTasks} / {quota.maxActiveTasks}</div>
            </div>
            <div>
              <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>排队任务</span>
              <div style={{ fontSize: '16px', fontWeight: 700, color: '#fff' }}>{quota.queuedTasks} / {quota.maxQueuedTasks}</div>
            </div>
            <div>
              <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>活动会话</span>
              <div style={{ fontSize: '16px', fontWeight: 700, color: '#fff' }}>{quota.activeSessions} / {quota.maxSessions}</div>
            </div>
            <div>
              <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>预约传输流量</span>
              <div style={{ fontSize: '16px', fontWeight: 700, color: '#fff' }}>
                {(quota.reservedTransferBytes / 1024 / 1024).toFixed(1)} / {(quota.maxTransferBytes / 1024 / 1024).toFixed(0)} MB
              </div>
            </div>
            <div>
              <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>已传输总量</span>
              <div style={{ fontSize: '16px', fontWeight: 700, color: '#fff' }}>
                {(quota.transferredTransferBytes / 1024 / 1024).toFixed(1)} MB
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Table: Tokens */}
      <div style={{ marginBottom: '24px' }}>
        <h3 style={{ margin: '0 0 12px', fontSize: '16px', fontWeight: 700, color: '#fff' }}>
          已签发 MCP Token ({tokens.length})
        </h3>
        <div className="table-wrapper">
          <table className="data-table">
            <thead>
              <tr>
                <th>显示名 / Principal ID</th>
                <th>Token ID</th>
                <th>Scopes</th>
                <th>状态 / 过期时间</th>
                <th style={{ textAlign: 'right' }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {tokens.length > 0 ? (
                tokens.map((item) => (
                  <tr key={item.tokenId}>
                    <td>
                      <strong style={{ color: '#fff', display: 'block' }}>{item.displayName || '未命名'}</strong>
                      <span className="font-mono" style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                        {item.principalId}
                      </span>
                    </td>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                        <span className="font-mono">{item.tokenId.slice(0, 14)}...</span>
                        <CopyButton text={item.tokenId} label="" size="sm" />
                      </div>
                    </td>
                    <td>
                      <span className="tag-badge">{item.scopes.join(', ')}</span>
                    </td>
                    <td>
                      <span style={{ fontSize: '12px' }}>
                        {item.revokedAt ? (
                          <span style={{ color: 'var(--accent-rose)' }}>已撤销</span>
                        ) : item.expiresAt ? (
                          new Date(item.expiresAt).toLocaleDateString()
                        ) : (
                          '永久有效'
                        )}
                      </span>
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      {!item.revokedAt && (
                        <button
                          type="button"
                          className="btn btn-danger btn-sm"
                          onClick={() => handleRevokeToken(item.tokenId)}
                        >
                          撤销
                        </button>
                      )}
                    </td>
                  </tr>
                ))
              ) : (
                <tr>
                  <td colSpan={5} style={{ padding: 0 }}>
                    <EmptyState title="暂无活跃 Token" description="使用上方表单签发第一个客户端接入 Token" />
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Table: Sessions */}
      <div style={{ marginBottom: '24px' }}>
        <h3 style={{ margin: '0 0 12px', fontSize: '16px', fontWeight: 700, color: '#fff' }}>
          活动执行会话与车道 ({sessions.length})
        </h3>
        <div className="table-wrapper">
          <table className="data-table">
            <thead>
              <tr>
                <th>会话 ID / 对话 ID</th>
                <th>机器 ID</th>
                <th>状态与能力</th>
                <th>隔离策略与车道</th>
                <th style={{ textAlign: 'right' }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {sessions.length > 0 ? (
                sessions.map((item) => (
                  <tr key={`${item.principalId}:${item.sessionId}`}>
                    <td>
                      <strong style={{ color: '#fff', display: 'block' }}>{item.sessionId}</strong>
                      <span className="font-mono" style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                        conv: {item.conversationId} · {item.principalId}
                      </span>
                    </td>
                    <td>
                      <span className="font-mono">{item.machineId}</span>
                    </td>
                    <td>
                      <span className="tag-badge">{item.status} · {item.capability ?? '—'}</span>
                    </td>
                    <td>
                      <span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>
                        {item.workspacePolicy ?? 'shared_serial'} · {item.laneMode ?? 'write'}
                      </span>
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      <button
                        type="button"
                        className="btn btn-danger btn-sm"
                        onClick={() => handleCloseSession(item)}
                      >
                        关闭
                      </button>
                    </td>
                  </tr>
                ))
              ) : (
                <tr>
                  <td colSpan={5} style={{ padding: 0 }}>
                    <EmptyState title="暂无活动会话" description="客户端在调用长连接或桌面会话时将在此列出" />
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Table: Machine & Project Grants */}
      <div>
        <h3 style={{ margin: '0 0 12px', fontSize: '16px', fontWeight: 700, color: '#fff' }}>
          机器与项目显式授权 ({machineGrants.length + projectMembers.length})
        </h3>
        <div className="table-wrapper">
          <table className="data-table">
            <thead>
              <tr>
                <th>资源类型</th>
                <th>目标资源 ID</th>
                <th>主体 (Principal ID)</th>
                <th>授权 Scopes</th>
                <th style={{ textAlign: 'right' }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {machineGrants.map((item) => (
                <tr key={`m:${item.principalId}:${item.machineId}`}>
                  <td><span className="tag-badge" style={{ color: 'var(--accent-sky)' }}>机器</span></td>
                  <td><span className="font-mono">{item.machineId}</span></td>
                  <td><strong style={{ color: '#fff' }}>{item.principalId}</strong></td>
                  <td><span>{item.scopes.join(', ')}</span></td>
                  <td style={{ textAlign: 'right' }}>
                    <button
                      type="button"
                      className="btn btn-danger btn-sm"
                      onClick={() => revokeMachine(token, { principal_id: item.principalId, machine_id: item.machineId }).then(reload)}
                    >
                      撤销
                    </button>
                  </td>
                </tr>
              ))}
              {projectMembers.map((item) => (
                <tr key={`p:${item.principalId}:${item.projectId}`}>
                  <td><span className="tag-badge" style={{ color: 'var(--accent-emerald)' }}>项目</span></td>
                  <td><span className="font-mono">{item.projectId}</span></td>
                  <td><strong style={{ color: '#fff' }}>{item.principalId}</strong></td>
                  <td><span>{item.scopes.join(', ')}</span></td>
                  <td style={{ textAlign: 'right' }}>
                    <button
                      type="button"
                      className="btn btn-danger btn-sm"
                      onClick={() => revokeProject(token, { principal_id: item.principalId, project_id: item.projectId }).then(reload)}
                    >
                      撤销
                    </button>
                  </td>
                </tr>
              ))}
              {!machineGrants.length && !projectMembers.length && (
                <tr>
                  <td colSpan={5} style={{ padding: 0 }}>
                    <EmptyState title="暂无显式授权规则" description="使用上方表单为用户授予机器或项目权限" />
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}
