# Remote Connect MCP

[![CI](https://github.com/Prodigalgal/remote_connect_mcp/actions/workflows/ci.yml/badge.svg)](https://github.com/Prodigalgal/remote_connect_mcp/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Remote Connect MCP 是一个面向 ChatGPT Web 的中心化多机器控制系统。ChatGPT 只连接一个 MCP Gateway；每台目标机器运行一个主动连接 Center 的 Agent。Center 同时提供机器注册、持久化异步任务、断线续传、Web 控制台和 Agent 集群升级编排。

产品需求基线见 [`docs/REQUIREMENTS.md`](docs/REQUIREMENTS.md)，分级任务清单见 [`docs/TASKS.md`](docs/TASKS.md)，M:M 用户/对话/MCP 设计见 [`docs/MULTI_USER_MODEL.md`](docs/MULTI_USER_MODEL.md)。本文档说明使用和部署；需求基线、任务、架构、异步契约和实现状态分别维护，规划中的能力不会自动视为已上线。

目标架构和演进边界见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)，异步调用契约见 [`docs/ASYNC_CONTRACT.md`](docs/ASYNC_CONTRACT.md)，详细语言、运行时、原生构建和前端选型见 [`docs/TECH_STACK.md`](docs/TECH_STACK.md)，Java/React 发布门禁见 [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md)，当前实现/生产阻塞见 [`docs/STATUS.md`](docs/STATUS.md)，SLO/告警见 [`docs/OBSERVABILITY.md`](docs/OBSERVABILITY.md)，传输评估见 [`docs/TRANSPORT.md`](docs/TRANSPORT.md)，HTTP 工件网关接口见 [`docs/ARTIFACT_GATEWAY.md`](docs/ARTIFACT_GATEWAY.md)，command/desktop/browser 组件级升级合同见 [`docs/COMPONENT_UPGRADES.md`](docs/COMPONENT_UPGRADES.md)。Java 25 Center/Agent 与 React 控制台已经进入生产路径；Go 组件仅作为离线节点的兼容/回滚基线保留。一个物理终端默认只有一个向 Center 注册的 `command-agent` 身份；桌面能力由同安装包启动的用户会话 `desktop-companion` 提供，浏览器能力由有界的本机 Browser Worker 提供，不增加额外 machine ID 或 Token。确需隔离时才为同一终端显式注册多个 Agent。

项目不代理其他 MCP，也不对命令内容做白名单过滤。任务支持 `project`、`worktree`、`path`、`workspace` 和显式 `unrestricted` 五种范围；默认安装策略是 `workspace`，整机模式必须由调用方明确声明并通过 Agent 的本地校验。

> [!CAUTION]
> MCP Token、管理 Token、Enrollment Token 和 Agent 凭据都属于高权限秘密。MCP Token 等同于所有已注册机器上的远程代码执行权限。请只通过 HTTPS 使用，将真实值保存在 Secret 或权限为 `0600` 的配置文件中，禁止提交到 Git。

## 架构

完整的目标架构、Desktop/Browser Agent、项目注册、worktree、长连接和热更新边界见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

Java/React 迁移已完成 Center/Console 的 v0.1.21 生产切换，四台在线 Oracle Agent 已升级到 v0.1.21，离线节点保留旧版兼容路径：`java/` 提供 Java 25 多模块 Center、command-agent、desktop-companion 和 browser-agent 三个 Native 构建目标，异步 MCP、事务任务/输出/工件适配和本机能力桥接；`web/` 提供独立 React/Vite 控制台并可用 Admin Token 读取 Center API。Java Center 的持久化路线固定为 PostgreSQL + Liquibase，不使用 Flyway；Go Center 已缩容为 0，Go Agent 仅作为尚未上线节点的兼容/回滚基线保留。

```text
ChatGPT Web
    |
    | HTTPS + Bearer Token
    v
remote-connect-mcp-gateway.example.invalid/mcp
    |
    v
Center / MCP Gateway / Task Store / Web Console
    ^
    | Agent 主动长轮询；无入站端口
    +--------- Host A: command-agent + optional desktop-companion + browser-agent
    +--------- Host B: command Agent
```

公网入口统一以 `remote-connect-mcp-*` 开头：

| 用途 | 地址 |
| --- | --- |
| ChatGPT MCP | `https://remote-connect-mcp-gateway.example.invalid/mcp` |
| Web 控制台 | `https://remote-connect-mcp-console.example.invalid/console/` |
| Agent 注册与任务通道 | `https://remote-connect-mcp-agent.example.invalid` |

域名采用“先利旧、再新增”的兼容策略：

| 类型 | 命名约定 | 用途 |
| --- | --- | --- |
| 生产主入口（利旧） | `remote-connect-mcp-gateway.*`、`remote-connect-mcp-agent.*`、`remote-connect-mcp-console.*` | 继续承载 MCP、Agent 通道和控制台；Java Center 切换时不要求 ChatGPT 或 Agent 修改 URL |
| 可选 Java 版本入口（新增） | `remote-connect-mcp-java-*.*` | 只有需要独立灰度、蓝绿或版本并行时才创建；域名显式包含 `java`，由私有部署 overlay 注入 |

Center 有公网 K8S 入口，因此不使用 Cloudflare Tunnel。Cloudflare 仅作为权威 DNS，生产主入口直接指向 Envoy Gateway 和 Java Center Service，不经过 Cloudflare 代理、CDN 或 WAF。Agent 只主动连接 Center，不监听入站端口，因此 Agent 宿主机不需要 DNS 记录、Tunnel 或入站防火墙规则。公开仓库只保留 `example.invalid` 模板；真实域名和 DNS 记录由私有 GitOps/Cloudflare 配置维护。

## 为什么命令不会再卡住

命令执行和 MCP HTTP 请求已经解耦：

1. `command_start` 把命令写入 Center，默认不等待 Agent，立即返回持久化 `task_id`。
2. Agent 领取任务后在本机独立进程中执行，stdout/stderr 持续写入本机受限日志。
3. Agent 按字节偏移上传输出；重复分片不会重复追加。
4. Center 或网络中断时命令继续运行，Agent 使用指数退避和随机抖动自动重连。
5. 重连后 Agent 从 Center 已确认的偏移继续补传输出和最终状态。
6. ChatGPT 使用非阻塞的 `task_wait` 或 `task_output` 获取有界输出，不需要让一次 HTTP 调用等待整个命令完成。

Agent 默认将单个任务捕获的 stdout/stderr 限制为 64 MiB，所有普通任务共享一个按并发自动计算的聚合 spool 上限（默认并发 1 时为 64 MiB，最高默认 256 MiB）。普通附着任务超出任一上限后丢弃后续输出但继续执行；无超时 durable 任务由磁盘看门器终止并以 `failed + output_truncated` 收口，避免离线期间无限增长。需要调整时设置 `REMOTE_CONNECT_MCP_AGENT_MAX_OUTPUT_BYTES` 和 `REMOTE_CONNECT_MCP_AGENT_MAX_AGGREGATE_OUTPUT_BYTES`，后者必须不小于前者。

`command_start` 支持 `idempotency_key`。同一机器使用相同键和相同参数重试时，Center 返回原任务而不会重复执行；如果相同键对应不同参数，Center 会拒绝请求。ChatGPT 应为每个逻辑命令生成一次稳定键，并在连接超时重试时复用。

长时间或无人值守任务应在创建后立即向用户返回 `task_id`，不要在同一个 ChatGPT 回合中连续轮询。之后可以在新消息中查询任务，也可以直接通过 Web 控制台查看持久化状态和输出。ChatGPT Web 显示消息超时时，应先检查任务列表，确认任务是否已经创建，再决定是否重试。

没有设置超时的任务会以可恢复进程启动：Agent 重启后会重新发现本机进程、续传已确认的输出并上报最终状态；运行中的进程不会因为一次 Center/Agent 网络断线而重复执行。设置了超时的任务仍采用附着进程，Agent 重启时会安全地报告失败。停止 Agent 服务不会自动杀掉无超时的可恢复任务，请使用 `task_cancel` 或控制台取消。

## MCP 工具

| 工具 | 用途 |
| --- | --- |
| `machines_list` | 分页列出机器 ID、平台、目录策略、能力和在线状态（默认 25 台，最多 50 台；有更多时使用 `next_offset`） |
| `machine_info` | 查看单台机器的心跳、默认目录和 Agent 版本 |
| `project` | 注册 Agent 本地项目，并异步创建/移除隔离 Git worktree；仓库内容不上传 Center |
| `command_start` | 在指定机器创建异步命令任务 |
| `task_wait` | 默认立即返回当前状态；可选短暂等待状态变化或下一页输出（默认 16 KiB） |
| `task_output` | 按字节游标分页读取有界任务输出（默认 16 KiB，单次最多 64 KiB） |
| `task_cancel` | 取消排队或正在运行的任务 |
| `desktop` | 仅对声明 `desktop` 能力的用户会话 Agent 提供有界截图、屏幕枚举、应用启动、点击、拖拽、按键、文本、剪贴板和窗口聚焦；截图以图片工件返回 |
| `browser` | 仅对声明 `browser` 能力且配置本机适配器的 Agent 投递一条有界 Worker 请求；快照可返回最多 64 个 `rcm-ref-v1` 元素引用供后续动作复用；不上传 Cookie/CDP 凭据 |

当前核心命令工具集为 6 个，另有项目工作流 `project` 和按能力启用的 `desktop`、`browser` 工具。工具数量不是硬性限制，只有在确有独立用户价值且能保持有界输入/输出时才扩展；Browser Agent 不会把 Playwright/Patchright/Comoufox 的全部底层 API 一次性暴露。机器数量不会扩大 ChatGPT 的工具元数据。每次机器操作都必须显式传入 `machine_id` 和范围；范围由任务 execution contract 统一表达，不为每种能力复制一组工具，避免污染 ChatGPT Web 上下文。

MCP 返回专门的精简视图：`machines_list` 使用分页摘要，`task_wait`/`task_output` 只返回有限输出页，任务状态不会回显提交时的环境变量或令牌；过长命令和错误文本会截断并标记。每个结果只发送一份 JSON 文本，不重复发送结构化副本。需要更多内容时使用 `next_cursor`/`offset` 分页，不会在单次对话中装载整台机器或完整日志。

执行合同中的输出、工件和时长预算只能进一步收紧 Agent 的启动配置；它们不会扩大宿主机的并发、磁盘或进程额度。Agent 在启动命令、桌面或浏览器子进程前再次校验合同，过期、身份不匹配或范围扩大都会失败关闭。

## 升级兼容契约

ChatGPT 连接器使用固定 `/mcp` URL 和固定 Bearer Token。当前核心工具集保持稳定；新增工具必须经过上下文体积、参数复杂度和兼容性评估，Center、Agent、控制台、存储实现和机器数量升级不得要求重新创建 ChatGPT 连接器。

- Center 通过 GitOps 固定镜像摘要升级；Service、HTTPRoute、域名和 Secret 名称保持不变，Java Center 的状态以 PostgreSQL 为准，不依赖 RWO PVC。
- Agent 先在少量机器试运行，再按批次升级；心跳会持续刷新实际版本、平台和默认目录。
- Center 必须兼容至少上一版 Agent；Agent 元数据使用可忽略的 HTTP Header 上报，使旧 Center 能安全忽略新字段。
- 新内部能力优先扩展 Center/Agent 协议和控制台；只有需要模型直接调用且无法复用现有工具时，才新增 MCP 工具，并为其设置有界分页和输出上限。
- 只有 MCP Token 泄露需要修改 ChatGPT 认证；确实改变工具 Schema 时，ChatGPT 可能需要重新扫描工具，但仍不更换 URL。

## Agent 自动无感升级

Java Center/Agent 已实现 Center 控制的 canary/批次升级协议；正式切换前仍必须在 CI 目标平台完成 Native Image、签名、服务安装和回滚验收，不会把未验证的 JVM JAR 当作生产二进制：

1. GitHub Actions 在每次 `main` 推送后构建 Linux amd64/arm64 与 Windows amd64 的 Native Image，并发布一个不可变的 `java-v0.0.0-main.<run>` 预发布版本；正式 `java-vX.Y.Z` 标签发布同样经过完整门禁。每个 Agent 资产都提供 `.sha256`；旧裸 ELF/EXE 仅作为兼容回退；Windows ARM64 暂使用 JVM/Go 兼容包，不把未经验证的交叉编译物标为 Native Image；
2. Center 在控制台加载或用户点击刷新时读取 GitHub Release 目录，管理员直接选择目标版本，设置金丝雀数量和后续批次大小；
3. Center 按目标机器的 OS/架构解析 Release 资产和 `.sha256`（也支持 Admin API 直接提交已校验的 HTTPS 资产），再向金丝雀 Agent 下发 HTTPS 下载地址和 SHA-256；
4. Agent 在没有附着（有超时）命令时接收升级；无超时可恢复任务可以继续运行，校验下载包后启动独立升级 Helper；
5. Helper 停止 systemd/Windows 启动任务、原子替换二进制（Windows 同时替换 ZIP 内 DLL）、重新启动并要求服务管理器返回成功；启动失败会恢复 `.previous` 版本；
6. Center 根据 Agent 心跳中的实际版本确认成功，再自动放行下一批；失败目标在租约过期后重新排队，任意机器明确失败都会暂停整个活动，管理员确认后可重试或取消。

升级活动及逐机状态在 Java Center 内存模式下用于协议回归，在 PostgreSQL 模式下由 Liquibase 管理的 `rcm_upgrade_campaign`/`rcm_upgrade_target` 表持久化。Center Pod 重启后会继续未完成批次。升级只改变 Agent 二进制，不改变机器身份、每机凭据、服务配置或 ChatGPT MCP 工具；React 页面调用真实 `/api/v1/admin/upgrades` API。

首个支持本能力的版本需要沿用现有安装脚本人工引导一次；此后版本均可由 Center 自升级。发布包必须来自受信任的 HTTPS Release，Center 和 Agent 都会拒绝缺失或不匹配的 SHA-256。当前 Agent 按计划直接从校验过的 Release URL 下载；Center 尚未提供共享二进制缓存，后续可接入对象存储或内部镜像而不改变 Agent 协议。

## Web 控制台

控制台使用独立的 Center Admin Token，支持：

- 查看机器在线状态、平台、Agent 版本和最后心跳；
- 创建 command、desktop 或 browser 任务，并指定机器、项目/worktree/path/unrestricted 范围、工作目录、风险、会话和超时；
- 注册 Agent 本地项目并创建/移除 Git worktree，任务可选择已就绪的 project/worktree cwd；项目卡片还支持带确认和幂等键的 Git status/diff/log/commit/merge/merge-abort；
- 查看最近任务、执行状态和完整输出；
- 取消排队或运行中的任务；
- 手动刷新机器、任务和当前输出；长输出按 cursor 分页读取，不阻塞页面。
- 查看升级活动、canary/批次进度，并暂停、恢复或取消发布；升级资产必须通过 HTTPS 和 SHA-256 校验。
- 查看有界脱敏审计事件；需要时可在审计页显式清理一年以前的记录，不会自动启动定时清理线程。
- 查看 GitHub Release 版本目录（稳定版/预发布、发布时间和三平台 Agent 资产覆盖），从下拉框选择升级目标；目录短缓存，GitHub 暂时不可达时显示最近一次成功结果。

管理 Token 只保存在 React 当前标签页内存，刷新或关闭页面后消失，不写入 `localStorage`、`sessionStorage` 或静态构建产物。

## Center 配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `REMOTE_CONNECT_MCP_CENTER_HOST` | `0.0.0.0` | 监听地址 |
| `REMOTE_CONNECT_MCP_CENTER_PORT` | `8080` | HTTP 端口 |
| `REMOTE_CONNECT_MCP_CENTER_STATE_DIR` | `/var/lib/remote-connect-mcp-center` | 机器、任务和输出状态目录 |
| `REMOTE_CONNECT_MCP_CENTER_MCP_TOKEN` | 必填 | ChatGPT Bearer Token |
| `REMOTE_CONNECT_MCP_CENTER_ADMIN_TOKEN` | 必填 | Web 控制台/API Token |
| `REMOTE_CONNECT_MCP_CENTER_ENROLLMENT_TOKEN` | 空 | 仅在 `RCM_CENTER_ALLOW_SHARED_ENROLLMENT=true` 的旧部署迁移窗口使用；生产默认不配置，新增 Agent 通过 Admin API 获取一次性 Token |
| `REMOTE_CONNECT_MCP_CENTER_CONSOLE_HOSTNAME` | 空 | 控制台域名，用于根路径跳转 |
| `RCM_CENTER_AGENT_UPGRADES_ENABLED` | `true` | 是否允许 Center 下发 Agent 升级计划；紧急情况下设为 `false` 只停止新升级，不影响现有任务 |
| `RCM_CENTER_RELEASE_BASE_URL`（兼容 `REMOTE_CONNECT_MCP_CENTER_RELEASE_BASE_URL`） | GitHub Releases 下载基址 | 自动解析发布资产和 `.sha256` 的基址；私有镜像源通过部署 Secret/env 覆盖 |
| `RCM_CENTER_RELEASE_TAG_PREFIX`（兼容 `REMOTE_CONNECT_MCP_CENTER_RELEASE_TAG_PREFIX`） | `java-` | 发布 URL 的 Tag 前缀；本仓库 Java 发布使用 `java-v1.2.3`，控制台版本仍填写 `v1.2.3`；使用普通 `v*` Tag 时设为空 |
| `RCM_CENTER_RELEASES_API_URL`（兼容 `REMOTE_CONNECT_MCP_CENTER_RELEASES_API_URL`） | `https://api.github.com/repos/Prodigalgal/remote_connect_mcp/releases` | 控制台版本目录的 GitHub Releases API；只读取公开元数据，不保存 GitHub 凭据 |
| `RCM_CENTER_PERSISTENCE_MODE` | `memory` | Java Center 使用 `postgres` 才启用 PostgreSQL 任务/机器/工件存储 |
| `RCM_CENTER_REQUIRE_DURABLE_STORAGE` | `false` | 设为 `true` 时，除 PostgreSQL 外的模式不会通过 `/api/v1/readyz`；生产必须开启 |
| `RCM_CENTER_ARTIFACT_STORE` | `filesystem` | PostgreSQL 模式的工件字节存储；支持 `filesystem` 或 `http`（内部对象网关），生产不得使用测试内存实现 |
| `RCM_CENTER_ARTIFACT_ROOT` | Linux `/var/lib/remote-connect-mcp-center/artifacts`；Windows `%ProgramData%\\remote-connect-mcp-center\\artifacts` | 工件对象根目录；必须位于持久卷/专用数据盘并由 Center 进程可写 |
| `RCM_CENTER_ARTIFACT_HTTP_BASE_URL` | 空 | `http` 后端的 HTTPS 对象网关基址；HTTP 仅允许 loopback 开发环境 |
| `RCM_CENTER_ARTIFACT_HTTP_TOKEN` | 空 | 对象网关 Bearer Token；只通过 Secret/env 注入，不写入 PostgreSQL 或日志 |
| `RCM_CENTER_ARTIFACT_HTTP_TIMEOUT_SECONDS` | `30` | 对象网关单次请求超时，范围 1–120 秒 |
| `RCM_CENTER_STRUCTURED_AUDIT_LOG` | `false` | 设为 `true` 时，将已脱敏的审计事件以 `rcm.audit {JSON}` 单行写入 stdout/journal，供 Loki/OTel 等采集器接收 |
| `RCM_CENTER_LIQUIBASE_ENABLED` | `true` | Java Center 是否在当前进程执行 Liquibase；生产 Pod 设为 `false`，由独立 migration Job 执行 |
| `RCM_CENTER_DATABASE_URL` | 空 | PostgreSQL JDBC URL（postgres 模式必填） |
| `RCM_CENTER_DATABASE_USERNAME` | 空 | PostgreSQL 用户名（postgres 模式必填） |
| `RCM_CENTER_DATABASE_PASSWORD` | 空 | PostgreSQL 密码（仅通过 Secret/env 注入） |
| `RCM_CENTER_ALLOW_SHARED_ENROLLMENT` | `false` | 仅应急兼容旧部署；生产默认关闭，新增 Agent 通过 Admin API 生成一次性 Token |

Java Center 生产使用 PostgreSQL 事务存储和单副本 `Recreate` Deployment；任务元数据使用数据库，工件字节使用单独挂载的受限持久卷，不再把大块内容写入 PostgreSQL `BYTEA`。任务创建、状态变化和升级变化会立即持久化；内存只保存可丢失的唤醒/等待状态和必要的短期快照，任何缓存失效都从 PostgreSQL 重建。`RCM_CENTER_REQUIRE_DURABLE_STORAGE=true` 会让误用 memory 模式或缺少持久工件卷的实例保持未就绪，避免无意接收生产流量。

旧 Go Center 切换到 Java/PostgreSQL 时，先备份并停止旧 Center，运行 Liquibase 迁移后使用 `rcm-center --import-go <旧状态目录或 state.json>` 导入机器、Agent 摘要、任务、输出和工件。导入不读取 MCP/Admin Token 明文；旧升级活动需暂停并在新 Center 重新创建。

Prometheus 可通过带 Center Admin Token 的 `GET /metrics` 读取机器在线数、任务状态、任务输出字节数和升级活动状态。指标不包含命令、路径、Token 或任务输出；生产环境应仅允许监控网络访问该端点。

Kubernetes 模板位于 [`deploy/k8s/java-center`](deploy/k8s/java-center)。真实 Token 必须通过集群外私密来源创建为 `remote-connect-mcp-java-secrets`，不要提交 `secret.example.yaml` 的替换版本。

### 新增机器注册 Token

日常接入新 Agent、重装 Agent 或恢复丢失的身份文件时，在 Center 控制台的“新增机器注册令牌”区域填写稳定机器名称，生成 1 小时至 30 天有效的一次性 Token。Token 与机器名称绑定，成功注册一次后立即失效；每次安装、重装或身份恢复都必须重新生成一个 Token。

明文只在创建响应和当前浏览器页面显示一次，Center 仅持久化 SHA-256 摘要。生成后可以分别复制 Token、复制已解压发布包的安装命令，或下载包含本次一次性 Token 的 `.ps1` / `.sh` 一键安装脚本。下载脚本会自动识别 amd64/arm64、下载最新 Release、校验 SHA-256 并安装系统服务；安装成功后应立即删除该脚本。Agent 注册成功会换取日常长轮询使用的每机独立身份 Token。旧部署如必须迁移，可临时启用共享环境变量并在迁移后立即关闭；新生产部署不使用共享 Enrollment Token。

## Agent 配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `REMOTE_CONNECT_MCP_AGENT_CENTER_URL` | 必填 | Agent Center 地址 |
| `REMOTE_CONNECT_MCP_AGENT_ENROLLMENT_TOKEN` | 首次注册时必填 | 一次性注册 Token；注册成功后应从长期服务环境移除，凭据恢复需重新人工注入新的 Token |
| `REMOTE_CONNECT_MCP_AGENT_NAME` | 主机名 | 稳定机器名称；同名重装会复用机器记录并轮换凭据 |
| `REMOTE_CONNECT_MCP_AGENT_HOST_ID` | 主机名 | 物理终端归组标识；同一终端的多个 Agent 使用同一个值，但不共享 machine ID 或权限 |
| `REMOTE_CONNECT_MCP_AGENT_DEFAULT_CWD` | 启动目录 | 相对工作目录的基准；在 `workspace` 模式下必须位于工作区根目录内 |
| `REMOTE_CONNECT_MCP_AGENT_SCOPE_MODE` | `workspace` | 支持 `project`、`worktree`、`path`、`workspace`；`unrestricted` 必须显式配置 |
| `REMOTE_CONNECT_MCP_AGENT_WORKSPACE_ROOT` | 空 | `workspace` 模式的根目录；为空时使用 `DEFAULT_CWD` |
| `REMOTE_CONNECT_MCP_AGENT_CAPABILITIES` | 内置 `command,durable_tasks` | 可选能力标签，使用逗号分隔；仅用于 Center/控制台展示，不直接授予权限 |
| `REMOTE_CONNECT_MCP_AGENT_VERSION` | `dev` | 初始上报版本；升级 Helper 成功后写入私有 `STATE_DIR/agent-version`，重启后自动上报新版本 |
| `REMOTE_CONNECT_MCP_AGENT_STATE_DIR` | Linux `/var/lib/remote-connect-mcp-agent` | Agent 身份和本机任务输出目录 |
| `REMOTE_CONNECT_MCP_AGENT_MAX_CONCURRENCY` | `1` | 同时运行任务数，范围 1–32 |
| `REMOTE_CONNECT_MCP_AGENT_MAX_BROWSER_WORKERS` | `1`（默认不超过 2，且不超过总并发） | Browser Worker 独立上限，范围 1–8；达到上限时 Agent 暂不向 Center 声明 `browser` 能力 |
| `REMOTE_CONNECT_MCP_AGENT_MAX_OUTPUT_BYTES` | `67108864` | 单任务 stdout/stderr 捕获上限，范围 1 MiB–1 GiB |
| `REMOTE_CONNECT_MCP_AGENT_MAX_AGGREGATE_OUTPUT_BYTES` | `67108864`（并发提高时默认最多 256 MiB） | 所有普通任务磁盘 spool 的聚合上限；必须不小于单任务上限，范围单任务上限–4 GiB；达到后任务继续运行但后续输出标记为截断 |
| `REMOTE_CONNECT_MCP_AGENT_MAX_TASK_DURATION_SECONDS` | `0` | 单任务墙钟上限；0 表示不额外收紧任务/合同（范围 0–2592000） |
| `REMOTE_CONNECT_MCP_AGENT_MAX_CHILD_PROCESSES` | `32` | 单任务进程树上限（含根进程，范围 1–256），超限会终止整棵树 |
| `REMOTE_CONNECT_MCP_AGENT_MAX_TOTAL_CHILD_PROCESSES` | `min(256, max(32, MAX_CONCURRENCY×32))` | command 任务和 browser-agent supervisor 共享的 Agent 级进程总预算，范围 1–4096；desktop-companion 使用自己的独立上限；耗尽时新任务 fail-closed，退出后自动释放 |
| `REMOTE_CONNECT_MCP_AGENT_MAX_RSS_BYTES` | `0` | 单任务 RSS 上限；Linux 通过 `/proc` 执行，0 或不支持的平台表示关闭（最多 16 GiB） |
| `REMOTE_CONNECT_MCP_AGENT_MAX_CPU_SECONDS` | `0` | 单任务累计 CPU 时间上限（范围 0–2592000） |
| `REMOTE_CONNECT_MCP_AGENT_RESOURCE_SAMPLE_INTERVAL_MS` | `1000` | 资源监督的任务级采样间隔（250–10000 ms）；只在任务运行时启用，不产生空闲 Agent 轮询 |
| `REMOTE_CONNECT_MCP_AGENT_CGROUP_PATH` | 空 | Linux 可选的预创建 cgroup v2 目录；任务启动时加入该 cgroup，目录不可用则任务 fail-closed；留空使用 JDK 进程树监督 |
| `REMOTE_CONNECT_MCP_AGENT_POLL_INTERVAL_MS` | `5000` | 仅用于旧 Center/长轮询关闭时的兼容退避；范围 250–60000 ms，断线时自动指数退避 |
| `REMOTE_CONNECT_MCP_AGENT_LONG_POLL_SECONDS` | `25` | Agent 单次 HTTPS 长轮询等待秒数（0–25）；事件/取消/配置到达即返回，0 仅用于旧 Center 兼容 |
| `REMOTE_CONNECT_MCP_AGENT_WAKE_TRANSPORT` | `poll` | 设置为 `websocket` 时启用额外的 Agent WebSocket 唤醒提示；任务数据和认证仍走 HTTPS，连接失败自动退避 |
| `REMOTE_CONNECT_MCP_AGENT_BINARY_PATH` | 空 | Center 自升级时当前 Agent 二进制的稳定绝对路径；未配置则拒绝自升级 |
| `REMOTE_CONNECT_MCP_AGENT_SERVICE_NAME` | 空 | 升级 Helper 停止/启动的 systemd 服务或 Windows 启动任务名；无名称时只做进程级替换 |
| `REMOTE_CONNECT_MCP_AGENT_DESKTOP_ENABLED` | `false` | 显式启用桌面伴侣；必须以用户会话运行，系统服务本身不链接 AWT |
| `REMOTE_CONNECT_MCP_AGENT_BROWSER_ADAPTER` | 空 | Browser Agent 本机 Playwright/Patchright/Comoufox Worker 命令；设置后才可执行 browser 任务，任务 JSON 通过临时请求文件传入，截图/下载通过受目录约束的结果清单回传；仓库参考 Worker 为 `scripts/browser-worker.mjs` |
| `REMOTE_CONNECT_MCP_AGENT_BROWSER_BINARY` | 同目录 `rcm-browser-agent` | 可选的独立 Browser Agent Native 二进制；未配置时自动查找 command-agent 同目录的 `rcm-browser-agent`，找不到则兼容地直接执行适配器命令 |
| `REMOTE_CONNECT_MCP_AGENT_BROWSER_PROFILE_DIR` | 空 | 可选的目标机持久浏览器 Profile 目录；只由 Browser Worker 使用，不上传 Cookie、扩展或 CDP 凭据 |
| `REMOTE_CONNECT_MCP_AGENT_BROWSER_ENGINE` | `playwright` | 本机适配器引擎：`playwright`、`patchright` 或 `comoufox`；不由 Center/模型远程选择 |
| `REMOTE_CONNECT_MCP_AGENT_BROWSER` | `chromium` | 本机浏览器类型：`chromium`、`firefox` 或 `webkit` |
| `REMOTE_CONNECT_MCP_AGENT_BROWSER_HEADLESS` | `1` | Browser Worker 是否无头运行；仅影响目标机本地会话，不改变 Center 权限 |
| `REMOTE_CONNECT_MCP_AGENT_DESKTOP_MAX_LAUNCHED_PROCESSES` | `16` | desktop-companion 的活动 GUI 启动上限（1–64）；没有用户会话时 command-agent 不回退启动桌面进程 |

Agent 首次注册后获得每机独立 Token，只保存其 SHA-256 摘要到 Center，原始值以 `0600` 权限保存在 Agent 状态目录。注册时会同时上报 `host_id`、`scope_mode` 和 `workspace_root`，控制台的机器详情可据此区分同一终端上的多个物理 Agent。注册后的机器名称和 `host_id` 属于不可变身份字段，心跳只更新平台、版本、能力和运行时自描述；若身份被吊销或丢失，请在 Center 重新生成一次性 Token，更新目标 Agent 的配置并重启；正常的 Center 重启和新建 Enrollment Token 不会影响已注册 Agent。

### 多 Agent 与桌面/浏览器能力

同一台物理终端默认只注册一个 Java `command-agent` 身份：系统服务负责命令/心跳，`-DesktopEnabled` 在用户登录时启动独立的 `rcm-desktop-companion` 进程，通过本机 IPC 获得截图、启动、点击、按键和文本输入能力，不新增 machine ID 或 Token。伴侣使用单实例锁、最多 4 个并发 IPC 请求和最多 16 个活动启动进程；命令 Agent 使用状态目录锁，防止服务重启重叠产生第二个子进程池；Browser Worker 默认最多 1 个（可显式提高但不超过 8）。若确实需要隔离运行多个物理 Agent，则为每个实例使用不同的 Agent 名称、一次性注册 Token、状态目录和 machine ID，并用相同的 `REMOTE_CONNECT_MCP_AGENT_HOST_ID` 归组；Center 只允许同名 Agent 在相同 `host_id` 下重装，来自其他 `host_id` 的同名注册会被拒绝，避免误旋转已有 Token；Browser Worker 的 Profile/Cookie 仍只保留在本机。详细边界见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

Browser `snapshot` 返回的 `rcm-ref-v1:*` 只是一段有界定位描述（role/name、test-id、placeholder 或 text 加序号），不是跨页面永久句柄；页面结构变化后应重新获取快照。为恢复多次调用之间的页面，给 Agent 服务环境配置独立的 Worker 变量 `RCM_BROWSER_PROFILE_DIR`，Agent 会在自己的状态目录保存不含查询参数和片段的最近页面路径；登录态仍由浏览器 profile 管理，任何一次性 URL 都必须显式再次导航。

### 工作区模式

在目标机器上设置：

```text
REMOTE_CONNECT_MCP_AGENT_SCOPE_MODE=workspace
REMOTE_CONNECT_MCP_AGENT_WORKSPACE_ROOT=/srv/project/demo
REMOTE_CONNECT_MCP_AGENT_DEFAULT_CWD=/srv/project/demo
```

Windows 示例：

```text
REMOTE_CONNECT_MCP_AGENT_SCOPE_MODE=workspace
REMOTE_CONNECT_MCP_AGENT_WORKSPACE_ROOT=D:\Work\Project\demo
REMOTE_CONNECT_MCP_AGENT_DEFAULT_CWD=D:\Work\Project\demo
```

`command_start` 中的 `cwd` 可以留空或使用相对路径，例如 `src`、`backend`。绝对路径、`..` 穿越、符号链接或 Windows Junction 解析到工作区外时会被 Agent 拒绝；Center 也会按目标平台做一次词法校验。切换机器的目录策略需要更新 Agent 配置并重新注册（删除身份文件后使用新的一次性注册 Token），避免旧策略被悄悄放宽。

Center 管理端可以通过 `GET/PUT /api/v1/admin/machines/{machineId}/config` 热更新单台 Agent 的心跳间隔（250–60000 ms）和并发槽位（1–32）。配置带单调递增 generation，Agent 原子写入 `runtime-config.json` 后立即生效；输出上限、Token 和工作区边界不会被热更新覆盖。

### 项目注册与 Git Worktree

控制台的“项目与 Worktree”页面或 MCP `project` 工具可以为指定 Agent 注册项目：`root_path` 和可选
`repository_path` 必须是目标机绝对路径，且仓库路径位于项目根目录内。Center 只保存路径元数据和不透明 ID，不读取源码。
创建 Worktree 时，Center 生成 `root_path/.rcm-worktrees/<worktree_id>`，在目标 Agent 上异步执行受控的
`git worktree add --detach`；任务完成前该 Worktree 不能作为任务 cwd。删除同样通过异步 `git worktree remove --force` 执行，
请求可使用 `idempotency_key` 安全重试。项目边界校验在 Center 和 Agent 各执行一次，原始 checkout 不会被自动修改。

工作区策略限制的是任务启动目录，不是操作系统级沙箱。命令仍以 Agent 运行账户运行，Shell 可以自行读取或写入其他路径。若需要强隔离，请再配合独立低权限账户、ACL、systemd `ReadWritePaths`、Windows 受限账户或容器/沙箱；不要把 `workspace` 模式当作 root 级安全边界。

Linux systemd 模板和 Java 安装脚本位于 [`deploy/systemd`](deploy/systemd)、[`scripts/install-java-agent.sh`](scripts/install-java-agent.sh) 与 [`scripts/install-java-agent.ps1`](scripts/install-java-agent.ps1)。安装器先完成一次注册，再创建不含 Enrollment Token 的长期运行配置。模板使用 `KillMode=process`，让无超时可恢复任务在 Agent 重启时继续运行；有超时的附着任务由 Agent 自己清理。systemd 设置文件描述符、任务数、CPU 和内存高低水位；Agent 自身还通过并发槽位、有界 spool、Browser/桌面子进程上限控制资源。Agent 不监听端口，不需要域名、Cloudflare Tunnel 或入站防火墙规则。

### Windows 启动任务

Windows amd64 使用 Native Image Agent，并由内置 Windows Task Scheduler 以 SYSTEM 身份在系统启动时运行。Native Image 是控制台程序，不能直接注册成 SCM ServiceMain；启动任务还配置失败重启，因此不会出现 SCM 7000/7009 超时。Windows ARM64 暂使用
兼容包，但安装参数和启动任务管理方式相同。请在管理员 PowerShell 7 中执行：

```powershell
./scripts/install-java-agent.ps1 `
  -BinaryPath ./remote-connect-mcp-agent-vX.Y.Z-windows-amd64.zip `
  -EnrollmentToken '<center enrollment token>' `
  -AgentName '<Headscale given_name>' `
  -DefaultCwd 'D:\Work\Project\demo' `
  -ScopeMode workspace `
  -WorkspaceRoot 'D:\Work\Project\demo' `
  -DesktopEnabled `
  -DesktopBinaryPath ./remote-connect-mcp-desktop-vX.Y.Z-windows-amd64.zip `
  -BrowserBinaryPath ./remote-connect-mcp-browser-vX.Y.Z-windows-amd64.zip
```

任务名为 `RemoteConnectMCPAgent`，默认开机启动，异常退出按 1 分钟间隔最多重试 3 次（Windows Task Scheduler 的最小重试间隔）。启用桌面能力时，`RemoteConnectMCPDesktopCompanion` 由登录触发，但使用 `wscript.exe //B //NoLogo` 无控制台启动，不会在登录时弹出 PowerShell 黑框。状态、进程和日志：

```powershell
Get-ScheduledTask -TaskName RemoteConnectMCPAgent
Get-ScheduledTaskInfo -TaskName RemoteConnectMCPAgent
Get-Process rcm-agent -ErrorAction SilentlyContinue
Get-Content "$env:ProgramData\RemoteConnectMCPAgent\agent.log" -Tail 100
```

`-BinaryPath` 可以指向 Release 的平铺 command-agent ZIP（推荐，内含 `rcm-agent.exe` 及同一构建生成的全部 DLL），`-DesktopBinaryPath` / `-BrowserBinaryPath` 分别安装两个独立的 Native companion ZIP；它们被放在隔离子目录，避免同名运行库覆盖。安装器会先停止并移除旧 SCM 服务/启动任务和桌面计划任务，复制完整运行时并清理旧 DLL，再完成注册和启动；桌面伴侣同时生成兼容手动诊断的 `run-desktop-companion.ps1`，但计划任务实际指向无控制台的 `run-desktop-companion.vbs`。不会把校验文件或 README 放进运行目录。Center 配置写入受 ACL 保护的启动脚本（不含 Enrollment Token），状态目录 ACL 仅允许 SYSTEM 与本机管理员访问。卸载时默认保留机器身份；需要同时清除身份时增加 `-PurgeState`：

```powershell
./scripts/install-java-agent.ps1 -Uninstall
./scripts/install-java-agent.ps1 -Uninstall -PurgeState
```

如果旧版本已经安装过桌面伴侣而登录时仍短暂弹出 PowerShell 黑框，只需在管理员
PowerShell 7 中运行一次 `scripts/repair-desktop-companion-task.ps1`。它不读取、不轮换
Enrollment/Agent Token，只重写桌面计划任务和无控制台 VBS 启动器；后续登录由
`wscript.exe //B //NoLogo` 直接启动 `rcm-desktop-companion.exe`。

## 连接 ChatGPT

在 ChatGPT Business 工作区开发者模式中创建远程 MCP：

| 设置 | 值 |
| --- | --- |
| 服务器 URL | `https://remote-connect-mcp-gateway.example.invalid/mcp` |
| 身份验证 | 访问令牌 / API 密钥 |
| 标头方案 | Bearer，中文界面通常显示“持有者” |
| 令牌 | Center 的 MCP Token 原始值，不添加 `Bearer ` 前缀 |

Java Center 暴露 `/mcp` Streamable HTTP 端点并兼容 MCP `2026-07-28` 无会话协议；Go 兼容基线另外保留 Gateway 根路径映射，迁移时仍以 `/mcp` 作为稳定连接器地址。

MCP 服务端不能强制绕过 ChatGPT Web 自己的动作确认；是否显示“始终允许”由 ChatGPT Business 工作区策略决定。

## 构建与发布

Java 25 迁移路径使用仓库内 Gradle Wrapper；React 控制台使用 pnpm。所有测试、JVM 包、React 资源和 Native Image 都由 GitHub Actions 执行，开发机和目标宿主机不执行任何编译或打包，避免编译峰值占满内存。CI 会输出 SHA-256、manifest、SBOM/证明和可安装归档；Windows/Linux Agent ZIP 可直接交给对应安装器，验证器会拒绝路径穿越、重复文件和超大归档。Native Image 构建机缺少 `native-image` 时不得把 JVM JAR 冒充原生发布物。旧 Go 实现仍保留为兼容基线，但不再作为 Java 发布流程的必需依赖。

正式发布完全交给 GitHub Actions：`.github/workflows/java-release.yml` 在推送
`java-vX.Y.Z` 标签时先执行 JVM/React/PostgreSQL 门禁，再由匹配架构 runner 构建并烟测
Linux amd64/arm64 与 Windows amd64，最后上传带 SHA-256 和 OIDC Artifact Attestation 的 Release
资产。手动触发只做构建验证，不创建 Release。推送前无需在本机安装 JDK、Gradle、GraalVM、Go 或
Node；只需提交源码和工作流，由 GitHub-hosted runner 按目标架构完成构建。
仓库内的 Native/React Dockerfile 也要求工作流注入 `RCM_CI_BUILD=true`，本机直接构建镜像会在
编译前拒绝执行；Java Gradle 根配置同样会拦截本机的构建、测试、打包和 Native 任务，避免
直接调用 Wrapper 绕过门禁。

CI 产出 Native Image 后由工作流自动运行 `scripts/smoke-java.ps1/.sh`、Agent 注册和 WebSocket
烟测；开发机不执行构建或测试。若需要复核 CI 下载的工件，可将
`-CenterBinary`（或 shell 的第一个参数）指向已验证的 Native Image，并运行相应烟测脚本。
在 Windows Native 构建后还可运行 `scripts/smoke-java-websocket.ps1 -CenterBinary <center>`，
验证带 Bearer 的 WebSocket `ready`、`ping/pong` 和任务创建后的 `wake` 提示；该脚本只启用
临时内存 Center，不修改生产配置。
Linux 构建机可运行 `scripts/smoke-java-agent.sh <center> <agent> [port]` 完成同一闭环。
烟测只使用临时令牌和临时内存状态，结束时会清理进程及状态目录。

## 敏感配置

仓库内不保存运行令牌，也不再使用项目根目录 `.env`。生产环境使用三类独立令牌：

- MCP Token：只供 ChatGPT Web 连接器使用；
- Admin Token：只供控制台和管理 API 使用；
- Enrollment Token：由 Center Admin API 按机器名生成的一次性、1 小时至 30 天令牌，只供新 Agent 首次注册使用；生产默认不接受共享 env Enrollment Token，旧部署仅可临时设置 `RCM_CENTER_ALLOW_SHARED_ENROLLMENT=true` 完成迁移后关闭。

生产令牌应由部署平台的 Secret 管理；Agent 注册后获得独立机器 Token，Center 只保存其 SHA-256 摘要。Java 安装器在首次注册完成后不把一次性 Enrollment Token 写入服务环境；不要把任何真实令牌提交到 Git。

每次 PR、main 推送和 Java Release 由 `scripts/scan-repository-secrets.sh` 执行仓库卫生检查：只报告文件名，不把匹配内容写入 Actions 日志；真实域名、内网地址、私钥、常见 Provider 密钥和高熵 RCM Secret 赋值必须放在部署平台外部。

令牌不提供应用内自动轮换：MCP、Admin 和一次性 Enrollment Token 由部署平台的 Secret/env 直接管理。若令牌遗失或怀疑泄露，先在 Secret 管理中替换对应值，再按部署平台滚动重启 Center；已注册 Agent 使用各自的日常 Token，不受重新生成 Enrollment Token 影响。Enrollment Token 仅通过 Admin API 创建，成功注册一次后立即失效。

## License

[MIT](LICENSE)
