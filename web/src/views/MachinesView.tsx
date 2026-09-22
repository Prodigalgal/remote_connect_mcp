import { useState } from 'react'
import { AlertCircleIcon, PlusIcon, ServerIcon } from '../icons/Icons'
import { StatusBadge, StatusTone } from '../components/StatusBadge'
import { CopyButton } from '../components/CopyButton'
import { EmptyState } from '../components/EmptyState'
import { listMachinesPage, type Machine } from '../api'
import { demoMachines, type DemoMachine, matchesMachine, usePagedTail } from '../utils'

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
    paged.rows ?? demoMachines.flatMap((m) => [m, { ...m, name: `${m.name}-02` }])

  const filteredRows = sourceRows
    .filter((machine) => {
      if (filter === 'all') return true
      const isOnline = 'id' in machine ? machine.online : machine.state !== '待接入'
      return filter === 'online' ? isOnline : !isOnline
    })
    .filter((machine) => matchesMachine(machine, query))

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
              <th>工作空间路径与隔离</th>
              <th>心跳状态</th>
              <th style={{ textAlign: 'right' }}>操作</th>
            </tr>
          </thead>
          <tbody>
            {filteredRows.length > 0 ? (
              filteredRows.map((machine) => {
                const isLive = 'id' in machine
                const isOnline = isLive ? machine.online : machine.state !== '待接入'
                const tone: StatusTone = isLive ? (isOnline ? 'online' : 'offline') : (machine.tone as StatusTone)
                const stateLabel = isLive ? (isOnline ? '在线' : '离线') : machine.state
                const host = isLive ? machine.hostId : machine.host
                const osInfo = isLive ? `${machine.os ?? 'unknown'} · ${machine.arch ?? 'unknown'}` : machine.os
                const capabilities = isLive ? machine.capabilities : [machine.role]
                const workspace = isLive
                  ? machine.workspaceRoot ?? machine.defaultCwd ?? '默认安全沙盒'
                  : '/workspace/default'
                const runtime = isLive ? machine.runtime : undefined

                return (
                  <tr key={isLive ? machine.id : machine.name}>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
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
                            color: 'var(--accent-sky)',
                          }}
                        >
                          <ServerIcon size={17} />
                        </div>
                        <div>
                          <strong style={{ color: '#fff', fontSize: '14px', display: 'block' }}>
                            {machine.name}
                          </strong>
                          <div style={{ display: 'flex', alignItems: 'center', gap: '6px', marginTop: '2px' }}>
                            {isLive && machine.version && (
                              <span className="version-tag">v{machine.version}</span>
                            )}
                            {runtime && (
                              <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                                限流 {runtime.maxConcurrency} 并发
                              </span>
                            )}
                          </div>
                        </div>
                      </div>
                    </td>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                        <span className="font-mono">{host}</span>
                        <CopyButton text={host} label="" size="sm" />
                      </div>
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
                      <span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>{osInfo}</span>
                    </td>
                    <td>
                      <div style={{ maxWidth: '200px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={workspace}>
                        <span className="font-mono" style={{ fontSize: '11px', color: 'var(--text-muted)' }}>
                          {workspace}
                        </span>
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
              {paged.loadingMore ? '正在加载更多节点...' : '加载下一页节点'}
            </button>
          )}
        </div>
      )}
    </div>
  )
}
