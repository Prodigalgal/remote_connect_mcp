# RCM 生产验收记录

更新时间：2026-09-15（Asia/Shanghai）

本文记录目标环境验收，不改变 [`TASKS.md`](TASKS.md) 中的代码状态。代码任务在实现和 CI 完成后即可标 `[x]`；只有这里的生产证据才会把对应能力标记为生产“通过”。所有地址、Token、数据库连接和机器敏感属性均不写入本文。

## 验收范围与原则

- 只验收已经部署的生产版本；本轮代码分支没有直接发布或写入生产。
- 生产探针优先使用健康、认证、协议、数据库状态和事件唤醒等无副作用检查。
- 任务闭环只使用固定 `printf` 输出，不读取文件、不修改配置、不启动额外服务。
- WebSocket、升级、Desktop/Browser 和故障演练若缺少安全的目标条件，记录为“待验收”，不使用 CI 结果替代。

## 2026-09-15 生产批次

生产应用 `remote-connect-mcp-java-production` 在 Kubernetes 中为 `Synced/Healthy`；Java Center、Console、PostgreSQL 均为 Ready，Liquibase migration Job 为 Complete。生产镜像版本已收敛到 `v0.1.22`，对应 GitOps 提交为 `ac82da9`，镜像使用不可变 digest。

| 能力 | 结果 | 证据与说明 |
| --- | --- | --- |
| Center 健康 | 通过 | `/api/v1/healthz` 返回 200，状态 `ok`，实现为 Java，版本 `v0.1.22` |
| Center 就绪与持久化 | 通过 | `/api/v1/readyz` 返回 200，状态 `ready`，持久化为 PostgreSQL |
| 版本识别 | 通过 | `/api/v1/version` 返回 Java 实现和生产版本 |
| MCP 未授权保护 | 通过 | 未携带 Bearer 调用 `/mcp` 返回 HTTP 401 |
| MCP 授权会话 | 通过 | 有效 MCP Token 完成 `initialize`、会话建立和 `tools/list`；精简工具面 9 个工具均可发现 |
| MCP 机器列表 | 通过 | `machines_list` 调用成功，结果未发现 Token、密码、Secret 或私钥字段 |
| Admin 鉴权与基础分页 | 通过 | 有效 Admin Token 可读取机器列表；`items/offset/limit` 存在 |
| Admin 新分页投影 | 通过 | 生产 `v0.1.22` 返回 `items/offset/limit/total/has_more`，机器列表 9 台且单页完整 |
| Metrics | 通过 | Admin 鉴权、Prometheus 文本格式和机器计数器可用；响应未发现 Token、密码、Secret 或私钥字段 |
| 事件唤醒 | 通过 | `/api/v1/admin/events` 的有界等待正常返回，实测约 621 ms；未使用固定间隔轮询 |
| Agent 命令闭环 | 通过 | 一台在线 Oracle ARM64 Agent（身份已脱敏）执行固定 `printf`，状态 `completed`、Attempt 1、退出码 0、输出 26 字节；通过事件等待得到终态并按字节校验 |
| Console 路由 | 通过 | 生产 Console 首页返回 HTTP 200 |
| WebSocket 生产握手 | 待验收 | CI smoke 已通过；尚未使用真实 Agent Token 在生产反向代理下做有效握手、断线与重连演练 |
| Desktop 真实操作 | 待验收 | 当前生产在线 Agent 仅声明 command/durable_tasks，尚无可验收的 Desktop 会话 |
| Browser 真实操作 | 待验收 | 当前生产在线 Agent 未声明 browser capability，未执行真实浏览器任务 |
| 范围合同绕过 | 待验收 | 代码/CI 已覆盖；仍需目标机上对 project/worktree/path/workspace/unrestricted 和符号链接边界做正式演练 |
| Center/Agent 重启恢复 | 待验收 | 不能在本批次无窗口重启生产组件；需安排可回滚演练窗口 |
| 升级在线/离线/失败回滚 | 部分通过 | `v0.1.22` 活动以 1 台 canary、批次 2 启动；4 台在线 Agent 全部完成、失败 0，5 台离线目标保持 pending，待重连自动领取；失败重排队/回滚仍待专门演练 |
| 对象网关、日志采集和保留策略 | 待验收 | 代码与 CI 已具备；生产采集器、对象生命周期和成本压测尚未执行 |
| SLO 告警通知 | 待验收 | PrometheusRule 模板和指标已存在；通知出口与演练尚未执行 |
| QUIC/HTTP3 | 待验收 | 当前只启用 HTTPS/WebSocket 能力协商与回退；真实 provider/灰度未启用 |

## 代码与 CI 回归证据

合并提交 `8e4839f`、稳定 tag `java-v0.1.22` 已在 GitHub Actions 完成大规模回归：

- 综合 CI `34937165824`：Go 测试/vet、四平台兼容构建、事件驱动检查和仓库卫生，成功；
- Java/React `34937165828`：PostgreSQL/Liquibase、JVM Ubuntu/Windows、React 构建、Linux amd64/arm64 Native 构建、MCP 冒烟和 Agent RSS 门禁，成功；
- 稳定 Native Release `34938856822`：Linux amd64/arm64、Windows amd64 Center/Agent/Desktop/Browser 资产、校验和、SBOM 与签名，成功；
- 本机没有执行 Java、Gradle、Native Image、React 或正式安装包构建。

## 发现与后续闭环

1. 生产基础链路已通过 GitHub Actions 发布并经 GitOps 收敛到不可变的 `v0.1.22`；已复验 `total/has_more`、MCP 工具发现、健康/就绪和新 Center PVC。
2. 生产命令闭环已通过一次低风险验证；第一次验证脚本因换行转义产生误报，修正后第二次按字节校验通过，不代表其他故障场景已经验收。
3. 下一批应在维护窗口内执行 Center/Agent 重启、断线恢复、长任务取消、资源超限、升级失败/回滚、离线领取和 WebSocket 反向代理演练；当前 5 台离线目标已在升级活动中等待重连。
4. Desktop/Browser 需要至少一台 Windows 和一台 Linux 目标机具备对应 capability，再执行截图、输入、会话恢复、浏览器 profile 和工件清理回归。
