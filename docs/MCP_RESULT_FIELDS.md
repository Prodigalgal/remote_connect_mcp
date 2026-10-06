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
| `machine.environment.command_user`、`command_home`、`interactive_user`、`desktop_path` | 详情，有可靠报告时 | 区分命令服务身份和桌面登录用户；Windows 重定向桌面由 companion 在用户会话中解析，缺失时省略。 |
| `task.id` | 默认 | 恢复异步任务的唯一句柄。 |
| `task.machine_id`、`kind` | 默认 | 知道结果来自哪台机器、哪种执行能力。 |
| `task.status` | 默认 | 判断继续等待、读取或处理失败。 |
| `task.change_seq` | 默认 | `task_read` 长等时避免漏掉变化。 |
| `task.exit_code` | 有值时 | 命令终态判断；非进程任务无此值。 |
| `task.error` | 有值时，默认最多 1024 字符 | 立即看见失败原因；详情最多 2048 字符。 |
| `task.output_bytes` | 非零时 | 判断是否还有日志、计算尾部偏移。 |
| `task.output_truncated` | 为真时 | 告知日志可能缺失，避免把尾部当完整输出。 |
| `task.attempt` | 重试后；详情总是给 | 区分新旧执行尝试。 |
| `task.progress_phase`、`progress_percent`、`progress_message` | 非终态且实际报告时；详情保留最后报告 | 避免完成或失败后仍显示“运行中 0%”；消息默认最多 256 字符。 |
| `task.progress_current`、`progress_total`、`progress_unit` | 无百分比且有值时 | 百分比不存在时仍可展示传输/步骤进度；详情可同时给两种表示。 |
| `output.text` 或 `output.data`、`cursor`、`next_cursor`、`more` | 请求输出且有内容时 | 命令/文本使用 `text`，完整浏览器结果使用结构化 `data`。空输出省略；默认 16 KiB，可指定 `limit` 或 `tail_bytes`。 |
| 浏览器 `data.warnings`、`warning_count` | 有警告时 | 优先显示网络、页面和控制台错误，不复制所有常规事件。 |
| 浏览器 `data.truncated`、`detail_available` | 截短或保留详情时 | 明确提示内容不完整，避免把截短结果当完整观察；`task_read(cursor=0, detail=true, limit=65536)` 读取保留详情。 |
| 浏览器 `data.diagnostics` | 详情 | 有界网络、控制台和页面事件；完整结构化对象仍超过预算时继续缩减并标记 `truncated`，不截断 JSON 或元素引用。 |
| `artifact.sha256`、`bytes`、`mime_type` | 有任务制品时 | 校验完整性、判断大小和展示方式。 |
| `artifact.inline` | 仅小图片内联时 | 告知图片内容已随结果返回；执行短等结果自动附带小截图，`task_read` 仅在 `include_artifact=true` 时附带像素。 |
| `transfer.transfer_id`、`artifact_id`、`status` | 文件传输时 | 恢复传输和文件的稳定句柄。 |
| `transfer.bytes`、`bytes_transferred` | 未完成且有意义时 | 展示传输进度；完成后与文件大小重复，默认省略。 |
| `transfer.error` | 失败时 | 直接解释传输失败。 |
| `file.artifact_id`、`download_url`、`preview_url` | 文件已 ready/delivered 时 | RCM 文件句柄及签名下载/预览地址；RCM ID 不冒充 ChatGPT 的 `file_id`。 |
| `file.file_name`、`mime_type`、`bytes`、`sha256` | 有可取文件时 | 展示文件、决定打开方式并验证内容；只放在 `file`，不在默认 `transfer` 重复。 |
| `delivery_mode=inline` | 内容真正内联时 | 区分直接返回内容和文件句柄。 |

`task_read(detail=true)` 额外返回 `required_capability`、命令任务的脱敏 `command`、`cwd`、`timeout_seconds`、完整 `attempt` 和输出计数，用于重试判断；`created_at`、`dispatched_at`、`started_at`、`finished_at` 用于定位等待耗时；完整进度计数与 `progress_updated_at` 用于排查停滞；`metadata_expires_at`、`output_expires_at`、`pinned`、`archived_at` 用于解释结果保留。浏览器请求可能含凭据，因此不把原始请求当详情回显。

`task_read(change_seq=..., wait_ms=...)` 默认只观察变化，不重复读取旧输出；显式 `include_output=true`，或指定 `cursor` / `tail_bytes` / `limit`，才同时读取日志。`include_output=false` 不能与输出分页参数混用。截图元数据始终可见，像素由 `include_artifact=true` 单独获取。

`task_read` 按认证主体、执行会话有效期和当前机器/工具授权恢复任务，连接 ID 变化不会丢失读取能力。客户端提供的 `_meta["openai/session"]` 只用于归并会话记录，缺失时使用传输连接 ID；它不能替代任何身份或权限检查。文件传输任务完成后，`task_read` 同时返回原有 transfer/file 句柄及标准 MCP `resource_link`，无需再次发起下载任务。

浏览器操作可通过 `request.include_snapshot=true` 在完成操作后一起返回页面观察，减少一次调用。`request.timeout_ms` 只限制本次页面操作，顶层 `timeout_seconds` 仍限制整个任务；等待动作的持续时间用 `request.wait_ms`。如果操作完成但可选观察失败，结果保留成功操作并提供 `observation_error`，避免重复点击或提交。

`artifact(get/read, delivery_mode=inline)` 的文本使用同样的 `output.text` 分页格式，默认 16 KiB、最大 64 KiB。`cursor`、`next_cursor` 是原始 UTF-8 字节偏移；边界不拆断中文或 emoji，极小预算允许多出最多 3 字节以返回完整字符。`file.bytes` 和 `file.sha256` 描述完整文件，完整内容通过下载 URL 获取，不把一页摘要当完整文件。`delivery_mode` 控制展示方式，显式 `wait_ms` 独立控制等待；`async` 未指定等待时立即返回，指定正数时先短等后返回句柄。

`artifact(get)` 读取一个普通文件，目录枚举与搜索先用 `command` 完成。可下载文件通过标准 MCP `resource_link` 提供文件名、MIME、大小和签名地址；工具不再自动挂载 iframe。宿主是否把它渲染为原生附件卡片需要真实 ChatGPT 网页验收，不能把标准资源链接等同于已注册的 ChatGPT 文件。

`artifact(read)` 额外给传输 `direction`、关联的 `task_id`/`machine_id`；若文件对象尚未形成，才在传输详情里保留 `file_name`、`mime_type`、`bytes`、`sha256`。这让模型能够排查未完成的传输，同时避免已完成时复制文件元数据。主体 ID、内部租约、执行会话、车道、风险与合同时间既不是后续 Tool 的入参，也不帮助处理结果，不经 MCP 返回。
