# Full-host baseline

这份文档是当前 RCM 底层模型的权威说明。早期关于 project、worktree、workspace、path scope 和 Agent 本地根目录的设计文档仅保留作历史记录，不再描述运行时行为。

## 一个最小执行模型

```text
Principal (Bearer/OAuth)
        │
        ├── MCP connection / conversation
        │       └── Center ExecutionSession
        │               └── durable Task + lease + result channel
        │
        └── machine grant ──> registered Agent (full host)
                              ├── command
                              ├── desktop companion (user session)
                              └── browser adapter (on demand)
```

权限只有两层：

1. Center 判断当前主体能否使用某台机器，以及能否读/执行某类操作；
2. 目标机操作系统决定 Agent 运行账户实际拥有的权限。

Agent 不再解析或执行任何 `scope_mode`、`scope_root`、`workspace_policy`、`project_id` 或 `worktree_id`。任务中的 `cwd` 只是工作目录提示，文件传输中的路径是目标机真实路径。

这里的 OAuth/MCP `scope` 只表示“这个主体能否调用 MCP 或使用某台机器”的授权，不表示文件系统范围；它不会被翻译成 Agent 的路径白名单。

## 并发与结果隔离

- 每个认证主体/连接生成稳定的 ExecutionSession，任务结果通过主体和会话归属读取。
- command 写任务默认共享同一台机器的主机车道；Desktop 对同一用户桌面独占；Browser 按 host + session/profile 协调。
- 车道只解决并发一致性，不决定路径权限。
- 任务、输出、工件、传输和会话全部有明确 TTL/配额；Agent 默认使用事件唤醒的 HTTPS 长轮询。WebSocket 保留给已有安装的兼容路径。

## MCP 公开面

只发布：`machines`、`command`、`desktop`、`browser`、`artifact`、`task_read`、`task_cancel`。

Tool schema 只保留模型需要的意图：机器句柄、操作枚举、命令/浏览器请求、文件对象和任务句柄。会话、车道、风险、预算、主体和租约由 Center 派生。执行类工具先创建持久任务；`wait_ms=0` 立即返回任务，正数短等有界结果，等待到期不取消任务。之后的状态、输出和截图统一由 `task_read` 读取；`tail_bytes` 可直接查看长日志末尾。结果使用任务状态、游标和错误字段，不再提供 `next_action` 决策对象。

## Desktop / Browser

- command-agent 是唯一向 Center 注册和领取任务的主 Agent。
- Desktop companion 只在用户登录会话中运行，通过受保护的 loopback IPC 接收短期合同；不注册第二台机器，不维护第二套路径策略。
- Browser adapter 在实际收到任务时按需启动，空闲时回收；正式安装使用 Camoufox，本机浏览器实现不扩散成多个 MCP Tool。

## 认证

Bearer 与 OAuth 只是获取 Principal 的两条入口。两者在 Center 内统一成 Principal → Connection → ExecutionSession → Task，不改变 Agent 注册 Token、机器授权、配额或结果隔离模型。

## 数据库清理

Liquibase 保留历史变更记录，但最新迁移会移除运行时不再使用的项目表、Agent 范围列和审计范围列。生产环境只执行 changelog，不手工回填这些列。
