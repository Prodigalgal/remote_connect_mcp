import { useEffect, useState } from 'react'
import { CheckCircleIcon, KeyIcon, TerminalIcon } from '../icons/Icons'
import { CopyButton } from '../components/CopyButton'
import { issueEnrollment, type ReleaseCatalog } from '../api'
import { psLiteral, shLiteral } from '../utils'

interface EnrollmentViewProps {
  adminToken: string
  releases: ReleaseCatalog | null
}

export function EnrollmentView({ adminToken, releases }: EnrollmentViewProps) {
  const [name, setName] = useState('')
  const [centerUrl, setCenterUrl] = useState(() => import.meta.env.VITE_RCM_CENTER_URL ?? window.location.origin.replace(/-console(?=\.)/, '-center'))
  const [lifetime, setLifetime] = useState('86400')
  const [version, setVersion] = useState('')
  const [mode, setMode] = useState<'command' | 'full'>('command')
  const [issued, setIssued] = useState<{ tokenId: string; token: string; expiresAt: string } | null>(null)
  const [message, setMessage] = useState('')
  const [commandTab, setCommandTab] = useState<'powershell' | 'bash'>('powershell')
  const [submitting, setSubmitting] = useState(false)

  // Auto-select preferred release version
  useEffect(() => {
    if (version || !releases?.items.length) return
    const preferred =
      releases.items.find((release) => !release.prerelease && release.assets.some((asset) => asset.available && asset.checksumAvailable)) ??
      releases.items.find((release) => release.assets.some((asset) => asset.available && asset.checksumAvailable)) ??
      releases.items[0]
    if (preferred) setVersion(preferred.version)
  }, [releases, version])

  const handleGenerate = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!adminToken.trim()) {
      setMessage('请在系统设置中配置 Admin Token')
      return
    }
    if (!name.trim()) {
      setMessage('请指定 Agent 标识名称')
      return
    }
    setSubmitting(true)
    setMessage('')
    try {
      const result = await issueEnrollment(adminToken, name.trim(), Number(lifetime))
      setIssued(result)
      setMessage('令牌生成成功！一次性令牌仅显示本次会话，请立即复制或下载。')
    } catch (err) {
      setMessage(err instanceof Error ? err.message : '生成注册令牌失败')
    } finally {
      setSubmitting(false)
    }
  }

  const downloadEnv = () => {
    if (!issued) return
    const content = `# Remote Connect MCP Agent - 完整环境安装与运行配置
# 生成时间: ${new Date().toLocaleString()}
# 令牌过期: ${issued.expiresAt}
# 说明：此环境变量文件包含了首次安装与 Agent 守护进程运行所需的全量参数。
# 令牌为一次性注册使用，Agent 完成首次接入后将在本地生成持久化公私钥与身份证明。

REMOTE_CONNECT_MCP_AGENT_CENTER_URL="${centerUrl.trim()}"
REMOTE_CONNECT_MCP_AGENT_ENROLLMENT_TOKEN="${issued.token}"
REMOTE_CONNECT_MCP_AGENT_NAME="${name.trim()}"
REMOTE_CONNECT_MCP_AGENT_VERSION="${version.trim()}"
REMOTE_CONNECT_MCP_AGENT_RELEASE_TAG="${releaseTag}"
REMOTE_CONNECT_MCP_AGENT_MODE="${mode}"

# ==============================================================================
# 首次一键安装脚本指令参考（可直接在终端中以管理员/root 权限执行）:
# ==============================================================================
# Windows (PowerShell 7):
# ${psCommand}
#
# Linux (Bash):
# ${shCommand}
`
    const url = URL.createObjectURL(new Blob([content], { type: 'text/plain;charset=utf-8' }))
    const link = document.createElement('a')
    link.href = url
    link.download = `remote-connect-mcp-agent-${name || 'node'}.env`
    link.click()
    URL.revokeObjectURL(url)
  }

  const selectedRelease = releases?.items.find((release) => release.version === version)
  const releaseTag = selectedRelease?.tag || `java-${version}`

  const psCommand = issued && version.trim() && name.trim()
    ? `$p=Join-Path $env:TEMP 'rcm-first-install.ps1'; Invoke-WebRequest -UseBasicParsing -Uri ${psLiteral(`https://raw.githubusercontent.com/Prodigalgal/remote_connect_mcp/${releaseTag}/scripts/first-install-java-agent.ps1`)} -OutFile $p; & $p -CenterUrl ${psLiteral(centerUrl.trim())} -AgentName ${psLiteral(name.trim())} -Version ${psLiteral(version.trim())} -ReleaseTag ${psLiteral(releaseTag)} -EnrollmentToken ${psLiteral(issued.token)} -Mode ${psLiteral(mode)}`
    : ''

  const shCommand = issued && version.trim() && name.trim()
    ? `$p=/tmp/rcm-first-install-java-agent.sh; curl -fsSL ${shLiteral(`https://raw.githubusercontent.com/Prodigalgal/remote_connect_mcp/${releaseTag}/scripts/first-install-java-agent.sh`)} -o "$p"; chmod 700 "$p"; sudo "$p" --center-url ${shLiteral(centerUrl.trim())} --agent-name ${shLiteral(name.trim())} --version ${shLiteral(version.trim())} --release-tag ${shLiteral(releaseTag)} --enrollment-token ${shLiteral(issued.token)} --mode ${shLiteral(mode)}`
    : ''

  return (
    <div>
      {/* Header */}
      <div style={{ marginBottom: '24px' }}>
        <h2 style={{ margin: 0, fontSize: '20px', fontWeight: 800, color: '#fff', letterSpacing: '-0.02em' }}>
          Agent 注册令牌与首次安装 (First Install)
        </h2>
        <p style={{ margin: '4px 0 0', fontSize: '13px', color: 'var(--text-secondary)' }}>
          为新终端签发与名称绑定的一次性接入凭证，生成全自动首次安装脚本，支持轻量与完整模式。
        </p>
      </div>

      {message && (
        <div className={`toast-bar ${message.includes('失败') ? 'error' : 'success'}`}>
          <CheckCircleIcon size={15} />
          <span>{message}</span>
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 320px), 1fr))', gap: '24px' }}>
        {/* Form Card */}
        <div className="card" style={{ padding: '24px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '16px' }}>
            <div
              style={{
                width: '34px',
                height: '34px',
                borderRadius: 'var(--radius-md)',
                background: 'rgba(99, 102, 241, 0.12)',
                border: '1px solid rgba(99, 102, 241, 0.25)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                color: 'var(--accent-primary)',
              }}
            >
              <KeyIcon size={17} />
            </div>
            <div>
              <h3 style={{ margin: 0, fontSize: '15px', fontWeight: 700, color: '#fff' }}>
                生成一次性接入令牌
              </h3>
              <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                成功注册一次后自动销毁失效
              </span>
            </div>
          </div>

          <form onSubmit={handleGenerate}>
            <div className="form-group">
              <label className="form-label">Center 地址 (Center URL)</label>
              <input
                type="text"
                className="form-input font-mono"
                value={centerUrl}
                onChange={(e) => setCenterUrl(e.target.value)}
                placeholder="https://remote-connect-mcp-center.example.invalid"
                required
              />
            </div>

            <div className="form-group">
              <label className="form-label">目标 Agent 名称</label>
              <input
                type="text"
                className="form-input"
                placeholder="例如 desktop-lab-02"
                value={name}
                onChange={(e) => setName(e.target.value)}
                required
              />
            </div>

            <div className="form-group">
              <label className="form-label">令牌有效时长 (TTL)</label>
              <select
                className="form-select"
                value={lifetime}
                onChange={(e) => setLifetime(e.target.value)}
              >
                <option value="3600">1 小时</option>
                <option value="21600">6 小时</option>
                <option value="86400">1 天 (默认)</option>
                <option value="604800">7 天</option>
                <option value="2592000">30 天</option>
              </select>
            </div>

            <div className="form-group">
              <label className="form-label">目标 Release 版本</label>
              <select
                className="form-select"
                value={version}
                onChange={(e) => setVersion(e.target.value)}
                disabled={!releases?.items.length}
                required
              >
                <option value="">{releases?.items.length ? '请选择版本' : '等待版本目录加载...'}</option>
                {releases?.items.map((release) => (
                  <option key={release.version} value={release.version}>
                    {release.version}{release.prerelease ? ' · 预发布' : ''}
                  </option>
                ))}
              </select>
            </div>

            <div className="form-group">
              <label className="form-label">安装模式与能力</label>
              <select
                className="form-select"
                value={mode}
                onChange={(e) => setMode(e.target.value as 'command' | 'full')}
              >
                <option value="command">Command + 文件传输（轻量级）</option>
                <option value="full">Command + Desktop + Browser（全功能完整版）</option>
              </select>
            </div>

            <button
              type="submit"
              className="btn btn-primary"
              style={{ width: '100%', marginTop: '10px' }}
              disabled={submitting || !adminToken || !name.trim()}
            >
              {submitting ? '正在签发...' : '生成安装令牌'}
            </button>

            {issued && (
              <div style={{ marginTop: '16px', padding: '12px', borderRadius: 'var(--radius-md)', background: '#07090f', border: '1px solid var(--border-default)' }}>
                <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginBottom: '4px' }}>
                  一次性注册令牌：
                </div>
                <div className="font-mono" style={{ color: 'var(--accent-sky)', wordBreak: 'break-all', fontSize: '11px', marginBottom: '10px' }}>
                  {issued.token}
                </div>
                <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
                  <CopyButton text={issued.token} label="复制令牌" size="sm" />
                  <button
                    type="button"
                    className="btn btn-secondary btn-sm"
                    onClick={downloadEnv}
                    title="下载包含 Center 接入点、版本与令牌的完整 Agent 环境变量配置文件"
                  >
                    下载完整配置 (.env)
                  </button>
                </div>
              </div>
            )}
          </form>
        </div>

        {/* Installation Instructions Card */}
        <div className="card" style={{ padding: '24px' }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '16px', flexWrap: 'wrap', gap: '10px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
              <div
                style={{
                  width: '34px',
                  height: '34px',
                  borderRadius: 'var(--radius-md)',
                  background: 'rgba(56, 189, 248, 0.1)',
                  border: '1px solid rgba(56, 189, 248, 0.25)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  color: 'var(--accent-sky)',
                }}
              >
                <TerminalIcon size={17} />
              </div>
              <div>
                <h3 style={{ margin: 0, fontSize: '15px', fontWeight: 700, color: '#fff' }}>
                  首次安装脚本 (first-install)
                </h3>
                <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                  自动下载 Native ZIP、校验 SHA-256 并完成服务注册
                </span>
              </div>
            </div>

            <div className="segmented-group">
              <button
                type="button"
                className={`segmented-btn ${commandTab === 'powershell' ? 'active' : ''}`}
                onClick={() => setCommandTab('powershell')}
              >
                <span>PowerShell</span>
              </button>
              <button
                type="button"
                className={`segmented-btn ${commandTab === 'bash' ? 'active' : ''}`}
                onClick={() => setCommandTab('bash')}
              >
                <span>Linux Bash</span>
              </button>
            </div>
          </div>

          {issued && version && name ? (
            <div>
              <div style={{ position: 'relative', marginBottom: '16px' }}>
                <pre
                  className="font-mono"
                  style={{
                    padding: '14px',
                    borderRadius: 'var(--radius-md)',
                    background: '#07090f',
                    border: '1px solid var(--border-default)',
                    color: '#cbd5e1',
                    fontSize: '11px',
                    lineHeight: '1.6',
                    whiteSpace: 'pre-wrap',
                    wordBreak: 'break-all',
                    margin: 0,
                    maxHeight: '260px',
                    overflowY: 'auto',
                  }}
                >
                  {commandTab === 'powershell' ? psCommand : shCommand}
                </pre>
              </div>

              <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
                <CopyButton
                  text={commandTab === 'powershell' ? psCommand : shCommand}
                  label={commandTab === 'powershell' ? '复制 PowerShell 脚本' : '复制 Bash 脚本'}
                  size="md"
                />
              </div>

              <div style={{ marginTop: '16px', padding: '12px', borderRadius: 'var(--radius-sm)', background: 'rgba(255, 255, 255, 0.02)', border: '1px solid var(--border-subtle)', fontSize: '11px', color: 'var(--text-tertiary)' }}>
                💡 <strong>提示：</strong>安装脚本会自动使用 <code>{releaseTag}</code> 发行分支的 <code>first-install-java-agent</code> 引导程序，自动拉取对应平台的 native 二进制文件，并在系统级别配置持久化守护进程。
              </div>
            </div>
          ) : (
            <div
              style={{
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                justifyContent: 'center',
                height: '240px',
                border: '1px dashed var(--border-subtle)',
                borderRadius: 'var(--radius-md)',
                color: 'var(--text-muted)',
                fontSize: '12px',
                textAlign: 'center',
                padding: '20px',
              }}
            >
              <TerminalIcon size={28} style={{ opacity: 0.3, marginBottom: '10px' }} />
              <div>在左侧填写 Agent 名称、选择 Release 版本并点击“生成安装令牌”以获取适配的首次安装脚本</div>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
