# Remote Connect MCP

[![CI](https://github.com/Prodigalgal/remote_connect_mcp/actions/workflows/ci.yml/badge.svg)](https://github.com/Prodigalgal/remote_connect_mcp/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Remote Connect MCP 是一个面向 ChatGPT Web 的中心化多机器控制系统。ChatGPT 只连接一个 MCP Gateway；每台目标机器运行一个主动连接 Center 的 Agent。Center 同时提供机器注册、持久化异步任务、断线续传和 Web 控制台。

项目不代理其他 MCP，也不在 Agent 内设置路径或命令白名单。通过鉴权后，Agent 能以其系统账户权限访问整台机器并执行任意命令。

> [!CAUTION]
> MCP Token、管理 Token、Enrollment Token 和 Agent 凭据都属于高权限秘密。MCP Token 等同于所有已注册机器上的远程代码执行权限。请只通过 HTTPS 使用，将真实值保存在 Secret 或权限为 `0600` 的配置文件中，禁止提交到 Git。

## 架构

```text
ChatGPT Web
    |
    | HTTPS + Bearer Token
    v
gateway.example.invalid/mcp
    |
    v
Center / MCP Gateway / Task Store / Web Console
    ^
    | Agent 主动长轮询；无入站端口
    +--------- Agent A / Agent B / Agent C / Agent D
```

公网入口统一以 `remote-connect-mcp-*` 开头：

| 用途 | 地址 |
| --- | --- |
| ChatGPT MCP | `https://gateway.example.invalid/mcp` |
| Web 控制台 | `https://console.example.invalid/console/` |
| Agent 注册与任务通道 | `https://agent.example.invalid` |

Center 有公网 K8S 入口，因此不使用 Cloudflare Tunnel。Cloudflare 仅作为权威 DNS，三个记录均为 DNS-only，直接指向同一个 Envoy Gateway 和 Center Service，不经过 Cloudflare 代理、CDN 或 WAF。

## 为什么命令不会再卡住

命令执行和 MCP HTTP 请求已经解耦：

1. `command_start` 把命令写入 Center，立即返回持久化 `task_id`。
2. Agent 领取任务后在本机独立进程中执行，stdout/stderr 持续写入本机受限日志。
3. Agent 按字节偏移上传输出；重复分片不会重复追加。
4. Center 或网络中断时命令继续运行，Agent 使用指数退避和随机抖动自动重连。
5. 重连后 Agent 从 Center 已确认的偏移继续补传输出和最终状态。
6. ChatGPT 使用 `task_wait` 或 `task_output` 获取有界输出，不需要让一次 HTTP 调用等待整个命令完成。

Agent 服务本身重启时，systemd 会终止其子进程；Agent 重启后会把未完成任务报告为失败，避免静默重复执行非幂等命令。普通网络断线不会终止命令。

## MCP 工具

| 工具 | 用途 |
| --- | --- |
| `machines_list` | 列出机器 ID、平台和在线状态 |
| `machine_info` | 查看单台机器的心跳、默认目录和 Agent 版本 |
| `command_start` | 在指定机器创建异步命令任务 |
| `task_wait` | 最多等待 20 秒，返回状态变化或下一页输出 |
| `task_output` | 按字节游标分页读取完整任务输出 |
| `task_cancel` | 取消排队或正在运行的任务 |

工具数量固定为 6 个，机器数量不会扩大 ChatGPT 的工具元数据。每次机器操作都必须显式传入 `machine_id`。

## 升级兼容契约

ChatGPT 连接器使用固定 `/mcp` URL、固定 Bearer Token 和上述 6 个稳定工具。Center、Agent、控制台、存储实现和机器数量升级时不得要求重新创建 ChatGPT 连接器。

- Center 通过 GitOps 固定镜像摘要升级；Service、HTTPRoute、PVC、域名和 Secret 名称保持不变。
- Agent 先在少量机器试运行，再按批次升级；心跳会持续刷新实际版本、平台和默认目录。
- Center 必须兼容至少上一版 Agent；Agent 元数据使用可忽略的 HTTP Header 上报，使旧 Center 能安全忽略新字段。
- 新内部能力优先扩展 Center/Agent 协议和控制台，不新增或重命名 MCP 工具。
- 只有 MCP Token 泄露需要修改 ChatGPT 认证；确实改变工具 Schema 时，ChatGPT 可能需要重新扫描工具，但仍不更换 URL。

## Web 控制台

控制台使用独立的 Center Admin Token，支持：

- 查看机器在线状态、平台、Agent 版本和最后心跳；
- 创建命令任务并指定机器、工作目录和超时；
- 查看最近任务、执行状态和完整输出；
- 取消排队或运行中的任务；
- 每 3 秒自动刷新机器、任务和当前输出。

管理 Token 只保存在浏览器当前标签页的 `sessionStorage`，关闭标签页后消失。

## Center 配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `REMOTE_CONNECT_MCP_CENTER_HOST` | `0.0.0.0` | 监听地址 |
| `REMOTE_CONNECT_MCP_CENTER_PORT` | `8080` | HTTP 端口 |
| `REMOTE_CONNECT_MCP_CENTER_STATE_DIR` | `/var/lib/remote-connect-mcp-center` | 机器、任务和输出状态目录 |
| `REMOTE_CONNECT_MCP_CENTER_MCP_TOKEN` | 必填 | ChatGPT Bearer Token |
| `REMOTE_CONNECT_MCP_CENTER_ADMIN_TOKEN` | 必填 | Web 控制台/API Token |
| `REMOTE_CONNECT_MCP_CENTER_ENROLLMENT_TOKEN` | 必填 | Agent 首次注册 Token |
| `REMOTE_CONNECT_MCP_CENTER_CONSOLE_HOSTNAME` | 空 | 控制台域名，用于根路径跳转 |

Center 使用一个 RWO PVC 和单副本 `Recreate` Deployment。状态文件采用临时文件、`fsync` 和原子替换；任务输出独立存储并分页读取。

Kubernetes 模板位于 [`deploy/k8s/center`](deploy/k8s/center)。真实 Token 必须通过集群外私密来源创建为 `remote-connect-mcp-secrets`，不要提交 `secret.example.yaml` 的替换版本。

## Agent 配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `REMOTE_CONNECT_MCP_AGENT_CENTER_URL` | 必填 | Agent Center 地址 |
| `REMOTE_CONNECT_MCP_AGENT_ENROLLMENT_TOKEN` | 必填 | 首次注册和凭据恢复 Token |
| `REMOTE_CONNECT_MCP_AGENT_NAME` | 主机名 | 稳定机器名称；同名重装会复用机器记录并轮换凭据 |
| `REMOTE_CONNECT_MCP_AGENT_DEFAULT_CWD` | 启动目录 | 相对工作目录的基准，不是权限边界 |
| `REMOTE_CONNECT_MCP_AGENT_STATE_DIR` | Linux `/var/lib/remote-connect-mcp-agent` | Agent 身份和本机任务输出目录 |
| `REMOTE_CONNECT_MCP_AGENT_MAX_CONCURRENCY` | `1` | 同时运行任务数，范围 1–32 |

Agent 首次注册后获得每机独立 Token，只保存其 SHA-256 摘要到 Center，原始值以 `0600` 权限保存在 Agent 状态目录。Center 吊销或重置凭据后，Agent 会自动重新注册。

Linux systemd 模板和安装脚本位于 [`deploy/systemd`](deploy/systemd) 与 [`scripts/install-agent.sh`](scripts/install-agent.sh)。Agent 不监听端口，不需要域名、Cloudflare Tunnel 或入站防火墙规则。

### Windows 服务

Windows AMD64/ARM64 使用同一个 Agent 二进制，并以原生 Windows SCM 服务运行。请在管理员 PowerShell 7 中执行：

```powershell
./scripts/install-agent.ps1 `
  -BinaryPath ./remote-connect-mcp-agent.exe `
  -EnrollmentToken '<center enrollment token>' `
  -AgentName '<Headscale given_name>' `
  -DefaultCwd 'C:\'
```

服务名为 `RemoteConnectMCPAgent`，默认自动启动，异常退出按 5/15/30 秒重启。状态、进程和日志：

```powershell
Get-Service RemoteConnectMCPAgent
Get-CimInstance Win32_Service -Filter "Name='RemoteConnectMCPAgent'"
Get-Content "$env:ProgramData\RemoteConnectMCPAgent\agent.log" -Tail 100
```

安装器把 Center 配置写入服务专属注册表环境，状态和日志目录 ACL 仅允许 SYSTEM 与本机管理员访问。卸载时默认保留机器身份；需要同时清除身份时增加 `-PurgeState`：

```powershell
./scripts/install-agent.ps1 -Uninstall
./scripts/install-agent.ps1 -Uninstall -PurgeState
```

## 连接 ChatGPT

在 ChatGPT Business 工作区开发者模式中创建远程 MCP：

| 设置 | 值 |
| --- | --- |
| 服务器 URL | `https://gateway.example.invalid/mcp` |
| 身份验证 | 访问令牌 / API 密钥 |
| 标头方案 | Bearer，中文界面通常显示“持有者” |
| 令牌 | Center 的 MCP Token 原始值，不添加 `Bearer ` 前缀 |

当前实现兼容 MCP `2026-07-28` 无会话协议，并在 `/mcp` 和 Gateway 根路径提供相同的 Bearer 鉴权 MCP Handler，以兼容 ChatGPT 的发现扫描。

MCP 服务端不能强制绕过 ChatGPT Web 自己的动作确认；是否显示“始终允许”由 ChatGPT Business 工作区策略决定。

## 构建与发布

需要 Go 1.25 或更高版本：

```sh
go test -count=1 ./...
go vet ./...
go build ./cmd/remote-connect-mcp-center
go build ./cmd/remote-connect-mcp-agent
```

CI 在 Windows/Linux 上运行测试和静态检查，并交叉构建 Windows/Linux AMD64/ARM64 的 Center 和 Agent。

`publish-center.yml` 将 Center 构建为 `linux/amd64`、`linux/arm64` Docker manifest，并推送到 `docker.io/speedproxy/remote-connect-mcp-center`。推送 `v*` 标签会生成四个平台的 Center 和 Agent 发布包及 SHA-256 文件。

## 从 v0.3 迁移

v0.3 是每台机器直接暴露 MCP 的单机架构。迁移到 Center 后：

1. 部署 Center、HTTPS Route、证书和三个 `remote-connect-mcp-*` DNS 记录；
2. 在目标机器安装 Agent 并确认控制台显示在线；
3. 用 Center MCP Gateway 替换 ChatGPT 中的旧连接器；
4. 验证跨四台机器的命令、长任务、输出分页和断线恢复；
5. 停止并删除旧 `remote_connect_mcp.service`、旧 Cloudflare Tunnel 和旧 DNS 记录。

旧服务只在新链路完成端到端验收后移除，避免迁移过程中失去管理入口。

## License

[MIT](LICENSE)
