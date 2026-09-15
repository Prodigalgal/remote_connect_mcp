# Java 25 实现

这是 RCM Java 25 迁移实现，包含：

- `protocol`：跨 Center/Agent/Transport 的协议记录和边界校验；
- `center`：Spring Boot 4.x Center、异步 MCP、Bearer 鉴权、注册/HTTPS 长轮询、任务/输出/工件队列、项目与 Git worktree 编排、Admin API 和 PostgreSQL + Liquibase 适配；
- `agent`：无 Spring 的 Java command-agent，支持虚拟线程命令执行、磁盘 spool 与异步重试、Desktop IPC 客户端、Browser 本机适配器桥接、断线重连和身份持久化；`agent` Native Image 不包含 AWT 桌面实现。
- `desktop`：独立的 `rcm-desktop-companion` Java Native Image，仅在用户会话中提供 AWT/Robot 截图与输入能力，不向 Center 注册第二个身份。
- `browser`：独立的 `rcm-browser-agent` Java Native Image，按 browser 任务编排本机 Playwright/Patchright/Comoufox 适配器，不向 Center 注册第二个身份。

根目录 Go Center/Agent 仍作为兼容基线保留，Java 组件可独立进行协议验收。`--check-config` 只校验配置；`--register-once` 注册并把 Center 返回的日常身份写入 `STATE_DIR/identity.json`；`--run`（或无参数，供 Windows 启动任务/Linux systemd 使用）启动注册、心跳和异步任务循环，支持并发槽位、输出游标、超时、取消、桌面工件、Browser Worker 和 Center 控制的 canary 自升级。命令输出先落入有界本机 spool，再由独立虚拟线程上传；单任务和 Agent 级聚合输出上限同时生效，达到聚合上限时普通任务仍继续执行并仅截断后续输出；Center 暂时不可达时不会终止子进程，但 durable 日志达到上限会由看门器终止并标记失败。

## 构建与烟测

本仓库不在开发机或目标宿主机编译。GitHub Actions 会自动执行 Java/React/PostgreSQL 门禁，并在
匹配 OS/CPU runner 上生成 Native Image。需要人工复核时，只下载 Actions 工件，再将其路径传给
`scripts/smoke-java.ps1/.sh`；烟测只启动临时内存模式 Center，使用临时令牌并在结束后清理进程，
不会触发 Gradle、Native Image 或前端构建。

运行 Java Agent（PowerShell 示例，生产安装推荐使用 `scripts/install-java-agent.ps1`）：

```powershell
$env:REMOTE_CONNECT_MCP_AGENT_CENTER_URL = 'https://remote-connect-mcp-agent.example.invalid'
$env:REMOTE_CONNECT_MCP_AGENT_ENROLLMENT_TOKEN = '<one-time-enrollment-token>'
$env:REMOTE_CONNECT_MCP_AGENT_NAME = 'dev-agent'
$env:REMOTE_CONNECT_MCP_AGENT_STATE_DIR = 'C:\ProgramData\remote-connect-mcp-agent'
$env:REMOTE_CONNECT_MCP_AGENT_BINARY_PATH = 'C:\Program Files\Remote Connect MCP Agent\rcm-agent.exe'
$env:REMOTE_CONNECT_MCP_AGENT_SERVICE_NAME = 'RemoteConnectMCPAgent'
$env:REMOTE_CONNECT_MCP_AGENT_BROWSER_BINARY = 'C:\Program Files\Remote Connect MCP Agent\browser\rcm-browser-agent.exe'
java -jar .\agent\build\libs\agent-0.1.0-SNAPSHOT.jar --run
```

`install-java-agent.ps1` / `install-java-agent.sh` 会先用一次性 Enrollment Token 调用
`--register-once`，确认 `identity.json` 写入成功后再创建长期运行配置，并且不把 Enrollment
Token 写入启动任务环境；日常通信只使用 `identity.json` 中的每机 Token。Java Agent 默认使用
25 秒 HTTP 长轮询，在任务、取消、配置、升级事件或服务端 deadline 时才返回；设置
`REMOTE_CONNECT_MCP_AGENT_LONG_POLL_SECONDS=0` 才恢复旧版固定间隔兼容模式。不要把该文件
或环境变量提交到 Git。

Windows 开启 `-DesktopEnabled` 时，安装器还会注册一个当前用户登录触发的
`rcm-desktop-companion --desktop-companion` 任务（旧安装若仍调用 Agent 的兼容入口，会由它转发到同目录伴侣）。伴侣进程只绑定 `127.0.0.1`，在
`STATE_DIR/desktop/desktop-companion.json` 发布本机端点；Windows 计划任务会显式传入同一
`STATE_DIR`，command-agent 通过该端点请求
截图、区域截图、屏幕枚举、启动、单击/双击/右击、移动指针、拖拽、组合按键、剪贴板、窗口聚焦和文本输入，不会新增 Center 身份。若用户会话未登录，Desktop
任务会明确返回不可用，而不会让 SYSTEM 会话伪装成桌面。

`nativeCompile` 需要 `JAVA_HOME` 指向带 `native-image` 的 GraalVM 25.x 或 Liberica NIK 25.x，但开发机和目标宿主机不执行该任务；正式构建由 GitHub Actions 在匹配 OS/CPU 架构的 runner 完成。Linux/Windows 发布的 command-agent、desktop-companion 和 browser-agent 都是各自包含 Native Image 运行库的平铺 ZIP：`remote-connect-mcp-agent-*`、`remote-connect-mcp-desktop-*`、`remote-connect-mcp-browser-*`。Browser Agent 不注册 Center 身份，由 command-agent 按 browser cap 按任务启动并在超时/取消时回收。Windows command-agent 可把 `remote-connect-mcp-agent-<version>-windows-amd64.zip` 传给 `scripts/install-java-agent.ps1 -BinaryPath`，并把 desktop/browser ZIP 分别传给对应的 companion 参数；Linux 可把三个 ZIP 分别通过 `REMOTE_CONNECT_MCP_AGENT_BINARY`、`REMOTE_CONNECT_MCP_AGENT_DESKTOP_BINARY` 和 `REMOTE_CONNECT_MCP_AGENT_BROWSER_BINARY` 交给 `scripts/install-java-agent.sh`，安装器会先校验同目录 `.sha256`（若提供）再复制 ELF 与旁路库；桌面伴侣仍需在用户会话中通过桌面环境自启动。完整 Linux tar 包同时包含三个 bundle 目录和安装脚本；传入原始可执行文件时也会自动带上其同目录 `.so`/DLL。
`scripts/build-java.*`、`scripts/build-native.*` 和 `scripts/build-all.*` 仅供 GitHub Actions 使用，
在本机直接运行会安全退出并提示提交到 Actions；`java/Dockerfile.*.native` 与 `web/Dockerfile` 也要求
CI 构建参数。Gradle 根配置还会拦截本机的 `build/test/compile/jar/native` 等任务；只读的
`tasks`、`dependencies` 查询不受影响。不要在目标主机安装或运行 Gradle/GraalVM。
Agent 默认使用 HTTPS 长轮询：单次 `/agent/v1/poll?wait_ms=25000` 会在任务、取消、配置或升级事件时立即返回，空闲只由服务端 deadline 结束，不再叠加固定 sleep。设置 `REMOTE_CONNECT_MCP_AGENT_WAKE_TRANSPORT=websocket` 后会额外连接 Center 的 `/agent/v1/ws`，只接收有界 `wake` 提示以进一步降低事件延迟；任务领取、输出、工件和 Token 校验仍走 HTTPS。长轮询/WebSocket 均不可用时才按指数退避重试。Center 端使用 `RCM_CENTER_AGENT_WEBSOCKET_ENABLED=true` 开启该可选端点。每次心跳的 runtime descriptor 还公布单任务和 Agent 级进程预算，旧 Center/Agent 缺失总预算字段时按有界默认值兼容。

Native Agent 烟测可设置 `RCM_SMOKE_RESOURCE_REPORT=/tmp/rcm-agent-resource.json`（Windows PowerShell
使用 `-ResourceReport`），脚本会在 Agent 在线和命令闭环期间采样工作集峰值；该文件只用于 CI 资源回归，
不应提交到仓库或作为运行时限制依据。

资源回收约束：command-agent 在 `STATE_DIR/agent.lock` 上单实例运行；Browser Worker 达到
`REMOTE_CONNECT_MCP_AGENT_MAX_BROWSER_WORKERS` 后不会再领取 browser 任务，超时/取消会终止整个子进程树并删除临时文件；Desktop companion 在 `STATE_DIR/desktop/desktop-companion.lock` 上单实例运行，最多 4 个 IPC 请求和 16 个活动启动进程，已退出的进程通过 `ProcessHandle.onExit()` 自动释放名额。没有用户会话时，command-agent 的桌面启动回退同样受 `REMOTE_CONNECT_MCP_AGENT_DESKTOP_MAX_LAUNCHED_PROCESSES`（1–64）限制。`REMOTE_CONNECT_MCP_AGENT_MAX_TOTAL_CHILD_PROCESSES`（默认按并发计算、封顶 256；可配置 1–4096）还会在 command、desktop 直启和 browser 任务之间共享一个 Agent 级进程预算。任务监督器观察到新的子进程时占用预算，任务终止/正常退出和桌面进程 `onExit()` 会释放预算；Agent 关闭、重启或升级时，直启预算会停止接受新进程、终止仍登记的 GUI 进程并释放名额；预算耗尽的任务 fail-closed 并回传可解释错误。Windows 启动任务和 Linux systemd 仍应配置运行管理器的重启/资源上限，不能用无限制的 `maxConcurrency` 代替容量规划。

Center 持久化统一使用 PostgreSQL + Liquibase，不使用 Flyway。默认 `RCM_CENTER_PERSISTENCE_MODE=memory` 只用于无数据库协议回归；生产设置 `RCM_CENTER_PERSISTENCE_MODE=postgres`、`RCM_CENTER_DATABASE_URL`、`RCM_CENTER_DATABASE_USERNAME` 和 `RCM_CENTER_DATABASE_PASSWORD`。任务、输出游标和有界截图工件均写入事务存储。生产通过 `rcm-center --migrate` 或 `deploy/k8s/java-center/migration-job.yaml` 单独执行 Liquibase，Center Pod 设置 `RCM_CENTER_LIQUIBASE_ENABLED=false`。

项目 API 位于 `/api/v1/admin/projects`，也可由 MCP `project` 工具调用。注册时只提交目标 Agent
上的绝对路径；创建/移除 worktree 会返回一个异步任务 ID，只有创建任务完成后，控制台的任务编排器才允许选择该
worktree 作为 cwd。项目与 worktree 记录由 Liquibase `007-projects-worktrees` 管理，Agent 名称唯一性由
`008-agent-name-unique` 保证，Center 不读取源码或 Cookie。

旧 Go Center 切换到 PostgreSQL 时，先备份并停止旧 Center，再执行 `rcm-center --migrate`，随后执行 `rcm-center --import-go <旧状态目录或 state.json>`。导入会保留机器/Agent 的 SHA-256 身份摘要、任务状态、输出和工件；MCP/Admin Token 仍必须通过环境 Secret 提供，旧升级活动需暂停后在新 Center 重新创建。

Browser Agent 需要在目标主机配置 `REMOTE_CONNECT_MCP_AGENT_BROWSER_ADAPTER`，指向受控的 Playwright/Patchright/Comoufox 本机 Worker。发布包同时提供独立的 `rcm-browser-agent` Native 二进制；command-agent 会优先启动同目录 Browser Agent（也可用 `REMOTE_CONNECT_MCP_AGENT_BROWSER_BINARY` 指定路径），由它再启动适配器。每个任务会在 Agent 状态目录创建一个短生命周期 JSON 请求文件，并通过 `RCM_BROWSER_TASK_REQUEST_FILE` 环境变量传给 Worker；`RCM_BROWSER_TASK_COMMAND` 仍保留用于兼容旧 Worker。Worker 只回传有界 stdout；浏览器 profile、Cookie、CDP 凭据不离开主机，任务结束后请求文件立即删除。
仓库提供一个不绑定具体浏览器包的参考 Worker：`scripts/browser-worker.mjs`。在目标机的独立目录安装所需运行时（例如 `npm install playwright`），再将适配器设置为 `node <绝对路径>/scripts/browser-worker.mjs`；通过 `RCM_BROWSER_ENGINE=playwright|patchright|comoufox` 和 `RCM_BROWSER_BROWSER=chromium|firefox|webkit` 选择实现。Worker 支持 `navigate`、`snapshot`、`click`、`hover`、`fill`、`check`、`uncheck`、`select`、`press`、`wait_for_selector`、`wait`、`reload`、`back`、`forward`、`title`、`url`、`text`、`screenshot` 和 `download`，不提供任意 `evaluate` 脚本入口，避免页面脚本把 Cookie、Profile 或其他凭据带回 Center；默认无头、单页、超时和输出有界；`snapshot` 额外返回最多 64 个 `rcm-ref-v1:*` 结构化元素引用，后续动作可用 `{"ref":"rcm-ref-v1:..."}` 复用，不需要把整棵 DOM 再发回模型。引用编码的是 role/name、test-id、placeholder 或 text 定位和有界序号，不是可泄露页面内容的句柄；页面变化后若定位失败应重新执行 `snapshot`。生产环境应为每个 Agent 使用独立的 `RCM_BROWSER_PROFILE_DIR`，不要把 Cookie、CDP 地址或代理密码写入 `command`、日志或 Center 配置。

启用独立的 `RCM_BROWSER_PROFILE_DIR` 后，Agent 会自动为该身份维护一个 `browser-session.json` 会话标记。Worker 只把最近页面的脱敏 origin/path 写入该文件（不保存 query、fragment、Cookie 或 CDP 凭据），下一次任务会先恢复该页面；没有 profile 或标记失效时按新页面处理。跨任务引用依赖页面仍可访问，涉及登录参数或一次性 URL 时请显式再次调用 `navigate`。

需要截图或下载时，Worker 将文件写入 `RCM_BROWSER_ARTIFACT_DIR`，再把以下 JSON 原子写入
`RCM_BROWSER_RESULT_FILE`；Agent 会校验路径必须位于该目录内、限制 8 MiB、计算 SHA-256
并上传为任务唯一工件：

```json
{"status":"completed","output":"页面已打开","artifact":{"path":"page.png","mime_type":"image/png"}}
```

`status` 为 `failed` 时可附带 `error`，Agent 会把任务收口为失败；未生成结果文件时仍以
适配器进程退出码和有界 stdout 判断任务结果。

Center 升级 API：控制台使用 `POST /api/v1/admin/upgrades` 创建活动，`GET /api/v1/admin/upgrades` 查看进度，`POST /api/v1/admin/upgrades/{campaignId}/resume|cancel` 控制活动。Agent 通过 `/agent/v1/poll` 领取计划，并以 `/agent/v1/upgrade/status` 回报下载、安装和结果；只有无附着超时任务时才会进入升级。

PostgreSQL 生产切换前可运行 `scripts/backup-postgres.ps1`（Windows）或
`scripts/backup-postgres.sh`（Linux）生成 custom-format 备份、SHA-256 和不含凭据的元数据；
恢复演练必须在隔离数据库完成，并在路由切换前验证 Liquibase、任务、输出和 Agent 心跳。
