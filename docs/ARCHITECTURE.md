# Remote Connect MCP 目标架构

本文档定义 RCM 的长期架构边界和演进顺序。仓库同时保留 Go 兼容基线与 Java 25 Center/Agent 实现；Java 路径已覆盖异步任务、MCP、PostgreSQL/Liquibase、桌面工件和 Browser Worker 入口，正式切换仍需通过 Native Image、数据库、路由与故障恢复门禁。React 控制台独立部署。文档中的“规划中”能力不会在没有协议、权限和兼容性评估时自动暴露给 MCP。Java/React 的具体技术选型见 [`docs/TECH_STACK.md`](TECH_STACK.md)。

## 1. 总体拓扑

```text
ChatGPT / 其他 MCP 客户端
             |
             | HTTPS + Bearer MCP Token
             v
      Center（Kubernetes，目标 Java 25 Native Image）
        |  MCP Gateway
        |  Admin Console/API
        |  Agent Registry / Capability Router
        |  Task Queue / Lease / Attempt / Artifact
        |  Upgrade Orchestrator / Audit / Metrics
        |
        +---- 主动 HTTPS / WebSocket / QUIC ----+

      物理终端 Host H1
        +-- command Agent（Java Native Image，系统服务，可无人值守）
        +-- desktop Agent（Java Native Image，用户会话，桌面能力）
        +-- browser Agent（Java 编排 + 专用适配器）
```

一个物理终端可以有多个物理 Agent。每个 Agent 都有独立 `machine_id`、Token、状态目录、进程和能力；`host_id` 只用于在 Center 中归组，不授予权限，也不能替代认证。

## 2. Center、Agent 与能力

### Center

Center 是唯一的公网入口和控制面；迁移目标为 Java 25 + Spring Boot MVC，正式发布使用平台原生二进制，当前 Go 实现作为兼容迁移基线：

- `/mcp`：精简、稳定的模型调用面；
- `/api/v1` 和 `/console/`：管理员操作面；
- `/agent/v1/*`：Agent 注册、心跳、任务和工件通道；
- `/metrics`：仅输出计数类指标，不返回命令、路径、Token 或输出；
- 升级编排：Center 保存 Release URL/SHA-256 和 canary/批次状态；Agent 通过 HTTPS 直取受校验资产，后续可把同一接口接到对象存储缓存，不改变 Agent 协议。

Center 不扫描 Agent 文件系统，也不假设同一 `host_id` 的 Agent 权限相同。任务在排队时绑定目标 Agent，派发时再次检查能力和租约。

### Agent

Agent 是执行信任边界；迁移目标为不带 Spring 的 Java 25 模块化 Native Image，负责：

- 在本机最终校验工作目录、符号链接/Junction 和进程参数；
- 执行命令、回传有界输出、续传断点和最终状态；
- 根据 capability 选择可执行的专用任务；
- 以指数退避、抖动和租约恢复连接；
- 在升级时保留可恢复任务并报告实际版本。

系统服务 Agent 默认只声明 `command,durable_tasks`。启用桌面能力时，服务仍可声明
`desktop`，但真正的 GUI 操作由同一身份的用户会话 companion 代办；浏览器 Agent
必须显式声明 `browser`，不把浏览器 Cookie 或调试端口凭据上传到 Center。

## 3. 任务与工件模型

任务生命周期：

```text
queued -> dispatching -> running -> completed
                              \-> failed / canceled
```

每次派发都有租约和 Attempt 语义：

- `idempotency_key` 防止 ChatGPT 超时重试造成重复执行；
- 无超时命令使用可恢复进程和输出游标；本地 durable 日志仍有硬上限，超过上限会停止任务并保留有界前缀；
- 有超时命令使用附着进程，Agent 重启后明确报告中断；
- 输出和图片等二进制工件与任务状态分离存储，MCP 只返回有界页或图片内容；
- Center 重启、Agent 断线、单次 HTTP 超时都不会自动创建第二个逻辑任务。
- `DISPATCHING` 租约过期会回到队列；无超时持久任务只有在同一 Agent 带着恢复任务 ID 重连时才续租，定时任务租约过期则标记失败，避免不确定的重复执行。

### 3.1 存储与内存边界

Java Center 在一个进程内只选择一个持久化适配器。生产配置固定为
`RCM_CENTER_PERSISTENCE_MODE=postgres`，PostgreSQL 是机器、任务、租约、Attempt、Token
摘要、配置、项目/worktree、升级活动、输出游标和工件元数据的唯一权威来源；Liquibase
由独立 migration Job 管理 schema。Go 的 JSON 文件存储只用于一次性迁移前基线，Java
memory adapter 只用于协议测试/开发，不能在生产与 PostgreSQL 并行或双写。

进程内存仅保留可丢失的加速状态：HTTP/WebSocket 唤醒会话、任务等待条件，以及必要的
短期只读快照。所有读热点缓存都必须有界、可按事件失效，缓存丢失时直接回源 PostgreSQL；
不会把租约、Attempt、Token、输出或工件作为“只在内存中”的事实。当前使用 PostgreSQL
共享缓冲区、Hikari 连接池和阻塞式 `LISTEN/NOTIFY`，不额外引入 Redis/Kafka 第二状态层。

## 4. 范围与项目策略

RCM 保留两种顶层策略：

- `unrestricted`：整机运维模式，适合受信任的系统 Agent；
- `workspace`：Center 做目标平台词法校验，Agent 做真实路径、符号链接和 Junction 校验。

Project Registry 已作为可选的开发工作流落地：项目由管理员按 Agent 注册，Center 只保存不透明项目 ID、路径元数据和 worktree
状态；Git `worktree add/remove` 以异步任务在 Agent 上执行，项目 ID 不能绕过 Agent 的根目录策略。对于需要修改代码的任务，优先
使用 Agent 管理的 Git worktree；原始 checkout 只有在本机显式接受结果后才改变。提交/差异审阅和显式合并仍属于后续阶段。

工作区模式不是 root 级沙箱。需要强隔离时，配合低权限账户、ACL、systemd `ReadWritePaths`、Windows 受限账户、容器或虚拟机；RCM 不会把“cwd 校验”描述成完整安全边界。

## 5. Desktop Agent

Desktop 能力默认由同一安装包的用户会话伴侣提供，而不是让 SYSTEM 服务伪装成 GUI：

- Center 仍只登记一个 Agent 身份；服务进程负责心跳、任务队列和权限校验，用户会话进程以 `--desktop-companion` 启动；
- 伴侣只绑定 `127.0.0.1`，通过 `STATE_DIR/desktop/desktop-companion.json` 的本机令牌和 ACL 保护的 IPC 接收任务，不向 Center 注册第二台机器；
- 当前支持有界截图/屏幕枚举、应用启动、点击/拖拽、组合按键、文本输入、剪贴板和窗口聚焦；截图以 `image/png` 工件回传，不回显 Base64 文本；
- 未登录或伴侣不可达时，输入操作明确失败、截图/启动按兼容回退处理，命令 Agent 继续保持无人值守可用；
- 同一终端仍可按不同 `STATE_DIR` 运行多个独立物理 Agent，互不共享 Center Token 或能力。

## 6. Browser Agent

Browser Agent 与 Desktop Agent 分离，Java Agent 负责身份、生命周期和能力路由，浏览器运行时通过本机适配器接入，避免把浏览器 Cookie、调试端口和整棵页面树带入普通命令面：

- Playwright 优先使用官方 Java API；Patchright/Comoufox 通过受控 Node/TypeScript Worker 适配，但 Worker 不注册第二个物理 Agent；
- 默认返回 Accessibility Snapshot、结构化元素引用和有界网络/控制台摘要；
- 截图只作为观察或工件，优先结构化状态而不是视觉点击；
- 浏览器 profile、Cookie、扩展和 CDP 凭据只留在 Agent 主机；
- Center 只路由带 `browser` capability 的任务，不代理任意第三方 MCP。Agent 为每次任务生成
  有界临时 JSON 请求文件，通过 `RCM_BROWSER_TASK_REQUEST_FILE` 传给适配器并在任务结束后删除；
 `RCM_BROWSER_TASK_COMMAND` 仅作为旧 Worker 的兼容字段保留。
  仓库提供 `scripts/browser-worker.mjs` 作为最小参考适配器，通过 `RCM_BROWSER_ENGINE` 动态加载 Playwright、Patchright 或 Comoufox，并把 `navigate`、`snapshot`、`click`、`fill`、`press`、`wait`、`title`、`url`、`screenshot`、`download`、`evaluate` 映射为少量结构化操作。`snapshot` 同时返回最多 64 个有界 `rcm-ref-v1` 元素引用；引用只编码 role/name、test-id、placeholder 或 text 定位及序号，后续任务可以复用引用而不把整棵 DOM 带回 MCP。启用独立 profile 时，Agent 在状态目录保留最近页面的脱敏 origin/path，会话重新打开时先尝试恢复该页面；query、fragment、Cookie 和 CDP 凭据永不写入会话标记。
  结果清单由 Agent 校验 MIME、路径、大小和 SHA-256 后才上传单个工件；适配器异常或越界均 fail-closed。Worker 已支持 CSS、`rcm-ref-v1`、role、label、placeholder、text 和 test-id 结构化定位，并返回有界脱敏网络/控制台/页面错误摘要；稳定引用依赖页面仍可访问，定位失败时应重新执行 `snapshot`。目标主机仍需安装浏览器运行时并完成持久会话、跨浏览器和真实站点回归。

## 7. 连接与配置演进

Java Agent 默认通过 25 秒 HTTPS 长轮询领取任务；请求在任务、取消、配置或升级事件到达时立即返回，空闲只由服务端 deadline 结束。旧 Go Center 或显式禁用长轮询时才退避重试；可选 WebSocket 仅传递唤醒提示，任务数据和认证仍由 HTTPS 负责。PostgreSQL 模式下 Center 还会用阻塞式 LISTEN/NOTIFY 在多副本之间转发 Agent 唤醒和按任务摘要路由的任务等待提示；高频输出 chunk 只在本副本唤醒任务等待者，避免广播风暴，终态/控制面事件仍跨副本广播。通知是 best-effort，丢失时由长轮询 deadline 和下一次显式读取修复，不把数据库通知当作任务状态来源。

1. WebSocket：已实现为可选 wake-only 通道，适合普通公网反向代理并降低事件延迟；消息丢失时由 HTTPS 长轮询补偿；
2. PostgreSQL LISTEN/NOTIFY：已实现跨 Center 副本的 Agent 唤醒和 `task_wait` 事件桥接，驱动在数据库 socket 上阻塞等待，连接异常时才自动退避重连；
3. QUIC：在需要更低延迟和更强连接恢复时启用；
4. Long polling：作为 Java Agent/控制台的默认事件通道；固定间隔 polling 仅保留给旧兼容端或内核事件能力不可用的极旧环境。

Agent 心跳自描述版本、平台、HostID、角色、能力、范围策略和会话状态；当前已支持按单调递增 generation 热更新长轮询等待时间、兼容退避间隔和并发槽位，并原子持久化。Token、身份、工作根目录和执行账户仍必须显式重注册或重启。

## 8. 安全基线

- MCP、Admin、Enrollment、Agent Token 分离；Center 只保存摘要；
- Enrollment Token 一次性、短期、与 Agent 名称绑定；
- 任务命令不做 allow-list，但限制输入大小、环境变量格式、超时、并发和输出；
- 所有范围判断在 Agent 本机最终执行；
- 审计日志只保留必要元数据，默认不落完整命令、环境和工件内容；
- 升级包校验 SHA-256，后续增加签名和来源证明；
- 旧 Center/Agent 通过可忽略字段和能力协商兼容，新能力缺失时 fail-closed。

## 9. MCP 面设计原则

工具数量不是固定数字，但必须满足：

- 每个工具有独立用户价值；
- 输入、输出、列表和日志都有上限；
- 能力不通过复制工具名扩散；
- 工具结果只返回下一步所需的精简视图；
- 新工具不得要求重新创建既有 `/mcp` 连接器。

当前核心命令面保持 `machines_list`、`machine_info`、`command_start`、`task_wait`、`task_output`、`task_cancel`；桌面和浏览器各自通过 capability 专用工具面接入，不把大量底层 API 一次性暴露给 ChatGPT。

## 10. 演进顺序

1. **已落地/正在固化**：异步 MCP/任务、独立进程输出 drain、断线续传、幂等、租约、工作区双端校验、输出/PNG 工件限制、Token 分层、HostID、多 Agent 能力路由。
2. **语言和存储迁移阶段**：冻结 JSON Schema/兼容夹具，Java Center（PostgreSQL + Liquibase）并行实现，随后替换 Java command Agent；Go 组件在兼容窗口内保留。
3. **桌面和控制台阶段**：Desktop Agent 截图/启动、工件预览、注册脚本，React 控制台独立部署并按 Host/Agent 分组。
4. **可靠性阶段**：完善 Task Attempt、死信/过期任务和 WebSocket；配置代次/热更新与心跳自描述已落地。
5. **开发工作流阶段**：Project Registry 与 Git worktree 已落地基础闭环；继续补结构化文件/Git/检查、提交审阅和显式合并工具。
6. **专用自动化阶段**：Browser Agent 的 Playwright/Patchright/Comoufox 完整 Worker 协议、会话生命周期和工件策略；桌面输入基础能力已落地，继续补窗口/焦点适配。
7. **规模化阶段**：在 PostgreSQL + Liquibase 持久化已经成为默认生产路径后，再按多副本需求增加 Center 副本、LISTEN/NOTIFY 唤醒和对象存储扩展，保持 MCP URL 与工具契约不变。

明确不在当前范围：OAuth 2.1 强制化、代理其他 MCP、把 `workspace` 冒充 OS 沙箱、把 ChatGPT 的动作审批策略写入 Center、或一次性暴露海量浏览器/桌面底层工具。
