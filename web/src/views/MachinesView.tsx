import { useState } from 'react'
import { AlertCircleIcon, PlusIcon, ServerIcon } from '../icons/Icons'
import { StatusBadge, StatusTone } from '../components/StatusBadge'
import { CopyButton } from '../components/CopyButton'
import { EmptyState } from '../components/EmptyState'
import { PaginationBar } from '../components/PaginationBar'
import { listMachinesPage, type Machine } from '../api'
import { type DemoMachine, matchesMachine, usePagedTail, usePagination } from '../utils'

interface MachinesViewProps {
  rows: Machine[] | null
  token: string
  onEnroll: () => void
  query: string
}

export function MachinesView({ rows, token, onEnroll, query }: MachinesViewProps) {
  const [filter, setFilter] = useState<'all' | 'online' | 'offline'>('all')
  const paged = usePagedTail(rows, 200, (offset, limit) => listMachinesPage(token, offset, limit))
  const sourceRows: Array<Machine | DemoMachine> =
    paged.rows ?? []

  const filteredRows = sourceRows
    .filter((machine) => {
      if (filter === 'all') return true
      const isOnline = 'id' in machine ? machine.online : machine.state !== '待接入'
      return filter === 'online' ? isOnline : !isOnline
    })
    .filter((machine) => matchesMachine(machine, query))

  const pagination = usePagination(filteredRows, { defaultPageSize: 10 })

  const totalCount = sourceRows.length
  const onlineCount = sourceRows.filter((m) => ('id' in m ? m.online : m.state !== '待接入')).length
  const offlineCount = totalCount - onlineCount

  return (
    <div>
      {/* Header Toolbar */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '20px', flexWrap: 'wrap', gap: '14px' }}>
        <div>
          <h2 style={{ margin: 0, fontSize: '20px', fontWeight: 800, color: '#fff', letterSpacing: '-0.02em' }}>
            机器与 Agent 集群
          </h2>
          <p style={{ margin: '4px 0 0', fontSize: '13px', color: 'var(--text-secondary)' }}>
            查看已连接的宿主机、操作系统环境、进程资源隔离策略与实时心跳状态。
          </p>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          <div className="segmented-group">
            <button
              type="button"
              className={`segmented-btn ${filter === 'all' ? 'active' : ''}`}
              onClick={() => setFilter('all')}
            >
              <span>全部</span>
              <b>{totalCount}</b>
            </button>
            <button
              type="button"
              className={`segmented-btn ${filter === 'online' ? 'active' : ''}`}
              onClick={() => setFilter('online')}
            >
              <span>在线</span>
              <b style={{ color: 'var(--accent-emerald)' }}>{onlineCount}</b>
            </button>
            <button
              type="button"
              className={`segmented-btn ${filter === 'offline' ? 'active' : ''}`}
              onClick={() => setFilter('offline')}
            >
              <span>离线</span>
              <b style={{ color: 'var(--accent-rose)' }}>{offlineCount}</b>
            </button>
          </div>

          <button
            type="button"
            className="btn btn-primary btn-sm"
            onClick={onEnroll}
          >
            <PlusIcon size={14} />
            <span>接入新机器</span>
          </button>
        </div>
      </div>

      {/* Machine Data Table */}
      <div className="table-wrapper">
        <table className="data-table">
          <thead>
            <tr>
              <th>Agent 标识与版本</th>
              <th>宿主 Host ID</th>
              <th>支持能力</th>
              <th>平台系统</th>
              <th>执行权限与资源</th>
              <th>心跳状态</th>
              <th style={{ textAlign: 'right' }}>操作</th>
            </tr>
          </thead>
          <tbody>
            {pagination.pagedItems.length > 0 ? (
              pagination.pagedItems.map((machine) => {
                const isLive = 'id' in machine
                const isOnline = isLive ? machine.online : machine.state !== '待接入'
                const tone: StatusTone = isLive ? (isOnline ? 'online' : 'offline') : (machine.tone as StatusTone)
                const stateLabel = isLive ? (isOnline ? '在线' : '离线') : machine.state
                const host = isLive ? machine.hostId : machine.host
                const osInfo = isLive ? `${machine.os ?? 'unknown'} · ${machine.arch ?? 'unknown'}` : machine.os
                const capabilities = isLive ? machine.capabilities : [machine.role]
                const runtime = isLive ? machine.runtime : undefined

                return (
                  <tr key={isLive ? machine.id : machine.name}>
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
                            color: 'var(--accent-primary)',
                          }}
                        >
                          <ServerIcon size={16} />
                        </div>
                        <div>
                          <strong style={{ color: '#fff', fontSize: '13px' }}>{machine.name}</strong>
                          {isLive && machine.version && (
                            <div style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                              v{machine.version}
                            </div>
                          )}
                        </div>
                      </div>
                    </td>
                    <td>
                      <span className="font-mono">{host}</span>
                    </td>
                    <td>
                      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px' }}>
                        {capabilities.map((cap) => (
                          <span key={cap} className="tag-badge">
                            {cap}
                          </span>
                        ))}
                      </div>
                    </td>
                    <td>
                      <span style={{ fontSize: '12px' }}>{osInfo}</span>
                    </td>
                    <td>
                      <div>
                        <span style={{ fontSize: '12px', color: '#e2e8f0' }}>全机权限</span>
                        {runtime && (
                          <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginTop: '2px' }}>
                            无路径栅栏 · 并发 {runtime.maxConcurrency}
                          </div>
                        )}
                      </div>
                    </td>
                    <td>
                      <StatusBadge label={stateLabel} tone={tone} pulse={isOnline} />
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      <CopyButton text={isLive ? machine.id : machine.name} label="ID" size="sm" />
                    </td>
                  </tr>
                )
              })
            ) : (
              <tr>
                <td colSpan={7} style={{ padding: 0 }}>
                  <EmptyState
                    title="暂无符合条件的机器节点"
                    description="请尝试切换过滤器或前往接入引导生成一键安装脚本"
                    action={{ label: '快速接入节点', onClick: onEnroll }}
                  />
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {/* Unified Apple/Stripe Pagination */}
      <PaginationBar
        currentPage={pagination.currentPage}
        totalPages={pagination.totalPages}
        totalItems={pagination.totalItems}
        startIndex={pagination.startIndex}
        endIndex={pagination.endIndex}
        pageSize={pagination.pageSize}
        onPageChange={pagination.goToPage}
        onPageSizeChange={pagination.setPageSize}
        pageSizeOptions={[10, 25, 50]}
        serverHasMore={paged.hasMore}
        serverLoading={paged.loadingMore}
        serverError={paged.loadError}
        onServerLoadMore={paged.loadMore}
        unit="台"
      />
    </div>
  )
}
