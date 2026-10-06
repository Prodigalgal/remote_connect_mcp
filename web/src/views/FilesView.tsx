import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useToast } from '../components/ToastProvider'
import { CopyButton } from '../components/CopyButton'
import { AdminApiError, cancelTask, downloadMachineFile, operateFiles, readFileTask, recoverFileRequest, uploadMachineFile,
  listMachinesPage, type FileEntry, type FileTaskResponse, type Machine, type NativeFileRequest, type NativeFileResult, type Task } from '../api'
import { usePagedTail } from '../utils'
import './FilesView.css'

const done = (task: Task) => ['completed', 'failed', 'canceled'].includes(task.status)
const join = (parent: string, leaf: string) => `${parent.replace(/[\\/]+$/, '')}${parent.includes('\\') ? '\\' : '/'}${leaf}`
const parentOf = (path: string) => {
  const trimmed = path.replace(/[\\/]+$/, '')
  const index = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
  if (index < 0) return path
  const parent = trimmed.slice(0, index)
  return /^[A-Za-z]:$/.test(parent) ? `${parent}\\` : parent || '/'
}
const size = (bytes: number) => bytes < 1024 ? `${bytes} B` : bytes < 1024 * 1024 ? `${(bytes / 1024).toFixed(1)} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`
const labels: Record<string, string> = { mkdir: '新建目录', write: '新建文本文件', copy: '复制', move: '移动 / 重命名', delete: '删除', archive: '压缩为 ZIP', extract: '解压 ZIP', upload: '上传文件' }
type Operation = NativeFileRequest['operation'] | 'upload'
type DialogState = { operation: Operation; entries: FileEntry[] }
type Editor = { path: string; text: string; encoding: string; bom: boolean; cursor: number; more: boolean; sha256?: string }

export function FilesView({ token, machines }: { token: string; machines: Machine[] }) {
  const notify = useToast()
  const allMachines = usePagedTail(machines, 200, (offset, limit) => listMachinesPage(token, offset, limit))
  const rows = allMachines.rows ?? machines
  const [machineId, setMachineId] = useState('')
  const machine = rows.find((item) => item.id === machineId)
  const [machineQuery, setMachineQuery] = useState('')
  const [path, setPath] = useState('')
  const [pathInput, setPathInput] = useState('')
  const [pattern, setPattern] = useState('')
  const [searching, setSearching] = useState(false)
  const [listing, setListing] = useState<NativeFileResult | null>(null)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [busy, setBusy] = useState(false)
  const [unconfirmed, setUnconfirmed] = useState('')
  const [pending, setPending] = useState<Task | null>(null)
  const [batchProgress, setBatchProgress] = useState('')
  const [dialog, setDialog] = useState<DialogState | null>(null)
  const [destination, setDestination] = useState('')
  const [recursive, setRecursive] = useState(false)
  const [overwrite, setOverwrite] = useState(false)
  const [encoding, setEncoding] = useState('UTF-8')
  const [newContent, setNewContent] = useState('')
  const [upload, setUpload] = useState<File | null>(null)
  const [editor, setEditor] = useState<Editor | null>(null)
  const [details, setDetails] = useState<NativeFileResult | null>(null)
  const [readyFile, setReadyFile] = useState<FileTaskResponse['file']>()
  const modal = useRef<HTMLDialogElement>(null)
  const editorModal = useRef<HTMLDialogElement>(null)
  const detailsModal = useRef<HTMLDialogElement>(null)
  const observation = useRef<AbortController | null>(null)
  const stopped = useRef(false)
  const generation = useRef(0)
  const entries = Array.isArray(listing?.entries) ? listing.entries : []
  const chosen = entries.filter((entry) => selected.has(entry.path))
  const available = Boolean(machine?.online && machine.capabilities.includes('files'))
  const locked = busy || Boolean(unconfirmed)

  useEffect(() => { if (!machineId) setMachineId(rows.find((item) => item.online && item.capabilities.includes('files'))?.id ?? rows[0]?.id ?? '') }, [rows, machineId])
  useEffect(() => { if (dialog) modal.current?.showModal() }, [dialog])
  useEffect(() => { if (editor) editorModal.current?.showModal() }, [Boolean(editor)])
  useEffect(() => { if (details) detailsModal.current?.showModal() }, [Boolean(details)])
  useEffect(() => () => { generation.current++; observation.current?.abort() }, [])

  const observe = async (initial: FileTaskResponse, current: number): Promise<FileTaskResponse> => {
    let response = initial
    if (current !== generation.current) throw new Error('操作观察已结束')
    sessionStorage.setItem(`rcm-files-task:${response.task.machineId}`, response.task.id)
    while (!done(response.task)) {
      setPending(response.task)
      const controller = new AbortController(); observation.current = controller
      response = await readFileTask(token, response.task, controller.signal)
      if (current !== generation.current) throw new Error('操作观察已结束')
    }
    setPending(response.task)
    sessionStorage.removeItem(`rcm-files-task:${response.task.machineId}`)
    if (response.task.status !== 'completed') throw new Error(response.task.error || `任务${response.task.status === 'canceled' ? '已取消' : '失败'}`)
    return response
  }

  const createFileTask = async (submit: (key: string) => Promise<FileTaskResponse>, current: number) => {
    const key = crypto.randomUUID(), target = machineId
    if (sessionStorage.getItem(`rcm-files-request:${target}`)) throw new Error('请先核对上次操作的任务创建结果')
    sessionStorage.setItem(`rcm-files-request:${target}`, key)
    try {
      const response = await submit(key)
      // Record the ID even if this view was closed while creation was in flight.
      sessionStorage.setItem(`rcm-files-task:${target}`, response.task.id)
      if (sessionStorage.getItem(`rcm-files-request:${target}`) === key) sessionStorage.removeItem(`rcm-files-request:${target}`)
      return observe(response, current)
    } catch (error) {
      const uncertain = !(error instanceof AdminApiError) || error.status === 0 || error.status >= 500
      if (sessionStorage.getItem(`rcm-files-request:${target}`) === key) {
        if (uncertain) { if (current === generation.current) setUnconfirmed(key) }
        else sessionStorage.removeItem(`rcm-files-request:${target}`)
      }
      throw error
    }
  }

  const execute = async (request: NativeFileRequest, current = generation.current) =>
    createFileTask((key) => operateFiles(token, machineId, request, key), current)

  const load = async (directory: string, offset = 0, search = false, current = generation.current) => {
    setBusy(true)
    try {
      const action: NativeFileRequest = search
        ? { operation: 'search', path: directory, pattern: pattern || '*', offset, limit: 25 }
        : { operation: 'list', path: directory, offset, limit: 25 }
      const response = await execute(action, current)
      if (current !== generation.current) return
      setListing(response.result ?? null); setSelected(new Set()); setSearching(search)
      setPath(directory); setPathInput(directory)
      if (response.result?.scan_truncated) notify('扫描已达到数量上限，可缩小目录或搜索范围。', 'warning')
      else if (response.result?.depth_limited) notify('搜索已达到目录深度上限，可进入子目录继续搜索。', 'info')
    } catch (error) { if (current === generation.current) notify(error instanceof Error ? error.message : '读取目录失败', 'error') }
    finally { if (current === generation.current) setBusy(false) }
  }

  useEffect(() => {
    const current = ++generation.current
    observation.current?.abort(); stopped.current = false
    setListing(null); setSelected(new Set()); setPending(null); setBusy(false); setEditor(null); setDialog(null); setDetails(null); setReadyFile(undefined); setUnconfirmed('')
    const initialPath = machine?.defaultCwd ?? ''
    setPath(initialPath); setPathInput(initialPath); setPattern(''); setSearching(false)
    if (!machineId || !token || !machine?.online || !machine.capabilities.includes('files')) return
    const saved = sessionStorage.getItem(`rcm-files-task:${machineId}`)
    const savedRequest = sessionStorage.getItem(`rcm-files-request:${machineId}`)
    if (savedRequest) setUnconfirmed(savedRequest)
    if (saved || savedRequest) {
      setBusy(true)
      void (savedRequest ? recoverFileRequest(token, machineId, savedRequest) : readFileTask(token, { id: saved, changeSeq: -1 } as Task))
        .then((response) => {
          sessionStorage.setItem(`rcm-files-task:${machineId}`, response.task.id); sessionStorage.removeItem(`rcm-files-request:${machineId}`)
          if (current === generation.current) setUnconfirmed('')
          return observe(response, current)
        }).then(async (response) => {
          if (current !== generation.current) return
          notify('已恢复上次文件任务的结果。', 'success')
          if (response.result && ['list', 'search'].includes(response.result.operation)) {
            setListing(response.result); setPath(response.result.path ?? initialPath); setPathInput(response.result.path ?? initialPath)
            setSearching(response.result.operation === 'search'); setPattern(response.result.pattern ?? '')
          } else { if (response.file) setReadyFile(response.file); if (initialPath) await load(initialPath, 0, false, current) }
        }).catch((error) => { if (current === generation.current) notify(error.message, 'error') })
        .finally(() => { if (current === generation.current) setBusy(false) })
    } else if (initialPath) void load(initialPath, 0, false, current)
  }, [machineId, token])

  const showDialog = (operation: Operation, targets = chosen) => {
    setDestination(operation === 'mkdir' ? join(path, '新建文件夹') : operation === 'write' ? join(path, '新建文件.txt') : operation === 'upload' ? path
      : operation === 'archive' && targets.length === 1 ? `${targets[0].path}.zip`
      : operation === 'extract' && targets.length === 1 ? targets[0].path.replace(/\.zip$/i, '') : '')
    setRecursive(false); setOverwrite(false); setUpload(null); setNewContent(''); setEncoding('UTF-8')
    setDialog({ operation, entries: targets })
  }

  const mutate = async (event: FormEvent) => {
    event.preventDefault(); if (!dialog || !available || locked) return
    const current = generation.current, action = dialog
    setBusy(true); stopped.current = false
    let completed = 0
    try {
      const targets = action.entries.length ? action.entries : [null]
      for (const entry of targets) {
        if (stopped.current || current !== generation.current) break
        setBatchProgress(`${completed + 1} / ${targets.length}`)
        if (action.operation === 'upload') {
          if (!upload) throw new Error('请选择文件')
          if (upload.size > 64 * 1024 * 1024) throw new Error('单个上传文件不能超过 64 MB')
          const file = upload
          await createFileTask((key) => uploadMachineFile(token, machineId, join(destination, file.name), file, overwrite, key), current)
        } else {
          const request: NativeFileRequest = { operation: action.operation as NativeFileRequest['operation'], path: entry?.path ?? destination }
          if (['copy', 'move', 'archive', 'extract'].includes(action.operation)) request.destination_path = targets.length > 1 && entry ? join(destination, entry.name) : destination
          if (['copy', 'delete', 'mkdir'].includes(action.operation)) request.recursive = recursive
          if (['copy', 'move', 'archive', 'write'].includes(action.operation)) request.overwrite = overwrite
          if (action.operation === 'write') { request.content = newContent; request.encoding = encoding }
          await execute(request, current)
        }
        completed++
      }
      if (current !== generation.current) return
      notify(`${labels[action.operation]}${stopped.current ? '已停止，完成' : '完成'} ${completed} / ${targets.length} 项。`, stopped.current ? 'info' : 'success')
      setDialog(null); await load(path, 0, false, current)
    } catch (error) { if (current === generation.current) notify(`${completed ? `已完成 ${completed} 项；` : ''}${error instanceof Error ? error.message : '操作失败'}`, 'error') }
    finally { if (current === generation.current) { setBusy(false); setBatchProgress('') } }
  }

  const inspect = async (entry: FileEntry, editing = false, chosenEncoding = 'auto') => {
    const current = generation.current
    setBusy(true)
    try {
      const response = await execute(editing ? { operation: 'read', path: entry.path, encoding: chosenEncoding, limit: 16384 } : { operation: 'stat', path: entry.path })
      if (current !== generation.current) return
      if (editing && response.result) setEditor({ path: entry.path, text: response.result.text ?? '', encoding: response.result.encoding ?? 'UTF-8', bom: Boolean(response.result.bom), cursor: response.result.next_cursor ?? 0, more: Boolean(response.result.has_more), sha256: response.result.sha256 })
      else setDetails(response.result ?? null)
    } catch (error) { if (current === generation.current) notify(error instanceof Error ? error.message : '读取文件失败', 'error') }
    finally { if (current === generation.current) setBusy(false) }
  }

  const readMore = async () => {
    if (!editor) return
    const current = generation.current
    setBusy(true)
    try {
      const response = await execute({ operation: 'read', path: editor.path, encoding: editor.encoding, offset: editor.cursor, limit: 16384 })
      if (current === generation.current && response.result) setEditor({ ...editor, text: editor.text + (response.result.text ?? ''), cursor: response.result.next_cursor ?? editor.cursor, more: Boolean(response.result.has_more) })
    } catch (error) { if (current === generation.current) notify(error instanceof Error ? error.message : '读取失败', 'error') }
    finally { if (current === generation.current) setBusy(false) }
  }

  const saveText = async () => {
    if (!editor || editor.more) return
    if (!window.confirm(`覆盖保存 ${editor.path}？`)) return
    const current = generation.current
    setBusy(true)
    try {
      await execute({ operation: 'write', path: editor.path, content: editor.text, encoding: editor.encoding, bom: editor.bom, overwrite: true, ...(editor.sha256 ? { expected_sha256: editor.sha256 } : {}) }, current)
      if (current !== generation.current) return
      notify('文件已保存。', 'success'); setEditor(null); await load(path)
    } catch (error) { if (current === generation.current) notify(error instanceof Error ? error.message : '保存失败', 'error') }
    finally { if (current === generation.current) setBusy(false) }
  }

  const download = async (entry: FileEntry) => {
    const current = generation.current
    setBusy(true)
    try {
      const response = await createFileTask((key) => downloadMachineFile(token, machineId, entry.path, key), current)
      if (current !== generation.current) return
      if (!response.file?.download_url) throw new Error('任务已完成但没有下载链接，请在传输与工件中读取结果')
      const link = document.createElement('a'); link.href = response.file.download_url; link.download = response.file.file_name; link.rel = 'noreferrer'; link.click()
      setReadyFile(response.file)
      notify('文件已准备好，下载已开始。', 'success')
    } catch (error) { if (current === generation.current) notify(error instanceof Error ? error.message : '下载失败', 'error') }
    finally { if (current === generation.current) setBusy(false) }
  }

  const inspectRoots = async () => {
    const current = generation.current
    setBusy(true)
    try {
      const response = await execute({ operation: 'roots' }, current)
      if (current === generation.current) setDetails(response.result ?? null)
    } catch (error) { if (current === generation.current) notify(error instanceof Error ? error.message : '读取根目录失败', 'error') }
    finally { if (current === generation.current) setBusy(false) }
  }

  const cancel = async () => {
    stopped.current = true
    if (pending && !done(pending)) try { await cancelTask(token, pending.id); notify('已请求取消当前任务，后续批量操作已停止。', 'info') }
    catch (error) { notify(error instanceof Error ? error.message : '取消失败', 'error') }
  }

  const recover = async () => {
    const current = generation.current
    setBusy(true)
    try {
      const response = unconfirmed ? await recoverFileRequest(token, machineId, unconfirmed)
        : pending ? await readFileTask(token, pending) : null
      if (!response) return
      sessionStorage.setItem(`rcm-files-task:${machineId}`, response.task.id); sessionStorage.removeItem(`rcm-files-request:${machineId}`)
      if (current !== generation.current) return
      setUnconfirmed(''); const observed = await observe(response, current)
      if (current !== generation.current) return
      if (observed.file) setReadyFile(observed.file)
      notify('已取得原任务结果。', 'success'); setDialog(null)
      await load(path, 0, false, current)
    } catch (error) { if (current === generation.current) notify(error instanceof Error ? error.message : '核对任务失败', 'error') }
    finally { if (current === generation.current) setBusy(false) }
  }

  return <section className="file-manager">
    <header className="file-toolbar"><div><h2>机器文件</h2><p>浏览与管理宿主机文件，操作结果和进度保留在任务记录中。</p></div>
      <input className="form-input" aria-label="筛选机器" placeholder="筛选机器" value={machineQuery} onChange={(event) => setMachineQuery(event.target.value)} />
      <select className="form-select" aria-label="选择文件管理机器" disabled={busy} value={machineId} onChange={(event) => setMachineId(event.target.value)}>
        <option value="">选择机器</option>{rows.filter((item) => !machineQuery || `${item.name} ${item.hostname ?? ''}`.toLowerCase().includes(machineQuery.toLowerCase())).map((item) => <option key={item.id} value={item.id}>{item.name}{item.online ? '' : ' · 离线'}{item.capabilities.includes('files') ? '' : ' · 待升级'}</option>)}
      </select>{allMachines.hasMore && <button className="btn btn-secondary" onClick={() => void allMachines.loadMore()}>更多机器</button>}
    </header>
    <form className="file-toolbar" onSubmit={(event) => { event.preventDefault(); void load(pathInput) }}>
      <button type="button" className="btn btn-secondary" disabled={!available || locked} onClick={() => void load(parentOf(path))}>上一级</button>
      <input className="form-input file-path" aria-label="目录路径" value={pathInput} onChange={(event) => setPathInput(event.target.value)} placeholder="输入完整目录路径" />
      <button className="btn btn-primary" disabled={!available || locked || !pathInput}>打开目录</button>
      <button type="button" className="btn btn-secondary" disabled={!available || locked} onClick={() => void inspectRoots()}>磁盘与根目录</button>
    </form>
    <form className="file-toolbar" onSubmit={(event) => { event.preventDefault(); void load(path, 0, true) }}>
      <input className="form-input file-path" aria-label="文件搜索" placeholder="文件名匹配，例如 大乐透*.xlsx；从当前目录搜索" value={pattern} onChange={(event) => setPattern(event.target.value)} />
      <button className="btn btn-secondary" disabled={!available || locked}>搜索</button>
      <button type="button" className="btn btn-secondary" disabled={!available || locked} onClick={() => void load(path)}>刷新目录</button>
    </form>
    <div className="file-toolbar">
      {(['mkdir', 'write', 'upload'] as Operation[]).map((operation) => <button key={operation} className="btn btn-secondary" disabled={!available || locked} onClick={() => showDialog(operation, [])}>{labels[operation]}</button>)}
      {(['copy', 'move', 'delete'] as Operation[]).map((operation) => <button key={operation} className="btn btn-secondary" disabled={!available || locked || !chosen.length} onClick={() => showDialog(operation)}>{labels[operation]}</button>)}
      <button className="btn btn-secondary" disabled={!available || locked || chosen.length !== 1} onClick={() => showDialog('archive')}>压缩 ZIP</button>
      <button className="btn btn-secondary" disabled={!available || locked || chosen.length !== 1 || !chosen[0]?.name.toLowerCase().endsWith('.zip')} onClick={() => showDialog('extract')}>解压 ZIP</button>
      <span className="file-muted">已选 {chosen.length} 项{searching ? ' · 搜索结果' : ''}</span>
    </div>
    {unconfirmed && <div className="file-task" aria-live="polite"><span>任务创建结果尚未确认；先核对任务记录，避免重复执行。</span>
      <button className="btn btn-secondary" disabled={busy} onClick={() => void recover()}>核对原任务</button>
      <button className="btn btn-secondary" disabled={busy} onClick={() => { if (window.confirm('已核对任务记录？解除待确认不会取消原操作，再次提交可能重复执行。')) { sessionStorage.removeItem(`rcm-files-request:${machineId}`); setUnconfirmed('') } }}>解除待确认</button>
    </div>}
    {pending && <div className="file-task" aria-live="polite"><span>{busy ? '处理中' : pending.status === 'completed' ? '最近任务已完成' : '最近任务'} {batchProgress} · {pending.progressMessage ?? pending.progressPhase ?? ''} {pending.progressCurrent != null ? `${pending.progressCurrent} ${pending.progressUnit ?? ''}` : ''}</span>
      <CopyButton text={pending.id} label="任务 ID" size="sm" />{busy && !done(pending) && <button className="btn btn-secondary" onClick={() => void cancel()}>取消</button>}
      {!busy && !unconfirmed && !done(pending) && <button className="btn btn-secondary" onClick={() => void recover()}>继续查看</button>}
      {readyFile && <a className="btn btn-secondary" href={readyFile.download_url} download={readyFile.file_name} rel="noreferrer">下载 {readyFile.file_name}</a>}
    </div>}
    {!available ? <p className="file-muted">{machine?.online ? '这台机器需要升级 Agent 后才能使用文件管理。' : '选择一台在线机器后浏览文件。'}</p>
      : <div className="file-table-scroll"><table className="file-table"><thead><tr>
        <th><input type="checkbox" aria-label="选择本页全部文件" checked={entries.length > 0 && selected.size === entries.length} disabled={locked} onChange={(event) => setSelected(event.target.checked ? new Set(entries.map((entry) => entry.path)) : new Set())} /></th>
        <th>名称</th><th>类型</th><th>大小</th><th>修改时间</th><th>操作</th>
      </tr></thead><tbody>{entries.map((entry) => <tr key={entry.path}>
        <td><input type="checkbox" aria-label={`选择 ${entry.name}`} checked={selected.has(entry.path)} disabled={locked} onChange={(event) => setSelected((previous) => { const next = new Set(previous); if (event.target.checked) next.add(entry.path); else next.delete(entry.path); return next })} /></td>
        <td><button className="file-name" disabled={locked} title={entry.path} onClick={() => entry.type === 'directory' ? void load(entry.path) : void inspect(entry)}>{entry.type === 'directory' ? '▸ ' : ''}{entry.name}</button>{entry.link_target && <small>{entry.link_target}</small>}</td>
        <td>{({ file: '文件', directory: '目录', symlink: '链接', other: '其他' })[entry.type]}</td><td>{entry.type === 'file' ? size(entry.bytes) : '—'}</td>
        <td>{new Date(entry.modified_at).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })}</td>
        <td><div className="file-row-actions"><button className="btn btn-secondary" disabled={locked} onClick={() => void inspect(entry)}>属性</button>
          {entry.type === 'file' && <><button className="btn btn-secondary" disabled={locked} onClick={() => void inspect(entry, true)}>文本</button><button className="btn btn-secondary" disabled={locked} onClick={() => void download(entry)}>下载</button></>}
        </div></td>
      </tr>)}</tbody></table>{!entries.length && <p className="file-muted">{busy ? '正在读取目录…' : '没有匹配的文件。'}</p>}</div>}
    {listing && <div className="file-toolbar file-pagination"><span className="file-muted">第 {(listing.offset ?? 0) + (entries.length ? 1 : 0)}–{(listing.offset ?? 0) + entries.length} 项 · 已扫描 {listing.scanned ?? 0} 项</span>
      <button className="btn btn-secondary" disabled={locked || !listing.offset} onClick={() => void load(path, Math.max(0, (listing.offset ?? 0) - 25), searching)}>上一页</button>
      <button className="btn btn-secondary" disabled={locked || !listing.has_more} onClick={() => void load(path, listing.next_offset ?? 0, searching)}>下一页</button>
    </div>}

    {dialog && <dialog ref={modal} className="file-dialog" onCancel={(event) => { if (busy) event.preventDefault(); else setDialog(null) }}><form onSubmit={(event) => void mutate(event)}>
      <h3>{labels[dialog.operation]}</h3>
      {dialog.entries.length > 0 && <div className="file-targets">{dialog.entries.map((entry) => <div key={entry.path}>{entry.path}</div>)}</div>}
      {dialog.operation !== 'delete' && <label>{dialog.operation === 'upload' ? '上传到目录' : dialog.entries.length > 1 ? '目标目录' : '完整目标路径'}<input className="form-input" required value={destination} onChange={(event) => setDestination(event.target.value)} /></label>}
      {dialog.operation === 'upload' && <label>选择文件<input type="file" required onChange={(event) => setUpload(event.target.files?.[0] ?? null)} /></label>}
      {['copy', 'delete', 'mkdir'].includes(dialog.operation) && <label className="file-check"><input type="checkbox" checked={recursive} onChange={(event) => setRecursive(event.target.checked)} />包含子目录和内容</label>}
      {['copy', 'move', 'archive', 'write', 'upload'].includes(dialog.operation) && <label className="file-check"><input type="checkbox" checked={overwrite} onChange={(event) => setOverwrite(event.target.checked)} />允许覆盖已有文件</label>}
      {dialog.operation === 'write' && <><label>编码<select className="form-select" value={encoding} onChange={(event) => setEncoding(event.target.value)}>{['UTF-8', 'GB18030', 'UTF-16LE', 'UTF-16BE'].map((value) => <option key={value}>{value}</option>)}</select></label><textarea className="form-input file-editor" aria-label="新文件内容" value={newContent} onChange={(event) => setNewContent(event.target.value)} /></>}
      {dialog.operation === 'delete' && <p>删除无法撤销。目录默认只允许删除空目录，勾选后将删除其中内容。</p>}
      {dialog.operation === 'extract' && <p>解压到新的目录，不会合并或覆盖已有目录。</p>}
      <footer>{busy && <button type="button" className="btn btn-secondary" onClick={() => void cancel()}>取消任务</button>}<button type="button" className="btn btn-secondary" disabled={busy} onClick={() => setDialog(null)}>关闭</button><button className="btn btn-primary" disabled={locked}>{busy ? '处理中…' : dialog.operation === 'delete' ? '确认删除' : '执行'}</button></footer>
    </form></dialog>}
    {editor && <dialog ref={editorModal} className="file-dialog file-editor-dialog" onCancel={(event) => { if (busy) event.preventDefault(); else setEditor(null) }}>
      <h3>文本文件</h3><p className="file-targets">{editor.path}</p>
      <div className="file-toolbar"><span>编码</span><select className="form-select" disabled={busy} value={editor.encoding} onChange={(event) => { if (window.confirm('重新按所选编码读取文件？未保存的编辑将丢失。')) void inspect({ path: editor.path } as FileEntry, true, event.target.value) }}>{['UTF-8', 'GB18030', 'UTF-16LE', 'UTF-16BE'].map((value) => <option key={value}>{value}</option>)}</select>{editor.more && <button className="btn btn-secondary" disabled={busy || editor.text.length > 1024 * 1024} onClick={() => void readMore()}>读取下一段</button>}</div>
      <textarea className="form-input file-editor" aria-label="文件内容" readOnly={editor.more || busy} value={editor.text} onChange={(event) => setEditor({ ...editor, text: event.target.value })} />
      <label><input type="checkbox" disabled={busy || editor.encoding === 'GB18030'} checked={editor.bom} onChange={(event) => setEditor({ ...editor, bom: event.target.checked })} /> 保留 BOM</label>
      <p className="file-muted">{editor.more ? '文件尚未读完，当前只读。大文件请下载后编辑。' : '保存前会检查读取时的文件版本；单次文本写入最多 64 KB。'}</p>
      <footer><button className="btn btn-secondary" disabled={busy} onClick={() => setEditor(null)}>关闭</button><button className="btn btn-primary" disabled={locked || editor.more || new TextEncoder().encode(editor.text).length > 65536} onClick={() => void saveText()}>保存文件</button></footer>
    </dialog>}
    {details && <dialog ref={detailsModal} className="file-dialog" onCancel={() => setDetails(null)}><h3>文件信息</h3>
      {details.roots?.map((root) => <button key={root} className="btn btn-secondary" onClick={() => { setDetails(null); void load(root) }}>{root}</button>)}
      <dl className="file-properties">{Object.entries(details.entry ?? details).filter(([key]) => !['operation', 'roots'].includes(key)).map(([key, value]) => <div key={key}><dt>{({ path: '路径', name: '名称', type: '类型', bytes: '大小', modified_at: '修改时间', readable: '可读', writable: '可写', link_target: '链接目标', cwd: '工作目录' } as Record<string, string>)[key] ?? key}</dt><dd>{typeof value === 'boolean' ? value ? '是' : '否' : String(value)}</dd></div>)}</dl>
      <footer><button className="btn btn-secondary" onClick={() => setDetails(null)}>关闭</button></footer></dialog>}
  </section>
}
