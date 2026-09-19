# Artifact Transport v2

更新时间：2026-09-18（Asia/Shanghai）

## 目标

Artifact Transport v2 只解决一条明确链路：

```text
ChatGPT Web 文件 → MCP Center → 目标终端 Agent → 终端文件系统
终端文件系统 → Agent → MCP Center → ChatGPT Web 展示/下载
```

它不是终端之间的文件同步，也不是把终端文件系统全量暴露给模型。

## 设计原则

1. MCP 是控制面，只传文件引用、状态和有界元数据。
2. Center 与 Agent 之间是二进制数据面，采用带校验的流式传输；大文件使用有界分块和偏移确认，断线后只重传未确认区间。
3. PostgreSQL 只保存 Artifact/Transfer 元数据，不保存大块文件内容。
4. Object Storage 保存实际文件；本地开发可以使用文件系统后端。
5. Task Artifact 保留给截图、诊断等小型任务结果；通用文件使用 File Transfer。
6. 文件内容不进入 command stdout、任务 JSON 或模型上下文。
7. 所有文件传输都绑定用户主体、MCP 会话、任务和目标 Agent。

## 两条业务链路

### ChatGPT Web → 终端

ChatGPT Apps SDK 通过 `fileParams` 将用户文件作为短期文件引用传给 `artifact` 的 `put` 操作。Center 立即消费 `download_url`，不把临时 URL 写入 durable task；下载过程中写入临时文件并计算大小、SHA-256，校验通过后上传 Object Storage。

随后 Center 创建一个 `WEB_TO_AGENT` transfer task。任务只携带：

```text
transfer_id
artifact_id
destination_path
expected_bytes
expected_sha256
overwrite
```

Agent 使用自身 Agent Token 从 Center 流式读取文件，写入目标路径旁路的 `.rcm-part-*` 临时文件，完成大小、哈希和本地 scope 校验后再原子改名。
`destination_path`/`source_path` 始终是完整目标/源文件路径；`file_name` 只是展示覆盖名，省略时取路径最后一段，绝不会再拼接到目标路径。

### 终端 → ChatGPT Web

模型通过 `artifact` 的 `get` 操作请求目标 Agent 的 `source_path`。Agent 先在本机校验 scope 和文件属性，再把文件流式上传到 Center。Center 生成 Artifact 元数据和短期签名读取地址。

MCP 只返回以下内容：

```json
{
  "artifact_id": "artifact_xxx",
  "file_name": "result.pdf",
  "mime_type": "application/pdf",
  "bytes": 183920,
  "bytes_transferred": 183920,
  "sha256": "...",
  "status": "ready"
}
```

React Artifact Viewer 按需读取文件：图片/PDF/媒体尝试预览，Office/压缩包/未知二进制提供下载。小型图片可以按需返回 MCP `ImageContent`，但不把它作为网页附件显示的唯一机制。

`artifact` 的 `put/get/read` 操作同时返回 MCP `structuredContent` 与一段
有界文本摘要。`structuredContent.file` 使用 ChatGPT 文件对象的
`download_url`、`file_id`、`mime_type`、`file_name` 形状；文本摘要只保留句柄、大小、哈希和
下一步动作，绝不复制二进制。这样支持文件 Host/Widget 的机器读取，也不会把文件内容灌入
模型上下文。

## 实体模型

```text
artifacts
---------
artifact_id, principal_id, machine_id, task_id, transfer_id
file_name, mime_type, storage_backend, object_key
bytes, sha256, status, created_at, expires_at

file_transfers
--------------
transfer_id, artifact_id, task_id, principal_id, machine_id
direction, source_path, destination_path
expected_bytes, expected_sha256, bytes_transferred
status, error, created_at, started_at, finished_at
```

Artifact 是可复用的文件对象；Transfer 是一次方向明确、可恢复、可审计的传输实例。

## 传输状态

```text
pending → ready → delivering → delivered
   │         │         │            │
   └─────────┴─────────┴────────────┴→ failed / canceled
```

当前使用带大小/SHA-256 校验的 HTTP 流和临时文件原子落盘；大文件的 Agent→Center
方向使用 8 MiB `Content-Range` 分块，Agent 先通过 `HEAD` 取得 Center PVC partial
spool 的确认偏移；Web→Agent 方向使用 HTTP `Range`，并把本地 `.rcm-part-*` 文件名
绑定到 transfer_id。重复请求通过幂等键和 transfer_id 复用同一逻辑传输。Agent 在本地
落盘/上传成功后发送 Attempt-fenced ACK，Center 只有在 ACK 或已提交的对象元数据可
证明成功时才进入终态。Center 在确认每个 8 MiB 分块前强制刷新 PVC 文件；`HEAD` 同时返回偏移和
`X-RCM-Transfer-Status`，只有 `delivered` 才允许 Agent 仅凭最终偏移恢复，完整但仍处于
`delivering` 的暂存会走一次幂等整流收口，避免伪造成功 ACK。中断后不会覆盖已确认字节，也不会把一次完整流重试误称为断点续传。
每个分块写入前都会同时申请进程内并发、主体/机器传输配额和持久 spool 容量 reservation；
Center 重启后会重新扫描已有 partial 文件并把它们计入容量，不会因内存计数丢失而超卖磁盘。
`pending` ingest 的
ChatGPT 临时 URL 不写入数据库；Center 重启时会把这类 reservation 一次性标记失败，
调用方需要使用新幂等键重新提交。

## MCP 工具面

工具数量保持精简：

| 工具 | 用途 | 默认返回 |
| --- | --- | --- |
| `artifact(operation=put)` | Web 文件写入终端 | transfer_id、任务状态和摘要 |
| `artifact(operation=get)` | 终端文件回传 Web | artifact_id、文件元数据 |
| `artifact(operation=read)` | 按需读取元数据和短期文件对象 | `structuredContent.file`（仅句柄）和有界摘要 |
| `artifact(operation=read)` | 按需读取元数据和短期文件对象 | `structuredContent.file` + `ui://remote-connect-mcp/artifact-viewer-v1.html` Viewer |

现有 `command`、`desktop`、`browser`、`task_read` 工具只引用 Artifact，不复制文件传输逻辑。

## ChatGPT Web 集成策略

首次接入文件能力时，`artifact` 聚合 Tool 必须同时声明标准 MCP Apps UI 资源和
ChatGPT 文件输入参数：

```json
{
  "_meta": {
    "ui": { "resourceUri": "ui://remote-connect-mcp/artifact-viewer-v1.html" },
    "openai/fileParams": ["file"]
  }
}
```

`file` 的输入对象遵循 OpenAI 文件生态的四字段形状：`download_url` 与 `file_id`
必须存在，`mime_type` 和 `file_name` 可选但必须在 schema 中声明。Center 在本次
MCP 请求中消费 `download_url`，只把摘要、哈希和自己的 Artifact 句柄持久化；不能
读取 ChatGPT 的 `/mnt/data`、浏览器沙箱路径或任何不可公开访问的内部路径，也不能把
ChatGPT 临时 URL 写入 durable Task/Transfer。

终端回传时，`structuredContent.file` 使用 Center 自己的短期 HTTPS
`download_url` 和 Artifact `file_id`。Viewer 优先使用该 URL，过期或缺失时使用
`window.openai.getFileDownloadUrl({ fileId })` 重新取得临时 URL；用户需要把文件保存
回当前会话或文件库时，Viewer 使用 `window.openai.uploadFile(file)` 或
`window.openai.uploadFile(file, { library: true })`。文件库能力是可选的，不能作为
终端回传成功的前置条件。

连接器只需在首次加入文件能力或工具声明发生变化后刷新一次。之后保持 `/mcp` 地址、
工具 schema 和 UI URI 稳定，Center/Agent 普通版本升级不需要重复配置。OpenAI/MCP
Apps 的标准 `ui.*` 元数据和 `ui/notifications/tool-result` 事件是唯一运行时契约，
不把 ChatGPT 私有文件系统路径当成 Center 的数据源。

## 安全与资源边界

- `download_url` 只在 Center 内存活于当前上传请求，不能进入持久任务。
- 签名读取地址短期有效，并绑定主体、会话、Artifact 和用途。
- 文件名不能携带目录分隔符、NUL 或控制字符。
- Agent 最终校验真实路径、scope、覆盖策略、大小和 SHA-256。
- 上传、下载、并发、spool 磁盘和保留周期均为可配置硬上限，用于资源保护而不是限制模型能力。
  生产可用 `RCM_CENTER_TRANSFER_SPOOL_ROOT` 把 spool 放到工件持久卷，避免容器 `/tmp`
  小配额与 4 GiB 单文件上限互相冲突。
- Center 对已建立的流同时执行绝对生命周期（默认 30 分钟）和无进展看门狗（默认 120 秒）；
  进度按 4 MiB 或 1 秒节流写入 `bytes_transferred`，超时会关闭输入流并将 Transfer/Task 收口为失败。
- 日志只记录 transfer_id、artifact_id、大小、结果和错误摘要，不记录文件内容或长期凭据。
- Center 重启、Agent 离线或网络中断不会产生重复文件或半成品目标文件；partial spool
  只在最终 SHA-256 校验和对象提交成功后删除，任务重试会从确认偏移继续。Agent 端
  上传使用稳定快照，hash 与上传读取同一份文件；`overwrite=false` 的最终原子 move
  负责最后一次存在性检查，并拒绝符号链接和特殊文件。
- 分块读写的连接中断返回可重试的 503；Agent 使用有界指数退避保持同一任务存活，
  不把临时公网抖动立即写成终态失败。参数校验、凭据错误和哈希冲突仍是不可重试错误。

## 实施顺序

1. **代码已完成**：协议记录、Artifact/Transfer 表、流式 Object Store、Agent 拉取/上传、完整流校验、分块/Range 续传、状态机/ACK、幂等预约、配额和安全边界。
2. **统一 CI 验证**：GitHub Actions 一次性覆盖 JDBC/Native/React、重复 chunk、lease 过期、Center/Agent 重启和对象流故障矩阵。
3. **生产验收**：React/Apps SDK Viewer、ChatGPT Web 双向附件、跨平台大文件和多用户多会话真实场景；通过后才更新生产验收表。

本项目禁止在开发机执行 Java、Native Image 或 React 构建；所有编译和集成测试由 GitHub Actions 完成。
