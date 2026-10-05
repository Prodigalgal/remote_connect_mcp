import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import { AlertCircleIcon, CheckCircleIcon, XIcon } from '../icons/Icons'

type ToastTone = 'success' | 'info' | 'warning' | 'error'
type Notify = (message: string, tone?: ToastTone) => void
interface Toast { id: number; message: string; tone: ToastTone }

const ToastContext = createContext<Notify | null>(null)
const durations: Record<ToastTone, number> = { success: 4000, info: 4000, warning: 6000, error: 8000 }
let nextToastId = 0

function ToastBubble({ toast, dismiss }: { toast: Toast; dismiss: (id: number) => void }) {
  const [hovered, setHovered] = useState(false)
  const [focused, setFocused] = useState(false)
  useEffect(() => {
    if (hovered || focused) return
    const timer = window.setTimeout(() => dismiss(toast.id), durations[toast.tone])
    return () => window.clearTimeout(timer)
  }, [toast.id, toast.tone, hovered, focused, dismiss])

  return (
    <li className={`toast-bubble ${toast.tone}`} role={toast.tone === 'error' ? 'alert' : 'status'}
      aria-atomic="true" onMouseEnter={() => setHovered(true)} onMouseLeave={() => setHovered(false)}
      onFocus={() => setFocused(true)} onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) setFocused(false)
      }}>
      {toast.tone === 'success' ? <CheckCircleIcon size={18} aria-hidden="true" /> : <AlertCircleIcon size={18} aria-hidden="true" />}
      <span className="toast-message">{toast.message}</span>
      <button type="button" className="toast-close" aria-label="关闭通知" onClick={() => dismiss(toast.id)}>
        <XIcon size={16} aria-hidden="true" />
      </button>
    </li>
  )
}

/** One bounded notification queue for the login screen and every Console page. */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([])
  const notify = useCallback<Notify>((message, tone = 'info') => {
    if (!message.trim()) return
    const toast = { id: ++nextToastId, message, tone }
    setToasts((current) => current.some((item) => item.message === message && item.tone === tone)
      ? current : [...current.slice(-2), toast])
  }, [])
  const dismiss = useCallback((id: number) => setToasts((current) => current.filter((item) => item.id !== id)), [])

  return (
    <ToastContext.Provider value={notify}>
      {children}
      <ol className="toast-viewport" aria-label="操作通知">
        {toasts.map((toast) => <ToastBubble key={toast.id} toast={toast} dismiss={dismiss} />)}
      </ol>
    </ToastContext.Provider>
  )
}

export function useToast(): Notify {
  const notify = useContext(ToastContext)
  if (!notify) throw new Error('useToast requires ToastProvider')
  return notify
}
