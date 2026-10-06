# Full-host baseline

这份文档是当前 RCM 底层模型的权威说明。早期关于 project、worktree、workspace、path scope 和 Agent 本地根目录的设计文档仅保留作历史记录，不再描述运行时行为。

## 一个最小执行模型

```text
Console-issued MCP credential (Bearer/OAuth)
        │
        ├── MCP connection / conversation
        │       └── Center ExecutionSession
        │               └── durable Task + lease + result channel
        │
        └── per-machine tool grants ──> registered Agent (full host)
                              ├── command
                              ├── native files + file transfer
                              ├── desktop companion (user session)
                              └── browser adapter (on demand)
```

权限只有两层：

1. Center 判断当前 MCP 凭证能否使用某台机器上的指定工具；
2. 目标机操作系统决定 Agent 运行账户实际拥有的权限。

Agent 不再解析或执行任何 `scope_mode`、`scope_root`、`workspace_policy`、`project_id` 或 `worktree_id`。任务中的 `cwd` 只是工作目录提示，文件传输中的路径是目标机真实路径。

OAuth/MCP `scope` 只控制协议级读/执行能力；Console 凭证上的机器/工具矩阵进一步决定具体机器上可调用的工具。两者都不表示文件系统范围，也不会被翻译成 Agent 的路径白名单。

## 并发与结果隔离

- 每个认证主体/连接生成稳定的 ExecutionSession，任务结果通过主体和会话归属读取。
- command 写任务默认共享同一台机器的主机车道；原生 files 的查询任务使用读车道，修改任务使用写车道；Desktop 对同一用户桌面独占；Browser 按 host + session/profile 协调。
- 车道只解决并发一致性，不决定路径权限。
- 任务、输出、工件、传输和会话全部有明确 TTL/配额；Agent 默认使用事件唤醒的 HTTPS 长轮询。WebSocket 保留给已有安装的兼容路径。

## MCP 公开面

只发布：`machines`、`command`、`desktop`、`browser`、`files`、`artifact`、`file_card`、`task_read`、`task_cancel`。

Tool schema 只保留模型需要的意图：机器句柄、操作枚举、命令/浏览器请求、文件对象和任务句柄。会话、车道、风险、预算、主体和租约由 Center 派生。操作分支约束会在任务下发前检查必需字段与互斥字段。新调用创建新任务，同一次调用的重试由显式 `idempotency_key` 去重。

执行类工具先创建持久任务；`wait_ms=0` 立即返回任务，正数短等有界结果，等待到期不取消任务。之后的状态、输出和截图统一由 `task_read` 读取；`tail_bytes` 可直接查看长日志末尾。默认输出页为 16 KiB，执行工具可用 `limit` 调整；命令与桌面操作短等待失败时优先返回日志尾部。`change_seq` 状态观察默认不重复附带输出，`include_output` 和 `include_artifact` 分别控制日志与截图内容。

浏览器默认返回结构化 `output.data`、必要警告和有界页面观察；`task_read(detail=true)` 可读取保留的诊断信息。操作后的可选观察失败不会把已经完成的操作报告为失败。内联制品文本按 UTF-8 字节游标分页，完整文件保留下载能力。任务详情还提供执行时间、命令上下文、完整进度和保留期；`artifact(read)` 提供传输诊断。内部租约、主体、会话、车道与风险不经 MCP 返回；MCP 不提供 `next_action` 决策对象。逐字段取舍见 [MCP 结果字段](MCP_RESULT_FIELDS.md)。

`files` 在 Agent 内用原生文件 API 执行，按操作校验参数，返回有界结构化 `result`；Console 使用同一任务链路。它不建立额外路径权限，也不调用 shell。目录/文本分页、编码、版本校验、扫描/字节/时间预算用于防止误操作和资源耗尽。修改结果不确定时停止自动重放，先核对目标；请求丢失可按原幂等键查找已有任务。任务的开始与结束时间由 Center 首次观察对应状态时记录，避免 Agent 时钟偏差造成倒序；历史记录不回填。

`file_card` 仅展示已有制品，复用 `artifact` 权限和文件句柄，不创建传输任务。宿主文件接口上传成功后才保存真实 ChatGPT 文件 ID；RCM 制品 ID、签名 URL 和标准资源链接均不能替代它。

## Desktop / Browser

- command-agent 是唯一向 Center 注册和领取任务的主 Agent。
- Desktop companion 只在用户登录会话中运行，通过受保护的 loopback IPC 接收短期合同；不注册第二台机器，不维护第二套路径策略。
- Browser adapter 在实际收到任务时按需启动，空闲时回收；正式安装使用 Camoufox，本机浏览器实现不扩散成多个 MCP Tool。

## 认证

直接 Bearer 与 OAuth 是同一张 Console 凭证的两种连接方式。OAuth 页面用该凭证换取短期令牌；Center 仍根据源凭证逐机器校验工具权限，撤销源凭证会同时使 OAuth 令牌失效。管理 API 的 Admin Token 与 MCP 凭证分开保存。

## 数据库清理

Liquibase 保留历史变更记录，但最新迁移会移除运行时不再使用的项目表、Agent 范围列和审计范围列。生产环境只执行 changelog，不手工回填这些列。
