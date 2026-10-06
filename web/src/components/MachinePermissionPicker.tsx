import { useEffect, useMemo, useRef, useState, type Dispatch, type SetStateAction } from 'react'
import { listMachinesPage, type Machine } from '../api'
import { matchesMachine, usePagedTail, usePagination } from '../utils'
import { PaginationBar } from './PaginationBar'

export const TOOL_OPTIONS = [
  { id: 'command', label: '命令', capability: 'command' },
  { id: 'desktop', label: '桌面', capability: 'desktop' },
  { id: 'browser', label: '浏览器', capability: 'browser' },
  { id: 'artifact', label: '文件传输', capability: 'file_transfer' },
  { id: 'files', label: '文件管理', capability: 'files' },
  { id: 'task_read', label: '读取任务', capability: undefined },
  { id: 'task_cancel', label: '取消任务', capability: undefined },
] as const

type Permissions = Record<string, string[]>
const availableTools = (machine: Machine) => TOOL_OPTIONS
  .filter((tool) => !tool.capability || machine.capabilities.includes(tool.capability))

function PermissionCheckbox({ label, checked, mixed, disabled, onChange }: {
  label: string
  checked: boolean
  mixed?: boolean
  disabled?: boolean
  onChange: (checked: boolean) => void
}) {
  const input = useRef<HTMLInputElement>(null)
  useEffect(() => { if (input.current) input.current.indeterminate = Boolean(mixed) }, [mixed])
  return <input ref={input} type="checkbox" aria-label={label}
    checked={checked} disabled={disabled} onChange={(event) => onChange(event.target.checked)} />
}

export function MachinePermissionPicker({ machines, token, permissions, onChange, disabled }: {
  machines: Machine[]
  token: string
  permissions: Permissions
  onChange: Dispatch<SetStateAction<Permissions>>
  disabled: boolean
}) {
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState('all')
  const paged = usePagedTail(machines, 200, (offset, limit) => listMachinesPage(token, offset, limit))
  const rows = paged.rows ?? []
  const filtered = useMemo(() => rows.filter((machine) => matchesMachine(machine, query)
    && (status === 'all' || machine.online === (status === 'online'))), [rows, query, status])
  const pagination = usePagination(filtered, { defaultPageSize: 10 })
  const selectedMachines = Object.values(permissions).filter((tools) => tools.length > 0).length
  const selectedTools = Object.values(permissions).reduce((count, tools) => count + tools.length, 0)
  const eligibleCount = filtered.reduce((count, machine) => count + availableTools(machine).length, 0)
  const checkedCount = filtered.reduce((count, machine) => count + availableTools(machine)
    .filter((tool) => permissions[machine.id]?.includes(tool.id)).length, 0)

  // Bulk actions use the filtered set, never just the visible page. Other
  // machines keep their draft permissions; unsupported tools are not added.
  const setRange = (checked: boolean, toolId?: string) => {
    onChange((current) => {
      const next = { ...current }
      for (const machine of filtered) {
        const available = availableTools(machine).map((tool) => tool.id as string)
        if (toolId && !available.includes(toolId)) continue
        const tools = new Set(current[machine.id] ?? [])
        for (const id of toolId ? [toolId] : available) {
          if (checked) tools.add(id)
          else tools.delete(id)
        }
        next[machine.id] = [...tools]
      }
      return next
    })
  }

  const setMachine = (machine: Machine, checked: boolean, toolId?: string) => {
    onChange((current) => {
      const tools = new Set(current[machine.id] ?? [])
      for (const id of toolId ? [toolId] : availableTools(machine).map((tool) => tool.id)) {
        if (checked) tools.add(id)
        else tools.delete(id)
      }
      return { ...current, [machine.id]: [...tools] }
    })
  }

  return <section className="permission-picker" aria-label="机器与工具权限">
    <div className="permission-toolbar">
      <input className="form-input" type="search" aria-label="筛选授权机器"
        placeholder="搜索机器名称或 ID" value={query}
        onChange={(event) => { setQuery(event.target.value); pagination.goToPage(1) }} />
      <select className="form-select" aria-label="授权机器状态" value={status}
        onChange={(event) => { setStatus(event.target.value); pagination.goToPage(1) }}>
        <option value="all">全部机器</option><option value="online">在线机器</option><option value="offline">离线机器</option>
      </select>
      <div className="permission-selection" role="status">已选 <strong>{selectedMachines}</strong> 台 · <strong>{selectedTools}</strong> 项权限</div>
      <button type="button" className="btn btn-secondary btn-sm" disabled={disabled || selectedTools === 0}
        onClick={() => onChange({})}>清空已选</button>
    </div>
    <p className="permission-hint">勾选机器可选中该机全部可用工具；勾选列标题可批量选择该工具。批量操作覆盖当前筛选的 {filtered.length} 台机器（包含其他页）。</p>
    <div className="table-wrapper">
      <table className="data-table permission-matrix">
        <thead><tr>
          <th><label className="permission-machine-heading">
            <PermissionCheckbox label="选择当前筛选的全部可用权限" checked={eligibleCount > 0 && checkedCount === eligibleCount}
              mixed={checkedCount > 0 && checkedCount < eligibleCount} disabled={disabled || eligibleCount === 0}
              onChange={(checked) => setRange(checked)} />机器
          </label></th>
          {TOOL_OPTIONS.map((tool) => {
            const eligible = filtered.filter((machine) => availableTools(machine).some((option) => option.id === tool.id))
            const count = eligible.filter((machine) => permissions[machine.id]?.includes(tool.id)).length
            return <th key={tool.id}><label className="permission-tool-heading">
              <PermissionCheckbox label={`为当前筛选机器批量选择${tool.label}`} checked={eligible.length > 0 && count === eligible.length}
                mixed={count > 0 && count < eligible.length} disabled={disabled || eligible.length === 0}
                onChange={(checked) => setRange(checked, tool.id)} />{tool.label}
            </label></th>
          })}
        </tr></thead>
        <tbody>{pagination.pagedItems.length === 0
          ? <tr><td colSpan={TOOL_OPTIONS.length + 1} className="permission-empty">{rows.length ? '没有匹配的机器，请调整筛选条件。' : '当前没有已注册机器。'}</td></tr>
          : pagination.pagedItems.map((machine) => {
            const available = availableTools(machine)
            const count = available.filter((tool) => permissions[machine.id]?.includes(tool.id)).length
            return <tr key={machine.id} data-selected={count > 0}>
              <td><div className="permission-machine">
                <PermissionCheckbox label={`选择${machine.name}的全部可用工具`} checked={count === available.length && available.length > 0}
                  mixed={count > 0 && count < available.length} disabled={disabled || available.length === 0}
                  onChange={(checked) => setMachine(machine, checked)} />
                <div><strong title={machine.id}>{machine.name}</strong>
                  <span className={machine.online ? 'permission-online' : ''}>{machine.online ? '在线' : '离线'}{machine.os ? ` · ${machine.os}` : ''}</span>
                </div>
              </div></td>
              {TOOL_OPTIONS.map((tool) => {
                const supported = available.some((option) => option.id === tool.id)
                return <td key={tool.id} title={supported ? undefined : `该机器尚未启用${tool.label}能力`}>
                  <PermissionCheckbox label={`${machine.name}：${tool.label}`} checked={permissions[machine.id]?.includes(tool.id) ?? false}
                    disabled={disabled || !supported} onChange={(checked) => setMachine(machine, checked, tool.id)} />
                </td>
              })}
            </tr>
          })}</tbody>
      </table>
    </div>
    <PaginationBar currentPage={pagination.currentPage} totalPages={pagination.totalPages}
      totalItems={pagination.totalItems} startIndex={pagination.startIndex} endIndex={pagination.endIndex}
      pageSize={pagination.pageSize} onPageChange={pagination.goToPage} onPageSizeChange={pagination.setPageSize}
      pageSizeOptions={[5, 10, 20]} serverHasMore={paged.hasMore} serverLoading={paged.loadingMore}
      serverError={paged.loadError} onServerLoadMore={paged.loadMore} unit="台" />
    <p className="permission-hint">查看所选机器的信息自动包含；未启用的工具不可选择。搜索和翻页不会清除已选权限。</p>
  </section>
}
