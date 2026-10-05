import { useEffect, useRef, useState } from 'react'
import { AdminApiError, readTaskOutput, type Task } from './api'

const PREVIEW_LIMIT = 64 * 1024
const isTerminal = (task: Task) => ['completed', 'failed', 'canceled', 'succeeded'].includes(task.status)

/** One bounded, cursor-based subscription per expanded task. */
export function useTaskOutput(token: string, listedTask: Task, enabled: boolean, encoding = 'utf-8') {
  const [snapshot, setSnapshot] = useState<Task | null>(null)
  const [output, setOutput] = useState('')
  const [more, setMore] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [live, setLive] = useState(false)
  const [cropped, setCropped] = useState(false)
  const [receivedAt, setReceivedAt] = useState<number | null>(null)
  const [readRequest, setReadRequest] = useState({ version: 0, reset: false })
  const appliedRequest = useRef(0)
  const cursor = useRef(0)
  const decoder = useRef(new TextDecoder(encoding))
  const appliedEncoding = useRef(encoding)
  const loaded = useRef(false)

  const task = snapshot?.id === listedTask.id
    && ((snapshot.changeSeq ?? 0) > (listedTask.changeSeq ?? 0)
      || ((snapshot.changeSeq ?? 0) === (listedTask.changeSeq ?? 0) && snapshot.outputBytes >= listedTask.outputBytes))
    ? snapshot : listedTask
  const taskRef = useRef(task)
  taskRef.current = task

  useEffect(() => {
    if (!enabled || !token.trim()) {
      setLive(false)
      return
    }
    const controller = new AbortController()
    let retryTimer: number | undefined
    let finishRetry: (() => void) | undefined
    if (appliedRequest.current !== readRequest.version || appliedEncoding.current !== encoding) {
      const reset = readRequest.reset || appliedEncoding.current !== encoding
      appliedRequest.current = readRequest.version
      appliedEncoding.current = encoding
      if (reset) {
        cursor.current = 0
        decoder.current = new TextDecoder(encoding)
        loaded.current = false
        setOutput('')
        setCropped(false)
        setReceivedAt(null)
      }
    }
    setLoading(true)
    setError('')

    const read = async () => {
      let waitMs = 0
      let sequence = taskRef.current.changeSeq ?? 0
      let following = !isTerminal(taskRef.current)
      let trimLeadingBytes = false
      while (!controller.signal.aborted) {
        try {
          const page = await readTaskOutput(token, listedTask.id, cursor.current, 16 * 1024,
            { waitMs, changeSeq: sequence, signal: controller.signal })
          if (controller.signal.aborted) return
          if (page.task) {
            setSnapshot(page.task)
            following ||= !isTerminal(page.task)
            // Open a noisy running command at its recent output rather than
            // downloading its entire history before showing current activity.
            if (!loaded.current && encoding === 'utf-8' && !isTerminal(page.task) && page.task.outputBytes > PREVIEW_LIMIT) {
              cursor.current = page.task.outputBytes - PREVIEW_LIMIT
              decoder.current = new TextDecoder(encoding)
              loaded.current = true
              trimLeadingBytes = true
              setCropped(true)
              continue
            }
          }
          const terminal = page.task ? isTerminal(page.task) : isTerminal(taskRef.current)
          const unread = page.task ? page.nextCursor < page.task.outputBytes : page.more
          let text = page.text
          if (page.dataBase64 != null) {
            let bytes = Uint8Array.from(atob(page.dataBase64), (value) => value.charCodeAt(0))
            if (trimLeadingBytes && bytes.length > 0) {
              let start = 0
              while (start < bytes.length && (bytes[start] & 0xc0) === 0x80) start++
              bytes = bytes.subarray(start)
              trimLeadingBytes = false
            }
            // Decode retained bytes, including legacy Windows encodings,
            // across pages. Changing encoding rereads from byte zero.
            text = decoder.current.decode(bytes, { stream: !terminal || unread })
          }
          if (text) {
            setOutput((previous) => {
              const combined = previous + text
              if (!following || combined.length <= PREVIEW_LIMIT) return combined
              let start = combined.length - PREVIEW_LIMIT
              const first = combined.charCodeAt(start)
              if (first >= 0xdc00 && first <= 0xdfff) start++
              return combined.slice(start)
            })
          }
          if (following && page.nextCursor > PREVIEW_LIMIT) setCropped(true)
          if (page.nextCursor > cursor.current || (page.task && page.task.changeSeq !== sequence)) {
            setReceivedAt(Date.now())
          }
          cursor.current = page.nextCursor
          loaded.current = true
          setMore(unread)
          setLoading(false)
          setError('')
          // Older Centers return a finite page without a task snapshot. Keep
          // manual reading compatible and never turn that into a tight poll.
          if (!page.task || !following || (terminal && !unread)) {
            setLive(false)
            return
          }
          sequence = page.task.changeSeq ?? 0
          setLive(!terminal)
          // A truncated running task can report more=true without additional
          // retained bytes. Only the byte cursor decides whether to drain.
          waitMs = unread ? 0 : 25_000
        } catch (failure) {
          if (controller.signal.aborted) return
          setLoading(false)
          setLive(false)
          const message = failure instanceof Error ? failure.message : '读取输出失败'
          if (failure instanceof AdminApiError && [400, 401, 403, 404].includes(failure.status)) {
            setError(message)
            return
          }
          setError(`${message}，正在重连…`)
          await new Promise<void>((resolve) => {
            finishRetry = resolve
            retryTimer = window.setTimeout(resolve, 3000)
          })
          waitMs = 0
        }
      }
    }
    void read()
    return () => {
      controller.abort()
      window.clearTimeout(retryTimer)
      finishRetry?.()
    }
  }, [enabled, token, listedTask.id, readRequest, encoding])

  return {
    task, output, more, loading, error, live, cropped, receivedAt,
    refresh: () => setReadRequest((current) => ({ version: current.version + 1, reset: true })),
    loadMore: () => setReadRequest((current) => ({ version: current.version + 1, reset: false })),
  }
}
