# RCM 异步执行契约

RCM 的“异步”是端到端契约，不只是把命令丢到一个线程池：请求、排队、派发、执行、输出上传和状态收口都有独立生命周期。任何一次 HTTP 超时或客户端重试都不能隐式再执行一遍命令。当前权限与工具入口以 [`FULL_HOST_MODEL.md`](FULL_HOST_MODEL.md) 为准。

## 心跳配置热更新

Agent 的 `/agent/v1/poll` 响应可以携带可选 `config` 对象：

```json
{"generation":4,"poll_interval_ms":1500,"max_concurrency":2}
```

`generation` 由 Center 单调递增，Agent 只接受更高版本，并把不含密钥的配置
原子写入 `STATE_DIR/runtime-config.json`；当前进程立即使用新的心跳间隔和并发槽位，
重启后继续沿用最后一次成功配置。输出上限和 Token 仍是启动时配置，
不会通过热更新绕过本机安全边界。管理端接口为
`GET/PUT /api/v1/admin/machines/{machineId}/config`。

每个新任务还携带 Center 签发的 execution contract，其中包含 machine/host 身份、所需能力、预算、过期时间、风险和幂等意图。Agent 在真正启动子进程前再次校验该合同；合同缺失、过期或身份不匹配时直接 fail-closed。合同预算只能收紧 Agent 启动时的输出、工件和操作时长上限，
不会通过任务请求放大宿主机资源额度；Center 会把请求预算进一步收窄到最近一次 Agent runtime
descriptor 宣告的输出、子进程、CPU/RSS 和时长上限。runtime descriptor 使用版本号，当前只接受
schema 1；runtime descriptor 缺失或字段不完整时直接拒绝，不猜测新字段的语义。

## 调用方语义

- `command`、`desktop`、`browser` 和 `artifact(put/get)` 先创建持久任务。`wait_ms=0` 立即返回任务；正数只在 MCP 请求内短等结果，等待到期不会取消任务。`artifact(get)` 的 `auto` 默认短等，`async` 立即返回文件句柄。
- `task_read` 的 `wait_ms` 仅允许显式的 0–20 秒短等待，用于减少一次往返；超时返回当前快照，不表示任务失败。
- `task_read` 可以携带上次返回的 `change_seq`；Center 只在任务的持久化版本、输出游标或终态发生变化时返回，模型拿到 `task_id` 后不得重新提交同一操作。
- 所有执行类任务的后续状态和结果统一由 `task_read` 读取。结果直接携带任务句柄、状态、`change_seq`、输出游标和错误；不再返回 `next_action` 决策对象。
- Agent 进度是受 Attempt 栅栏保护的有限快照（phase、percent、message、current、total、unit），属于提示性元数据；进度上报失败不能阻塞命令执行，任务状态和输出仍是恢复事实来源。
- 命令可选择在任务环境设置 `RCM_PROGRESS_FILE`。Agent 只在已解析的任务 cwd 下注册 `WatchService`，读取不超过 64 KiB 的 JSON `TaskProgressUpdate`，按 250 ms 最小间隔节流并在任务结束时关闭；路径越界、符号链接、解析失败和进度上传失败都只记录为受限诊断，不影响命令本身。
- PostgreSQL 模式下，Center 会在读取任务行前捕获该任务的变更序号，并挂起等待
  `LISTEN rcm_task_change`/`NOTIFY`；不同任务不会互相唤醒并查询，不会按固定间隔持续查询。通知丢失或监听器故障时，
  等待在调用方的截止时间返回当前快照；下一次显式 `task_read` 再读取权威任务行。内存模式
  使用本地条件变量。任务行始终是唯一事实来源。
- `task_read` 使用字节 cursor 分页，单页最多 64 KiB；cursor 可以直接跳到已知字节偏移。`tail_bytes` 可从当前输出末尾读取有界字节，与 cursor/limit 互斥。顺序读取时调用方保存 `next_cursor`，不能把整段输出塞回 MCP 上下文。
- `idempotency_key` 在同一 Agent 上绑定命令参数；重试得到原任务视图，参数变化会被拒绝。
- `wait_ms` 和制品 `delivery_mode` 只影响本次结果呈现，不参与服务端自动幂等键的参数指纹；切换等待方式不会变成另一项任务。
- `task_cancel` 是幂等的：排队任务立即取消，已派发任务先进入 `cancel_requested`，由 Agent 杀掉进程并上报终态。

## Center

- MCP 使用官方 `McpAsyncServer`；工具处理返回 Reactor `Mono`，在可关闭的虚拟线程执行器上运行。
- Agent/Admin Servlet 控制器返回 `CompletableFuture<ResponseEntity<?>>`。JDBC 是阻塞集成，但只运行在 Center 虚拟线程，不占住 Tomcat 容器载体线程。
- PostgreSQL 写入以单事务完成状态、租约、游标和工件更新；数据库断线不会创建第二个任务。任务创建/取消/状态变更会 best-effort 发布 `pg_notify`：一条通道唤醒 Agent，另一条通道唤醒 `task_read`。高频输出/工件增量使用带任务摘要的 task-local 通知，不把每个 chunk 广播到所有 Admin/Agent；通知丢失时由长轮询截止时间和下一次显式读取补偿，不启动固定查询循环。
- `queued -> dispatching` 使用租约和 `SKIP LOCKED`；新任务、取消和升级会按机器发送唤醒提示，租约过期后由下一次正常派发请求按机器范围修复：无超时任务重新排队并允许原 Agent 带任务 ID 恢复，定时任务转为明确失败。修复产生的任务 ID 在事务提交后精准唤醒 `task_wait`，不运行 Center 侧定时扫描。
- Center 每次派发都会递增并把 `attempt` 放入任务响应；Agent 在状态、输出和工件请求中携带 `X-Task-Attempt`，Center 对已回收的旧 attempt fail-closed；缺少 attempt 的请求一律拒绝。

## Agent

- 一个任务由独立虚拟线程承载；进程等待、stdout/stderr drain、Center 上传是三个可相互取消的阶段。
- 普通有超时任务把输出写入有界磁盘 spool；单任务上限与 Agent 级聚合上限同时生效，达到聚合上限时仅截断后续输出、不中断子进程。上传线程按 cursor 幂等提交，并以 1–30 秒指数退避处理 408/425/429/5xx/网络断线。
- 无超时任务使用 durable PID/日志记录。Agent 重启会按记录重新附着；服务停止只中断 Agent 观察者，不重复启动命令。日志增量通过 `WatchService` 事件和 `ProcessHandle.onExit` 驱动，durable 日志超过 `REMOTE_CONNECT_MCP_AGENT_MAX_OUTPUT_BYTES` 时终止任务并上报 `failed + output_truncated`，保护磁盘。
- 所有 HTTP 请求由 `HttpClient.sendAsync` 发起并带超时取消；认证错误不重试，运行时连接错误采用带抖动的退避。
- Agent 退出时取消普通任务的观察线程；输出 spool、临时截图和一次性下载文件均在终态清理，Windows 句柄关闭存在短暂延迟时只做一次非阻塞清理，遗留文件交给下一次启动扫尾。

## 前端

React 控制台只请求分页摘要；Admin API 同时返回 `offset`、`limit`、`total` 和 `has_more`，列表不会因为机器或任务数量增长而一次性加载无界数据。Admin Token 仅保存在内存。验证 Token 后挂起一个 Admin 事件长连接，只有收到变更才重新读取分页摘要；传输故障才使用带退避的重连，AbortController 在请求截止时取消失联请求。

## 无稳态轮询门禁

- Agent 空闲时保持一次有界 HTTPS 长轮询；任务、取消、配置和升级事件通过条件变量、带单调序列号的 WebSocket 唤醒或 PostgreSQL `LISTEN/NOTIFY` 返回，不使用固定间隔请求。重复/乱序提示按序列号丢弃。
- Center 的 PostgreSQL 监听使用带正超时的 `PGConnection.getNotifications(30000)` 阻塞在数据库 socket；无参或零超时的非阻塞 API 不得用于监听循环。通知会立即返回，30 秒超时只用于连接存活检查。
- 进程输出/完成由 `fsnotify`、`WatchService`、`ProcessHandle.onExit`、Linux pidfd 或 Windows 进程句柄驱动；不通过固定间隔读取文件或探测 PID。
- 定时器仅允许用于请求截止时间、网络失败指数退避、关闭/终止宽限期和桌面拖拽动画。
- `scripts/check-event-driven.sh` 在 Java/React 生产路径上执行静态回归门禁：禁止固定间隔调度器和 pgjdbc 非阻塞通知 API。

该契约允许 ChatGPT Web 在一次消息超时后安全重试：重试只读取同一个任务和 cursor，不会把长时间命令重新执行一遍。
