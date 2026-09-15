# 可观测性与 SLO

RCM 的业务请求、Agent 通道和工件路径仍然是异步的。Center 不运行固定间隔的业务扫描线程；Prometheus 按自己的 scrape 周期读取受 Admin Token 保护的 `/metrics`，应用只在请求到达时生成一个低基数快照。

## 指标

指标不包含命令、路径、Token、Cookie 或任务输出。当前指标分为四组：

| 组 | 指标 | 用途 |
| --- | --- | --- |
| Agent | `remote_connect_mcp_machines_total`、`remote_connect_mcp_machines_online`、`remote_connect_mcp_machines_online_ratio` | 注册规模和心跳可用率 |
| 任务 | `remote_connect_mcp_tasks_queue_depth`、`remote_connect_mcp_tasks_oldest_queued_age_seconds`、`remote_connect_mcp_tasks_active`、`remote_connect_mcp_tasks_expired_leases` | 排队、恢复和卡住任务门禁 |
| 任务结果 | `remote_connect_mcp_tasks_terminal`、`remote_connect_mcp_tasks_success_ratio`、`remote_connect_mcp_tasks_failure_ratio`、`remote_connect_mcp_tasks_canceled_ratio` | 任务成功率和失败率 |
| 工件/审计 | `remote_connect_mcp_artifact_bytes`、`remote_connect_mcp_artifacts_total`、`remote_connect_mcp_audit_queue_depth`、`remote_connect_mcp_audit_events_dropped_total` | 存储容量、审计背压和丢弃可见性 |

已有的 `remote_connect_mcp_tasks_total{status=...}` 和 `remote_connect_mcp_upgrades_total{status=...}` 保留，用于按状态观察，不把 machine ID、task ID 或 campaign ID 放进 label，避免高基数爆炸。

## 推荐告警

可直接把 [`../deploy/monitoring/prometheus-rules.yaml`](../deploy/monitoring/prometheus-rules.yaml) 应用到 Prometheus Operator。规则覆盖：

- 终态任务失败率超过 20%；
- 队列中最老任务超过 5 分钟；
- 活跃任务租约过期；
- Agent 新鲜心跳比例低于 80%；
- 升级活动出现失败终态；
- 审计队列背压或发生丢弃。

阈值是单 Center 默认值，生产环境应先用一周历史数据校准。告警通知由 Prometheus/Alertmanager 负责，Center 不持有 webhook、邮件或聊天平台凭据。

## 日志与工件

Center/Agent 继续把日志写到 stdout/stderr 或 systemd journal，由宿主机或 K8s 日志采集器转发到 Loki、OpenTelemetry Collector 等集中日志系统。Center 设置 `RCM_CENTER_STRUCTURED_AUDIT_LOG=true` 后，会额外输出单行 `rcm.audit {JSON}` 审计投影；JSON 只包含已脱敏、低基数的事件字段，不包含完整命令、环境变量、Cookie 或工件内容。采集器配置、远端凭据和保留周期不放入公开仓库。

任务工件由 `ArtifactStore` 抽象承载，PostgreSQL 只保留 key、大小、MIME 和 SHA-256 元数据；默认后端是受持久卷保护的 filesystem，也可通过 `RCM_CENTER_ARTIFACT_STORE=http` 接入内部 HTTPS 对象网关。filesystem 的 GC 通过显式维护入口执行并有并发写入宽限期；远程网关由网关侧负责枚举和生命周期，Center 不做无界扫描。

## 验收顺序

1. 使用 Admin Token 从监控网络读取 `/metrics`，确认响应不含敏感字段。
2. 导入 PrometheusRule，在测试环境制造排队、失败任务、离线 Agent 和升级失败，确认规则触发与恢复。
3. 对工件卷做备份/恢复演练，核对 SHA-256 和元数据一致性。
4. 在生产环境接入集中日志后，只保留脱敏后的错误摘要和运行指标；不要把原始命令、环境变量或浏览器会话数据送入日志平台。

## 通知出口

仓库只提供 PrometheusRule，不把 Chat/Slack/Webhook URL 或 Token 写入公开
YAML。生产由现有 Prometheus/Alertmanager 安装在私有 GitOps 中添加一条
`remote-connect-mcp` 路由，接收 `severity=warning|critical` 的规则并转到团队
通知渠道；接收器凭据放在 Alertmanager Secret。通知链路故障不能阻塞 Center 或
Agent，必须通过 Alertmanager 自身的投递失败指标继续观察。
