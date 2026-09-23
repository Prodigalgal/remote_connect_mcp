import { useCallback, useEffect, useState } from 'react'
import { CopyButton } from '../components/CopyButton'
import {
  grantMachine,
  issueMcpToken,
  listMachineGrants,
  listMcpTokens,
  revokeMachine,
  revokeMcpToken,
  type Machine,
  type MachineGrant,
  type McpToken,
} from '../api'

interface AccessControlViewProps {
  token: string
  machines: Machine[]
}

export function AccessControlView({ token, machines }: AccessControlViewProps) {
  const [principal, setPrincipal] = useState('owner')
  const [expires, setExpires] = useState('2592000')
  const [tokens, setTokens] = useState<McpToken[]>([])
  const [grants, setGrants] = useState<MachineGrant[]>([])
  const [issued, setIssued] = useState('')
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)

  const refresh = useCallback(async (principalId = principal) => {
    if (!token.trim()) return
    try {
      const [tokenPage, machineGrants] = await Promise.all([
        listMcpTokens(token),
        listMachineGrants(token, principalId),
      ])
      setTokens(tokenPage.items)
      setGrants(machineGrants)
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '读取连接凭证失败')
    }
  }, [token, principal])

  useEffect(() => { void refresh() }, [refresh])

  const hasAllMachines = grants.some((grant) => grant.principalId === principal.trim()
    && grant.machineId === '*' && grant.scopes.includes('execute'))

  const issue = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!token.trim() || !principal.trim()) return
    setBusy(true)
    setIssued('')
    setMessage('')
    try {
      const result = await issueMcpToken(token, {
        principal_id: principal.trim(),
        display_name: principal.trim(),
        expires_in_seconds: Number(expires),
        scopes: ['mcp:read', 'mcp:execute'],
      })
      setIssued(result.token)
      try {
        await grantMachine(token, {
          principal_id: result.principalId,
          machine_id: '*',
          scopes: ['read', 'execute'],
        })
        setMessage('连接凭证已创建，并可访问所有机器。明文仅显示一次，请立即复制。')
      } catch (error) {
        setMessage(`凭证已创建，但机器授权失败：${error instanceof Error ? error.message : String(error)}。请使用下方按钮重试。`)
      }
      await refresh(result.principalId)
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '创建连接凭证失败')
    } finally {
      setBusy(false)
    }
  }

  const changeGrant = async () => {
    if (!principal.trim()) return
    setBusy(true)
    setMessage('')
    try {
      const payload = { principal_id: principal.trim(), machine_id: '*' }
      if (hasAllMachines) {
        await revokeMachine(token, payload)
        setMessage('已撤销所有机器的访问权限。')
      } else {
        await grantMachine(token, { ...payload, scopes: ['read', 'execute'] })
        setMessage('已授权访问所有机器，包括之后加入的机器。')
      }
      await refresh()
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '修改机器授权失败')
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
      setMessage('凭证已撤销。')
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '撤销凭证失败')
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
            为自己的 MCP 客户端创建凭证，统一访问 {machines.length} 台机器。
          </p>
        </div>
        <button type="button" className="btn btn-secondary btn-sm" onClick={() => void refresh()} disabled={busy}>刷新</button>
      </div>

      {message && <div className={`toast-bar ${message.includes('失败') ? 'error' : 'success'}`}><span>{message}</span></div>}

      <div className="card" style={{ padding: '24px', marginBottom: '20px' }}>
        <form onSubmit={issue}>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 220px), 1fr))', gap: '16px' }}>
            <div className="form-group">
              <label className="form-label">我的账户</label>
              <input className="form-input font-mono" value={principal} onChange={(event) => setPrincipal(event.target.value)} required />
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
          <button className="btn btn-primary" type="submit" disabled={busy || !principal.trim() || !token.trim()}>
            创建连接凭证并授权所有机器
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

      <div className="card" style={{ padding: '20px', marginBottom: '20px' }}>
        <strong style={{ color: '#fff' }}>机器访问</strong>
        <p style={{ color: 'var(--text-secondary)' }}>
          {principal.trim() || '当前账户'}：{hasAllMachines ? '已授权全部机器' : '尚未授权全部机器'}
        </p>
        <button type="button" className="btn btn-secondary btn-sm" onClick={() => void changeGrant()} disabled={busy || !principal.trim()}>
          {hasAllMachines ? '撤销全部机器授权' : '授权全部机器'}
        </button>
      </div>

      <div className="card" style={{ padding: '20px' }}>
        <h3 style={{ margin: '0 0 12px', color: '#fff' }}>已有凭证</h3>
        {tokens.length === 0 && <p style={{ color: 'var(--text-secondary)' }}>还没有连接凭证。</p>}
        {tokens.map((item) => (
          <div key={item.tokenId} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '12px', padding: '12px 0', borderTop: '1px solid var(--border-subtle)' }}>
            <div>
              <strong style={{ color: '#fff' }}>{item.displayName || item.principalId}</strong>
              <div style={{ color: 'var(--text-tertiary)', fontSize: '12px' }}>
                {item.principalId} · {item.revokedAt ? '已撤销' : item.expiresAt ? `到期 ${new Date(item.expiresAt).toLocaleDateString()}` : '不过期'}
              </div>
            </div>
            {!item.revokedAt && <button type="button" className="btn btn-danger btn-sm" onClick={() => void revoke(item.tokenId)} disabled={busy}>撤销</button>}
          </div>
        ))}
      </div>
    </div>
  )
}
