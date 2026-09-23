import { useEffect, useRef, useState } from 'react'
import type { Machine, PageResult, Task } from './api'

export const demoMachines = [
  { name: 'edge-lab-01', host: 'edge-lab-01', role: 'command', os: 'Linux · amd64', state: '在线', tone: 'good' },
  { name: 'desktop-lab-01', host: 'edge-lab-01', role: 'desktop', os: 'Windows · amd64', state: '用户会话', tone: 'info' },
  { name: 'browser-lab-01', host: 'edge-lab-01', role: 'browser', os: 'Linux · arm64', state: '待接入', tone: 'muted' },
]

export type DemoMachine = typeof demoMachines[number]

export const demoTasks: Task[] = [
  {
    id: 'task-demo-01',
    machineId: 'edge-lab-01',
    kind: 'command',
    command: 'git status && pnpm test',
    status: 'succeeded',
    attempt: 1,
    exitCode: 0,
    outputBytes: 1240,
    outputTruncated: false,
    artifactBytes: 0,
    createdAt: '2026-09-22T14:10:00Z',
    finishedAt: '2026-09-22T14:10:05Z',
    timeoutSeconds: 120,
  },
  {
    id: 'task-demo-02',
    machineId: 'edge-lab-01',
    kind: 'command',
    command: 'docker compose ps',
    status: 'running',
    attempt: 1,
    outputBytes: 450,
    outputTruncated: false,
    artifactBytes: 0,
    createdAt: '2026-09-22T14:25:00Z',
    timeoutSeconds: 300,
  },
  {
    id: 'task-demo-03',
    machineId: 'desktop-lab-01',
    kind: 'desktop',
    status: 'succeeded',
    attempt: 1,
    outputBytes: 0,
    outputTruncated: false,
    artifactBytes: 24000,
    createdAt: '2026-09-22T13:45:00Z',
    timeoutSeconds: 60,
  },
]

export function matchesMachine(machine: Machine | DemoMachine, query: string): boolean {
  const needle = query.trim().toLocaleLowerCase()
  if (!needle) return true
  const searchable = 'id' in machine
    ? [machine.id, machine.name, machine.hostId, machine.hostname, machine.os, machine.arch, machine.version, ...machine.capabilities]
    : [machine.name, machine.host, machine.role, machine.os, machine.state]
  return searchable.filter(Boolean).some((value) => String(value).toLocaleLowerCase().includes(needle))
}

export function matchesTask(task: Task, query: string): boolean {
  const needle = query.trim().toLocaleLowerCase()
  if (!needle) return true
  const searchable = [task.id, task.machineId, task.kind, task.command, task.status]
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

export interface UsePaginationOptions {
  initialPage?: number
  defaultPageSize?: number
}

export function usePagination<T>(items: T[], options?: UsePaginationOptions) {
  const defaultPageSize = options?.defaultPageSize ?? 10
  const [pageSize, setPageSize] = useState(defaultPageSize)
  const [currentPage, setCurrentPage] = useState(options?.initialPage ?? 1)

  const totalItems = items.length
  const totalPages = Math.max(1, Math.ceil(totalItems / pageSize))

  useEffect(() => {
    if (currentPage > totalPages) {
      setCurrentPage(totalPages)
    }
  }, [totalPages, currentPage])

  const startIndex = totalItems === 0 ? 0 : (currentPage - 1) * pageSize
  const endIndex = Math.min(startIndex + pageSize, totalItems)
  const pagedItems = items.slice(startIndex, endIndex)

  const goToPage = (page: number) => {
    const clamped = Math.max(1, Math.min(page, totalPages))
    setCurrentPage(clamped)
  }

  const nextPage = () => goToPage(currentPage + 1)
  const prevPage = () => goToPage(currentPage - 1)
  const firstPage = () => goToPage(1)
  const lastPage = () => goToPage(totalPages)

  const changePageSize = (newSize: number) => {
    setPageSize(newSize)
    setCurrentPage(1)
  }

  return {
    currentPage,
    pageSize,
    setPageSize: changePageSize,
    totalPages,
    totalItems,
    startIndex: totalItems === 0 ? 0 : startIndex + 1,
    endIndex,
    pagedItems,
    goToPage,
    nextPage,
    prevPage,
    firstPage,
    lastPage,
    hasPrev: currentPage > 1,
    hasNext: currentPage < totalPages,
  }
}
