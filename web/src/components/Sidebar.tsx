import React from 'react'
import {
  FileCodeIcon,
  HomeIcon,
  KeyIcon,
  LockIcon,
  RocketIcon,
  ServerIcon,
  SettingsIcon,
  ShieldIcon,
  TerminalIcon,
} from '../icons/Icons'

export type PageId =
  | 'overview'
  | 'machines'
  | 'tasks'
  | 'artifacts'
  | 'audit'
  | 'enrollment'
  | 'access'
  | 'upgrades'
  | 'settings'

export interface NavItem {
  id: PageId
  label: string
  group: string
  icon: (props: { size?: number }) => React.JSX.Element
}

export const NAV_ITEMS: NavItem[] = [
  { id: 'overview', label: '总览', group: '工作台', icon: HomeIcon },
  { id: 'machines', label: '机器', group: '资源管理', icon: ServerIcon },
  { id: 'tasks', label: '任务记录', group: '执行记录', icon: TerminalIcon },
  { id: 'artifacts', label: '文件与工件', group: '执行记录', icon: FileCodeIcon },
  { id: 'audit', label: '审计日志', group: '执行记录', icon: ShieldIcon },
  { id: 'enrollment', label: '添加机器', group: '安全中心', icon: KeyIcon },
  { id: 'access', label: '连接凭证', group: '安全中心', icon: LockIcon },
  { id: 'upgrades', label: '更新', group: '运维治理', icon: RocketIcon },
  { id: 'settings', label: '系统设置', group: '安全中心', icon: SettingsIcon },
]

interface SidebarProps {
  currentPage: PageId
  onNavigate: (page: PageId) => void
  isOpen: boolean
  onCloseMobile: () => void
  connectionState: 'online' | 'connecting' | 'offline' | 'idle'
  connectionLabel: string
}

export function Sidebar({
  currentPage,
  onNavigate,
  isOpen,
  onCloseMobile,
  connectionState,
  connectionLabel,
}: SidebarProps) {
  const groups = Array.from(new Set(NAV_ITEMS.map((item) => item.group)))

  return (
    <>
      {isOpen && (
        <button
          type="button"
          className="mobile-scrim"
          aria-label="关闭侧边栏"
          onClick={onCloseMobile}
        />
      )}

      <aside className={`sidebar ${isOpen ? 'open' : ''}`}>
        <div className="brand-header">
          <div className="brand-logo-wrap">
            <span className="brand-logo-text">RC</span>
          </div>
          <div className="brand-title-wrap">
            <strong>Remote Connect</strong>
            <span>MCP Control Center</span>
          </div>
        </div>

        <nav className="sidebar-nav" aria-label="主导航">
          {groups.map((group) => {
            const items = NAV_ITEMS.filter((item) => item.group === group)
            return (
              <div key={group}>
                <div className="nav-group-title">{group}</div>
                <div className="nav-group-items">
                  {items.map((item) => {
                    const isActive = item.id === currentPage
                    const IconComponent = item.icon
                    return (
                      <button
                        key={item.id}
                        type="button"
                        className={`nav-item-btn ${isActive ? 'active' : ''}`}
                        onClick={() => {
                          onNavigate(item.id)
                          onCloseMobile()
                        }}
                        aria-current={isActive ? 'page' : undefined}
                      >
                        <span className="nav-item-icon">
                          <IconComponent size={18} />
                        </span>
                        <span>{item.label}</span>
                      </button>
                    )
                  })}
                </div>
              </div>
            )
          })}
        </nav>

        <div className="sidebar-footer">
          <div className="connection-indicator">
            <span className={`pulse-dot`} style={{
              color:
                connectionState === 'online'
                  ? 'var(--accent-emerald)'
                  : connectionState === 'connecting'
                  ? 'var(--accent-amber)'
                  : connectionState === 'offline'
                  ? 'var(--accent-rose)'
                  : 'var(--text-muted)'
            }} />
            <span>{connectionLabel}</span>
          </div>
          <span className="version-tag">0.1</span>
        </div>
      </aside>
    </>
  )
}
