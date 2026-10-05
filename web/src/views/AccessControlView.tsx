import { useCallback, useEffect, useMemo, useState } from 'react'
import { CopyButton } from '../components/CopyButton'
import { useToast } from '../components/ToastProvider'
import { MachinePermissionPicker, TOOL_OPTIONS } from '../components/MachinePermissionPicker'
import {
  issueMcpToken,
  listMcpTokens,
  revokeMcpToken,
  type Machine,
  type McpToken,
} from '../api'

interface AccessControlViewProps {
  token: string
  machines: Machine[]
}

const TOOL_LABELS = Object.fromEntries(TOOL_OPTIONS.map((tool) => [tool.id, tool.label])) as Record<string, string>

export function AccessControlView({ token, machines }: AccessControlViewProps) {
  const notify = useToast()
  const [displayName, setDisplayName] = useState('ChatGPT')
  const [expires, setExpires] = useState('2592000')
  const [tokens, setTokens] = useState<McpToken[]>([])
  const [permissions, setPermissions] = useState<Record<string, string[]>>({})
  const [issued, setIssued] = useState('')
  const [busy, setBusy] = useState(false)

  const refresh = useCallback(async (announceSuccess = false) => {
    if (!token.trim()) return
    try {
      setTokens((await listMcpTokens(token)).items)
      if (announceSuccess) notify('连接凭证已刷新', 'success')
    } catch (error) {
      notify(error instanceof Error ? error.message : '读取连接凭证失败', 'error')
    }
  }, [token, notify])

  useEffect(() => { void refresh() }, [refresh])

  const selected = useMemo(() => Object.fromEntries(
    Object.entries(permissions).filter(([, tools]) => tools.length > 0),
  ), [permissions])
  const selectedCount = Object.values(selected).reduce((count, tools) => count + tools.length, 0)

  const issue = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!token.trim() || !displayName.trim() || selectedCount === 0) {
      notify('至少为一台机器选择一个工具。', 'warning')
      return
    }
    setBusy(true)
    setIssued('')
    try {
      const result = await issueMcpToken(token, {
        principal_id: 'owner',
        display_name: displayName.trim(),
        expires_in_seconds: Number(expires),
        scopes: ['mcp:read', 'mcp:execute'],
        machine_permissions: selected,
      })
      setIssued(result.token)
      notify(`凭证已创建：授权 ${Object.keys(selected).length} 台机器、${selectedCount} 项工具权限。明文仅显示一次，请立即复制。`, 'success')
      setPermissions({})
      await refresh()
    } catch (error) {
      notify(error instanceof Error ? error.message : '创建连接凭证失败', 'error')
    } finally {
      setBusy(false)
    }
  }

  const revoke = async (tokenId: string) => {
    if (!window.confirm('撤销后，使用此凭证的连接将失效。确定撤销吗？')) return
    setBusy(true)
    try {
      await revokeMcpToken(token, tokenId)
      await refresh()
      notify('凭证已撤销。', 'success')
    } catch (error) {
      notify(error instanceof Error ? error.message : '撤销凭证失败', 'error')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '20px', gap: '12px' }}>
        <div>
          <h2 style={{ margin: 0, fontSize: '20px', color: '#fff' }}>连接凭证</h2>
          <p style={{ margin: '4px 0 0', color: 'var(--text-secondary)' }}>
            为 MCP 客户端创建凭证，选择可访问的机器和工具；支持批量勾选。
          </p>
        </div>
        <button type="button" className="btn btn-secondary btn-sm" onClick={() => void refresh(true)} disabled={busy}>刷新</button>
      </div>

      <div className="card" style={{ padding: '24px', marginBottom: '20px' }}>
        <form onSubmit={issue}>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 220px), 1fr))', gap: '16px' }}>
            <div className="form-group">
              <label className="form-label">凭证名称</label>
              <input className="form-input" value={displayName} onChange={(event) => setDisplayName(event.target.value)} required />
            </div>
            <div className="form-group">
              <label className="form-label">有效期</label>
              <select className="form-select" value={expires} onChange={(event) => setExpires(event.target.value)}>
                <option value="2592000">30 天</option>
                <option value="86400">1 天</option>
                <option value="0">不过期，手动撤销</option>
              </select>
            </div>
          </div>

          <div style={{ margin: '8px 0 12px', color: 'var(--text-secondary)', fontSize: '13px' }}>
            授权后，此凭证只会看到所选机器；任务读取和取消权限也按任务所在机器校验。
          </div>
          <MachinePermissionPicker machines={machines} token={token} permissions={permissions}
            onChange={setPermissions} disabled={busy} />
          <button className="btn btn-primary" type="submit" disabled={busy || !displayName.trim() || !token.trim() || selectedCount === 0}>
            创建连接凭证
          </button>
        </form>
        {issued && (
          <div style={{ marginTop: '16px', padding: '12px', borderRadius: 'var(--radius-md)', background: 'var(--bg-subtle)' }}>
            <div style={{ marginBottom: '8px' }}>凭证只显示这一次：</div>
            <div className="font-mono" style={{ wordBreak: 'break-all', marginBottom: '8px' }}>{issued}</div>
            <CopyButton text={issued} label="复制凭证" size="sm" />
          </div>
        )}
      </div>

      <div className="card" style={{ padding: '20px' }}>
        <h3 style={{ margin: '0 0 12px', color: '#fff' }}>已有凭证</h3>
        {tokens.length === 0 && <p style={{ color: 'var(--text-secondary)' }}>还没有连接凭证。</p>}
        {tokens.map((item) => (
          <div key={item.tokenId} style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: '12px', padding: '12px 0', borderTop: '1px solid var(--border-subtle)' }}>
            <div style={{ minWidth: 0 }}>
              <strong style={{ color: '#fff' }}>{item.displayName || item.principalId}</strong>
              <div style={{ color: 'var(--text-tertiary)', fontSize: '12px', margin: '3px 0 8px' }}>
                {item.principalId} · {item.revokedAt ? '已撤销' : item.expiresAt ? `到期 ${new Date(item.expiresAt).toLocaleDateString()}` : '不过期'}
              </div>
              <details className="credential-permissions">
                <summary>{Object.keys(item.machinePermissions).length} 台机器 · {Object.values(item.machinePermissions).reduce((count, tools) => count + tools.length, 0)} 项工具权限</summary>
                <div style={{ display: 'grid', gap: '4px', marginTop: '8px' }}>
                {Object.entries(item.machinePermissions).map(([machineId, tools]) => {
                  const machine = machines.find((value) => value.id === machineId)
                  return <div key={machineId} style={{ color: 'var(--text-secondary)', fontSize: '12px', overflowWrap: 'anywhere' }}>
                    {machine?.name ?? machineId}：{tools.map((tool) => TOOL_LABELS[tool] ?? tool).join('、')}
                  </div>
                })}
                </div>
              </details>
            </div>
            {!item.revokedAt && <button type="button" className="btn btn-danger btn-sm" onClick={() => void revoke(item.tokenId)} disabled={busy}>撤销</button>}
          </div>
        ))}
      </div>
    </div>
  )
}
