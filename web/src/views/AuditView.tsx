import { useState } from 'react'
import { AlertCircleIcon, ShieldIcon, TrashIcon } from '../icons/Icons'
import { CopyButton } from '../components/CopyButton'
import { EmptyState } from '../components/EmptyState'
import { listAuditPage, purgeAudit, type AuditEvent, type Machine } from '../api'
import { usePagedTail } from '../utils'

interface AuditViewProps {
  rows: AuditEvent[] | null
  machines: Machine[]
  token: string
  onRefresh: () => void
  query: string
}

export function AuditView({ rows, machines, token, onRefresh, query }: AuditViewProps) {
  const paged = usePagedTail(rows, 100, (offset, limit) => listAuditPage(token, offset, limit))
  const [purging, setPurging] = useState(false)
  const [riskFilter, setRiskFilter] = useState('all')

  const auditEvents = paged.rows ?? []
  const filtered = auditEvents
    .filter((e) => {
      if (riskFilter === 'all') return true
      return e.risk === riskFilter
    })
    .filter((e) => {
      if (!query.trim()) return true
      const q = query.toLowerCase()
      return (
        e.eventType.toLowerCase().includes(q) ||
        e.actor.toLowerCase().includes(q) ||
        (e.detail && e.detail.toLowerCase().includes(q)) ||
        (e.agentId && e.agentId.toLowerCase().includes(q))
      )
    })

  const handlePurge = async () => {
    const daysStr = window.prompt('清除多少天之前的安全审计日志？', '30')
    if (!daysStr) return
    const days = parseInt(daysStr, 10)
    if (isNaN(days) || days < 0) return

    if (!window.confirm(`确认清理 ${days} 天前的所有安全审计记录吗？`)) return

    setPurging(true)
    try {
      await purgeAudit(token, days, 500)
      onRefresh()
      alert('审计日志清理完成')
    } catch (err) {
      alert(`清理失败: ${err instanceof Error ? err.message : String(err)}`)
    } finally {
      setPurging(false)
    }
  }

  return (
    <div>
      {/* Header Toolbar */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '20px', flexWrap: 'wrap', gap: '14px' }}>
        <div>
          <h2 style={{ margin: 0, fontSize: '20px', fontWeight: 800, color: '#fff', letterSpacing: '-0.02em' }}>
            合规与安全审计日志
          </h2>
          <p style={{ margin: '4px 0 0', fontSize: '13px', color: 'var(--text-secondary)' }}>
            记录每一次管理操作、执行授权、特权命令触发及鉴权事件，具备不可篡改审计追踪链。
          </p>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <div className="segmented-group">
            <button
              type="button"
              className={`segmented-btn ${riskFilter === 'all' ? 'active' : ''}`}
              onClick={() => setRiskFilter('all')}
            >
              <span>全部级别</span>
            </button>
            <button
              type="button"
              className={`segmented-btn ${riskFilter === 'high' ? 'active' : ''}`}
              onClick={() => setRiskFilter('high')}
            >
              <span style={{ color: 'var(--accent-rose)' }}>高危事件</span>
            </button>
          </div>

          <button
            type="button"
            className="btn btn-secondary btn-sm"
            onClick={handlePurge}
            disabled={purging}
          >
            <TrashIcon size={13} />
            <span>{purging ? '清理中...' : '归档清理'}</span>
          </button>
        </div>
      </div>

      {/* Audit Table */}
      <div className="table-wrapper">
        <table className="data-table">
          <thead>
            <tr>
              <th>事件类型</th>
              <th>操作者 (Actor)</th>
              <th>目标 Agent / 机器</th>
              <th>风险评级</th>
              <th>执行结果</th>
              <th>事件详情与 Payload</th>
              <th style={{ textAlign: 'right' }}>时间戳</th>
            </tr>
          </thead>
          <tbody>
            {filtered.length > 0 ? (
              filtered.map((item) => {
                const isHighRisk = item.risk === 'high' || item.risk === 'critical'
                const isSuccess = item.outcome === 'success' || item.outcome === 'allow'

                return (
                  <tr key={item.id}>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                        <div
                          style={{
                            width: '30px',
                            height: '30px',
                            borderRadius: 'var(--radius-sm)',
                            background: isHighRisk ? 'var(--accent-rose-soft)' : 'rgba(99, 102, 241, 0.1)',
                            border: `1px solid ${isHighRisk ? 'rgba(244, 63, 94, 0.3)' : 'rgba(99, 102, 241, 0.2)'}`,
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                            color: isHighRisk ? 'var(--accent-rose)' : 'var(--accent-sky)',
                          }}
                        >
                          <ShieldIcon size={15} />
                        </div>
                        <strong style={{ color: '#fff', fontSize: '13px' }}>{item.eventType}</strong>
                      </div>
                    </td>
                    <td>
                      <span className="font-mono">{item.actor}</span>
                    </td>
                    <td>
                      <span className="font-mono" style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                        {item.agentId || '—'}
                      </span>
                    </td>
                    <td>
                      <span
                        className="status-pill"
                        style={{
                          background: isHighRisk ? 'var(--accent-rose-soft)' : 'rgba(255, 255, 255, 0.04)',
                          color: isHighRisk ? 'var(--accent-rose)' : 'var(--text-secondary)',
                          borderColor: isHighRisk ? 'rgba(244, 63, 94, 0.25)' : 'var(--border-subtle)',
                        }}
                      >
                        {item.risk || 'low'}
                      </span>
                    </td>
                    <td>
                      <span
                        style={{
                          fontSize: '11px',
                          fontWeight: 600,
                          color: isSuccess ? 'var(--accent-emerald)' : 'var(--accent-rose)',
                        }}
                      >
                        {item.outcome || 'executed'}
                      </span>
                    </td>
                    <td>
                      <div
                        className="font-mono"
                        style={{
                          maxWidth: '380px',
                          fontSize: '11px',
                          color: 'var(--text-secondary)',
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                          whiteSpace: 'nowrap',
                        }}
                        title={item.detail}
                      >
                        {item.detail || '—'}
                      </div>
                    </td>
                    <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                      <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                        {item.createdAt ? new Date(item.createdAt).toLocaleString() : '—'}
                      </span>
                    </td>
                  </tr>
                )
              })
            ) : (
              <tr>
                <td colSpan={7} style={{ padding: 0 }}>
                  <EmptyState
                    title="暂无审计事件记录"
                    description="系统操作与 Agent 鉴权调用均会自动留存痕迹"
                  />
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

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
              {paged.loadingMore ? '正在加载更多审计记录...' : '加载更多记录'}
            </button>
          )}
        </div>
      )}
    </div>
  )
}
