# RCM 异步执行契约

RCM 的“异步”是端到端契约，不只是把命令丢到一个线程池：请求、排队、派发、执行、输出上传和状态收口都有独立生命周期。任何一次 HTTP 超时或客户端重试都不能隐式再执行一遍命令。

## 心跳配置热更新

Agent 的 `/agent/v1/poll` 响应可以携带可选 `config` 对象：

```json
{"generation":4,"poll_interval_ms":1500,"max_concurrency":2}
```

`generation` 由 Center 单调递增，Agent 只接受更高版本，并把不含密钥的配置
原子写入 `STATE_DIR/runtime-config.json`；当前进程立即使用新的心跳间隔和并发槽位，
重启后继续沿用最后一次成功配置。输出上限、工作区范围和 Token 仍是启动时配置，
不会通过热更新绕过本机安全边界。管理端接口为
`GET/PUT /api/v1/admin/machines/{machineId}/config`。

## 调用方语义

- `command_start`、`desktop`、`browser` 只负责校验并创建任务，成功后立即返回 `task_id`；默认不等待子进程。
- 项目/worktree 注册与 `git worktree add/remove` 同样只创建异步任务；创建完成前不能把 worktree 当作任务 cwd，重复请求应使用同一个 `idempotency_key`。
- `task_wait` 仅允许显式的 0–20 秒短等待，用于减少一次往返；超时返回当前快照，不表示任务失败。
- `task_output` 使用字节 cursor 分页，单页最多 64 KiB；调用方必须保存 `next_cursor`，不能把整段输出塞回 MCP 上下文。
- `idempotency_key` 在同一 Agent 上绑定命令参数；重试得到原任务视图，参数变化会被拒绝。
- `task_cancel` 是幂等的：排队任务立即取消，已派发任务先进入 `cancel_requested`，由 Agent 杀掉进程并上报终态。

## Center

- MCP 使用官方 `McpAsyncServer`；工具处理返回 Reactor `Mono`，在可关闭的虚拟线程执行器上运行。
- Agent/Admin Servlet 控制器返回 `CompletableFuture<ResponseEntity<?>>`。JDBC 是阻塞集成，但只运行在 Center 虚拟线程，不占住 Tomcat 容器载体线程。
- PostgreSQL 写入以单事务完成状态、租约、游标和工件更新；数据库断线不会创建第二个任务。
- `queued -> dispatching` 使用租约和 `SKIP LOCKED`；租约过期后回到队列，等待下一次心跳派发。

## Agent

- 一个任务由独立虚拟线程承载；进程等待、stdout/stderr drain、Center 上传是三个可相互取消的阶段。
- 普通有超时任务把输出写入有界磁盘 spool；单任务上限与 Agent 级聚合上限同时生效，达到聚合上限时仅截断后续输出、不中断子进程。上传线程按 cursor 幂等提交，并以 1–30 秒指数退避处理 408/425/429/5xx/网络断线。
- 无超时任务使用 durable PID/日志记录。Agent 重启会按记录重新附着；服务停止只中断 Agent 观察者，不重复启动命令。durable 日志超过 `REMOTE_CONNECT_MCP_AGENT_MAX_OUTPUT_BYTES` 时终止任务并上报 `failed + output_truncated`，保护磁盘。
- 所有 HTTP 请求由 `HttpClient.sendAsync` 发起并带超时取消；认证错误不重试，运行时连接错误采用带抖动的退避。
- Agent 退出时取消普通任务的观察线程；输出 spool、临时截图和一次性下载文件均在终态清理，Windows 句柄关闭存在短暂延迟时使用有界重试。

## 前端

React 控制台只请求分页摘要；Admin Token 仅保存在内存。验证 Token 后使用单飞的 5 秒刷新循环，上一轮未完成时不会堆积下一轮请求，AbortController 在 8 秒后取消失联请求。

该契约允许 ChatGPT Web 在一次消息超时后安全重试：重试只读取同一个任务和 cursor，不会把长时间命令重新执行一遍。
