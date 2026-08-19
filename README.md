# Remote Connect MCP

[![CI](https://github.com/Prodigalgal/remote_connect_mcp/actions/workflows/ci.yml/badge.svg)](https://github.com/Prodigalgal/remote_connect_mcp/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Remote Connect MCP 是一个面向 ChatGPT Web 的远程编码 MCP Server。它在目标机器上运行，通过 Cloudflare Tunnel 暴露 Streamable HTTP MCP 端点，并使用一个固定 Bearer Token 鉴权。

项目不提供管理后台、不代理其他 MCP、不使用 OAuth，也不对模型增加路径、命令或操作白名单。鉴权通过后，能力边界由上游模型和目标机器账户决定。

> [!CAUTION]
> Bearer Token 等同于目标机器上的远程代码执行权限。任何持有者都能读取文件、修改文件、执行命令并访问网络。只通过 HTTPS 使用，使用足够长的随机令牌，禁止提交真实 `remote_connect_mcp.env`，泄露后立即停机并更换令牌。

## 功能

| 工具 | 用途 |
| --- | --- |
| `server_info` | 查看平台、版本和默认工作目录 |
| `list_files` | 分页列出文件和目录 |
| `search_text` | 按普通文本或正则检索 |
| `read_file` | 分页读取文本或二进制文件 |
| `apply_patch` | 原子新增、修改、移动或删除文件 |
| `exec_command` | 前台或后台执行任意命令 |
| `process` | 读取输出、写 stdin、关闭 stdin、停止任务 |
| `view_image` | 将本地图片返回给模型检查 |

工具描述和 JSON Schema 保持精简，适合上下文较小的 Web 对话。文件、搜索与命令输出均支持分页；完整命令输出落到目标机器的临时日志，不会长期堆在 MCP 内存中。交互日志仅记录 MCP 方法、工具名、状态和耗时，不记录工具参数、文件内容或令牌。

## 工作方式

```text
ChatGPT Web
    |
    | HTTPS + Authorization: Bearer <token>
    v
Cloudflare Named Tunnel
    |
    | http://127.0.0.1:8765/mcp
    v
Remote Connect MCP -> files / shell / background processes
```

目标机器不需要开放入站端口。Remote Connect MCP 和 `cloudflared` 都只向外建立连接。

## 快速开始

支持 Windows amd64/arm64 与 Linux amd64/arm64。目标机器没有 Go 时，从 [Releases](https://github.com/Prodigalgal/remote_connect_mcp/releases) 下载对应压缩包；源码目录缺少预编译文件时，启动脚本也可以调用本机 Go 自动构建。

### 1. 创建配置

Windows PowerShell：

```powershell
Copy-Item remote_connect_mcp.env.example remote_connect_mcp.env
notepad remote_connect_mcp.env
```

Linux：

```sh
cp remote_connect_mcp.env.example remote_connect_mcp.env
chmod 600 remote_connect_mcp.env
```

全自动 Cloudflare 模式最少只需填写四项：

```dotenv
REMOTE_CONNECT_MCP_TOKEN=replace-with-a-long-fixed-bearer-token
REMOTE_CONNECT_MCP_CF_AUTOPROVISION=1
CLOUDFLARE_API_TOKEN=replace-with-a-scoped-cloudflare-api-token
REMOTE_CONNECT_MCP_PUBLIC_HOSTNAME=remote-connect-mcp.example.com
```

`REMOTE_CONNECT_MCP_TUNNEL_NAME` 留空时会由公网域名自动生成。`CLOUDFLARE_ACCOUNT_ID`、`CLOUDFLARE_ZONE_ID` 和 `CLOUDFLARE_ZONE_NAME` 均可留空。

### 2. 创建 Cloudflare API Token

在 Cloudflare API Token 中授予：

- Account / Cloudflare Tunnel / Edit
- Zone / DNS / Edit
- Zone / Zone / Read

资源范围只选择实际使用的 Account 和 Zone。Zone Read 用于根据 `REMOTE_CONNECT_MCP_PUBLIC_HOSTNAME` 自动发现 Zone ID 和 Account ID；如果不愿授予 Zone Read，可以在配置中同时填写 `CLOUDFLARE_ACCOUNT_ID` 与 `CLOUDFLARE_ZONE_ID`。

全自动启动会按顺序执行：

1. 从域名发现 Zone 和 Account。
2. 创建或复用同名的 remotely-managed Named Tunnel。
3. 写入该 Tunnel 的 ingress 配置。
4. 获取运行用 Tunnel Token，但不写回磁盘。
5. 创建或更新指向 `<tunnel-id>.cfargotunnel.com` 的代理 CNAME。
6. 下载并校验固定版本的 `cloudflared`，然后启动 MCP 与 Tunnel。

请给本项目使用独立的 Tunnel 名称。复用同名 Tunnel 时，程序会重写它的 ingress 配置；如果公网主机名已经存在非 CNAME 记录，程序会停止并要求人工处理，不会自动删除记录。

### 3. 启动

Windows：

```text
双击 start-remote_connect_mcp.cmd
```

该 CMD 窗口会同时显示 MCP 交互日志、连接 URL 与 Bearer Token。保持窗口打开；按 `Ctrl+C` 或关闭窗口会停止 MCP、Tunnel 及 MCP 启动的后台任务，不会再弹出额外的常驻黑框。Windows 命令默认优先使用 PowerShell 7，找不到 `pwsh.exe` 时回退到 `cmd.exe`。

Linux：

```sh
chmod +x start-remote_connect_mcp.sh
./start-remote_connect_mcp.sh
```

启动脚本所在目录只是默认工作目录，不是访问边界。通过鉴权的模型可以使用绝对路径访问整台机器上当前系统账户有权限访问的路径，也可以执行任意命令。`REMOTE_CONNECT_MCP_DEFAULT_CWD` 只影响省略路径或 `cwd` 时的相对路径解析。

## 连接 ChatGPT

在 ChatGPT Business 工作区启用开发者模式并创建 MCP 连接器：

| 设置 | 值 |
| --- | --- |
| 服务器 URL | 启动窗口显示的 `https://你的域名/mcp` |
| 身份验证 | 访问令牌 / API 密钥 |
| 标头方案 | Bearer，中文界面通常显示“持有者” |
| 令牌 | `REMOTE_CONNECT_MCP_TOKEN` 的原始值，不要添加 `Bearer ` 前缀 |

成功后，目标机器日志会依次出现 `initialize`、`notifications/initialized`、`tools/list`。建议再让 ChatGPT 调用 `server_info`，确认返回的 `default_cwd` 和目标机器一致，并确认 `scope` 明确显示整机访问。

一次完整的协议检查应满足：

- 不带令牌访问 `/mcp` 返回 HTTP `401`。
- 正确令牌执行 `initialize` 返回 HTTP `200`。
- `notifications/initialized` 返回 HTTP `202`。
- `tools/list` 返回 8 个工具。

## 配置文件

默认读取启动目录下的 `remote_connect_mcp.env`。系统环境变量优先于文件中的同名值；也可以用 `REMOTE_CONNECT_MCP_ENV_FILE` 指向其他文件。配置文件支持空行、`#` 注释、`KEY=VALUE`、可选的 `export` 前缀和单/双引号值，不执行变量插值。

真实的 `remote_connect_mcp.env` 已被 `.gitignore` 排除。项目不会自动生成 Bearer Token，也不会把 Cloudflare API Token、Tunnel Token 写回配置文件或日志。

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `REMOTE_CONNECT_MCP_TOKEN` | 无，必填 | 固定 Bearer Token |
| `REMOTE_CONNECT_MCP_CF_AUTOPROVISION` | `0` | `1` 表示自动维护 Cloudflare Tunnel 与 DNS |
| `CLOUDFLARE_API_TOKEN` | 空 | 自动配置使用的 Cloudflare API Token |
| `REMOTE_CONNECT_MCP_PUBLIC_HOSTNAME` | 空 | 公网主机名，不带协议、端口和路径 |
| `REMOTE_CONNECT_MCP_TUNNEL_NAME` | 由主机名生成 | 自动创建或复用的专用 Tunnel 名称 |
| `CLOUDFLARE_ACCOUNT_ID` | 自动发现 | 可选覆盖值 |
| `CLOUDFLARE_ZONE_ID` | 自动发现 | 可选覆盖值；与 Account ID 同时填写可免 Zone Read |
| `CLOUDFLARE_ZONE_NAME` | 自动发现 | 可选，限制域名发现到指定 Zone |
| `REMOTE_CONNECT_MCP_DEFAULT_CWD` | 启动目录 | 默认工作目录，不是沙箱或访问边界 |
| `REMOTE_CONNECT_MCP_HOST` | `127.0.0.1` | 本地监听地址 |
| `REMOTE_CONNECT_MCP_PORT` | `8765` | 本地监听端口 |
| `REMOTE_CONNECT_MCP_MAX_REQUEST_BYTES` | `16777216` | MCP HTTP 请求体技术上限 |
| `REMOTE_CONNECT_MCP_CLOUDFLARED` | 自动发现/下载 | 自定义 `cloudflared` 可执行文件路径 |
| `REMOTE_CONNECT_MCP_TUNNEL_TOKEN` | 空 | 手工 Named Tunnel 模式使用 |
| `REMOTE_CONNECT_MCP_PUBLIC_URL` | 空 | 手工模式下用于显示的公网基础 URL |
| `REMOTE_CONNECT_MCP_TUNNEL_CONFIG` | 空 | 使用本地 `cloudflared` 配置文件 |
| `REMOTE_CONNECT_MCP_QUICK_TUNNEL` | `0` | `1` 启用临时 `trycloudflare.com` Tunnel |

轮换 MCP Token 时，先停止启动脚本，修改 `remote_connect_mcp.env`，再重新启动并同步更新 ChatGPT 连接器。旧版本可能留下的本地 token 文件不会被读取。

## ChatGPT 审批

MCP 服务端不能绕过 ChatGPT Web 的动作确认。若连接器设置中提供 `Always allow`，可在第一次审批卡片中选择它；也可以在 ChatGPT 的 Settings → Apps → App Preferences 中把该应用的 `Ask permission` 调整为允许自动执行。Business 工作区的持久化权限可能由管理员统一控制；如果界面没有 `Always allow`，需要管理员在 Workspace settings → Apps → Action control 中允许相应操作。高风险动作仍可能被 ChatGPT 强制确认或阻止。

这只改变 ChatGPT 何时询问，不会扩大 MCP 的权限。当前服务端已经是整机能力模型：持有有效 Bearer Token 的调用可以访问当前账户有权限的任意本地路径并执行任意命令。

## 手工 Tunnel 与临时模式

不希望 API Token 自动修改 Cloudflare 时，可以提前在控制台创建 Named Tunnel，并配置 Public Hostname 指向 `http://127.0.0.1:8765`：

```dotenv
REMOTE_CONNECT_MCP_TOKEN=your-fixed-token
REMOTE_CONNECT_MCP_CF_AUTOPROVISION=0
REMOTE_CONNECT_MCP_TUNNEL_TOKEN=your-cloudflare-tunnel-token
REMOTE_CONNECT_MCP_PUBLIC_URL=https://remote-connect-mcp.example.com
```

临时测试可以使用 Quick Tunnel：

```dotenv
REMOTE_CONNECT_MCP_TOKEN=your-fixed-token
REMOTE_CONNECT_MCP_CF_AUTOPROVISION=0
REMOTE_CONNECT_MCP_QUICK_TUNNEL=1
```

Quick Tunnel 地址每次可能变化，不适合作为长期 ChatGPT 连接器。

## 从源码构建

需要 Go 1.25 或更高版本。

Windows PowerShell：

```powershell
.\scripts\build-all.ps1
```

Linux：

```sh
chmod +x scripts/build-all.sh
./scripts/build-all.sh
```

构建脚本先运行全部测试，再生成：

```text
dist/
  windows-amd64/remote_connect_mcp.exe
  windows-arm64/remote_connect_mcp.exe
  linux-amd64/remote_connect_mcp
  linux-arm64/remote_connect_mcp
```

常规开发检查：

```sh
go test ./...
go vet ./...
```

推送 `v*` 标签后，GitHub Actions 会测试、交叉编译并生成四个平台的独立压缩包和 SHA256 文件。

## 安全设计

- MCP HTTP 端点使用常量时间比较验证 Bearer Token。
- MCP 与源站默认只监听 `127.0.0.1`。
- Cloudflare API Token 和 MCP Token 不传给 `cloudflared` 子进程；Tunnel Token 仅作为 Named Tunnel 的启动凭据使用。
- `cloudflared` 与 MCP 日志会过滤已知令牌，不记录工具参数或工具输出正文。
- Windows 使用 Job Object，Linux 使用 parent-death signal，主进程退出时清理子进程。
- 命令输出写入权限受限的本机状态目录，并支持分页读取。
- 文件写入采用临时文件加原子替换。

这不是多租户权限系统。项目不包含角色、审批、路径隔离、命令限制、OAuth 或管理后台。若需要这些能力，应在 Remote Connect MCP 之前增加独立的身份和授权层。

## 参考

- [Cloudflare Tunnel API](https://developers.cloudflare.com/api/resources/zero_trust/subresources/tunnels/subresources/cloudflared/methods/create/)
- [Cloudflare Tunnel configuration API](https://developers.cloudflare.com/api/resources/zero_trust/subresources/tunnels/subresources/cloudflared/subresources/configurations/methods/update/)
- [Cloudflare DNS Records API](https://developers.cloudflare.com/api/resources/dns/subresources/records/methods/create/)
- [Model Context Protocol](https://modelcontextprotocol.io/)

调研参考了 `coding-tools-mcp`、`chatgpt-local-coder`、`filesystem-mcp`、`mcp-shell`、Serena、Git MCP 和 MCP 官方 Servers。实现使用官方 Go SDK，没有直接复制这些仓库的代码，详见 [NOTICE](NOTICE)。

## License

[MIT](LICENSE)
