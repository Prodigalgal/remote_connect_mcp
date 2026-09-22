import React from 'react'
import {
  AlertCircleIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
  ChevronsLeftIcon,
  ChevronsRightIcon,
  RefreshCwIcon,
} from '../icons/Icons'

export interface PaginationBarProps {
  currentPage: number
  totalPages: number
  totalItems: number
  startIndex?: number
  endIndex?: number
  onPageChange: (page: number) => void
  pageSize?: number
  onPageSizeChange?: (pageSize: number) => void
  pageSizeOptions?: number[]
  serverHasMore?: boolean
  serverLoading?: boolean
  serverError?: string
  onServerLoadMore?: () => void
  compact?: boolean
  unit?: string
}

export function PaginationBar({
  currentPage,
  totalPages,
  totalItems,
  startIndex,
  endIndex,
  onPageChange,
  pageSize,
  onPageSizeChange,
  pageSizeOptions = [10, 20, 50],
  serverHasMore = false,
  serverLoading = false,
  serverError = '',
  onServerLoadMore,
  compact = false,
  unit = '项',
}: PaginationBarProps) {
  // If no items and no server error, don't show pagination
  if (totalItems === 0 && !serverHasMore && !serverError) {
    return null
  }

  // Calculate smart page sequence
  const getPageNumbers = (): (number | string)[] => {
    if (totalPages <= 7) {
      return Array.from({ length: totalPages }, (_, i) => i + 1)
    }

    if (currentPage <= 4) {
      return [1, 2, 3, 4, 5, '...', totalPages]
    }

    if (currentPage >= totalPages - 3) {
      return [1, '...', totalPages - 4, totalPages - 3, totalPages - 2, totalPages - 1, totalPages]
    }

    return [1, '...', currentPage - 1, currentPage, currentPage + 1, '...', totalPages]
  }

  const pages = getPageNumbers()
  const displayStart = startIndex ?? (totalItems === 0 ? 0 : (currentPage - 1) * (pageSize ?? 10) + 1)
  const displayEnd = endIndex ?? Math.min(totalItems, currentPage * (pageSize ?? 10))

  return (
    <div className={`pagination-container ${compact ? 'compact' : ''}`}>
      {serverError && (
        <div className="pagination-error-banner">
          <AlertCircleIcon size={14} />
          <span>{serverError}</span>
          {onServerLoadMore && (
            <button
              type="button"
              className="pagination-retry-btn"
              onClick={onServerLoadMore}
              disabled={serverLoading}
            >
              重试
            </button>
          )}
        </div>
      )}

      <div className="pagination-inner">
        {/* Left: Item summary & page size selector */}
        <div className="pagination-summary">
          <span className="pagination-range font-mono">
            显示 {displayStart}–{displayEnd} / 共 {totalItems} {unit}
          </span>

          {onPageSizeChange && pageSize && (
            <div className="pagination-size-wrapper">
              <select
                className="pagination-size-select"
                value={pageSize}
                onChange={(e) => onPageSizeChange(Number(e.target.value))}
                aria-label="每页显示条数"
              >
                {pageSizeOptions.map((opt) => (
                  <option key={opt} value={opt}>
                    {opt} {unit}/页
                  </option>
                ))}
              </select>
            </div>
          )}

          {serverHasMore && onServerLoadMore && (
            <button
              type="button"
              className="pagination-cloud-btn"
              onClick={onServerLoadMore}
              disabled={serverLoading}
              title="拉取云端历史后续记录"
            >
              <RefreshCwIcon
                size={12}
                style={{ animation: serverLoading ? 'spin 1s linear infinite' : undefined }}
              />
              <span>{serverLoading ? '云端同步中...' : '加载更多云端数据'}</span>
            </button>
          )}
        </div>

        {/* Right: Page navigation buttons */}
        <div className="pagination-nav">
          {!compact && totalPages > 4 && (
            <button
              type="button"
              className="page-nav-btn icon-only"
              onClick={() => onPageChange(1)}
              disabled={currentPage <= 1}
              title="第一页"
              aria-label="第一页"
            >
              <ChevronsLeftIcon size={14} />
            </button>
          )}

          <button
            type="button"
            className="page-nav-btn prev-btn"
            onClick={() => onPageChange(currentPage - 1)}
            disabled={currentPage <= 1}
            aria-label="上一页"
          >
            <ChevronLeftIcon size={14} />
            {!compact && <span>上一页</span>}
          </button>

          {compact ? (
            <span className="pagination-compact-indicator font-mono">
              {currentPage} / {totalPages}
            </span>
          ) : (
            <div className="page-numbers">
              {pages.map((item, idx) => {
                if (item === '...') {
                  return (
                    <span key={`ellipsis-${idx}`} className="page-ellipsis">
                      …
                    </span>
                  )
                }
                const pageNum = item as number
                const isActive = pageNum === currentPage
                return (
                  <button
                    key={pageNum}
                    type="button"
                    className={`page-num-btn ${isActive ? 'active' : ''}`}
                    onClick={() => onPageChange(pageNum)}
                    aria-current={isActive ? 'page' : undefined}
                  >
                    {pageNum}
                  </button>
                )
              })}
            </div>
          )}

          <button
            type="button"
            className="page-nav-btn next-btn"
            onClick={() => onPageChange(currentPage + 1)}
            disabled={currentPage >= totalPages}
            aria-label="下一页"
          >
            {!compact && <span>下一页</span>}
            <ChevronRightIcon size={14} />
          </button>

          {!compact && totalPages > 4 && (
            <button
              type="button"
              className="page-nav-btn icon-only"
              onClick={() => onPageChange(totalPages)}
              disabled={currentPage >= totalPages}
              title="最后一页"
              aria-label="最后一页"
            >
              <ChevronsRightIcon size={14} />
            </button>
          )}
        </div>
      </div>
    </div>
  )
}
