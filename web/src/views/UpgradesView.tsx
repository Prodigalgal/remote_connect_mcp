import { useEffect, useState } from 'react'
import { AlertCircleIcon, PlayIcon, PlusIcon, RefreshCwIcon, RocketIcon, XIcon } from '../icons/Icons'
import { StatusBadge, StatusTone } from '../components/StatusBadge'
import { EmptyState } from '../components/EmptyState'
import {
  controlUpgrade,
  createUpgrade,
  listReleaseComponents,
  listUpgradesPage,
  retryUpgradeTarget,
  type Machine,
  type ReleaseCatalog,
  type UpgradeCampaign,
  type UpgradeComponentCatalog,
} from '../api'
import { usePagedTail } from '../utils'

interface UpgradesViewProps {
  token: string
  rows: UpgradeCampaign[] | null
  releases: ReleaseCatalog | null
  machines: Machine[]
  onRefresh: () => void
  onRefreshReleases: () => void
  query: string
}

export function UpgradesView({
  token,
  rows,
  releases,
  machines,
  onRefresh,
  onRefreshReleases,
  query,
}: UpgradesViewProps) {
  const [version, setVersion] = useState('')
  const [canary, setCanary] = useState('1')
  const [batch, setBatch] = useState('2')
  const [componentMode, setComponentMode] = useState<'all' | 'command' | 'selected'>('all')
  const [componentCatalog, setComponentCatalog] = useState<UpgradeComponentCatalog | null>(null)
  const [selectedComponents, setSelectedComponents] = useState<Record<string, boolean>>({})
  const [componentLoading, setComponentLoading] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [message, setMessage] = useState('')
  const [manualOpen, setManualOpen] = useState(false)

  const paged = usePagedTail(rows, 50, (offset, limit) => listUpgradesPage(token, offset, limit))
  const campaigns = paged.rows ?? []

  const names = Object.fromEntries(machines.map((machine) => [machine.id, machine.name]))

  useEffect(() => {
    if (version || !releases?.items.length) return
    const preferred =
      releases.items.find((release) => !release.prerelease && release.assets.some((asset) => asset.available && asset.checksumAvailable)) ??
      releases.items.find((release) => release.assets.some((asset) => asset.available && asset.checksumAvailable)) ??
      releases.items[0]
    if (preferred) setVersion(preferred.version)
  }, [releases, version])

  useEffect(() => {
    if (!token || !version.trim() || componentMode === 'all') {
      setComponentCatalog(null)
      return
    }
    setComponentLoading(true)
    void listReleaseComponents(token, version.trim())
      .then((catalog) => {
        setComponentCatalog(catalog)
        const defaults: Record<string, boolean> = {}
        Object.values(catalog.components).flat().forEach((item) => {
          defaults[item.component] = true
        })
        setSelectedComponents(defaults)
      })
      .catch(() => setComponentCatalog(null))
      .finally(() => setComponentLoading(false))
  }, [token, version, componentMode])

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!token.trim()) {
      setMessage('未配置 Admin Token，无法启动升级')
      return
    }
    if (!version.trim()) {
      setMessage('请选择或指定目标升级版本')
      return
    }
    setSubmitting(true)
    setMessage('')
    try {
      const payload: Record<string, unknown> = {
        version: version.trim(),
        canary_count: Number(canary) || 1,
        batch_size: Number(batch) || 1,
      }
      if (componentMode !== 'all') {
        const componentPlans: Record<string, unknown[]> = {}
        const knownPlatforms = componentCatalog
          ? Object.keys(componentCatalog.components)
          : Array.from(new Set(machines.filter((m) => m.os && m.arch).map((m) => `${m.os}/${m.arch}`)))

        if (componentMode === 'command') {
          // Explicitly supply empty component list for all platforms so Center does not resolve companion components
          knownPlatforms.forEach((platform) => {
            componentPlans[platform] = []
          })
          payload.component_plans = componentPlans
        } else if (componentCatalog) {
          Object.entries(componentCatalog.components).forEach(([platform, plans]) => {
            componentPlans[platform] = plans.filter((plan) => selectedComponents[plan.component])
          })
          payload.component_plans = componentPlans
        }
      }
      await createUpgrade(token, payload)
      setMessage('升级活动已创建并下发，Agent 将在下一次心跳中领取任务。')
      onRefresh()
    } catch (err) {
      setMessage(err instanceof Error ? err.message : '创建升级失败')
    } finally {
      setSubmitting(false)
    }
  }

  const action = async (upgradeId: string, value: 'resume' | 'cancel') => {
    if (!window.confirm(`确认执行 [${value}] 操作吗？`)) return
    try {
      await controlUpgrade(token, upgradeId, value)
      onRefresh()
    } catch (err) {
      alert(`控制操作失败: ${err instanceof Error ? err.message : String(err)}`)
    }
  }

  const retryTarget = async (campaign: UpgradeCampaign, machineId: string) => {
    if (!window.confirm('确认只重新排队该失败 Agent？已成功的目标不会重复升级。')) return
    try {
      await retryUpgradeTarget(token, campaign.id, machineId)
      setMessage(`已重新排队 ${names[machineId] ?? machineId}`)
      onRefresh()
    } catch (err) {
      alert(`重试失败: ${err instanceof Error ? err.message : String(err)}`)
    }
  }

  const filteredCampaigns = campaigns.filter((c) => {
    if (!query.trim()) return true
    const q = query.toLowerCase()
    return (
      c.id.toLowerCase().includes(q) ||
      c.version.toLowerCase().includes(q)
    )
  })

  return (
    <div>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '20px', flexWrap: 'wrap', gap: '14px' }}>
        <div>
          <h2 style={{ margin: 0, fontSize: '20px', fontWeight: 800, color: '#fff', letterSpacing: '-0.02em' }}>
            自动化升级编排与灰度发布
          </h2>
          <p style={{ margin: '4px 0 0', fontSize: '13px', color: 'var(--text-secondary)' }}>
            通过金丝雀金字塔进行分批次平滑升级，支持组件清单过滤、一键中止与单机重试。
          </p>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <button
            type="button"
            className="btn btn-secondary btn-sm"
            onClick={onRefreshReleases}
            disabled={!token}
            title="拉取最新 Release 版本元数据"
          >
            <RefreshCwIcon size={14} />
            <span>刷新版本目录</span>
          </button>
        </div>
      </div>

      {message && (
        <div className={`toast-bar ${message.includes('失败') ? 'error' : 'success'}`}>
          {message}
        </div>
      )}

      {/* Upgrade Composer Card */}
      <div className="card" style={{ padding: '24px', marginBottom: '24px', background: 'var(--bg-elevated)' }}>
        <h3 style={{ margin: '0 0 6px', fontSize: '16px', fontWeight: 700, color: '#fff' }}>
          发起全集群金丝雀升级 (Canary Release)
        </h3>
        <p style={{ margin: '0 0 16px', fontSize: '12px', color: 'var(--text-tertiary)' }}>
          GitHub Actions 发布的新版本会出现在目录中。Center 按目标机器平台重新校验资产与 SHA-256，先 canary，成功后按批次推进。
        </p>

        <form onSubmit={submit}>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 280px), 1fr))', gap: '16px', marginBottom: '14px' }}>
            <div className="form-group">
              <label className="form-label">目标 Release 版本</label>
              <select
                className="form-select"
                value={version}
                onChange={(e) => setVersion(e.target.value)}
                disabled={!releases?.items.length}
                required
              >
                <option value="">{releases?.items.length ? '请选择版本' : '等待版本目录加载...'}</option>
                {releases?.items.map((release) => {
                  const ready = release.assets.filter((asset) => asset.available && asset.checksumAvailable).length
                  return (
                    <option key={release.version} value={release.version}>
                      {release.version}{release.prerelease ? ' · 预发布' : ''} · {ready}/{release.assets.length} 平台资产
                    </option>
                  )
                })}
              </select>
            </div>

            <div className="form-group">
              <label className="form-label">首批金丝雀节点数 (canary_count)</label>
              <input
                type="number"
                className="form-input"
                min="1"
                value={canary}
                onChange={(e) => setCanary(e.target.value)}
              />
            </div>

            <div className="form-group">
              <label className="form-label">后续推进批次大小 (batch_size)</label>
              <input
                type="number"
                className="form-input"
                min="1"
                value={batch}
                onChange={(e) => setBatch(e.target.value)}
              />
            </div>

            <div className="form-group">
              <label className="form-label">升级组件范围</label>
              <select
                className="form-select"
                value={componentMode}
                onChange={(e) => setComponentMode(e.target.value as any)}
              >
                <option value="all">跟随 Release：command + 可用 companion</option>
                <option value="command">仅升级 command-agent</option>
                <option value="selected">按组件选择</option>
              </select>
            </div>
          </div>

          {componentMode === 'selected' && (
            <div style={{ padding: '12px 14px', borderRadius: 'var(--radius-md)', background: 'var(--bg-subtle)', border: '1px solid var(--border-subtle)', marginBottom: '16px' }}>
              <div style={{ fontSize: '11px', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: '8px' }}>
                选择要升级的组件
              </div>
              {componentLoading && <span style={{ fontSize: '12px', color: 'var(--text-muted)' }}>正在读取 Release 组件清单...</span>}
              {componentCatalog && (
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: '14px' }}>
                  {Array.from(new Set(Object.values(componentCatalog.components).flat().map((item) => item.component))).map((cName) => (
                    <label key={cName} style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', cursor: 'pointer', fontSize: '12px', color: '#fff' }}>
                      <input
                        type="checkbox"
                        checked={selectedComponents[cName] !== false}
                        onChange={(e) => setSelectedComponents((prev) => ({ ...prev, [cName]: e.target.checked }))}
                        style={{ accentColor: 'var(--accent-primary)' }}
                      />
                      <span>{cName}</span>
                    </label>
                  ))}
                </div>
              )}
            </div>
          )}

          {/* Manual version dropdown */}
          <div style={{ marginBottom: '16px' }}>
            <button
              type="button"
              className="btn btn-ghost btn-sm"
              onClick={() => setManualOpen(!manualOpen)}
              style={{ fontSize: '11px', padding: '0' }}
            >
              <span>{manualOpen ? '收起手动填写' : '高级：手动填写特定版本号'}</span>
            </button>
            {manualOpen && (
              <div style={{ marginTop: '8px' }}>
                <input
                  type="text"
                  className="form-input font-mono"
                  placeholder="例如: v0.1.15"
                  value={version}
                  onChange={(e) => setVersion(e.target.value)}
                  style={{ maxWidth: '300px' }}
                />
              </div>
            )}
          </div>

          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '10px' }}>
            <button
              type="submit"
              className="btn btn-primary"
              disabled={submitting || !token || !version.trim()}
            >
              {submitting ? '下发升级活动中...' : '开始灰度升级'}
            </button>
          </div>
        </form>
      </div>

      {/* Campaigns List */}
      {filteredCampaigns.length > 0 ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
          {filteredCampaigns.map((campaign) => {
            const isRunning = campaign.status === 'running'
            const isPaused = campaign.status === 'paused'
            const isCompleted = campaign.status === 'completed'
            const isFailed = campaign.status === 'failed'

            const tone: StatusTone = isCompleted
              ? 'good'
              : isFailed
              ? 'danger'
              : isPaused
              ? 'warning'
              : 'info'

            const done = campaign.targets.filter((target) => target.status === 'completed').length
            const failed = campaign.targets.filter((target) => target.status === 'failed').length
            const percent = campaign.targets.length ? Math.round((done * 100) / campaign.targets.length) : 0
            const retryAllowed = campaign.status !== 'completed' && campaign.status !== 'canceled'

            return (
              <div key={campaign.id} className="card" style={{ padding: '22px' }}>
                <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: '14px', flexWrap: 'wrap', gap: '12px' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
                    <div
                      style={{
                        width: '36px',
                        height: '36px',
                        borderRadius: 'var(--radius-md)',
                        background: 'rgba(139, 92, 246, 0.12)',
                        border: '1px solid rgba(139, 92, 246, 0.25)',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        color: 'var(--accent-purple)',
                      }}
                    >
                      <RocketIcon size={18} />
                    </div>
                    <div>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                        <h3 style={{ margin: 0, fontSize: '15px', fontWeight: 700, color: '#fff' }}>
                          全集群升级 → {campaign.version}
                        </h3>
                        <span className="font-mono" style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                          {campaign.id.slice(0, 10)}
                        </span>
                      </div>
                      <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                        canary {campaign.canaryCount} · 每批 {campaign.batchSize} · 总目标 {campaign.targets.length} 台 (成功 {done} / 失败 {failed})
                      </span>
                    </div>
                  </div>

                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                    <StatusBadge label={campaign.status} tone={tone} pulse={isRunning} />

                    {campaign.status === 'paused' && (
                      <button
                        type="button"
                        className="btn btn-secondary btn-sm"
                        onClick={() => action(campaign.id, 'resume')}
                      >
                        <PlayIcon size={12} />
                        <span>恢复</span>
                      </button>
                    )}

                    {campaign.status === 'running' && (
                      <button
                        type="button"
                        className="btn btn-danger btn-sm"
                        onClick={() => action(campaign.id, 'cancel')}
                      >
                        <XIcon size={12} />
                        <span>取消</span>
                      </button>
                    )}
                  </div>
                </div>

                {/* Progress bar */}
                <div style={{ marginBottom: '14px' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '12px', color: 'var(--text-secondary)', marginBottom: '6px' }}>
                    <span>
                      执行进度 ({done} / {campaign.targets.length} 完成)
                    </span>
                    <strong style={{ color: '#fff' }}>{percent}%</strong>
                  </div>
                  <div className="progress-rail" style={{ height: '8px' }}>
                    <div className="progress-fill" style={{ width: `${percent}%` }} />
                  </div>
                </div>

                {/* Target nodes list */}
                {campaign.targets.length > 0 && (
                  <div style={{ marginTop: '14px', paddingTop: '14px', borderTop: '1px solid var(--border-subtle)' }}>
                    <div style={{ fontSize: '11px', fontWeight: 600, color: 'var(--text-muted)', marginBottom: '8px', textTransform: 'uppercase' }}>
                      目标节点执行状态
                    </div>
                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(min(100%, 260px), 1fr))', gap: '8px' }}>
                      {campaign.targets.map((target) => (
                        <div
                          key={target.machineId}
                          style={{
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'space-between',
                            padding: '6px 10px',
                            borderRadius: 'var(--radius-sm)',
                            background: 'rgba(255, 255, 255, 0.02)',
                            border: '1px solid var(--border-subtle)',
                            fontSize: '11px',
                          }}
                        >
                          <div style={{ minWidth: 0 }}>
                            <strong style={{ color: '#fff', display: 'block' }}>{names[target.machineId] ?? target.machineId}</strong>
                            <span style={{ fontSize: '10px', color: target.status === 'failed' ? 'var(--accent-rose)' : 'var(--text-tertiary)' }}>
                              {target.status}{target.error ? ` · ${target.error}` : ''}
                            </span>
                          </div>
                          <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                            <span className="font-mono" style={{ fontSize: '10px' }}>{target.attempts}次</span>
                            {target.status === 'failed' && retryAllowed && (
                              <button
                                type="button"
                                className="btn btn-secondary btn-sm"
                                style={{ padding: '2px 5px', fontSize: '10px' }}
                                onClick={() => retryTarget(campaign, target.machineId)}
                              >
                                重试该机
                              </button>
                            )}
                          </div>
                        </div>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            )
          })}
        </div>
      ) : (
        <EmptyState
          title="暂无升级编排活动"
          description="选择 Release 版本即可创建第一批 canary 升级流水线"
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
              {paged.loadingMore ? '正在加载更多升级活动...' : '加载更多升级活动'}
            </button>
          )}
        </div>
      )}
    </div>
  )
}
