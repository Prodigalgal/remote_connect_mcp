import { MenuIcon, RefreshCwIcon, SearchIcon, XIcon } from '../icons/Icons'
import { PageId, NAV_ITEMS } from './Sidebar'

interface TopbarProps {
  currentPage: PageId
  search: string
  onSearchChange: (value: string) => void
  onToggleMobileNav: () => void
  connectionState: 'online' | 'connecting' | 'offline' | 'idle'
  connectionLabel: string
  loading: boolean
  onRefresh: () => void
}

export function Topbar({
  currentPage,
  search,
  onSearchChange,
  onToggleMobileNav,
  connectionState,
  connectionLabel,
  loading,
  onRefresh,
}: TopbarProps) {
  const currentItem = NAV_ITEMS.find((item) => item.id === currentPage) ?? NAV_ITEMS[0]

  return (
    <header className="topbar">
      <div className="topbar-left">
        <button
          type="button"
          className="mobile-menu-trigger"
          aria-label="切换菜单"
          onClick={onToggleMobileNav}
        >
          <MenuIcon size={18} />
        </button>

        <div className="topbar-heading">
          <span className="topbar-breadcrumb">{currentItem.group}</span>
          <h1 className="topbar-title">{currentItem.label}</h1>
        </div>
      </div>

      <div className="topbar-right">
        <div className="search-box">
          <SearchIcon size={15} style={{ color: 'var(--text-tertiary)' }} />
          <input
            type="search"
            aria-label="全局快速过滤"
            placeholder="搜索机器、任务或 ID..."
            value={search}
            onChange={(e) => onSearchChange(e.target.value)}
          />
          {search ? (
            <button
              type="button"
              className="search-clear-btn"
              onClick={() => onSearchChange('')}
              aria-label="清空搜索"
            >
              <XIcon size={14} />
            </button>
          ) : (
            <span className="search-shortcut">⌘K</span>
          )}
        </div>

        <div className={`connection-pill ${connectionState}`}>
          <span className="pulse-dot" />
          <span>{connectionLabel}</span>
        </div>

        <button
          type="button"
          className="icon-btn"
          onClick={onRefresh}
          disabled={loading}
          title="刷新数据"
          aria-label="刷新数据"
        >
          <RefreshCwIcon
            size={16}
            style={{
              animation: loading ? 'spin 1s linear infinite' : undefined,
              transition: 'transform 0.3s ease',
            }}
          />
        </button>

        <div className="profile-pill">
          <span className="profile-avatar">A</span>
          <span>Admin</span>
        </div>
      </div>

      <style>{`
        @keyframes spin {
          from { transform: rotate(0deg); }
          to { transform: rotate(360deg); }
        }
      `}</style>
    </header>
  )
}
