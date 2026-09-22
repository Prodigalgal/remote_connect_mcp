import { useEffect, useState } from 'react'
import { KeyIcon, LockIcon } from '../icons/Icons'

interface AuthGateProps {
  initialToken: string
  loading: boolean
  message: string
  onSubmit: (token: string) => void
}

/** Public shell for the console; business data is unavailable until Center auth succeeds. */
export function AuthGate({ initialToken, loading, message, onSubmit }: AuthGateProps) {
  const [draft, setDraft] = useState(initialToken)

  useEffect(() => {
    setDraft(initialToken)
  }, [initialToken])

  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    const token = draft.trim()
    if (token) onSubmit(token)
  }

  return (
    <main className="auth-shell">
      <section className="auth-card" aria-labelledby="auth-title">
        <div className="auth-brand-mark" aria-hidden="true">RC</div>
        <div className="auth-kicker">REMOTE CONNECT MCP</div>
        <h1 id="auth-title">登录 Control Center</h1>
        <p className="auth-description">
          这是受保护的终端控制面板。请输入 Center Admin Token，验证通过后才能查看机器、任务、文件和审计数据。
        </p>
        <form className="auth-form" onSubmit={submit}>
          <label className="auth-label" htmlFor="admin-token">
            <span>Admin Token</span>
            <span className="auth-label-hint">仅保存在当前标签页内存</span>
          </label>
          <div className="auth-input-wrap">
            <KeyIcon size={16} aria-hidden="true" />
            <input
              id="admin-token"
              name="admin-token"
              type="password"
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
              placeholder="粘贴 Center Admin Token"
              autoComplete="current-password"
              autoFocus
              required
            />
          </div>
          {message && (
            <div className="auth-error" role="alert">
              <LockIcon size={14} aria-hidden="true" />
              <span>{message}</span>
            </div>
          )}
          <button type="submit" className="auth-submit" disabled={loading || !draft.trim()}>
            {loading ? '正在验证…' : '登录并连接 Center'}
          </button>
        </form>
        <div className="auth-footer">
          <span className="auth-status-dot" aria-hidden="true" />
          <span>未认证 · 业务数据已锁定</span>
        </div>
      </section>
    </main>
  )
}
