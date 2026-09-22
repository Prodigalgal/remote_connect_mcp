import { useEffect, useState } from 'react'
import { AlertCircleIcon, ClockIcon, FileCodeIcon, RefreshCwIcon, TrashIcon } from '../icons/Icons'
import { CopyButton } from '../components/CopyButton'
import { StatusBadge, StatusTone } from '../components/StatusBadge'
import { EmptyState } from '../components/EmptyState'
import {
  deleteArtifact,
  extendArtifactRetention,
  listArtifacts,
  type ArtifactAdmin,
  type Machine,
} from '../api'
import { usePagedTail } from '../utils'

interface ArtifactsViewProps {
  token: string
  machines: Machine[]
  query: string
}

export function ArtifactsView({ token, machines, query }: ArtifactsViewProps) {
  const [selectedMachineId, setSelectedMachineId] = useState<string>('')
  const [statusFilter, setStatusFilter] = useState<string>('all')
  const [loading, setLoading] = useState(false)
  const [errorMsg, setErrorMsg] = useState('')
  const [initialRows, setInitialRows] = useState<ArtifactAdmin[] | null>(null)

  const loadInitial = async (mId = selectedMachineId) => {
    if (!token.trim()) return
    setLoading(true)
    setErrorMsg('')
    try {
      const filters = mId ? { machineId: mId } : {}
      const res = await listArtifacts(token, filters, 0, 50)
      setInitialRows(res.items)
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : '获取工件列表失败')
      setInitialRows([])
    } finally {
      setLoading(false)
    }
  }

  const paged = usePagedTail(initialRows, 50, (offset, limit) => {
    const filters = selectedMachineId ? { machineId: selectedMachineId } : {}
    return listArtifacts(token, filters, offset, limit)
  })

  useEffect(() => {
    void loadInitial(selectedMachineId)
  }, [selectedMachineId, token])

  const handleDelete = async (artifactId: string) => {
    if (!window.confirm('确定要删除此工件吗？此操作无法撤销。')) return
    try {
      await deleteArtifact(token, artifactId)
      await loadInitial(selectedMachineId)
    } catch (err) {
      alert(`删除失败: ${err instanceof Error ? err.message : String(err)}`)
    }
  }

  const handleExtendRetention = async (artifactId: string) => {
    const hoursStr = window.prompt('延长保留时间（小时）：', '24')
    if (!hoursStr) return
    const seconds = parseInt(hoursStr, 10) * 3600
    if (isNaN(seconds) || seconds <= 0) return
    try {
      await extendArtifactRetention(token, artifactId, seconds)
      await loadInitial(selectedMachineId)
      alert('延长保留期成功')
    } catch (err) {
      alert(`延长失败: ${err instanceof Error ? err.message : String(err)}`)
    }
  }

  const allItems = paged.rows ?? []
  const filtered = allItems
    .filter((item) => {
      if (statusFilter === 'all') return true
      return item.status === statusFilter || item.transferStatus === statusFilter
    })
    .filter((item) => {
      if (!query.trim()) return true
      const q = query.toLowerCase()
      return (
        item.fileName.toLowerCase().includes(q) ||
        item.artifactId.toLowerCase().includes(q) ||
        (item.mimeType && item.mimeType.toLowerCase().includes(q)) ||
        (item.machineId && item.machineId.toLowerCase().includes(q))
      )
    })

  const getStatusTone = (status: string): StatusTone => {
    const s = status.toLowerCase()
    if (s === 'completed' || s === 'succeeded' || s === 'ready') return 'good'
    if (s === 'failed' || s === 'error') return 'danger'
    if (s === 'transferring' || s === 'in_progress' || s === 'running') return 'info'
    if (s === 'pending' || s === 'queued') return 'warning'
    return 'muted'
  }

  return (
    <div>
      {/* Header Toolbar */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '20px', flexWrap: 'wrap', gap: '14px' }}>
        <div>
          <h2 style={{ margin: 0, fontSize: '20px', fontWeight: 800, color: '#fff', letterSpacing: '-0.02em' }}>
            工件与生成文件管理
          </h2>
          <p style={{ margin: '4px 0 0', fontSize: '13px', color: 'var(--text-secondary)' }}>
            跨机器查看分布式任务生成的输出工件、真实传输进度、过期生命周期与清理。
          </p>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '10px', flexWrap: 'wrap' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <span style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>宿主过滤:</span>
            <select
              className="form-select"
              style={{ width: '160px', padding: '6px 10px', fontSize: '12px' }}
              value={selectedMachineId}
              onChange={(e) => setSelectedMachineId(e.target.value)}
            >
              <option value="">全部机器节点</option>
              {machines.map((m) => (
                <option key={m.id} value={m.id}>
                  {m.name}
                </option>
              ))}
            </select>
          </div>

          <button
            type="button"
            className="icon-btn"
            onClick={() => void loadInitial(selectedMachineId)}
            disabled={loading}
            title="刷新工件列表"
          >
            <RefreshCwIcon size={15} style={{ animation: loading ? 'spin 1s linear infinite' : undefined }} />
          </button>
        </div>
      </div>

      {errorMsg && (
        <div className="toast-bar error">{errorMsg}</div>
      )}

      {/* Artifacts Table */}
      <div className="table-wrapper">
        <table className="data-table">
          <thead>
            <tr>
              <th>工件名称</th>
              <th>工件 ID</th>
              <th>所属机器</th>
              <th>MIME 类型</th>
              <th>文件大小 / 传输进度</th>
              <th>真实状态</th>
              <th>过期时间</th>
              <th style={{ textAlign: 'right' }}>操作</th>
            </tr>
          </thead>
          <tbody>
            {filtered.length > 0 ? (
              filtered.map((item) => {
                const statusStr = item.transferStatus || item.status || 'unknown'
                const tone = getStatusTone(statusStr)
                const isTransferring = statusStr === 'transferring' || statusStr === 'in_progress'

                return (
                  <tr key={item.artifactId}>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
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
                          <FileCodeIcon size={16} />
                        </div>
                        <div>
                          <strong style={{ color: '#fff', fontSize: '13px', display: 'block' }}>{item.fileName}</strong>
                          {item.taskId && (
                            <span className="font-mono" style={{ fontSize: '10px', color: 'var(--text-tertiary)' }}>
                              task: {item.taskId.slice(0, 12)}
                            </span>
                          )}
                        </div>
                      </div>
                    </td>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                        <span className="font-mono">{item.artifactId.slice(0, 12)}...</span>
                        <CopyButton text={item.artifactId} label="" size="sm" />
                      </div>
                    </td>
                    <td>
                      <span className="font-mono" style={{ fontSize: '11px', color: 'var(--text-secondary)' }}>
                        {item.machineId || '—'}
                      </span>
                    </td>
                    <td>
                      <span className="tag-badge">{item.mimeType || 'application/octet-stream'}</span>
                    </td>
                    <td>
                      <div style={{ fontSize: '12px' }}>
                        {(item.bytes / 1024).toFixed(1)} KB
                        {item.bytesTransferred > 0 && item.bytesTransferred < item.bytes && (
                          <div style={{ fontSize: '10px', color: 'var(--accent-sky)' }}>
                            已传 {(item.bytesTransferred / 1024).toFixed(1)} KB
                          </div>
                        )}
                      </div>
                    </td>
                    <td>
                      <StatusBadge label={statusStr} tone={tone} pulse={isTransferring} />
                    </td>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '4px', fontSize: '12px', color: 'var(--text-tertiary)' }}>
                        <ClockIcon size={13} />
                        <span>{item.expiresAt ? new Date(item.expiresAt).toLocaleDateString() : '永久保留'}</span>
                      </div>
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      <div style={{ display: 'inline-flex', gap: '6px' }}>
                        <button
                          type="button"
                          className="btn btn-secondary btn-sm"
                          onClick={() => handleExtendRetention(item.artifactId)}
                          title="延长保留时间"
                        >
                          延长
                        </button>
                        <button
                          type="button"
                          className="btn btn-danger btn-sm"
                          onClick={() => handleDelete(item.artifactId)}
                          title="立即删除"
                        >
                          <TrashIcon size={12} />
                        </button>
                      </div>
                    </td>
                  </tr>
                )
              })
            ) : (
              <tr>
                <td colSpan={8} style={{ padding: 0 }}>
                  <EmptyState
                    title="暂无工件文件"
                    description={selectedMachineId ? '所选机器当前无工件记录，可切换为“全部机器节点”查看' : '下发产生输出文件的任务后，相关工件将在此安全归档与存储'}
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
              {paged.loadingMore ? '正在加载更多工件...' : '加载下一页工件'}
            </button>
          )}
        </div>
      )}
    </div>
  )
}
