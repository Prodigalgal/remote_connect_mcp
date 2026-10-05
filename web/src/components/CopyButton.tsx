import { useEffect, useRef, useState } from 'react'
import { CheckIcon, CopyIcon } from '../icons/Icons'
import { useToast } from './ToastProvider'

interface CopyButtonProps {
  text: string
  label?: string
  className?: string
  size?: 'sm' | 'md'
}

export function CopyButton({ text, label, className = '', size = 'sm' }: CopyButtonProps) {
  const notify = useToast()
  const [copied, setCopied] = useState(false)
  const timer = useRef<number | undefined>(undefined)
  useEffect(() => () => window.clearTimeout(timer.current), [])

  const handleCopy = async (e: React.MouseEvent) => {
    e.stopPropagation()
    try {
      try {
        await navigator.clipboard.writeText(text)
      } catch {
        const textarea = document.createElement('textarea')
        textarea.value = text
        document.body.appendChild(textarea)
        try {
          textarea.select()
          if (!document.execCommand('copy')) throw new Error('Clipboard unavailable')
        } finally {
          textarea.remove()
        }
      }
      setCopied(true)
      window.clearTimeout(timer.current)
      timer.current = window.setTimeout(() => setCopied(false), 2000)
      notify('已复制到剪贴板', 'success')
    } catch {
      notify('复制失败，请手动选择并复制内容', 'error')
    }
  }

  return (
    <button
      type="button"
      className={`btn btn-secondary ${size === 'sm' ? 'btn-sm' : ''} ${className}`}
      onClick={handleCopy}
      title="复制到剪贴板"
      style={{
        transition: 'all 0.2s cubic-bezier(0.16, 1, 0.3, 1)',
        color: copied ? 'var(--accent-emerald)' : undefined,
        borderColor: copied ? 'rgba(16, 185, 129, 0.3)' : undefined,
        background: copied ? 'var(--accent-emerald-soft)' : undefined
      }}
    >
      {copied ? <CheckIcon size={13} /> : <CopyIcon size={13} />}
      <span>{copied ? '已复制' : (label ?? '复制')}</span>
    </button>
  )
}
