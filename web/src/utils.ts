import { useEffect, useRef, useState } from 'react'
import type { Machine, PageResult } from './api'

export const demoMachines = [
  { name: 'edge-lab-01', host: 'edge-lab-01', role: 'command', os: 'Linux · amd64', state: '在线', tone: 'good' },
  { name: 'desktop-lab-01', host: 'edge-lab-01', role: 'desktop', os: 'Windows · amd64', state: '用户会话', tone: 'info' },
  { name: 'browser-lab-01', host: 'edge-lab-01', role: 'browser', os: 'Linux · arm64', state: '待接入', tone: 'muted' },
]

export type DemoMachine = typeof demoMachines[number]

export function matchesMachine(machine: Machine | DemoMachine, query: string): boolean {
  const needle = query.trim().toLocaleLowerCase()
  if (!needle) return true
  const searchable = 'id' in machine
    ? [machine.id, machine.name, machine.hostId, machine.hostname, machine.os, machine.arch, machine.version, machine.scopeMode, machine.workspaceRoot, ...machine.capabilities]
    : [machine.name, machine.host, machine.role, machine.os, machine.state]
  return searchable.filter(Boolean).some((value) => String(value).toLocaleLowerCase().includes(needle))
}

export function usePagedTail<T>(rows: T[] | null, pageSize: number, loadPage: (offset: number, limit: number) => Promise<PageResult<T>>) {
  const [tail, setTail] = useState<T[]>([])
  const [hasMore, setHasMore] = useState(false)
  const [loadingMore, setLoadingMore] = useState(false)
  const [loadError, setLoadError] = useState('')
  const generation = useRef(0)

  useEffect(() => {
    generation.current += 1
    setTail([])
    setHasMore(Boolean(rows && rows.length >= pageSize))
    setLoadingMore(false)
    setLoadError('')
  }, [rows, pageSize])

  const loadMore = async () => {
    if (!rows || loadingMore || !hasMore) return
    const requestGeneration = generation.current
    setLoadingMore(true)
    setLoadError('')
    try {
      const page = await loadPage(rows.length + tail.length, pageSize)
      if (generation.current !== requestGeneration) return
      setTail((current) => [...current, ...page.items])
      setHasMore(page.hasMore)
    } catch (error) {
      if (generation.current !== requestGeneration) return
      setLoadError(error instanceof Error ? error.message : '下一页加载失败')
    } finally {
      if (generation.current === requestGeneration) setLoadingMore(false)
    }
  }

  return { rows: rows ? [...rows, ...tail] : null, hasMore, loadingMore, loadError, loadMore }
}

export function psLiteral(value: string): string {
  return `'${value.replace(/'/g, "''")}'`
}

export function shLiteral(value: string): string {
  return `'${value.replace(/'/g, `'\\''`)}'`
}
