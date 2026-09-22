import { useEffect, useRef, useState } from 'react'
import { CopyButton } from './CopyButton'

interface TerminalOutputProps {
  title?: string
  content: string
  error?: string
  loading?: boolean
  onRefresh?: () => void
  onLoadMore?: () => void
  hasMore?: boolean
}

export function TerminalOutput({
  title = 'task_execution.log',
  content,
  error,
  loading = false,
  onRefresh,
  onLoadMore,
  hasMore = false,
}: TerminalOutputProps) {
  const [autoScroll, setAutoScroll] = useState(true)
  const bodyRef = useRef<HTMLPreElement>(null)

  useEffect(() => {
    if (autoScroll && bodyRef.current) {
      bodyRef.current.scrollTop = bodyRef.current.scrollHeight
    }
  }, [content, autoScroll])

  return (
    <div className="terminal-window">
      <div className="terminal-titlebar">
        <div style={{ display: 'flex', alignItems: 'center' }}>
          <div className="terminal-dots">
            <span className="terminal-dot red" />
            <span className="terminal-dot yellow" />
            <span className="terminal-dot green" />
          </div>
          <span className="terminal-title">{title}</span>
        </div>

        <div className="terminal-actions">
          {onRefresh && (
            <button
              type="button"
              className="btn btn-ghost btn-sm"
              onClick={onRefresh}
              disabled={loading}
              title="刷新输出"
            >
              {loading ? '刷新中...' : '刷新'}
            </button>
          )}

          {hasMore && onLoadMore && (
            <button
              type="button"
              className="btn btn-secondary btn-sm"
              onClick={onLoadMore}
              disabled={loading}
            >
              加载更多日志
            </button>
          )}

          <button
            type="button"
            className="btn btn-ghost btn-sm"
            onClick={() => setAutoScroll(!autoScroll)}
            style={{ color: autoScroll ? 'var(--accent-sky)' : 'var(--text-tertiary)' }}
          >
            {autoScroll ? '✓ 自动跟随' : '锁定滚动'}
          </button>

          <CopyButton text={content} label="复制" />
        </div>
      </div>

      {error ? (
        <div
          style={{
            padding: '12px 16px',
            background: 'var(--accent-rose-soft)',
            color: 'var(--accent-rose)',
            fontSize: '12px',
            borderBottom: '1px solid rgba(244, 63, 94, 0.2)',
          }}
        >
          {error}
        </div>
      ) : null}

      <pre
        ref={bodyRef}
        className="terminal-body"
      >
        {content || <span style={{ color: 'var(--text-muted)' }}>-- 暂无输出内容 --</span>}
      </pre>
    </div>
  )
}
