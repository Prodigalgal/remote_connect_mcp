# MCP 结果字段取舍

默认结果服务于下一步行动：选择机器、等待任务、定位失败、继续读取输出或取回文件。字段按需出现；详情由 `machines(operation=detail)`、`task_read(detail=true)` 和 `artifact(operation=read)` 获取。`structuredContent` 是主数据；同一份有界 JSON 文本保留给旧 MCP 客户端兼容，不再另造决策对象。

| 字段 | 默认/详情 | 留下的理由 |
| --- | --- | --- |
| `machines[].id` | 默认 | 后续操作的机器句柄。 |
| `machines[].name` | 默认 | 让人和模型区分机器。 |
| `machines[].os`、`arch` | 默认 | 判断命令语法和组件平台。 |
| `machines[].version` | 默认 | 判断 Agent 是否落后。 |
| `machines[].capabilities` | 默认，最多 16 项 | 选择可执行工具；超出时才给 `capabilities_truncated=true`。 |
| `machines[].online` | 默认 | 避免向离线机器误判即时响应。 |
| `machines` 的 `offset`、`limit`、`total`、`has_more` | 默认 | 明确分页是否覆盖全量机器。 |
| 机器的 `host_id`、`hostname`、`default_cwd`、`runtime`、`created_at`、`last_seen` | 详情 | 排障和主机核对才需要；默认列表不复制完整运行预算。 |
| `task.id` | 默认 | 恢复异步任务的唯一句柄。 |
| `task.machine_id`、`kind` | 默认 | 知道结果来自哪台机器、哪种执行能力。 |
| `task.status` | 默认 | 判断继续等待、读取或处理失败。 |
| `task.change_seq` | 默认 | `task_read` 长等时避免漏掉变化。 |
| `task.exit_code` | 有值时 | 命令终态判断；非进程任务无此值。 |
| `task.error` | 有值时，默认最多 1024 字符 | 立即看见失败原因；详情最多 2048 字符。 |
| `task.output_bytes` | 非零时 | 判断是否还有日志、计算尾部偏移。 |
| `task.output_truncated` | 为真时 | 告知日志可能缺失，避免把尾部当完整输出。 |
| `task.attempt` | 重试后；详情总是给 | 区分新旧执行尝试。 |
| `task.progress_phase`、`progress_percent`、`progress_message` | 实际报告时 | 不读取长日志也能知道长任务进度；消息默认最多 256 字符。 |
| `task.progress_current`、`progress_total`、`progress_unit` | 无百分比且有值时 | 百分比不存在时仍可展示传输/步骤进度；详情可同时给两种表示。 |
| `output.text`、`cursor`、`next_cursor`、`more` | 有输出时 | 有界读取及继续分页；空输出整个 `output` 省略。默认 16 KiB，可指定 `limit` 或 `tail_bytes`。 |
| `artifact.sha256`、`bytes`、`mime_type` | 有任务制品时 | 校验完整性、判断大小和展示方式。 |
| `artifact.inline` | 仅小图片内联时 | 告知图片内容已随结果返回；非内联时不放无意义的 `false`。 |
| `transfer.transfer_id`、`artifact_id`、`status` | 文件传输时 | 恢复传输和文件的稳定句柄。 |
| `transfer.bytes`、`bytes_transferred` | 未完成且有意义时 | 展示传输进度；完成后与文件大小重复，默认省略。 |
| `transfer.error` | 失败时 | 直接解释传输失败。 |
| `file.file_id`、`download_url`、`preview_url` | 有可取文件时 | 客户端下载/预览所必需的短期对象句柄。 |
| `file.file_name`、`mime_type`、`bytes`、`sha256` | 有可取文件时 | 展示文件、决定打开方式并验证内容；只放在 `file`，不在默认 `transfer` 重复。 |
| `delivery_mode=inline` | 内容真正内联时 | 区分直接返回内容和文件句柄。 |

`task_read(detail=true)` 额外返回 `required_capability`、命令任务的脱敏 `command`、`cwd`、`timeout_seconds`、完整 `attempt` 和输出计数，用于重试判断；`created_at`、`dispatched_at`、`started_at`、`finished_at` 用于定位等待耗时；完整进度计数与 `progress_updated_at` 用于排查停滞；`metadata_expires_at`、`output_expires_at`、`pinned`、`archived_at` 用于解释结果保留。浏览器请求可能含凭据，因此不把原始请求当详情回显。

`artifact(read)` 额外给传输 `direction`、关联的 `task_id`/`machine_id`；若文件对象尚未形成，才在传输详情里保留 `file_name`、`mime_type`、`bytes`、`sha256`。这让模型能够排查未完成的传输，同时避免已完成时复制文件元数据。主体 ID、内部租约、执行会话、车道、风险与合同时间既不是后续 Tool 的入参，也不帮助处理结果，不经 MCP 返回。
