# RCM 生产任务清单

更新时间：2026-09-16（Asia/Shanghai）

本文是 Remote Connect MCP 的可持续任务清单。第一列只表示代码交付状态：实现和自动化检查完成即可勾选；生产验收单独记录在 [`PRODUCTION_ACCEPTANCE.md`](PRODUCTION_ACCEPTANCE.md)，不再阻止代码任务勾选。这样可以明确区分“代码没做完”和“代码已完成但尚未在目标环境验收”。

需求基线：[`REQUIREMENTS.md`](REQUIREMENTS.md)；当前实现证据：[`STATUS.md`](STATUS.md)；架构约束：[`ARCHITECTURE.md`](ARCHITECTURE.md)；异步契约：[`ASYNC_CONTRACT.md`](ASYNC_CONTRACT.md)。

## 状态规则

- `[x]` 代码完成：实现和自动化检查有证据；生产状态看独立验收表。
- `[ ]` 代码待办：尚未开始或尚未形成可验收实现。
- `[~]` 代码实施中：已有部分实现，但仍有代码/协议/测试缺口。
- `[—]` 明确不做：经需求决策从当前路线移除，不再作为交付门禁。
- `生产验收` 不再改变第一列；在 [`PRODUCTION_ACCEPTANCE.md`](PRODUCTION_ACCEPTANCE.md) 中记录 `通过`、`部分通过`、`待验收` 或 `不适用`。
- 每项代码完成后，在“验收证据”列补充 CI run/测试证据，再将代码状态改为 `[x]`；生产目标机证据随后补到独立验收表。
- 不在本机编译 Java、Native Image、React 或正式安装包；构建证据必须来自 GitHub Actions。

## 当前阶段：选定 P2 实施，代码状态与生产验收分离

截至当前 `main`，P0/P1 的主要协议、Center/Agent/Console 主流程和兼容实现已经完成，选定的 P2-01/03/04/07 的代码与 CI 门禁已完成；P1-02/03/04/08、P2-06 仍有明确的平台/协议代码缺口，保留 `[~]`。生产验收记录见 [`PRODUCTION_ACCEPTANCE.md`](PRODUCTION_ACCEPTANCE.md)：生产 Java Center、Console 与 9 台在线 Agent 已在 `v0.1.28` 收敛，Linux GUI canary 已用 GitHub Actions preview `v0.0.0-main.57` 完成 Desktop screens/截图和 Browser navigate；Windows Desktop 交互会话、更多 Browser 场景、升级故障/回滚、Center 重启重试和告警通知仍待验收。P2-02 和 P2-05 已按需求决策明确不做。

| 层级 | 当前判断 | 剩余工作 |
| --- | --- | --- |
| P0 | 核心可靠性与安全实现基本完成 | Center/Agent 重启、断线与重试、资源硬限额、工件卷备份恢复、离线升级等目标环境门禁 |
| P1 | 主流程已具备，平台特性待实测 | Windows/Linux Desktop 与 Browser、Git/Console/升级/长连接真实矩阵，以及无障碍、视觉和代理故障验收 |
| P2 | P2-01/03/04/06/07 已进入实施队列；P2-02/05 按决策移除 | QUIC/HTTP3 评估、集中日志与对象存储规模化、SLO/告警、桌面/浏览器平台增强和 Go 回滚路径退出 |

当前证据基线：Java Native Release `35043403122`（tag `java-v0.1.28`）成功；Linux GUI canary preview `35060687497`（commit `2868934`）的三平台 Native、镜像、SBOM、签名和 release jobs 成功；GitOps revision `eee62d2` 已由 Argo 报告 `Synced/Healthy/Succeeded`；事件驱动检查、仓库敏感信息扫描和浏览器脚本静态检查均通过。本机没有执行 Java、Gradle、Native Image 或 React 构建。

## P0：平台必须可靠

| 代码状态 | 编号 | 任务 | 当前情况与完成条件 | 代码验收证据 |
| --- | --- | --- | --- | --- |
| [x] | P0-01 | 固定 MCP 地址和多机器路由 | `/mcp`、Bearer 和按 machine ID 路由已可用；后续不因 Center/Agent/Console 升级改变连接器地址 | v0.1.21 MCP/health/ready 验收 |
| [x] | P0-02 | 机器注册与凭据分层 | 一次性 Enrollment Token、独立 Agent Token、稳定 MCP Token、独立 Admin Token 已实现；Enrollment 不写入长期配置 | 注册与身份测试、生产 Secret |
| [x] | P0-03 | 异步任务全链路恢复 | 已有幂等、租约、Attempt、旧 attempt 回传栅栏、取消、输出游标和 LISTEN/NOTIFY；Agent 对重复 poll 回传增加原子 `putIfAbsent` dispatch fence，避免覆盖正在运行的 Future；Center 重启、Agent 断线、重复重试、长任务和高并发属于独立生产验收 | `AgentRuntimeTest.duplicateTaskRegistrationKeepsTheFirstRunner`、GitHub Actions |
| [x] | P0-04 | PostgreSQL + Liquibase 唯一事实来源 | PostgreSQL、Liquibase `001`–`014`、旧 Go 状态导入和迁移 Job 的代码与 CI 实现完成；生产迁移/版本收敛属于独立生产验收 | PostgreSQL/Liquibase CI、`PostgresIntegrationTest` |
| [x] | P0-05 | 任务与工件的持久化边界 | `ArtifactStore`、持久卷文件对象、原子写入/读取校验、旧 `artifact_data` 懒迁移和显式 GC API 已实现；生产卷备份/恢复、保留策略和规模压测属于独立生产验收 | 对象存储适配、迁移/恢复、生命周期测试、GitHub Actions |
| [x] | P0-06 | 执行范围与权限合同 | project/worktree/path/workspace/unrestricted 合同、Center/Agent/桌面双端校验、预算收窄和凭据过滤已实现；目标机绕过与回归属于独立生产验收 | `DesktopCompanionServerTest`、GitHub Actions |
| [x] | P0-07 | Agent 资源硬限制 | 任务级进程树/墙钟/CPU/RSS 监督、输出/磁盘/并发上限、Linux cgroup 可选边界、Windows 有界进程树/Task Scheduler 路径和 Agent 总进程预算已实现；目标机压测属于独立生产验收 | GitHub Actions Native smoke、RSS gate、资源监督测试 |
| [x] | P0-08 | 隐私、密钥和仓库卫生 | 公开仓库使用模板值；真实域名、Token、Secret 和私有 GitOps 留在受保护环境；日志/指标有脱敏约定 | 仓库扫描、CI hygiene、私有部署检查 |
| [x] | P0-09 | GitHub Actions 构建和可安装包 | Java/Native/React/安装包、SBOM、签名和烟测由 GitHub Actions 完成；开发机不编译 | Java Release workflow、Native smoke、RSS gate |
| [x] | P0-10 | 全部已登记 Agent 的恢复 | 升级活动默认把未显式指定的全部登记 Agent（含离线）写入持久目标集；离线 Agent 下次心跳自动领取同一 offer；真实在线清单、升级活动和任务闭环属于独立生产验收 | 升级活动目标集测试、GitHub Actions |

### P0 生产验收门禁（不计代码状态）

- [x] P0-G1：范围合同不能被改变 cwd、环境变量或任务重试绕过；Linux/Windows path 合同、环境变量伪造、越界路径和幂等重试已完成目标机验收。
- [~] P0-G2：Agent 断线重连和 durable 任务不重复执行已验收；Center 重启及 ChatGPT 端重试仍需维护窗口演练。
- [~] P0-G3：长输出分页、任务超时、无超时 durable 任务和 32 子进程上限已验收；截图/下载能力与 RSS/CPU 极限压测尚未具备目标条件。
- [x] P0-G4：所有 9 台在线 Agent 均通过固定 `/mcp` 的 `command_start` 路由并以 `task_wait`/终态核对。

## P1：核心生产体验

| 代码状态 | 编号 | 任务 | 当前情况与完成条件 | 代码验收证据 |
| --- | --- | --- | --- | --- |
| [x] | P1-01 | Project Registry 与 Git worktree 闭环 | 注册、受保护删除、创建/删除 worktree、cwd 解析、status/diff/log、幂等 commit、显式 merge 和 `merge_abort` 冲突恢复排队 API 已实现并接入精简 MCP；冲突输出、提交差异审阅和目标机权限属于独立生产验收 | ProjectService/协议测试、GitHub Actions |
| [~] | P1-02 | Windows/Linux Desktop Companion 真实能力 | 基础截图、屏幕枚举、启动、输入和剪贴板已有；IPC 已复用 Center 合同的 scope/真实路径/过期检查，并限制连接、启动进程和工件大小；Windows 多会话/UAC/RDP、Linux X11/Wayland、多显示器和会话失效恢复仍有代码缺口 | Desktop 协议测试、GitHub Actions；平台适配待补齐 |
| [~] | P1-03 | Browser Agent 生产运行时 | Worker 监管、结构化引用、有界快照、下载工件和清理已有；目标浏览器安装、持久 profile、登录态、stale ref 恢复和 Playwright/Patchright/Comoufox 兼容仍有代码/运行时缺口 | BrowserTaskRunner、Node 静态检查、GitHub Actions；目标运行时待补齐 |
| [~] | P1-04 | React 控制台完整工作流 | 基础机器、项目、任务、令牌、升级和审计页面及大部分编排已实现；实时状态、无障碍和视觉回归门禁仍有代码/测试缺口 | React CI 构建、现有 Console 测试；E2E/a11y/视觉待补齐 |
| [x] | P1-05 | 升级 offer/attempt 与兼容回滚 | canary、批次、SHA-256、原子替换和回滚、attempt 栅栏、PostgreSQL 行锁、失败目标单独重排队均已实现；数据库并发、版本兼容、离线补升级和现场演练属于独立生产验收 | `UpgradeServiceTest`、GitHub Actions |
| [x] | P1-06 | WebSocket/事件唤醒生产验收 | wake-only WebSocket、指数重连、HTTPS 回退、PG 通知桥接和序列号去重已实现；真实反向代理、断线和 Center 重启属于独立生产验收 | WebSocket smoke、TransportNegotiation/AgentWake 测试、GitHub Actions |
| [x] | P1-07 | 配置与心跳自描述 | 版本化 runtime descriptor、generation/CAS、有限历史和回滚、旧 schema 有界兼容已实现；目标机回滚演练属于独立生产验收 | AgentRuntimeSettings/ConfigurationService/runtime descriptor 测试、GitHub Actions |
| [~] | P1-08 | 终端与子 Agent 生命周期 | 默认一个 command-agent 身份，桌面/浏览器作为子组件；command-agent 的桌面直启进程已纳入有界预算，并在 Agent 关闭/升级/JVM shutdown hook 中回收；多物理 Agent 的显式隔离、互斥、崩溃拉起和 Center 视图仍有代码缺口 | `DesktopProcessBudgetTest`、GitHub Actions；多 Agent 主机测试待补齐 |
| [x] | P1-09 | 审计与错误可解释性 | 有界异步审计队列、PostgreSQL `rcm_audit_event`、Admin/Console 查询、来源区分、错误脱敏和有界保留清理入口已实现；审批来源细化、脱敏抽样和真实故障报告属于独立生产验收 | StructuredLog/AuditService/脱敏测试、GitHub Actions |

### P1 生产验收门禁（不计代码状态）

- [ ] P1-G1：至少一台 Windows 和一台 Linux 目标机完成 Desktop/Browser 真实操作回归。
- [ ] P1-G2：控制台可以从选择机器开始，完成范围选择、任务提交、日志查看、工件查看和取消。
- [ ] P1-G3：升级活动在在线、离线、迟到报告和启动失败情况下都能安全收口。
- [ ] P1-G4：反向代理下的 WebSocket、长轮询和 Center 重启均能恢复，不依赖固定间隔轮询。

## P2：规模化增强

| 代码状态 | 编号 | 任务 | 当前情况与完成条件 | 代码验收证据 |
| --- | --- | --- | --- | --- |
| [x] | P2-01 | QUIC/HTTP3 传输评估与实现 | transport 能力协商标头、HTTPS 选择、兼容回退和一次性基准脚本已实现；真实 QUIC provider/灰度属于后续生产实验，不改变默认 HTTPS/WebSocket | TransportNegotiation/AgentTransport 测试、GitHub Actions |
| [—] | P2-02 | Center 多副本与高可用 | **明确不做。** 当前保持单副本稳定路径，不建立多副本、PDB、HPA 或跨副本一致性门禁 | 需求决策记录 |
| [x] | P2-03 | 集中日志与对象存储规模化 | 异步审计、`rcm.audit` JSON 行导出、filesystem/HTTPS 对象网关、GC 边界和容量指标已实现；生产采集器、对象生命周期/索引与成本压测属于独立生产验收 | `HttpArtifactStore`、StructuredLog、artifact gateway contract、GitHub Actions |
| [x] | P2-04 | SLO、告警和升级通知 | 任务成功率、排队延迟、断线恢复、资源、升级失败和工件容量指标及 PrometheusRule 已实现；生产通知出口和演练属于独立生产验收 | MetricsController、PrometheusRule、GitHub Actions |
| [—] | P2-05 | 多用户/多租户权限模型 | **明确不做。** 当前维持单管理域，不设计租户、角色、配额和组织隔离模型 | 需求决策记录 |
| [~] | P2-06 | 更丰富的桌面和浏览器平台 | 已有受控桌面 companion 和 Browser Worker；补齐常用交互、平台适配、会话恢复和兼容性自描述，不扩大 MCP 原始工具面 | 平台兼容矩阵和资源报告 |
| [x] | P2-07 | 旧 Go 回滚路径退出 | Java 已是生产路径，Go 自动发布/部署已降为兼容归档；旧 workflow、Deployment、Service/PVC 和旧代码的最终移除属于独立生产变更验收 | release workflow、GO_RETIREMENT 文档、GitHub Actions |

## 当前最短生产验收路径

代码状态已按实现/CI 更新；接下来只推进独立的生产验收，不重复改动已完成代码。P0/P1 现场门禁仍是“完全替换旧版”和“完整生产体验”声明的前置条件：

1. `P0-06` 执行范围与权限合同；
2. `P0-05` 工件存储与生命周期；
3. `P0-03`、`P0-07`、`P0-10` 可靠性、资源和离线 Agent 验收；
4. `P1-01` 至 `P1-06` 的真实项目、桌面、浏览器、控制台和升级流程；
5. `P1-07` 至 `P1-09` 的热更新、生命周期和审计；
6. P2-04/P2-03：可观测性、日志与对象生命周期；
7. P2-06：桌面/浏览器平台增强；
8. P2-01：传输基准、可选 QUIC/HTTP3 provider 和安全回退；
9. P2-07：完成兼容窗口后退出 Go 回滚路径；P2-02/P2-05 不进入实施。

未完成 P0 门禁前，不应宣称“完全替换旧版”；未完成 P1 门禁前，不应宣称“完整生产体验”；P2 是规模化路线，不阻塞单 Center 生产运行。
