# RCM 生产任务清单

更新时间：2026-09-18（Asia/Shanghai）

本文是 Remote Connect MCP 的可持续任务清单。第一列只表示代码交付状态：实现和自动化检查完成即可勾选；生产验收单独记录在 [`PRODUCTION_ACCEPTANCE.md`](PRODUCTION_ACCEPTANCE.md)，不再阻止代码任务勾选。这样可以明确区分“代码没做完”和“代码已完成但尚未在目标环境验收”。

需求基线：[`REQUIREMENTS.md`](REQUIREMENTS.md)；当前实现证据：[`STATUS.md`](STATUS.md)；架构约束：[`ARCHITECTURE.md`](ARCHITECTURE.md)；异步契约：[`ASYNC_CONTRACT.md`](ASYNC_CONTRACT.md)；M:M 用户/对话/MCP 设计：[`MULTI_USER_MODEL.md`](MULTI_USER_MODEL.md)。

## 状态规则

- `[x]` 代码完成：实现和自动化检查有证据；生产状态看独立验收表。
- `[ ]` 代码待办：尚未开始或尚未形成可验收实现。
- `[~]` 代码实施中：已有部分实现，但仍有代码/协议/测试缺口。
- `[—]` 明确不做：经需求决策从当前路线移除，不再作为交付门禁。
- `生产验收` 不再改变第一列；在 [`PRODUCTION_ACCEPTANCE.md`](PRODUCTION_ACCEPTANCE.md) 中记录 `通过`、`部分通过`、`待验收` 或 `不适用`。
- 每项代码完成后，在“验收证据”列补充 CI run/测试证据，再将代码状态改为 `[x]`；生产目标机证据随后补到独立验收表。
- 不在本机编译 Java、Native Image、React 或正式安装包；构建证据必须来自 GitHub Actions。

## 当前阶段：开发项已闭环，等待一次统一 CI/生产验收

截至当前 `main`，P0/P1 的主要协议、Center/Agent/Console 主流程和兼容实现已经完成；本轮 **Artifact Transport v2、会话/配额、READ/WRITE/EXCLUSIVE 车道、桌面/浏览器资源回收和 Viewer 支撑代码也已完成**。现有 Task Artifact 继续承担小型截图和诊断结果；通用文件改走独立的流式 File Transfer 数据面。原 P2-02 和完整 SaaS 多租户仍明确不做，P2-05-lite 保持轻量主体隔离路线。接下来只做一次统一 GitHub Actions 构建/集成验证，再进入目标机和 ChatGPT Web 生产验收。

| 层级 | 当前判断 | 剩余工作 |
| --- | --- | --- |
| P0 | 核心可靠性、安全和 Artifact Transport v2 代码已完成 | 统一 Actions 集成、Center/Agent 重启/断线组合矩阵、工件卷备份恢复和离线升级目标环境门禁 |
| P1 | 主流程、桌面/浏览器、控制台和 Viewer 代码已完成 | Windows/Linux Desktop 与 Browser、ChatGPT Web 文件对象、Git/Console/升级/长连接真实矩阵，以及无障碍/视觉验收 |
| P2 | P2-01/03/04/06/07 与 P2-05-lite 代码已完成；P2-02 和完整 SaaS 多租户按决策移除 | QUIC/HTTP3 真实 Provider、集中日志/对象生命周期、SLO/告警演练和多主体双账号现场验收 |

当前证据基线：Java Native Release `35043403122`（tag `java-v0.1.28`）成功；稳定 `java-v0.1.29` Release `35064539692` 的三平台 Native、镜像、SBOM、签名和 release jobs 成功，Linux GUI canary 已使用其 arm64 Desktop 资产完成真实 screens/截图；GitOps revision `eee62d2` 已由 Argo 报告 `Synced/Healthy/Succeeded`；事件驱动检查、仓库敏感信息扫描和浏览器脚本静态检查均通过。本机没有执行 Java、Gradle、Native Image 或 React 构建。

## P0：平台必须可靠

| 代码状态 | 编号 | 任务 | 当前情况与完成条件 | 代码验收证据 |
| --- | --- | --- | --- | --- |
| [x] | P0-01 | 固定 MCP 地址和多机器路由 | `/mcp`、Bearer 和按 machine ID 路由已可用；后续不因 Center/Agent/Console 升级改变连接器地址 | v0.1.21 MCP/health/ready 验收 |
| [x] | P0-02 | 机器注册与凭据分层 | 一次性 Enrollment Token、独立 Agent Token、稳定 MCP Token、独立 Admin Token 已实现；Enrollment 不写入长期配置 | 注册与身份测试、生产 Secret |
| [x] | P0-03 | 异步任务全链路恢复 | 已有幂等、租约、Attempt、旧 attempt 回传栅栏、取消、输出游标和 LISTEN/NOTIFY；Agent 对重复 poll 回传增加原子 `putIfAbsent` dispatch fence，避免覆盖正在运行的 Future；Center 重启、Agent 断线、重复重试、长任务和高并发属于独立生产验收 | `AgentRuntimeTest.duplicateTaskRegistrationKeepsTheFirstRunner`、GitHub Actions |
| [x] | P0-04 | PostgreSQL + Liquibase 唯一事实来源 | PostgreSQL、Liquibase `001`–`018`、旧 Go 状态导入和迁移 Job 的代码与 CI 实现完成；生产迁移/版本收敛属于独立生产验收 | PostgreSQL/Liquibase CI、`PostgresIntegrationTest` |
| [x] | P0-05 | 任务与工件的持久化边界 | `ArtifactStore`、持久卷文件对象、原子写入/读取校验、旧 `artifact_data` 懒迁移和显式 GC API 已实现；生产卷备份/恢复、保留策略和规模压测属于独立生产验收 | 对象存储适配、迁移/恢复、生命周期测试、GitHub Actions |
| [x] | P0-06 | 执行范围与权限合同 | project/worktree/path/workspace/unrestricted 合同、Center/Agent/桌面双端校验、预算收窄和凭据过滤已实现；目标机绕过与回归属于独立生产验收 | `DesktopCompanionServerTest`、GitHub Actions |
| [x] | P0-07 | Agent 资源硬限制 | 任务级进程树/墙钟/CPU/RSS 监督、输出/磁盘/并发上限、Linux cgroup 可选边界、Windows 有界进程树/Task Scheduler 路径和 Agent 总进程预算已实现；目标机压测属于独立生产验收 | GitHub Actions Native smoke、RSS gate、资源监督测试 |
| [x] | P0-08 | 隐私、密钥和仓库卫生 | 公开仓库使用模板值；真实域名、Token、Secret 和私有 GitOps 留在受保护环境；日志/指标有脱敏约定 | 仓库扫描、CI hygiene、私有部署检查 |
| [x] | P0-09 | GitHub Actions 构建和可安装包 | Java/Native/React/安装包、SBOM、签名和烟测由 GitHub Actions 完成；开发机不编译 | Java Release workflow、Native smoke、RSS gate |
| [x] | P0-10 | 全部已登记 Agent 的恢复 | 升级活动默认把未显式指定的全部登记 Agent（含离线）写入持久目标集；离线 Agent 下次心跳自动领取同一 offer；真实在线清单、升级活动和任务闭环属于独立生产验收 | 升级活动目标集测试、GitHub Actions |
| [x] | P0-11 | Artifact Transport v2 双向文件链路 | 协议、`020` 元数据表、流式 ObjectStore、Agent GET/PUT、大小/SHA-256/范围校验、断点续传、并发/磁盘配额和精简 `artifact_put`/`artifact_get`/`artifact_read` 已完成；端到端 CI、ChatGPT Web 附件渲染和目标环境故障矩阵单独验收 | 设计基线见 [`ARTIFACT_TRANSPORT_V2.md`](ARTIFACT_TRANSPORT_V2.md)；本轮代码与测试完成，统一 Actions 待跑 |

### P0 生产验收门禁（不计代码状态）

- [x] P0-G1：范围合同不能被改变 cwd、环境变量或任务重试绕过；Linux/Windows path 合同、环境变量伪造、越界路径和幂等重试已完成目标机验收。
- [~] P0-G2：Agent 断线重连和 durable 任务不重复执行已验收；Center 重启及 ChatGPT 端重试仍需维护窗口演练。
- [~] P0-G3：长输出分页、任务超时、无超时 durable 任务和 32 子进程上限已验收；截图/下载能力与 RSS/CPU 极限压测尚未具备目标条件。
- [x] P0-G4：所有 9 台在线 Agent 均通过固定 `/mcp` 的 `command_start` 路由并以 `task_wait`/终态核对。

### P0-11 Artifact Transport v2 子任务

| 状态 | 子任务 | 完成条件 | 证据/下一步 |
| --- | --- | --- | --- |
| [x] | P0-11-01 | 固化 `FileTransferAction`/`FileTransferResponse`、`file_transfer` capability 与任务幂等/Attempt 兼容 | protocol/TaskService/JDBC 静态实现；Actions 编译待验证 |
| [x] | P0-11-02 | Liquibase `020` 创建 Artifact/Transfer 元数据和 task action JSONB，数据库不存二进制 | `020-artifact-transport.yaml`；PostgreSQL Actions 待验证 |
| [x] | P0-11-03 | Center filesystem/HTTP ObjectStore 支持流式写入/打开、临时文件和 SHA-256 校验 | `ArtifactStore`、`FileSystemArtifactStore`、`HttpArtifactStore` |
| [x] | P0-11-04 | Agent 侧通过目标路径合同校验、`.rcm-part-*` 临时文件和原子改名收发文件 | `FileTransferTaskRunner`、`AgentPaths`、`AgentTransportClient` |
| [x] | P0-11-05 | MCP 暴露精简 `artifact_put`/`artifact_get`/`artifact_read`，使用 `openai/fileParams`，文本只返回句柄 | `McpConfiguration`；连接器刷新和 Web 实测待验收 |
| [x] | P0-11-06 | 断点分块/偏移确认、断线续传和传输状态幂等恢复 | Agent→Center `HEAD` 偏移探测、8 MiB `Content-Range` 分块、Center PVC partial spool、断线后按偏移重试，以及 Web→Agent HTTP Range + 稳定 `.rcm-part-*` 文件已完成；PostgreSQL/真实中断矩阵和 Actions 属于独立验收 |
| [ ] | P0-11-07 | ChatGPT Web 文件对象真实渲染/下载闭环（图片、PDF、Office、未知二进制） | 依赖 P1-10 Artifact Viewer 和真实连接器 |

### Artifact Transport v2 补充风险清单

以下条目对应本轮文件传输审查提出的 20 个具体风险。状态仍按“代码实现 + GitHub Actions”判定；真实 Web/生产验收继续记录在 `PRODUCTION_ACCEPTANCE.md`，不会用静态检查代替现场证据。

| 状态 | 编号 | 风险/任务 | 当前实现与剩余门禁 |
| --- | --- | --- | --- |
| [x] | P0-AT-01 | Agent→Center 大文件上传超时 | Agent 上传使用独立 transfer timeout，普通控制请求不会把长流误判为 30 秒超时；长流 Actions/现场回归单独验收 |
| [x] | P0-AT-02 | Artifact 保留期限与签名 URL TTL 解耦 | Artifact 保留期、签名 URL TTL 和 GC 窗口分开配置；JDBC 生命周期/GC 属于统一验收 |
| [x] | P0-AT-03 | Transfer 状态机与 Agent ACK | `pending→ready→delivering→delivered/failed/canceled`、Attempt 栅栏、幂等终态和 Agent ACK 已接入；重启/重复 ACK 属于统一验收 |
| [x] | P0-AT-04 | `overwrite=false` 原子覆盖竞争 | Agent 最终 move 不带 `REPLACE_EXISTING`，并拒绝符号链接/特殊文件；Windows/Linux 文件系统回归单独验收 |
| [x] | P0-AT-05 | 幂等预约前置 | durable reservation、同键短期 future hand-off、异常插入和重启失败收口已实现；跨实例门禁不属于本路线 |
| [x] | P0-AT-06 | 并发与临时磁盘硬配额 | Center 进程内全局/主体/机器并发、持久 partial spool 总量和可用空间 reservation 已实现；单 Center 容量演练单独验收 |
| [x] | P0-AT-07 | Web→Center ingest 异步化 | MCP 只创建 pending 句柄，Center 虚拟线程完成下载、校验和发布；失败/重启行为进入统一 Actions/现场验收 |
| [x] | P0-AT-08 | 端到端故障恢复矩阵 | PostgreSQL 集成路径、偏移恢复、重复上传幂等、对象提交和安全收口代码已完成；Center/Agent 真重启、lease 过期、对象存储短暂失败和中断组合属于生产门禁 |
| [ ] | P1-AT-01 | ChatGPT Web Artifact 真实 E2E | 需要真实连接器验证 Web 上传→终端、终端→当前会话 |
| [x] | P1-AT-02 | React Artifact Viewer v1 | 任务记录页支持图片、PDF、音视频、文本预览及通用下载，Office/压缩包/未知类型保持下载；真实连接器附件渲染属于 E2E |
| [x] | P1-AT-03 | MCP annotations 与严格 Output Schema | 文件工具 annotations、嵌套 task/transfer/file schema 和稳定 `ui://` Viewer 资源已收紧；连接器刷新验证单独进行 |
| [x] | P1-AT-04 | 签名 URL Session/Purpose 绑定 | HMAC subject 绑定主体、connection、purpose 和 execution session，并保留滚动发布兼容路径；代理回归单独进行 |
| [x] | P1-AT-05 | Artifact 下载安全 Header | `private/no-store`、`nosniff`、`no-referrer` 和 MIME fallback 已加入；代理缓存回归单独进行 |
| [x] | P1-AT-06 | Agent 源文件快照一致性 | 同目录稳定快照用于 hash + upload，并有磁盘余量、符号链接和临时文件校验 |
| [x] | P1-AT-07 | Stall timeout / progress watchdog | Center 与 Agent 流式读取拆分 absolute lifetime 与无进展超时，Center 按 4 MiB/1 秒节流更新 `bytes_transferred`；慢链路矩阵单独验收 |
| [x] | P2-AT-01 | READ / WRITE / EXCLUSIVE 执行车道 | `LaneMode` 已接入协议、合同、内存/JDBC poll；同车道 READ 可并发，WRITE/EXCLUSIVE 互斥 |
| [x] | P2-AT-02 | 零字节与边缘文件语义 | Center filesystem/HTTP、协议、Agent、Browser 小工件和 PostgreSQL 迁移允许合法 0 字节；特殊文件/符号链接明确拒绝 |
| [x] | P2-AT-03 | destination path / file name 语义收敛 | destination/source 是完整路径，`file_name` 仅为展示覆盖名，缺省取路径末段；协议、校验和 UI 已同步 |
| [x] | P2-AT-04 | 传输容量、SLO 与生命周期指标 | `/metrics` 暴露 active/delivered/failed/canceled、传输/声明字节、resume、partial spool、GC bytes 和终态时长等低基数指标 |

## P1：核心生产体验

| 代码状态 | 编号 | 任务 | 当前情况与完成条件 | 代码验收证据 |
| --- | --- | --- | --- | --- |
| [x] | P1-01 | Project Registry 与 Git worktree 闭环 | 注册、受保护删除、创建/删除 worktree、cwd 解析、status/diff/log、幂等 commit、显式 merge 和 `merge_abort` 冲突恢复排队 API 已实现并接入精简 MCP；冲突输出、提交差异审阅和目标机权限属于独立生产验收 | ProjectService/协议测试、GitHub Actions |
| [x] | P1-02 | Windows/Linux Desktop Companion 真实能力 | 独立 Desktop Native 目标、A​WT/Java2D、X11/Wayland 原生截图 fallback、屏幕/窗口枚举、输入/剪贴板/聚焦、IPC 合同复核、连接/启动进程上限和退出回收已完成；Windows 登录任务使用无控制台 VBS 启动器，旧安装可用 repair 脚本切换；Windows 多会话/UAC/RDP 等属于现场验收 | Desktop 协议/服务测试；平台矩阵和 GitHub Actions 统一验收 |
| [x] | P1-03 | Browser Agent 生产运行时 | 独立 Browser Native 目标、Playwright/Patchright/Comoufox 适配、结构化引用、有界快照、持久 profile、登录态 marker、stale ref 恢复、下载工件和 admission 清理已完成；浏览器安装与跨引擎属于现场验收 | BrowserTaskRunner、Node 静态检查；GitHub Actions/运行时矩阵单独验收 |
| [x] | P1-04 | React 控制台完整工作流 | 机器、项目/worktree、任务、Token/ACL、升级、审计、会话/车道/配额、Artifact Viewer 和范围编排入口已完成；实时/无障碍/视觉门禁属于现场验收 | React 源码/类型边界与 UI 组件实现；GitHub Actions/E2E 单独验收 |
| [x] | P1-05 | 升级 offer/attempt 与兼容回滚 | canary、批次、SHA-256、原子替换和回滚、attempt 栅栏、PostgreSQL 行锁、失败目标单独重排队均已实现；数据库并发、版本兼容、离线补升级和现场演练属于独立生产验收 | `UpgradeServiceTest`、GitHub Actions |
| [x] | P1-06 | WebSocket/事件唤醒生产验收 | wake-only WebSocket、指数重连、HTTPS 回退、PG 通知桥接和序列号去重已实现；真实反向代理、断线和 Center 重启属于独立生产验收 | WebSocket smoke、TransportNegotiation/AgentWake 测试、GitHub Actions |
| [x] | P1-07 | 配置与心跳自描述 | 版本化 runtime descriptor、generation/CAS、有限历史和回滚、旧 schema 有界兼容已实现；目标机回滚演练属于独立生产验收 | AgentRuntimeSettings/ConfigurationService/runtime descriptor 测试、GitHub Actions |
| [x] | P1-08 | 终端与子 Agent 生命周期 | command-agent、desktop-companion、browser-agent 已拆为独立构建目标；各自拥有锁、并发/进程预算、会话隔离和崩溃/退出回收，Center 只登记 command-agent 身份；多物理 Agent 主机现场矩阵单独验收 | `AgentRuntime`、`DesktopCompanionServer`、`BrowserTaskRunner` 边界实现；统一 Actions/多 Agent 现场验收 |
| [x] | P1-09 | 审计与错误可解释性 | 有界异步审计队列、PostgreSQL `rcm_audit_event`、Admin/Console 查询、来源区分、错误脱敏和有界保留清理入口已实现；审批来源细化、脱敏抽样和真实故障报告属于独立生产验收 | StructuredLog/AuditService/脱敏测试、GitHub Actions |
| [x] | P1-10 | ChatGPT Web Artifact Viewer | 稳定 `ui://remote-connect-mcp/artifact-viewer-v1.html` 资源、`openai/fileParams`、structured `file` 对象、React 任务页预览/下载和大文件引用桥接已实现；真实 ChatGPT Web 渲染属于 P1-AT-01 E2E | `McpConfiguration` resource/output schema、React Artifact Viewer；连接器刷新/真实 Web E2E |

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
| [x] | P2-05 | 轻量多主体、对话与 MCP 连接模型 | 每个用户使用独立不透明 Bearer Token；主体、连接元数据、任务归属、主体维度幂等键、READ/WRITE/EXCLUSIVE 车道、机器/项目 ACL、执行会话合同、配额和 Desktop/Browser 隔离已接入；MCP 列表固定大小摘要、详情按需查询，工件大于 512 KiB 只返回引用；不做完整 SaaS 多租户、跨组织计费或复杂 RBAC | `McpPrincipalService`、`McpAccessService`、`ExecutionSessionService`、`McpQuotaService`、`TaskService`、`015`–`020`；统一 Actions/双账号现场验收 |
| [x] | P2-06 | 更丰富的桌面和浏览器平台 | Desktop 常用输入/窗口/剪贴板/截图、Wayland fallback，Browser 常用动作/引用/会话恢复/多引擎适配及资源回收均已实现，不扩大 MCP 原始工具面 | Desktop/Browser 代码与协议测试；平台兼容矩阵和资源报告单独验收 |
| [x] | P2-07 | 旧 Go 回滚路径退出 | Java 已是生产路径，Go 自动发布/部署已降为兼容归档；旧 workflow、Deployment、Service/PVC 和旧代码的最终移除属于独立生产变更验收 | release workflow、GO_RETIREMENT 文档、GitHub Actions |

### P2-05-lite：M:M 用户、对话与 MCP 实施清单

本节是对原 P2-05 的收窄替代：只做轻量主体隔离和协同调度，不引入完整多租户平台。代码实现已完成；真实双账号/多窗口行为保留为独立现场验收。

| 状态 | 子任务 | 完成条件 | 依赖/验收 |
| --- | --- | --- | --- |
| [x] | P2-05-D | 从多用户、多对话、同项目/同终端稳定并发和结果隔离需求反选设计；完成实体关系、竞品比较和最终选型 | [`MULTI_USER_MODEL.md`](MULTI_USER_MODEL.md) §11–§12 |
| [x] | P2-05-01 | Liquibase 增加 `principal`、用户 MCP Token、Token 范围/撤销、任务主体和配额字段；兼容全局 Token 映射为 owner/shared 主体；机器/项目 ACL 在 `018`/`020` | `015/018/020`、`McpPrincipalService`、`McpAccessService`、Token 哈希/一次性明文返回实现；统一 Actions 待跑 |
| [x] | P2-05-02 | Center 从 Bearer 派生主体并把主体送入 MCP 异步 Exchange；任务、输出、工件、项目和机器按主体过滤，配额和 Admin access API 已接入 | `McpTransportContext`、`McpAccessService`、`McpQuotaService`、Admin access API |
| [x] | P2-05-03 | 幂等键加入主体维度；同一主体重试复用任务，不同主体相同键互不冲突 | `TaskServiceTest` principal/idempotency；JDBC 并发属于现场门禁 |
| [x] | P2-05-04 | 按机器+项目/worktree/path 派生稳定 lane_key，READ 可共享、WRITE/EXCLUSIVE 互斥；WorkspacePolicy 和 lease 细化已落地 | `ExecutionLaneKey`、内存/JDBC poll 车道实现与测试 |
| [x] | P2-05-05 | Desktop 独占 lease、Browser 按主体/对话隔离 Context/Profile，任务结束回收、stale marker 清理和崩溃退出已实现 | `DesktopCompanionServer`、`BrowserTaskRunner`；两用户现场矩阵单独验收 |
| [x] | P2-05-06 | React Console 增加主体、Token、项目成员、机器授权、会话、范围、配额、撤销和任务归属页面 | `AccessControl`、Admin API、脱敏投影；UI E2E/a11y 单独验收 |
| [ ] | P2-05-07 | 双账号多窗口端到端验收；同 URL、不同 Token、同项目/不同 worktree、撤销和故障恢复 | ChatGPT Web/Console/Center/Agent 真实矩阵 |
| [x] | P2-05-R08 | MCP 上下文预算与摘要投影 | `machines_list` 只返回固定字段摘要（最多 25 台），`project list` 不返回本地路径且 worktree 摘要最多 10 条；需要时可通过 `project(operation=detail)` 分页获取项目详情，路径必须显式 `include_paths=true`；任务输出保持游标分页，MCP JSON 设置 192 KiB 最后防线；图片仅在不超过 512 KiB 时内联，较大工件改用 SHA-256/Console 引用 | `McpConfiguration` compact/detail projection/response guard；GitHub Actions |

### P2-05 需求驱动并发子项

以下子项直接对应“多用户多会话同时处理同一项目/终端，稳定且结果不串话”的不可违反
需求。它们是上面实现任务的细化验收点，代码完成后逐项勾选：

| 状态 | 子项 | 目标和完成条件 |
| --- | --- | --- |
| [x] | P2-05-R01 | 固化并发不变量：任务绑定主体、ExecutionSession、Attempt 和 ResultChannel；禁止全局结果广播 | 任务主体/连接元数据、显式 `execution_session_id`、任务专属 `result_channel`、lane_key、会话合同、访问时过期回收和固定预算摘要/游标均已落地 |
| [x] | P2-05-R01-A | 任务创建时绑定认证主体、连接来源、幂等键和派发车道；跨主体同键不冲突 | `015/016`、`TaskServiceTest`、`PostgresIntegrationTest` |
| [x] | P2-05-R01-B | 持久化显式 `execution_session_id` 与任务专属 `result_channel`，重连/重启可恢复且不做全局广播 | `017-task-session-channel`、`a96653f`、GitHub Actions `35101569455`/`35101569558` |
| [x] | P2-05-R01-C | 补齐会话生命周期、能力/预算持久化和会话级 ACL | `019-execution-sessions`、`ExecutionSessionService` 实现 ensure/close/list、访问时自动过期、能力/合同校验和 Console 页面 |
| [x] | P2-05-R02 | 固化 `isolated`、`shared_serial`、`host` 三种 WorkspacePolicy；默认写任务使用会话 worktree | `WorkspacePolicyMode`、合同校验、MCP/Admin/Console 范围入口 |
| [x] | P2-05-R03 | 同一 checkout 写车道串行、不同 worktree 有界并行、只读任务有限并行；不做隐式合并 | READ/WRITE/EXCLUSIVE lane 冲突判定、内存/JDBC poll 已实现 |
| [x] | P2-05-R04 | 实现整机/终端 host lane：独立进程组/cwd/env；主机全局写入串行，冲突任务持久化排队 | host lane、进程树监督、持久化 lease 和唤醒路径已实现 |
| [x] | P2-05-R05 | 实现任务句柄+主体/会话 ACL 的结果路由；`task_wait/output/cancel` 不接受跨会话猜测查询 | 任务句柄、主体 ACL、会话能力校验和专属 result channel 已实现 |
| [x] | P2-05-R06 | 实现同一桌面会话独占 Desktop lease、浏览器 Context/Profile 隔离和崩溃回收 | `DesktopCompanionServer`/`BrowserTaskRunner` ref-counted locks、进程回收和 admission 清理 |
| [ ] | P2-05-R07 | 双用户/多窗口/同项目/同终端故障矩阵：超时、重试、断线、Center/Agent 重启均不重复执行、不串结果 |

## 当前最短生产验收路径

开发项已按代码实现和测试门禁收口；接下来只推进一次统一 GitHub Actions 验证，再推进独立生产验收，不重复改动已完成代码。P0/P1 现场门禁仍是“完全替换旧版”和“完整生产体验”声明的前置条件：

1. `P0-06` 执行范围与权限合同；
2. `P0-05` 工件存储与生命周期；
3. `P0-03`、`P0-07`、`P0-10` 可靠性、资源和离线 Agent 验收；
4. `P1-01` 至 `P1-06` 的真实项目、桌面、浏览器、控制台和升级流程；
5. `P1-07` 至 `P1-09` 的热更新、生命周期和审计；
6. P2-04/P2-03：可观测性、日志与对象生命周期；
7. P2-06：桌面/浏览器平台增强；
8. P2-01：传输基准、可选 QUIC/HTTP3 provider 和安全回退；
9. P2-07：完成兼容窗口后退出 Go 回滚路径；P2-02 和完整 SaaS 多租户不进入实施。
10. P2-05-lite：在不改变 `/mcp` URL、Agent 身份和 MCP 工具数量的前提下，验收已实现的多主体 Token、ACL、执行车道、会话/桌面/浏览器隔离和配额。
11. `P0-11` → `P1-10`：先完成 Center/Agent 文件数据面，再接入 ChatGPT Web Artifact Viewer；首次变更工具声明时刷新一次连接器，之后以稳定 schema/URI 维持兼容。

未完成 P0 门禁前，不应宣称“完全替换旧版”；未完成 P1 门禁前，不应宣称“完整生产体验”；P2 是规模化路线，不阻塞单 Center 生产运行。
