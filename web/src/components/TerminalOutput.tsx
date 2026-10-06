import { useEffect, useId, useRef, useState } from 'react'
import { CopyButton } from './CopyButton'
import { useToast } from './ToastProvider'

interface TerminalOutputProps {
  title?: string
  content: string
  error?: string
  loading?: boolean
  onRefresh?: () => void
  onLoadMore?: () => void
  hasMore?: boolean
  live?: boolean
  receivedAt?: number | null
  encoding?: string
  onEncodingChange?: (encoding: string) => void
}

export function TerminalOutput({
  title = 'task_execution.log',
  content,
  error,
  loading = false,
  onRefresh,
  onLoadMore,
  hasMore = false,
  live = false,
  receivedAt,
  encoding = 'auto',
  onEncodingChange,
}: TerminalOutputProps) {
  const notify = useToast()
  const lastError = useRef('')
  const [autoScroll, setAutoScroll] = useState(true)
  const [sourceDraft, setSourceDraft] = useState(encoding)
  const sourceOptions = useId()
  const bodyRef = useRef<HTMLPreElement>(null)

  useEffect(() => { setSourceDraft(encoding) }, [encoding])
  useEffect(() => { lastError.current = '' }, [receivedAt])
  useEffect(() => {
    if (error && error !== lastError.current) {
      notify(error, 'error')
      lastError.current = error
    }
  }, [error, notify])

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
          {onEncodingChange && <>
            <input className="form-input terminal-encoding" aria-label="日志源编码" list={sourceOptions}
              title="源编码：auto 为自动识别，也可输入字符集名称。按 Enter 或离开输入框应用，回显统一为 UTF-8。"
              value={sourceDraft} maxLength={64} onChange={(event) => setSourceDraft(event.target.value)}
              onKeyDown={(event) => { if (event.key === 'Enter') event.currentTarget.blur() }}
              onBlur={() => {
                const selected = sourceDraft.trim() || 'auto'
                setSourceDraft(selected)
                if (selected !== encoding) onEncodingChange(selected)
              }} />
            <datalist id={sourceOptions}>
              <option value="auto" label="自动识别源编码" />
              <option value="UTF-8" /><option value="GB18030" /><option value="GBK" />
              <option value="UTF-16LE" /><option value="UTF-16BE" />
              <option value="windows-1252" /><option value="Shift_JIS" /><option value="IBM850" />
            </datalist>
          </>}
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

      <div style={{ padding: '6px 16px', color: live ? 'var(--accent-sky)' : 'var(--text-tertiary)', fontSize: '11px', borderBottom: '1px solid var(--border-subtle)' }}>
        {live ? '实时接收 · 等待宿主机回传' : '日志记录'}
        {receivedAt != null && ` · 最近接收 ${new Date(receivedAt).toLocaleTimeString()}`}
      </div>

      <pre
        ref={bodyRef}
        className="terminal-body"
      >
        {content || <span style={{ color: 'var(--text-muted)' }}>{live ? '等待宿主机回传输出…' : '-- 暂无输出内容 --'}</span>}
      </pre>
    </div>
  )
}
