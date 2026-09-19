# Remote Connect MCP 目标架构

本文档定义 RCM 的长期架构边界和演进顺序。上游需求基线见 [`docs/REQUIREMENTS.md`](REQUIREMENTS.md)；M:M 用户、对话、连接和执行关系见 [`docs/MULTI_USER_MODEL.md`](MULTI_USER_MODEL.md)；本文只说明组件如何实现这些需求。当前运行路径统一为 Java 25 Center/Agent 与独立 React 控制台。文档中的“规划中”能力不会在没有协议、权限和安全评估时自动暴露给 MCP。Java/React 的具体技术选型见 [`docs/TECH_STACK.md`](TECH_STACK.md)。

## 1. 总体拓扑

```text
多个 Web 账号 / 多个对话 / 其他 MCP 客户端
             |
             | 固定 /mcp + 各自的不透明 Bearer Token
             v
      Center（Kubernetes，目标 Java 25 Native Image）
        |  MCP Gateway
        |  Principal / Token / Conversation / Connection
        |  Admin Console/API
        |  Agent Registry / Capability Router
        |  Task Queue / Lane / Lease / Attempt / Artifact
        |  Upgrade Orchestrator / Audit / Metrics
        |
        +---- 主动 HTTPS / WebSocket / QUIC ----+

        物理终端 Host H1
          +-- command-agent（Java Native Image，系统服务，可无人值守）
          +-- desktop-companion（独立 Java Native Image，用户会话，可选）
          +-- browser-agent（独立 Java Native Image，按任务启动）
                +-- browser worker（本机 Playwright/Patchright/Comoufox 适配器，可选）
```

一个物理终端默认只有一个向 Center 注册的 `command-agent` 身份。桌面伴侣和浏览器 Worker 是该身份的本机子组件，不注册第二个
`machine_id`，也不持有 Center Token；`host_id` 仍只用于归组，不授予权限，也不能替代认证。需要强隔离时才为同一终端显式启动多个
command-agent 实例，并为每个实例使用独立状态目录、一次性注册 Token 和 machine ID。
每个 `machine_name` 在 Center 中仍是唯一的人类可读身份；同名重装只有在
`host_id` 也一致时才会复用原 `machine_id` 并旋转日常 Token。另一个
`host_id` 试图占用同名时会明确失败，避免错误的注册令牌接管已有 Agent。
注册后的 `machine_name`/`host_id` 也是不可由心跳修改的身份字段；机器改名或
重新归组必须重新注册，心跳只更新平台、版本、能力和运行时自描述。

多个主体和对话共享同一个 Center，但不共享默认授权：Bearer Token 在 Center 侧映射到
`Principal`，再经过项目 ACL、机器能力和任务执行合同裁剪。MCP 连接或外部对话 ID 只作
关联信息，不能替代 Token。一个主体可以打开多个对话和 MCP Connection；一个项目可以
显式共享给多个主体；同一 worktree 的写任务通过执行车道串行，不同 worktree 才并行。

## 2. Center、Agent 与能力

### Center

Center 是唯一的公网入口和控制面；正式发布使用 Java 25 + Spring Boot MVC 平台原生二进制：

- `/mcp`：精简、稳定的模型调用面；
- `/api/v1` 和 `/console/`：管理员操作面；
- `/agent/v1/*`：Agent 注册、心跳、任务和工件通道；
- `/metrics`：仅输出计数类指标，不返回命令、路径、Token 或输出；
- 升级编排：Center 保存 Release URL/SHA-256 和 canary/批次状态；Agent 通过 HTTPS 直取受校验资产，后续可把同一接口接到对象存储缓存，不改变 Agent 协议。升级活动读取有界的分段机器快照（最多 10000 台），Admin 机器列表仍按 200 台分页。

Center 不扫描 Agent 文件系统，也不假设同一 `host_id` 的 Agent 权限相同。任务在排队时绑定目标 Agent，派发时再次检查能力和租约。

### 2.1 用户、对话与 MCP 连接

RCM 采用三层身份/上下文分离：

1. `Principal` 是 RCM 内部用户或服务主体，由不透明 Bearer Token 识别；
2. `Conversation` 和 `MCPConnection` 是 Web 对话与 Streamable HTTP 连接的生命周期记录，
   一个主体可以拥有多个，多个对话也可以在授权后共同参与一个项目；
3. `ExecutionSession` 和 `Task` 是真正的执行边界，绑定 machine、project/worktree/path、
   capability、预算、幂等键和执行车道。

MCP 工具不要求模型传入 `principal_id` 或用户账号。Center 从认证头派生主体，并在返回
机器、项目、任务、输出和工件时做主体/项目 ACL 过滤。每个主体使用独立不透明 Bearer Token；
配置型单主体部署也通过同一 Principal 流程解析。

### 2.2 执行车道与公平调度

`lane_key` 由 Center 根据目标机器、项目/worktree/path 和能力派生，调用方不能任意覆盖：

- 同一车道的可能写操作只有一个活动任务；其余任务持久化排队并由事件唤醒；
- 只读任务可以在 Agent 总预算允许时有限并行；Shell 默认按可能写操作处理；
- 不同 worktree 使用不同车道，可以在 Agent 并发和资源上限内并行；
- 每个主体有独立的排队/运行配额，调度器使用公平策略避免单一主体饿死其他主体；
- 车道 lease、任务状态和幂等关系持久化在 PostgreSQL，通知丢失时通过任务 ID 和游标恢复，
  不运行 Center 固定频率扫描。

工作区策略由执行合同明确给出：写任务默认使用会话专属 `isolated` worktree；用户明确要求
共同 checkout 时使用 `shared_serial` 并接受排队；整机/环境任务使用 `host`，独立进程仍然
共享主机写车道。任务结果通过任务句柄和专属序列游标回传，不依赖 ChatGPT 的私有对话 ID，
因此多个 Web 对话可以同时提交任务，但不会收到彼此的主动结果。

### Agent

Agent 是执行信任边界；迁移目标为不带 Spring 的 Java 25 模块化 Native Image，负责：

- 在本机最终校验工作目录、符号链接/Junction 和进程参数；
- 执行命令、回传有界输出、续传断点和最终状态；
- 根据 capability 选择可执行的专用任务；
- 以指数退避、抖动和租约恢复连接；
- 在升级时保留可恢复任务并报告实际版本。

系统服务 Agent 默认声明 `command,durable_tasks,file_transfer`。启用桌面能力时，服务仍可声明
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
- 输出和图片等二进制工件与任务状态分离存储，通用文件通过 Artifact Transport v2 的独立流式数据面传输；MCP 只返回有界页、文件句柄或图片内容；
- Center 重启、Agent 断线、单次 HTTP 超时都不会自动创建第二个逻辑任务。
- `DISPATCHING` 租约过期会回到队列；无超时持久任务只有在同一 Agent 带着恢复任务 ID 重连时才续租，定时任务租约过期则标记失败，避免不确定的重复执行。

### 3.1 存储与内存边界

Java Center 在一个进程内只选择一个持久化适配器。生产配置固定为
`RCM_CENTER_PERSISTENCE_MODE=postgres`，PostgreSQL 是机器、任务、租约、Attempt、Token
摘要、配置、项目/worktree、升级活动、输出游标和工件元数据的唯一权威来源；Liquibase
由独立 migration Job 管理 schema。Java memory adapter 只用于协议测试/开发，不能在生产与 PostgreSQL 并行或双写。

进程内存仅保留可丢失的加速状态：HTTP/WebSocket 唤醒会话、任务等待条件，以及必要的
短期只读快照。所有读热点缓存都必须有界、可按事件失效，缓存丢失时直接回源 PostgreSQL；
不会把租约、Attempt、Token、输出或工件作为“只在内存中”的事实。当前使用 PostgreSQL
共享缓冲区、Hikari 连接池和阻塞式 `LISTEN/NOTIFY`，不额外引入 Redis/Kafka 第二状态层。

### 3.2 Artifact Transport v2

文件传输是独立于命令输出的二进制数据面。`artifact_put` 接收 ChatGPT Apps
SDK 的短期 `download_url`/`file_id` 引用，Center 只在当前请求中下载并写入已配置的字节后端，随后
以 `file_transfer` 任务将对象流送到 Agent；`artifact_get` 反向读取 Agent 文件，Center
校验大小与 SHA-256 后生成短期签名文件对象 URL。任务 JSON 只携带 transfer/artifact
引用和路径，不携带 Base64 或文件内容。文件名、范围合同、来源 Agent、用户主体和会话
均在 Center 与 Agent 两端校验。v2 使用 HTTP 流式传输和原子临时文件；大文件通过
`HEAD` 偏移确认、8 MiB `Content-Range` 分块和 Web→Agent `Range` 续传，partial
spool 只在最终哈希提交后删除。默认字节后端是 Center 持久卷上的 filesystem；
`RCM_CENTER_ARTIFACT_STORE=http` 才启用内部 HTTPS 对象网关，外部对象存储不是运行前置条件。
每个工件写入独立 `expires_at`，签名 URL TTL 与文件 TTL 解耦；外部 CronJob 以有界批次执行
TTL GC，Center 内不运行定时轮询。React Artifact Viewer 和真实连接器渲染仍需独立验收，
详见 [`ARTIFACT_TRANSPORT_V2.md`](ARTIFACT_TRANSPORT_V2.md)。

## 4. 范围与项目策略

RCM 的范围由每个任务携带的 Center-issued execution contract 确定，支持五种模式：

- `project`：注册项目根目录；
- `worktree`：注册项目下的一个 Git worktree；
- `path`：调用方明确提供的绝对根目录；
- `workspace`：Agent 注册的工作区根目录；
- `unrestricted`：整机运维模式，必须由调用方显式声明，不能由默认值或模型猜测获得。

Center 持久化任务的 machine/host、项目/worktree、范围根、能力、预算、过期时间、风险和幂等意图；Agent
在执行前再次校验身份、能力、过期时间和真实路径。范围合同是误操作控制边界，不把 cwd 校验描述成操作系统沙箱。

Project Registry 已作为可选的开发工作流落地：项目由管理员按 Agent 注册，Center 只保存不透明项目 ID、路径元数据和 worktree
状态；Git `worktree add/remove` 以异步任务在 Agent 上执行，项目 ID 不能绕过 Agent 的根目录策略。对于需要修改代码的任务，优先
使用 Agent 管理的 Git worktree；原始 checkout 只有在本机显式接受结果后才改变。提交/差异审阅和显式合并仍属于后续阶段。

工作区、项目和路径模式不是 root 级沙箱。需要强隔离时，配合低权限账户、ACL、systemd `ReadWritePaths`、Windows 受限账户、容器或虚拟机；RCM 不会把“cwd 校验”描述成完整安全边界。

## 5. Desktop Agent

Desktop 能力默认由同一安装包的用户会话伴侣提供，而不是让 SYSTEM 服务伪装成 GUI：

- Center 仍只登记一个 Agent 身份；服务进程负责心跳、任务队列和权限校验，用户会话由独立的 `rcm-desktop-companion` 可执行文件启动；
- 伴侣只绑定 `127.0.0.1`，通过 `STATE_DIR/desktop/desktop-companion.json` 的本机令牌和 ACL 保护的 IPC 接收任务，不向 Center 注册第二台机器；
- command-agent 在启动/重注册时原子发布 `desktop-companion-policy.json`；伴侣在 IPC 执行前再次检查 scope、真实路径和合同过期时间。缺失或损坏策略均 fail-closed；
- 当前支持有界截图/屏幕枚举、应用启动、点击/拖拽、组合按键、文本输入、剪贴板和窗口聚焦；截图以 `image/png` 工件回传，不回显 Base64 文本；
- 伴侣使用状态目录中的 `desktop-companion.lock` 保证单实例；默认最多 4 个并发 IPC 请求、16 个仍存活的启动进程，达到上限时返回可重试错误；进程退出通过 `ProcessHandle.onExit()` 释放槽位，不留下无限增长的注册表；
- 未登录或伴侣不可达时，所有桌面操作都明确失败，不让 SYSTEM command-agent 伪装成用户桌面；命令 Agent 的无人值守命令能力继续独立可用；
- command-agent 使用 `agent.lock` 防止服务重启重叠产生第二个任务执行器；停止时附着任务按既有取消语义回收，durable 任务保留其可恢复日志。

任务的环境变量会在 Center 持久化前、Agent 启动子进程前各过滤一次；`TOKEN`、`PASSWORD`、`PASSWD`、`SECRET`、`COOKIE`、`AUTHORIZATION`、`API_KEY`、`PRIVATE_KEY` 和 `CREDENTIAL` 形态的键不会进入任务投影或子进程环境。MCP 列表只返回固定大小摘要：机器列表最多 25 条且不带运行时详情，项目列表不带本地路径且 worktree 摘要最多 10 条；需要时通过显式 `project(detail)` 分页取详情，路径必须再次显式开启。任务输出使用游标分页，JSON 文本有 192 KiB 最后防线。图片只有不超过 512 KiB 才内联，较大图片/下载只返回 SHA-256、大小和控制台下载提示，避免工具结果污染对话上下文。

## 6. Browser Agent

Browser Agent 与 Desktop Agent 分离，Java Agent 负责身份、生命周期和能力路由，浏览器运行时通过本机适配器接入，避免把浏览器 Cookie、调试端口和整棵页面树带入普通命令面：

- Playwright 优先使用官方 Java API；Patchright/Comoufox 通过受控 Node/TypeScript Worker 适配，但 Worker 不注册第二个物理 Agent；
- 默认返回 Accessibility Snapshot、结构化元素引用和有界网络/控制台摘要；
- 截图只作为观察或工件，优先结构化状态而不是视觉点击；
- 浏览器 profile、Cookie、扩展和 CDP 凭据只留在 Agent 主机；
- Center 只路由带 `browser` capability 的任务，不代理任意第三方 MCP。Agent 为每次任务生成
  有界临时 JSON 请求文件，通过 `RCM_BROWSER_TASK_REQUEST_FILE` 传给适配器并在任务结束后删除；
 Worker 只接收 Agent 写入的结构化请求文件，不从环境变量读取命令。
  仓库提供 `scripts/browser-worker.mjs` 作为最小参考适配器，通过 `RCM_BROWSER_ENGINE` 动态加载 Playwright、Patchright 或 Comoufox，并把 `navigate`、`snapshot`、`click`、`fill`、`press`、`wait`、`title`、`url`、`screenshot`、`download` 映射为少量结构化操作。Worker 不提供任意 `evaluate` 脚本入口，避免页面脚本把 Cookie、Profile 或其他凭据带回 Center。`snapshot` 同时返回最多 64 个有界 `rcm-ref-v1` 元素引用；引用只编码 role/name、test-id、placeholder 或 text 定位及序号，后续任务可以复用引用而不把整棵 DOM 带回 MCP。启用独立 profile 时，Agent 在状态目录保留最近页面的脱敏 origin/path，会话重新打开时先尝试恢复该页面；query、fragment、Cookie 和 CDP 凭据永不写入会话标记。
  结果清单由 Agent 校验 MIME、路径、大小和 SHA-256 后才上传单个工件；适配器异常或越界均 fail-closed。Worker 已支持 CSS、`rcm-ref-v1`、role、label、placeholder、text 和 test-id 结构化定位，并返回有界脱敏网络/控制台/页面错误摘要；稳定引用依赖页面仍可访问，定位失败时应重新执行 `snapshot`。目标主机仍需安装浏览器运行时并完成持久会话、跨浏览器和真实站点回归。
- Agent 默认只允许 1 个 Browser Worker（总并发为 1 时自然为 1），可通过 `REMOTE_CONNECT_MCP_AGENT_MAX_BROWSER_WORKERS` 提高到最多 8，且始终不超过总并发。每个 browser 任务由独立的 `rcm-browser-agent` Native 进程编排本机适配器；该进程无 Center Token、只存活一个任务，任务有默认 300 秒超时、最长 24 小时硬上限，超时/取消/Agent 关闭会终止整个子进程树并删除临时请求、结果和工件目录；达到 Browser cap 时 Agent 从下一次 poll 的 `available_capabilities` 中移除 `browser`，不会在本机堆积等待进程。
- command 和 browser-agent supervisor 共享 `REMOTE_CONNECT_MCP_AGENT_MAX_TOTAL_CHILD_PROCESSES` Agent 级进程总预算（默认随并发增长但封顶 256，范围 1–4096）。监督器在任务启动时先为根进程保留名额，观察到新增后代时动态占用；预算耗尽立即终止该任务并回传原因，任务退出时释放名额。用户会话中的 desktop-companion 不进入该预算：它只由自己的 IPC 并发、GUI 启动上限和 `ProcessHandle.onExit()` 回收机制管理，command-agent 在没有伴侣时不执行桌面直启回退。这样三个 Native 目标不会共享桌面实现、进程注册表或资源回收代码。

## 7. 连接与配置演进

Java Agent 默认通过 25 秒 HTTPS 长轮询领取任务；请求在任务、取消、配置或升级事件到达时立即返回，空闲只由服务端 deadline 结束。可选 WebSocket 仅传递带单调序列号的唤醒提示，任务数据和认证仍由 HTTPS 负责。PostgreSQL 模式下保留阻塞式 LISTEN/NOTIFY 桥接能力；通知是 best-effort，丢失时由长轮询 deadline 和下一次显式读取修复，不把数据库通知当作任务状态来源。

1. WebSocket：已实现为可选 wake-only 通道，适合普通公网反向代理并降低事件延迟；消息丢失时由 HTTPS 长轮询补偿；
2. PostgreSQL LISTEN/NOTIFY：已实现跨 Center 副本的 Agent 唤醒和 `task_wait` 事件桥接，驱动在数据库 socket 上阻塞等待，连接异常时才自动退避重连；
3. QUIC：在需要更低延迟和更强连接恢复时启用；
4. Long polling：作为 Java Agent/控制台的默认事件通道；不使用固定间隔 polling。

Agent 心跳自描述版本、平台、HostID、角色、能力、范围策略、会话状态和单任务/Agent 总进程预算；当前已支持按单调递增 generation 热更新长轮询等待时间、断线退避间隔和并发槽位，并原子持久化。Token、身份、工作根目录和执行账户仍必须显式重注册或重启。

## 8. 安全基线

- MCP、Admin、Enrollment、Agent Token 分离；Center 只保存摘要；
- Enrollment Token 一次性、短期、与 Agent 名称绑定；
- 任务命令不做 allow-list，但限制输入大小、环境变量格式、超时、并发和输出；
- 所有范围判断在 Agent 本机最终执行；
- 审计日志只保留必要元数据，默认不落完整命令、环境和工件内容；
- 升级包校验 SHA-256，后续增加签名和来源证明；
- 当前 Center/Agent 通过严格字段和能力协商工作，新能力缺失时 fail-closed。

## 9. MCP 面设计原则

工具数量不是固定数字，但必须满足：

- 每个工具有独立用户价值；
- 输入、输出、列表和日志都有上限；
- 能力不通过复制工具名扩散；
- 工具结果只返回下一步所需的精简视图；
- 新工具不得要求重新创建既有 `/mcp` 连接器。

当前核心模型面固定为 `machines`、`command`、`desktop`、`browser`、`project`、`artifact`、`task_read`、`task_cancel`；每个工具内部通过 operation/request 枚举承载能力，不把大量底层 API 一次性暴露给 ChatGPT。

长任务使用持久化 `rcm_task.change_seq` 作为版本事实，`LISTEN/NOTIFY` 仅用于事件唤醒；`task_read` 可按 `change_seq` 和输出 cursor 增量读取。进度快照与状态机分离，不改变任务终态合同，也不会把原始日志塞入 MCP 上下文。

## 10. 演进顺序

1. **已落地/正在固化**：异步 MCP/任务、独立进程输出 drain、断线续传、幂等、租约、工作区双端校验、输出/PNG 工件限制、Token 分层、HostID、多 Agent 能力路由。
2. **Java/存储固化阶段**：冻结 JSON Schema，Java Center（PostgreSQL + Liquibase）和三类 Native Agent 作为唯一发布路径。
3. **桌面和控制台阶段**：Desktop Agent 截图/启动、工件预览、注册脚本，React 控制台独立部署并按 Host/Agent 分组。
4. **可靠性阶段**：完善 Task Attempt、死信/过期任务和 WebSocket；配置代次/热更新与心跳自描述已落地。
5. **开发工作流阶段**：Project Registry 与 Git worktree 已落地基础闭环；继续补结构化文件/Git/检查、提交审阅和显式合并工具。
6. **专用自动化阶段**：Browser Agent 的 Playwright/Patchright/Comoufox 完整 Worker 协议、会话生命周期和工件策略；桌面输入基础能力已落地，继续补窗口/焦点适配。
7. **规模化阶段**：在 PostgreSQL + Liquibase 持久化已经成为默认生产路径后，继续扩展轻量多主体/执行车道、集中日志、可选 S3 兼容对象存储适配、SLO/告警和可选 QUIC provider，保持 MCP URL 与工具契约不变；Center 多副本、完整 SaaS 多租户和跨组织计费不属于当前路线。单 Center 默认继续使用独立持久卷，外部对象存储只有在 `RCM_CENTER_ARTIFACT_STORE` 显式切换后才启用。

明确不在当前范围：OAuth 2.1 强制化、代理其他 MCP、把任意范围模式冒充 OS 沙箱、把 ChatGPT 的动作审批策略写入 Center、或一次性暴露海量浏览器/桌面底层工具。
