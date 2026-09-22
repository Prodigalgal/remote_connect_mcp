import { useEffect, useState } from 'react'
import { CheckCircleIcon, KeyIcon, SettingsIcon } from '../icons/Icons'
import {
  getMachineConfig,
  rollbackMachineConfig,
  updateMachineConfig,
  type AgentConfig,
  type Machine,
} from '../api'

interface SettingsViewProps {
  token: string
  machines: Machine[]
  onTokenChange: (token: string) => void
  onRefresh: () => void
}

export function SettingsView({
  token,
  machines,
  onTokenChange,
  onRefresh,
}: SettingsViewProps) {
  const [localToken, setLocalToken] = useState(token)
  const [revealed, setRevealed] = useState(false)
  const [selectedMachineId, setSelectedMachineId] = useState(machines[0]?.id ?? '')
  const [machineConfig, setMachineConfig] = useState<AgentConfig | null>(null)
  const [loadingConfig, setLoadingConfig] = useState(false)
  const [savingConfig, setSavingConfig] = useState(false)
  const [message, setMessage] = useState('')

  useEffect(() => {
    if (machines.length > 0 && (!selectedMachineId || !machines.some((m) => m.id === selectedMachineId))) {
      setSelectedMachineId(machines[0].id)
    }
  }, [machines, selectedMachineId])

  // Form states for config update
  const [pollIntervalMs, setPollIntervalMs] = useState(1000)
  const [maxConcurrency, setMaxConcurrency] = useState(4)

  const handleSaveToken = (e: React.FormEvent) => {
    e.preventDefault()
    onTokenChange(localToken.trim())
    onRefresh()
    setMessage('Admin Token 已更新并尝试重新连接 Center')
  }

  const loadConfig = async (mId = selectedMachineId) => {
    if (!token.trim() || !mId) return
    setLoadingConfig(true)
    setMessage('')
    try {
      const cfg = await getMachineConfig(token, mId)
      setMachineConfig(cfg)
      setPollIntervalMs(cfg.pollIntervalMs)
      setMaxConcurrency(cfg.maxConcurrency)
    } catch (err) {
      setMessage(err instanceof Error ? err.message : '获取机器配置失败')
      setMachineConfig(null)
    } finally {
      setLoadingConfig(false)
    }
  }

  useEffect(() => {
    if (selectedMachineId && token.trim()) {
      void loadConfig(selectedMachineId)
    }
  }, [selectedMachineId, token])

  const handleUpdateConfig = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!token.trim() || !selectedMachineId) return
    setSavingConfig(true)
    setMessage('')
    try {
      const updated = await updateMachineConfig(token, selectedMachineId, {
        poll_interval_ms: Number(pollIntervalMs),
        max_concurrency: Number(maxConcurrency),
        expected_generation: machineConfig?.generation,
      })
      setMachineConfig(updated)
      setMessage(`配置已更新至代际 g${updated.generation}`)
      onRefresh()
    } catch (err) {
      setMessage(`更新失败: ${err instanceof Error ? err.message : String(err)}`)
    } finally {
      setSavingConfig(false)
    }
  }

  const handleRollback = async () => {
    if (!machineConfig || machineConfig.generation <= 1) {
      alert('当前已是初代配置，无法进一步回滚')
      return
    }
    if (!window.confirm(`确认将配置回滚到上一代配置吗？`)) return
    try {
      const res = await rollbackMachineConfig(token, selectedMachineId)
      setMachineConfig(res)
      setPollIntervalMs(res.pollIntervalMs)
      setMaxConcurrency(res.maxConcurrency)
      setMessage(`已成功回滚至代际 g${res.generation}`)
      onRefresh()
    } catch (err) {
      alert(`回滚失败: ${err instanceof Error ? err.message : String(err)}`)
    }
  }

  return (
    <div>
      {/* Header */}
      <div style={{ marginBottom: '24px' }}>
        <h2 style={{ margin: 0, fontSize: '20px', fontWeight: 800, color: '#fff', letterSpacing: '-0.02em' }}>
          系统设置与运行时治理
        </h2>
        <p style={{ margin: '4px 0 0', fontSize: '13px', color: 'var(--text-secondary)' }}>
          配置 Center 通信凭证、修改 Agent 动态轮询与并发限制，保障集群稳定高可用。
        </p>
      </div>

      {message && (
        <div className={`toast-bar ${message.includes('失败') ? 'error' : 'success'}`}>
          <CheckCircleIcon size={15} />
          <span>{message}</span>
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 300px), 1fr))', gap: '24px' }}>
        {/* Admin Token Card */}
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
                Center 访问凭证 (Admin Token)
              </h3>
              <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                仅保留在当前会话内存中，不写入本地 localStorage
              </span>
            </div>
          </div>

          <form onSubmit={handleSaveToken}>
            <div className="form-group">
              <label className="form-label">管理员认证密钥</label>
              <div style={{ display: 'flex', gap: '8px' }}>
                <input
                  type={revealed ? 'text' : 'password'}
                  className="form-input font-mono"
                  placeholder="输入 Java Center 的 RCM_ADMIN_TOKEN"
                  value={localToken}
                  onChange={(e) => setLocalToken(e.target.value)}
                  autoComplete="current-password"
                  style={{ fontSize: '12px' }}
                />
                <button
                  type="button"
                  className="btn btn-secondary btn-sm"
                  onClick={() => setRevealed(!revealed)}
                >
                  {revealed ? '隐藏' : '明文'}
                </button>
              </div>
            </div>

            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: '16px' }}>
              <button
                type="button"
                className="btn btn-ghost btn-sm"
                onClick={() => {
                  setLocalToken('')
                  onTokenChange('')
                  onRefresh()
                }}
              >
                退出登录并清空凭证
              </button>

              <button type="submit" className="btn btn-primary btn-sm">
                保存并连接
              </button>
            </div>
          </form>
        </div>

        {/* Runtime Config Editor */}
        <div className="card" style={{ padding: '24px' }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '16px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
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
                <SettingsIcon size={17} />
              </div>
              <div>
                <h3 style={{ margin: 0, fontSize: '15px', fontWeight: 700, color: '#fff' }}>
                  Agent 动态运行时参数
                </h3>
                <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                  支持配置代际追踪与秒级热回滚
                </span>
              </div>
            </div>

            {machineConfig && (
              <span className="version-tag" style={{ color: 'var(--accent-sky)' }}>
                代际: g{machineConfig.generation}
              </span>
            )}
          </div>

          <div className="form-group">
            <label className="form-label">选择目标机器</label>
            <select
              className="form-select"
              value={selectedMachineId}
              onChange={(e) => setSelectedMachineId(e.target.value)}
            >
              {machines.map((m) => (
                <option key={m.id} value={m.id}>
                  {m.name} ({m.hostId})
                </option>
              ))}
            </select>
          </div>

          {machineConfig ? (
            <form onSubmit={handleUpdateConfig}>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '14px' }}>
                <div className="form-group">
                  <label className="form-label">轮询间隔 (ms)</label>
                  <input
                    type="number"
                    className="form-input"
                    value={pollIntervalMs}
                    onChange={(e) => setPollIntervalMs(Number(e.target.value))}
                    min={200}
                    max={60000}
                  />
                </div>

                <div className="form-group">
                  <label className="form-label">最大并行任务数</label>
                  <input
                    type="number"
                    className="form-input"
                    value={maxConcurrency}
                    onChange={(e) => setMaxConcurrency(Number(e.target.value))}
                    min={1}
                    max={64}
                  />
                </div>
              </div>

              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: '16px' }}>
                <button
                  type="button"
                  className="btn btn-secondary btn-sm"
                  onClick={handleRollback}
                  disabled={machineConfig.generation <= 1}
                >
                  回滚至上一代
                </button>

                <button
                  type="submit"
                  className="btn btn-primary btn-sm"
                  disabled={savingConfig}
                >
                  {savingConfig ? '应用中...' : '更新配置'}
                </button>
              </div>
            </form>
          ) : (
            <div style={{ padding: '20px', textAlign: 'center', color: 'var(--text-muted)', fontSize: '12px' }}>
              {loadingConfig ? '正在加载配置参数...' : '请连接 Center 后查看运行时参数'}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
