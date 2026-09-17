# RCM 生产任务清单

更新时间：2026-09-17（Asia/Shanghai）

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

## 当前阶段：P0/P1 生产验收与 Artifact Transport v2 实施

截至当前 `main`，P0/P1 的主要协议、Center/Agent/Console 主流程和兼容实现已经完成，现新增 **Artifact Transport v2** 作为 P0 文件能力增量：目标是让 ChatGPT Web 上传的文件可靠写入终端，并让终端生成的任意文件通过 MCP/React UI 返回网页。现有 Task Artifact 继续承担小型截图和诊断结果；通用文件改走独立的流式 File Transfer 数据面。原 P2-02 和完整 SaaS 多租户仍明确不做，P2-05-lite 按既定轻量主体隔离路线继续。

| 层级 | 当前判断 | 剩余工作 |
| --- | --- | --- |
| P0 | 核心可靠性与安全实现基本完成；Artifact Transport v2 已进入实施 | Center/Agent 重启、断线与重试、资源硬限额、文件分块传输、工件卷备份恢复、离线升级等目标环境门禁 |
| P1 | 主流程已具备，平台特性待实测 | Windows/Linux Desktop 与 Browser、Git/Console/升级/长连接真实矩阵，以及无障碍、视觉和代理故障验收 |
| P2 | P2-01/03/04/06/07 已进入实施/验收队列；P2-02 和完整 SaaS 多租户按决策移除；P2-05-lite 已完成第一阶段主体/任务/车道/ACL 代码，仍在补齐会话、配额和桌面/浏览器隔离 | QUIC/HTTP3 真实 Provider、集中日志与对象生命周期、SLO/告警通知、桌面/浏览器平台增强、Go 回滚路径退出，以及多主体会话/配额 |

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
| [~] | P0-11 | Artifact Transport v2 双向文件链路 | 已完成协议记录、`020` 元数据表、流式 ObjectStore、Agent GET/PUT、大小/SHA-256/范围校验和精简 `artifact_put`/`artifact_get`/`artifact_read` 工具；断点分块、端到端 CI、ChatGPT Web 附件渲染仍待完成 | 设计基线见 [`ARTIFACT_TRANSPORT_V2.md`](ARTIFACT_TRANSPORT_V2.md)；本机静态门禁通过，等待 GitHub Actions |

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
| [ ] | P0-11-06 | 断点分块/偏移确认、断线续传和传输状态幂等恢复 | 需要 Center/Agent integration test 与 Actions |
| [ ] | P0-11-07 | ChatGPT Web 文件对象真实渲染/下载闭环（图片、PDF、Office、未知二进制） | 依赖 P1-10 Artifact Viewer 和真实连接器 |

### Artifact Transport v2 补充风险清单

以下条目对应本轮文件传输审查提出的 20 个具体风险。状态仍按“代码实现 + GitHub Actions”判定；真实 Web/生产验收继续记录在 `PRODUCTION_ACCEPTANCE.md`，不会用静态检查代替现场证据。

| 状态 | 编号 | 风险/任务 | 当前实现与剩余门禁 |
| --- | --- | --- | --- |
| [~] | P0-AT-01 | Agent→Center 大文件上传超时 | Agent 上传已使用独立 transfer timeout；需 Actions 原生/长流回归 |
| [~] | P0-AT-02 | Artifact 保留期限与签名 URL TTL 解耦 | 保留期与 URL TTL 分开配置；需 JDBC 生命周期/GC 回归 |
| [~] | P0-AT-03 | Transfer 状态机与 Agent ACK | `pending→ready→delivering→delivered/failed/canceled`、Attempt 栅栏和 ACK 已接入；需重启/重复 ACK 矩阵 |
| [~] | P0-AT-04 | `overwrite=false` 原子覆盖竞争 | Agent 最终 move 不带 `REPLACE_EXISTING`；需 Windows/Linux 文件系统回归 |
| [~] | P0-AT-05 | 幂等预约前置 | pending durable reservation、同键短期 future hand-off 和重启失败收口已实现；跨实例/异常插入回归待补 |
| [~] | P0-AT-06 | 并发与临时磁盘硬配额 | Center 进程内全局/主体/机器并发及可用空间 reservation 已实现；多进程容量演练待补 |
| [~] | P0-AT-07 | Web→Center ingest 异步化 | MCP 只创建 pending 句柄，Center 虚拟线程完成下载、校验和发布；失败/重启恢复待 Actions 验证 |
| [~] | P0-AT-08 | 端到端故障恢复矩阵 | PostgreSQL 集成门禁已覆盖独立 Center facade 重启读、流式 Agent→Web 状态/字节落库、重复上传幂等和签名对象恢复；Center/Agent 真重启、lease 过期、对象存储短暂失败和中断组合仍待现场矩阵 |
| [ ] | P1-AT-01 | ChatGPT Web Artifact 真实 E2E | 需要真实连接器验证 Web 上传→终端、终端→当前会话 |
| [~] | P1-AT-02 | React Artifact Viewer v1 | 任务记录页已支持图片、PDF、音视频、文本预览及通用下载，Office/压缩包/未知类型保持下载；真实连接器附件渲染仍待 E2E |
| [~] | P1-AT-03 | MCP annotations 与严格 Output Schema | 文件工具 annotations、嵌套 task/transfer/file schema 已收紧；需 Actions 与连接器刷新验证 |
| [~] | P1-AT-04 | 签名 URL Session/Purpose 绑定 | HMAC subject 已绑定主体、connection、purpose 和 execution session，并保留滚动发布兼容路径；需 Actions/代理回归 |
| [~] | P1-AT-05 | Artifact 下载安全 Header | `private/no-store`、`nosniff`、`no-referrer` 已加入；需代理缓存回归 |
| [~] | P1-AT-06 | Agent 源文件快照一致性 | 同目录快照用于 hash + upload，并有磁盘余量检查；文件持续写入语义待验收 |
| [~] | P1-AT-07 | Stall timeout / progress watchdog | Center 流式读取已拆分 absolute lifetime 与无进展超时，按 4 MiB/1 秒节流更新 `bytes_transferred`；Agent 端和慢链路矩阵待 Actions/现场验收 |
| [ ] | P2-AT-01 | READ / WRITE / EXCLUSIVE 执行车道 | 当前仍按 lane 串行；读写分类和共享读并发待实现 |
| [~] | P2-AT-02 | 零字节与边缘文件语义 | Center filesystem/HTTP、协议、Agent 和 Browser 小工件已允许合法 0 字节；特殊文件/符号链接策略待补 |
| [ ] | P2-AT-03 | destination path / file name 语义收敛 | 目前同时保留目标路径和展示文件名；需明确 API 契约及迁移兼容 |
| [~] | P2-AT-04 | 传输容量、SLO 与生命周期指标 | `/metrics` 已增加 active/delivered/failed/canceled、传输/声明字节、平均/最大终态时长等低基数指标；resume 次数、GC bytes 与现场 SLO 面板待接入 |

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
| [~] | P1-08 | 终端与子 Agent 生命周期 | 默认一个 command-agent 身份，桌面/浏览器作为独立 Native 目标；command-agent 只负责 Center 生命周期和 IPC/Worker 编排，不包含 AWT 或桌面直启实现；desktop-companion、browser-agent 各自拥有锁、并发和子进程回收；多物理 Agent 的显式隔离、互斥、崩溃拉起和 Center 视图仍有代码缺口 | `DesktopCompanionServerTest`、边界静态门禁、GitHub Actions；多 Agent 主机测试待补齐 |
| [x] | P1-09 | 审计与错误可解释性 | 有界异步审计队列、PostgreSQL `rcm_audit_event`、Admin/Console 查询、来源区分、错误脱敏和有界保留清理入口已实现；审批来源细化、脱敏抽样和真实故障报告属于独立生产验收 | StructuredLog/AuditService/脱敏测试、GitHub Actions |
| [ ] | P1-10 | ChatGPT Web Artifact Viewer | 设计已固定为稳定 `ui://` 资源 URI + 文件对象 `download_url`；React Apps SDK 组件支持上传/选择文件、终端文件预览、下载和可选保存到 ChatGPT 尚未实现 | `_meta.ui.resourceUri`、`openai/fileParams`、文件桥接 E2E |

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
| [~] | P2-05 | 轻量多主体、对话与 MCP 连接模型 | **第一阶段已实施。** 每个用户使用独立不透明 Bearer Token；主体、连接元数据、任务归属、主体维度幂等键、执行车道、机器/项目 ACL 和执行会话合同已接入；MCP 列表使用固定大小摘要投影，详情按需查询，工件大于 512 KiB 时只返回引用；配额、会话自动过期和 Desktop/Browser 会话隔离待补；不做完整 SaaS 多租户、跨组织计费或复杂 RBAC | `McpPrincipalService`、`McpAccessService`、`ExecutionSessionService`、`McpTransportContext`、`TaskService`、`015`–`019`；GitHub Actions 待跑 |
| [~] | P2-06 | 更丰富的桌面和浏览器平台 | 已有受控桌面 companion 和 Browser Worker；补齐常用交互、平台适配、会话恢复和兼容性自描述，不扩大 MCP 原始工具面 | 平台兼容矩阵和资源报告 |
| [x] | P2-07 | 旧 Go 回滚路径退出 | Java 已是生产路径，Go 自动发布/部署已降为兼容归档；旧 workflow、Deployment、Service/PVC 和旧代码的最终移除属于独立生产变更验收 | release workflow、GO_RETIREMENT 文档、GitHub Actions |

### P2-05-lite：M:M 用户、对话与 MCP 实施清单

本节是对原 P2-05 的收窄替代：只做轻量主体隔离和协同调度，不引入完整多租户平台。设计已完成；R01、主体基础代码和机器/项目 ACL 已进入第一阶段，剩余项按依赖继续实施。

| 状态 | 子任务 | 完成条件 | 依赖/验收 |
| --- | --- | --- | --- |
| [x] | P2-05-D | 从多用户、多对话、同项目/同终端稳定并发和结果隔离需求反选设计；完成实体关系、竞品比较和最终选型 | [`MULTI_USER_MODEL.md`](MULTI_USER_MODEL.md) §11–§12 |
| [~] | P2-05-01 | Liquibase 增加 `principal`、用户 MCP Token、Token 范围/撤销和任务主体字段；兼容全局 Token 映射为 owner/shared 主体；机器/项目 ACL 已进入 `018`，用户配额待补 | `015/018`、`McpPrincipalService`、`McpAccessService`、哈希/一次性明文返回测试；GitHub Actions `35101569455`/`35101569558` |
| [~] | P2-05-02 | Center 从 Bearer 派生主体并把主体送入 MCP 异步 Exchange；任务、输出、工件按主体过滤；机器/项目 ACL 已在 MCP inventory/task/project/git 路径执行，配额与 UI 待补 | `McpTransportContext`、`McpAccessServiceTest`、Admin access API；GitHub Actions `35101569455`/`35101569558` |
| [~] | P2-05-03 | 幂等键加入主体维度；同一主体重试复用任务，不同主体相同键互不冲突 | `TaskServiceTest` principal/idempotency；JDBC 并发 CI 待跑 |
| [~] | P2-05-04 | 已按机器+项目/worktree/path 派生稳定 lane_key，并阻止同车道任务重复领取；WorkspacePolicy、lease 细化和公平调度待补 | `ExecutionLaneKey`、内存/JDBC poll 车道互斥测试；不同 worktree/主体配额待补 |
| [ ] | P2-05-05 | Desktop 独占 lease、Browser 按主体/对话隔离 Context/Profile，任务结束回收或续租 | 两用户交错操作、Cookie/下载隔离、崩溃回收 |
| [ ] | P2-05-06 | React Console 增加主体、Token、项目成员、范围、配额、撤销和任务归属页面 | UI E2E、a11y、脱敏和审计 |
| [ ] | P2-05-07 | 双账号多窗口端到端验收；同 URL、不同 Token、同项目/不同 worktree、撤销和故障恢复 | ChatGPT Web/Console/Center/Agent 真实矩阵 |
| [x] | P2-05-R08 | MCP 上下文预算与摘要投影 | `machines_list` 只返回固定字段摘要（最多 25 台），`project list` 不返回本地路径且 worktree 摘要最多 10 条；需要时可通过 `project(operation=detail)` 分页获取项目详情，路径必须显式 `include_paths=true`；任务输出保持游标分页，MCP JSON 设置 192 KiB 最后防线；图片仅在不超过 512 KiB 时内联，较大工件改用 SHA-256/Console 引用 | `McpConfiguration` compact/detail projection/response guard；GitHub Actions |

### P2-05 需求驱动并发子项

以下子项直接对应“多用户多会话同时处理同一项目/终端，稳定且结果不串话”的不可违反
需求。它们是上面实现任务的细化验收点，代码完成后逐项勾选：

| 状态 | 子项 | 目标和完成条件 |
| --- | --- | --- |
| [~] | P2-05-R01 | 固化并发不变量：任务绑定主体、ExecutionSession、Attempt 和 ResultChannel；禁止全局结果广播 | 任务主体/连接元数据、显式 `execution_session_id`、任务专属 `result_channel`、lane_key 已落地；`019` 已持久化会话最新合同并支持显式关闭；会话能力/自动过期回收待补；MCP 结果采用固定预算摘要和游标，不把长输出或全量清单注入对话 |
| [x] | P2-05-R01-A | 任务创建时绑定认证主体、连接来源、幂等键和派发车道；跨主体同键不冲突 | `015/016`、`TaskServiceTest`、`PostgresIntegrationTest` |
| [x] | P2-05-R01-B | 持久化显式 `execution_session_id` 与任务专属 `result_channel`，重连/重启可恢复且不做全局广播 | `017-task-session-channel`、`a96653f`、GitHub Actions `35101569455`/`35101569558` |
| [~] | P2-05-R01-C | 补齐会话生命周期、能力/预算持久化和会话级 ACL | `019-execution-sessions`、`ExecutionSessionService` 已实现 ensure/close/list；自动过期回收、能力 ACL 和 Console 页面待补 |
| [ ] | P2-05-R02 | 固化 `isolated`、`shared_serial`、`host` 三种 WorkspacePolicy；默认写任务使用会话 worktree |
| [~] | P2-05-R03 | 实现同一 checkout 写车道串行、不同 worktree 有界并行、只读快照有限并行；不做隐式合并 | 同一 lane 已串行；isolated/shared_serial 策略和只读并发分类待补 |
| [~] | P2-05-R04 | 实现整机/终端 host lane：独立进程组/cwd/env；主机全局写入串行，冲突任务持久化排队 | host lane 已按机器范围互斥；任务进程组已有，持久化 lease/公平队列待补 |
| [~] | P2-05-R05 | 实现任务句柄+主体/会话 ACL 的结果路由；`task_wait/output/cancel` 不接受跨会话猜测查询 | 任务句柄和主体 ACL 已接入；项目共享 ACL/会话能力待补 |
| [ ] | P2-05-R06 | 实现同一桌面会话独占 Desktop lease、浏览器 Context/Profile 隔离和崩溃回收 |
| [ ] | P2-05-R07 | 双用户/多窗口/同项目/同终端故障矩阵：超时、重试、断线、Center/Agent 重启均不重复执行、不串结果 |

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
9. P2-07：完成兼容窗口后退出 Go 回滚路径；P2-02 和完整 SaaS 多租户不进入实施。
10. P2-05-lite：在不改变 `/mcp` URL、Agent 身份和 MCP 工具数量的前提下，实施多主体 Token、ACL、执行车道和会话隔离。
11. `P0-11` → `P1-10`：先完成 Center/Agent 文件数据面，再接入 ChatGPT Web Artifact Viewer；首次变更工具声明时刷新一次连接器，之后以稳定 schema/URI 维持兼容。

未完成 P0 门禁前，不应宣称“完全替换旧版”；未完成 P1 门禁前，不应宣称“完整生产体验”；P2 是规模化路线，不阻塞单 Center 生产运行。
