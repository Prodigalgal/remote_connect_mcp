import { ActivityIcon, ChevronRightIcon, ClockIcon, RocketIcon, ServerIcon, TerminalIcon } from '../icons/Icons'
import { MetricCard } from '../components/MetricCard'
import { StatusBadge, StatusTone } from '../components/StatusBadge'
import { EmptyState } from '../components/EmptyState'
import type { PageId } from '../components/Sidebar'
import type { Machine, Task, UpgradeCampaign } from '../api'
import { demoMachines, type DemoMachine, matchesMachine } from '../utils'

interface OverviewViewProps {
  onNavigate: (page: PageId) => void
  rows: Machine[] | null
  tasks: Task[] | null
  upgrades: UpgradeCampaign[] | null
  query: string
}

export function OverviewView({ onNavigate, rows, tasks, upgrades, query }: OverviewViewProps) {
  const sourceRows: Array<Machine | DemoMachine> = rows ?? demoMachines
  const displayRows = sourceRows.filter((machine) => matchesMachine(machine, query)).slice(0, 8)
  const onlineCount = rows ? rows.filter((m) => m.online).length : 2
  const totalCount = rows ? rows.length : 3
  const activeTaskCount = tasks
    ? tasks.filter((t) => ['queued', 'dispatching', 'running', 'cancel_requested'].includes(t.status)).length
    : 0
  const activeUpgradeCount = upgrades
    ? upgrades.filter((u) => ['running', 'paused'].includes(u.status)).length
    : 0

  return (
    <div>
      {/* Stripe-style Hero Card */}
      <section className="hero-card">
        <div style={{ position: 'relative', zIndex: 2 }}>
          <span className="hero-kicker">REMOTE CONTROL PLANE · LIVE OPERATIONS</span>
          <h2 className="hero-title">让每一台分布式终端都清晰可控</h2>
          <p className="hero-desc">
            Center、Agent 与控制台已完成解耦升级。配置 Admin Token 后即可直接接管真实节点状态；未接入时保留脱敏演示数据以便调试。
          </p>
          <div className="hero-actions">
            <button
              type="button"
              className="btn btn-primary"
              onClick={() => onNavigate('machines')}
            >
              <ServerIcon size={16} />
              <span>查看全部 Agent</span>
            </button>
            <button
              type="button"
              className="btn btn-secondary"
              onClick={() => onNavigate('tasks')}
            >
              <TerminalIcon size={16} />
              <span>新建执行任务</span>
            </button>
            <button
              type="button"
              className="btn btn-ghost"
              onClick={() => onNavigate('upgrades')}
            >
              <RocketIcon size={16} />
              <span>升级编排</span>
            </button>
          </div>
        </div>

        <div className="hero-badge-art">
          <div className="hero-badge-core">
            <span>RCM</span>
            <small>READY</small>
          </div>
        </div>
      </section>

      {/* KPI Metric Grid */}
      <div className="metric-grid">
        <MetricCard
          label="已注册 Agent"
          value={String(totalCount)}
          meta={rows ? 'Center 实时接入' : '脱敏演示数据'}
          tone="blue"
          icon={<ServerIcon size={18} />}
        />
        <MetricCard
          label="在线节点"
          value={String(onlineCount)}
          meta={rows ? `${Math.round((onlineCount / Math.max(1, totalCount)) * 100)}% 节点可用率` : '67% 可用率'}
          tone="emerald"
          icon={<ActivityIcon size={18} />}
        />
        <MetricCard
          label="运行中任务"
          value={String(activeTaskCount)}
          meta={tasks ? '排队及执行中' : '无活跃任务'}
          tone="amber"
          icon={<ClockIcon size={18} />}
        />
        <MetricCard
          label="活动升级流程"
          value={upgrades ? String(activeUpgradeCount) : '—'}
          meta={upgrades ? `${upgrades.length} 项历史编排` : '输入 Token 后查询'}
          tone="purple"
          icon={<RocketIcon size={18} />}
        />
      </div>

      {/* Machine / Agent Quick List */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '14px' }}>
        <div>
          <h3 style={{ margin: 0, fontSize: '16px', fontWeight: 700, color: '#fff' }}>活跃终端与 Agent</h3>
          <span style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>实时监控分布式节点的在线状态与运行时限制</span>
        </div>
        <button
          type="button"
          className="btn btn-ghost btn-sm"
          onClick={() => onNavigate('machines')}
          style={{ gap: '4px' }}
        >
          <span>查看全部</span>
          <ChevronRightIcon size={14} />
        </button>
      </div>

      <div className="table-wrapper">
        <table className="data-table">
          <thead>
            <tr>
              <th>Agent 标识</th>
              <th>宿主终端 (Host ID)</th>
              <th>主要能力</th>
              <th>操作系统 / 架构</th>
              <th>运行时限制</th>
              <th>当前状态</th>
            </tr>
          </thead>
          <tbody>
            {displayRows.length > 0 ? (
              displayRows.map((machine) => {
                const isLive = 'id' in machine
                const role = isLive ? machine.capabilities[0] ?? 'command' : machine.role
                const isOnline = isLive ? machine.online : machine.state !== '待接入'
                const tone: StatusTone = isLive ? (isOnline ? 'online' : 'offline') : (machine.tone as StatusTone)
                const stateLabel = isLive ? (isOnline ? '在线' : '离线') : machine.state
                const host = isLive ? machine.hostId : machine.host
                const osInfo = isLive ? `${machine.os ?? 'unknown'} · ${machine.arch ?? 'unknown'}` : machine.os
                const runtime = isLive ? machine.runtime : undefined
                const runtimeDesc = runtime
                  ? `并发 ${runtime.maxConcurrency} · 进程上限 ${runtime.maxTotalChildProcesses} · gen ${runtime.configGeneration}`
                  : '标准默认配额'

                return (
                  <tr key={isLive ? machine.id : machine.name}>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                        <div
                          style={{
                            width: '30px',
                            height: '30px',
                            borderRadius: 'var(--radius-sm)',
                            background: 'rgba(99, 102, 241, 0.1)',
                            border: '1px solid rgba(99, 102, 241, 0.2)',
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                            color: 'var(--accent-sky)',
                          }}
                        >
                          <ServerIcon size={15} />
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
                      <span className="tag-badge" style={{ color: 'var(--accent-sky)' }}>
                        {role}
                      </span>
                    </td>
                    <td>
                      <span style={{ fontSize: '12px' }}>{osInfo}</span>
                    </td>
                    <td>
                      <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                        {runtimeDesc}
                      </span>
                    </td>
                    <td>
                      <StatusBadge label={stateLabel} tone={tone} pulse={isOnline} />
                    </td>
                  </tr>
                )
              })
            ) : (
              <tr>
                <td colSpan={6} style={{ padding: 0 }}>
                  <EmptyState title="未找到匹配的机器节点" description="请检查搜索关键词或调整过滤规则" />
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
