export type ArtifactViewerFile = {
  name: string
  mime: string
  url: string
}

export type PreviewHandler = {
  id: string
  matches: (file: ArtifactViewerFile) => boolean
  render: (file: ArtifactViewerFile, host: HTMLElement, readRange: (url: string, limit: number) => Promise<string>) => void
}

const textName = /\.(md|markdown|csv|json|ya?ml|toml|ini|log|txt|xml|html?|css|js|ts|java|go|py|sh|ps1|sql)$/i
const diffName = /\.(diff|patch)$/i
const officeMime = /^(application\/(?:msword|vnd\.ms-|vnd\.openxmlformats-|vnd\.oasis\.opendocument\.)|application\/rtf$)/i
const archiveMime = /^(application\/(?:zip|gzip|x-7z-compressed|x-rar-compressed|x-tar)|application\/x-tar$)/i

const downloadOnly = (label: string): PreviewHandler['render'] => (file, host) => {
  const note = document.createElement('small')
  note.textContent = `${label}: ${file.name}（为避免在 MCP App 中解压或执行内容，请下载后使用本机应用打开）`
  host.replaceChildren(note)
}

/**
 * MIME handlers are intentionally data-only and bounded. The MCP App shell
 * can register additional handlers without changing Center routing or tool
 * schemas; unknown formats remain download-only.
 */
export const defaultPreviewHandlers: PreviewHandler[] = [
  { id: 'image', matches: (file) => file.mime.startsWith('image/'), render: (file, host) => { const image = document.createElement('img'); image.alt = file.name; image.src = file.url; host.replaceChildren(image) } },
  { id: 'pdf', matches: (file) => file.mime === 'application/pdf', render: (file, host) => { const frame = document.createElement('iframe'); frame.title = file.name; frame.src = file.url; frame.loading = 'lazy'; host.replaceChildren(frame) } },
  { id: 'video', matches: (file) => file.mime.startsWith('video/'), render: (file, host) => { const video = document.createElement('video'); video.controls = true; video.src = file.url; host.replaceChildren(video) } },
  { id: 'audio', matches: (file) => file.mime.startsWith('audio/'), render: (file, host) => { const audio = document.createElement('audio'); audio.controls = true; audio.src = file.url; host.replaceChildren(audio) } },
  { id: 'diff', matches: (file) => file.mime.includes('diff') || diffName.test(file.name), render: (file, host, readRange) => { void readRange(file.url, 262144).then((text) => { const pre = document.createElement('pre'); pre.className = 'artifact-diff'; pre.textContent = text; host.replaceChildren(pre) }).catch(() => { host.textContent = 'Diff preview unavailable; use download.' }) } },
  { id: 'text', matches: (file) => file.mime.startsWith('text/') || file.mime.includes('json') || file.mime.includes('xml') || file.mime.includes('csv') || file.mime.includes('yaml') || file.mime.includes('markdown') || textName.test(file.name), render: (file, host, readRange) => { void readRange(file.url, 262144).then((text) => { const pre = document.createElement('pre'); pre.textContent = text; host.replaceChildren(pre) }).catch(() => { host.textContent = 'Preview unavailable; use download.' }) } },
  { id: 'office', matches: (file) => officeMime.test(file.mime) || /\.(docx?|xlsx?|pptx?|odt|ods|odp|rtf)$/i.test(file.name), render: downloadOnly('Office 文档') },
  { id: 'archive', matches: (file) => archiveMime.test(file.mime) || /\.(zip|7z|rar|tar|gz|tgz)$/i.test(file.name), render: downloadOnly('压缩包') },
]

export function pickPreviewHandler(file: ArtifactViewerFile, handlers: PreviewHandler[] = defaultPreviewHandlers): PreviewHandler | undefined {
  return handlers.find((handler) => handler.matches(file))
}
