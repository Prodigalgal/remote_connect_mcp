import { useState } from 'react'
import { CheckIcon, CopyIcon } from '../icons/Icons'

interface CopyButtonProps {
  text: string
  label?: string
  className?: string
  size?: 'sm' | 'md'
}

export function CopyButton({ text, label, className = '', size = 'sm' }: CopyButtonProps) {
  const [copied, setCopied] = useState(false)

  const handleCopy = async (e: React.MouseEvent) => {
    e.stopPropagation()
    try {
      await navigator.clipboard.writeText(text)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      // fallback
      const textarea = document.createElement('textarea')
      textarea.value = text
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand('copy')
      document.body.removeChild(textarea)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
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
