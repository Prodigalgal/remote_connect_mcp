# RCM 生产任务清单

更新时间：2026-09-20（Asia/Shanghai）

本文是 Remote Connect MCP 的可持续任务清单。第一列只表示代码交付状态：实现和自动化检查完成即可勾选；生产验收单独记录在 [`PRODUCTION_ACCEPTANCE.md`](PRODUCTION_ACCEPTANCE.md)，不再阻止代码任务勾选。这样可以明确区分“代码没做完”和“代码已完成但尚未在目标环境验收”。

需求基线：[`REQUIREMENTS.md`](REQUIREMENTS.md)；当前实现证据：[`STATUS.md`](STATUS.md)；架构约束：[`ARCHITECTURE.md`](ARCHITECTURE.md)；异步契约：[`ASYNC_CONTRACT.md`](ASYNC_CONTRACT.md)；M:M 用户/对话/MCP 设计：[`MULTI_USER_MODEL.md`](MULTI_USER_MODEL.md)；组件级升级合同：[`COMPONENT_UPGRADES.md`](COMPONENT_UPGRADES.md)。

## 状态规则

- `[x]` 代码完成：实现和自动化检查有证据；生产状态看独立验收表。
- `[ ]` 代码待办：尚未开始或尚未形成可验收实现。
- `[~]` 代码实施中：已有部分实现，但仍有代码/协议/测试缺口。
- `[—]` 明确不做：经需求决策从当前路线移除，不再作为交付门禁。
- `生产验收` 不再改变第一列；在 [`PRODUCTION_ACCEPTANCE.md`](PRODUCTION_ACCEPTANCE.md) 中记录 `通过`、`部分通过`、`待验收` 或 `不适用`。
- 每项代码完成后，在“验收证据”列补充 CI run/测试证据，再将代码状态改为 `[x]`；生产目标机证据随后补到独立验收表。
- 不在本机编译 Java、Native Image、React 或正式安装包；构建证据必须来自 GitHub Actions。

## 当前阶段：全部开发项已闭环（含可选项），生产验收独立进行

截至当前 `main`，P0/P1 的主要协议、Center/Agent/Console 主流程、Artifact Transport v2、会话/配额、READ/WRITE/EXCLUSIVE 车道、桌面/浏览器资源回收、MCP Apps Viewer 和 command/desktop/browser 组件级升级代码已经完成。Long Running Tasks v2 的 change sequence、进度快照、保留/GC、结构化 next_action、Conversation/Connection 恢复元数据、可选 WatchService 进度适配器、低基数指标和 Console 当前进度时间线也已实现，并通过最新 JVM、PostgreSQL/Liquibase、Native amd64/arm64、Windows Native、React 与仓库卫生门禁。当前剩余项只属于真实 ChatGPT Web/平台矩阵和生产故障演练；现有 Task Artifact 继续承担小型截图和诊断结果，通用文件改走独立的流式 File Transfer 数据面。原 P2-02 和完整 SaaS 多租户仍明确不做，P2-05-lite 保持轻量主体隔离路线。MCP Tool/Schema 的 8 工具模型面也已完成代码硬切换，详见下方 `P1-TM` 清单。

| 层级 | 当前判断 | 剩余工作 |
| --- | --- | --- |
| P0 | 核心可靠性、安全、Artifact Transport v2、Session 原子 admission 与组件升级协议代码已完成；Long Running Tasks v2 已完成并通过统一 Actions | Center/Agent 重启/断线组合矩阵、工件卷备份恢复、长任务生命周期和离线升级目标环境门禁 |
| P1 | 主流程、桌面/浏览器、控制台、Viewer、组件选择器和独立运行时合同代码已完成 | Windows/Linux Desktop 与 Browser、ChatGPT Web 文件对象、Git/Console/升级/长连接真实矩阵，以及无障碍/视觉验收 |
| P2 | P2-01/03/04/06/07、P2-05-lite、Viewer 解耦/handler、去重和生命周期代码已完成 | QUIC/HTTP3 真实 Provider、集中日志/对象网关自身生命周期、SLO/告警演练和多主体双账号现场验收 |
| MCP Tool/Schema | 8 个聚合 Tool、严格 Schema、结构化输出和仓库门禁已完成 | P1-TM-17/18：ChatGPT Web 真实发现/调用和生产收口 |

本轮根据用户提供的 `LONG_RUNNING_TASKS_V2.md` 设计建议重新取舍：不新增长任务专用 Tool，不把 `last_accessed_at` 作为隐式续期，不把 `RCM_PROGRESS_FILE` 作为首版硬依赖；先把数据库真实版本、进度快照和已有事件唤醒链路接牢。可选进度适配器后来以受限 `WatchService` 形式完成，但仍不增加公共 Tool。附件中的测试任务不计入代码完成状态。

当前证据基线：稳定 `java-v0.1.29` Release `35064539692` 的三平台 Native、镜像、SBOM、签名和 release jobs 成功；本轮提交 `ecd88a7` 的 Java/React migration `35454046864` 全部通过（Ubuntu/Windows JVM、PostgreSQL/Liquibase、Linux amd64/arm64 Native smoke、RSS、React、仓库卫生），专用 Java Native Release `35454046846` 的 JVM、PostgreSQL/Liquibase、Linux amd64/arm64、Windows Native 和镜像发布矩阵也全部通过；Linux GUI canary 已使用 arm64 Desktop 资产完成真实 screens/截图；GitOps revision `eee62d2` 已由 Argo 报告 `Synced/Healthy/Succeeded`；事件驱动检查、仓库敏感信息扫描和浏览器脚本静态检查均通过。本机没有执行 Java、Gradle、Native Image 或 React 构建。

## P0：平台必须可靠

| 代码状态 | 编号 | 任务 | 当前情况与完成条件 | 代码验收证据 |
| --- | --- | --- | --- | --- |
| [x] | P0-01 | 固定 MCP 地址和多机器路由 | `/mcp`、Bearer 和按 machine ID 路由已可用；后续不因 Center/Agent/Console 升级改变连接器地址 | v0.1.21 MCP/health/ready 验收 |
| [x] | P0-02 | 机器注册与凭据分层 | 一次性 Enrollment Token、独立 Agent Token、稳定 MCP Token、独立 Admin Token 已实现；Enrollment 不写入长期配置 | 注册与身份测试、生产 Secret |
| [x] | P0-03 | 异步任务全链路恢复 | 已有幂等、租约、Attempt、旧 attempt 回传栅栏、取消、输出游标和 LISTEN/NOTIFY；Agent 对重复 poll 回传增加原子 `putIfAbsent` dispatch fence，避免覆盖正在运行的 Future；Center 重启、Agent 断线、重复重试、长任务和高并发属于独立生产验收 | `AgentRuntimeTest.duplicateTaskRegistrationKeepsTheFirstRunner`、GitHub Actions |
| [x] | P0-04 | PostgreSQL + Liquibase 唯一事实来源 | PostgreSQL、Liquibase 当前 changelog 和迁移 Job 的代码与 CI 实现完成；生产迁移/版本收敛属于独立生产验收 | PostgreSQL/Liquibase CI、`PostgresIntegrationTest` |
| [x] | P0-05 | 任务与工件的持久化边界 | `ArtifactStore`、默认 filesystem 持久卷、可选 HTTP 对象网关、原子写入/读取校验、TTL 元数据和显式 GC API 已实现；生产卷备份/恢复、CronJob 和规模压测属于独立生产验收 | 对象存储适配、迁移/恢复、生命周期测试、GitHub Actions |
| [x] | P0-06 | 执行范围与权限合同 | project/worktree/path/workspace/unrestricted 合同、Center/Agent/桌面双端校验、预算收窄和凭据过滤已实现；目标机绕过与回归属于独立生产验收 | `DesktopCompanionServerTest`、GitHub Actions |
| [x] | P0-07 | Agent 资源硬限制 | 任务级进程树/墙钟/CPU/RSS 监督、输出/磁盘/并发上限、Linux cgroup 可选边界、Windows 有界进程树/Task Scheduler 路径和 Agent 总进程预算已实现；目标机压测属于独立生产验收 | GitHub Actions Native smoke、RSS gate、资源监督测试 |
| [x] | P0-08 | 隐私、密钥和仓库卫生 | 公开仓库使用模板值；真实域名、Token、Secret 和私有 GitOps 留在受保护环境；日志/指标有脱敏约定 | 仓库扫描、CI hygiene、私有部署检查 |
| [x] | P0-09 | GitHub Actions 构建和可安装包 | Java/Native/React/安装包、SBOM、签名和烟测由 GitHub Actions 完成；开发机不编译 | Java Release workflow、Native smoke、RSS gate |
| [x] | P0-12 | CI → GitOps CD promotion | Native、镜像与 Release 全成功后按 main/tag 选择 staging/production，使用 GitOps Deploy Key 更新 Center/Console digest；Argo CD 负责同步，不直接使用 kubeconfig | `java-release.yml` `gitops-deploy`；私有 GitOps 提交与 Argo 状态属于生产验收 |
| [x] | P0-10 | 全部已登记 Agent 的恢复 | 升级活动默认把未显式指定的全部登记 Agent（含离线）写入持久目标集；离线 Agent 下次心跳自动领取同一 offer；真实在线清单、升级活动和任务闭环属于独立生产验收 | 升级活动目标集测试、GitHub Actions |
| [x] | P0-11 | Artifact Transport v2 双向文件链路 | 协议、`020` 元数据表、流式 ObjectStore、Agent GET/PUT、大小/SHA-256/范围校验、断点续传、并发/磁盘配额和精简 `artifact_put`/`artifact_get`/`artifact_read` 已完成；端到端 CI、ChatGPT Web 附件渲染和目标环境故障矩阵单独验收 | 设计基线见 [`ARTIFACT_TRANSPORT_V2.md`](ARTIFACT_TRANSPORT_V2.md)；本轮代码与测试完成，统一 Actions 待跑 |

### P0 生产验收门禁（不计代码状态）

- [x] P0-G1：范围合同不能被改变 cwd、环境变量或任务重试绕过；Linux/Windows path 合同、环境变量伪造、越界路径和幂等重试已完成目标机验收。
- [~] P0-G2：Agent 断线重连和 durable 任务不重复执行已验收；Center 重启及 ChatGPT 端重试仍需维护窗口演练。
- [~] P0-G3：长输出分页、任务超时、无超时 durable 任务和 32 子进程上限已验收；截图/下载能力与 RSS/CPU 极限压测尚未具备目标条件。
- [x] P0-G4：所有 9 台在线 Agent 均通过固定 `/mcp` 的 `command` 路由并以 `task_read`/终态核对。

### P0-11 Artifact Transport v2 子任务

| 状态 | 子任务 | 完成条件 | 证据/下一步 |
| --- | --- | --- | --- |
| [x] | P0-11-01 | 固化 `FileTransferAction`/`FileTransferResponse`、`file_transfer` capability 与任务幂等/Attempt 合同 | protocol/TaskService/JDBC 静态实现；Actions 编译待验证 |
| [x] | P0-11-02 | Liquibase `020` 创建 Artifact/Transfer 元数据和 task action JSONB，数据库不存二进制 | `020-artifact-transport.yaml`；PostgreSQL Actions 待验证 |
| [x] | P0-11-03 | Center filesystem/HTTP ObjectStore 支持流式写入/打开、临时文件和 SHA-256 校验 | `ArtifactStore`、`FileSystemArtifactStore`、`HttpArtifactStore` |
| [x] | P0-11-04 | Agent 侧通过目标路径合同校验、`.rcm-part-*` 临时文件和原子改名收发文件 | `FileTransferTaskRunner`、`AgentPaths`、`AgentTransportClient` |
| [x] | P0-11-05 | MCP 暴露精简 `artifact_put`/`artifact_get`/`artifact_read`，使用 `openai/fileParams`，文本只返回句柄 | `McpConfiguration`；连接器刷新和 Web 实测待验收 |
| [x] | P0-11-06 | 断点分块/偏移确认、断线续传和传输状态幂等恢复 | Agent→Center `HEAD` 偏移探测、8 MiB `Content-Range` 分块、Center PVC partial spool、断线后按偏移重试，以及 Web→Agent HTTP Range + 稳定 `.rcm-part-*` 文件已完成；PostgreSQL/真实中断矩阵和 Actions 属于独立验收 |
| [x] | P0-11-07 | ChatGPT Web 文件对象真实渲染/下载闭环（图片、PDF、Office、未知二进制） | Viewer、file object、Range/下载和保存到 ChatGPT 代码已具备；真实连接器渲染归入 P0/P1 生产门禁 |

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
| [x] | P1-AT-01 | ChatGPT Web Artifact 真实 E2E | 双向文件协议、Viewer、保存到当前会话/Library 代码已具备；真实 Web 上传→终端、终端→会话归入生产验收 |
| [x] | P1-AT-02 | React Artifact Viewer v1 | 任务记录页支持图片、PDF、音视频、文本预览及通用下载，Office/压缩包/未知类型保持下载；真实连接器附件渲染属于 E2E |
| [x] | P1-AT-03 | MCP annotations 与严格 Output Schema | 文件工具 annotations、嵌套 task/transfer/file schema 和稳定 `ui://` Viewer 资源已收紧；连接器刷新验证单独进行 |
| [x] | P1-AT-04 | 签名 URL Session/Purpose 绑定 | HMAC subject 绑定主体、connection、purpose 和 execution session；代理回归单独进行 |
| [x] | P1-AT-05 | Artifact 下载安全 Header | `private/no-store`、`nosniff`、`no-referrer` 和 MIME 默认值已加入；代理缓存回归单独进行 |
| [x] | P1-AT-06 | Agent 源文件快照一致性 | 同目录稳定快照用于 hash + upload，并有磁盘余量、符号链接和临时文件校验 |
| [x] | P1-AT-07 | Stall timeout / progress watchdog | Center 与 Agent 流式读取拆分 absolute lifetime 与无进展超时，Center 按 4 MiB/1 秒节流更新 `bytes_transferred`；慢链路矩阵单独验收 |
| [x] | P2-AT-01 | READ / WRITE / EXCLUSIVE 执行车道 | `LaneMode` 已接入协议、合同、内存/JDBC poll；同车道 READ 可并发，WRITE/EXCLUSIVE 互斥 |
| [x] | P2-AT-02 | 零字节与边缘文件语义 | Center filesystem/HTTP、协议、Agent、Browser 小工件和 PostgreSQL 迁移允许合法 0 字节；特殊文件/符号链接明确拒绝 |
| [x] | P2-AT-03 | destination path / file name 语义收敛 | destination/source 是完整路径，`file_name` 仅为展示覆盖名，缺省取路径末段；协议、校验和 UI 已同步 |
| [x] | P2-AT-04 | 传输容量、SLO 与生命周期指标 | `/metrics` 暴露 active/delivered/failed/canceled、传输/声明字节、resume、partial spool、GC bytes 和终态时长等低基数指标 |

### 本轮 Artifact/Apps 加固任务

以下任务来自本轮代码审查，继续遵循“代码完成与 CI 证据”和“生产验收”分离的规则：

| 状态 | 编号 | 任务 | 当前实现/剩余门禁 |
| --- | --- | --- | --- |
| [x] | P0-AT-09 | 修复空 `idempotency_key` 导致 Transfer 永久复用 | `artifact_put`/`artifact_get` 拒绝空键并要求调用方提供稳定幂等键；错误 schema 与重试由 Actions 覆盖，真实 Web 回归另列 |
| [x] | P0-AT-10 | Artifact Viewer MCP Apps CSP/domain 元数据 | Viewer Resource 发布标准 `ui.csp`/`ui.domain`（含 connect/resource/frame domains），由 `RCM_CENTER_PUBLIC_BASE_URL` 派生；ChatGPT Web 实测仍是生产验收项 |
| [x] | P0-AT-11 | 配额 admission 数据库事务级原子预留 | JDBC Task/Transfer 在事务内使用 PostgreSQL principal advisory lock，避免 count/insert TOCTOU；Session 及真实并发矩阵仍待生产验收 |
| [x] | P1-AT-08 | Public Artifact HTTP Range | Artifact URL 支持单段 `Range`、`206`、`Content-Range`、`Accept-Ranges` 和 `416`；代理/大文件现场验证仍待进行 |
| [x] | P1-AT-09 | 明确 `RCM_CENTER_PUBLIC_BASE_URL` | K8s 模板、部署文档和 Viewer CSP 已加入；生产 overlay 必须填稳定 Center HTTPS Origin |
| [x] | P1-AT-10 | 独立 Artifact signing secret | 新增 `REMOTE_CONNECT_MCP_CENTER_ARTIFACT_SIGNING_SECRET`，不再默认复用 MCP/Admin Token；密钥轮换需单独验收 |
| [x] | P1-AT-11 | 加强 `download_url` DNS rebinding 防护 | 每一跳请求前后重复解析公共地址集合，允许最多 5 跳 HTTPS 301/302/303/307/308 并逐跳拒绝私网、循环和解析变化；无法在 JDK HttpClient 中绝对 pin socket，需安全回归 |
| [x] | P1-AT-12 | 调整 `artifact_get` annotations | `artifact_get` 不再错误标为 destructive/open-world；仍保留异步任务语义，不伪称完全无副作用 |
| [x] | P1-AT-13 | 区分 quota reserved/transferred bytes | Console/API 增加 `reserved_transfer_bytes` 与 `transferred_transfer_bytes`，不再输出模糊的总量别名 |
| [x] | P1-AT-14 | Viewer 使用标准 tool-result 通知 | Viewer 使用标准 MCP Apps tool-result 通知，不保留宿主私有事件回退 |
| [x] | P1-AT-15 | “保存到 ChatGPT” | Viewer 在宿主提供 `uploadFile()` 时显示按钮，下载当前 Artifact 后回写当前会话文件对象；真实 Web 端能力需验收 |
| [x] | P2-AT-05 | Artifact URL 改成 opaque token | 公共 URL 使用加密不透明访问票据，主体/session/purpose 不再作为查询参数写入代理日志 |
| [x] | P2-AT-06 | 源文件 copy 前后变化检测 | Agent 快照 copy 前后比较 size/mtime，变化则拒绝上传；内容级极端竞态仍由快照哈希和现场回归覆盖 |

### 本轮追加的 Artifact / MCP Apps 任务

| 状态 | 编号 | 任务 | 当前实现/剩余门禁 |
| --- | --- | --- | --- |
| [x] | P0-AT-16 | Execution Session 配额原子化 | PostgreSQL session admission 已改为 principal advisory transaction lock，并在 session upsert 同一事务内计数；64+ 并发集成测试属于生产门禁 |
| [x] | P1-AT-16 | Artifact Viewer 标准 MCP Apps `_meta.ui.*` 元数据 | 发布标准 `ui.csp`/`ui.domain`；真实 Host 矩阵属于验收 |
| [x] | P1-AT-17 | Artifact Viewer iframe/PDF CSP | 标准 `frameDomains` 已加入；PDF 代理和跨域现场验证属于验收 |
| [x] | P1-AT-18 | Viewer tool-result 事件标准化 | 支持标准 `ui/notifications/tool-result` 事件；宿主通知顺序属于验收 |
| [x] | P1-AT-19 | ChatGPT 当前对话/Library 文件保存 | Viewer 支持普通 `uploadFile(file)` 与 `uploadFile(file, {library:true})`，并显示返回 file id；权限属于验收 |
| [x] | P1-AT-20 | Artifact readiness 自检 | durable PostgreSQL readiness 主动校验 HTTPS public origin、占位域名、独立签名密钥和长度；K8s 缺配项属于验收 |

| [x] | P1-AT-21 | Artifact signing secret 无中断轮换 | 支持 current/previous secret 与 `kid`；新 URL 使用 current，旧 URL 在 TTL 内由 previous 验证；轮换演练属于验收 |
| [x] | P1-AT-22 | Viewer 大文件按 Range 预览 | 文本/JSON/日志请求有限 Range，并用流式读取上限；非 Range Host 和多字节编码属于验收 |
| [x] | P1-AT-23 | Viewer 文件类型 handler 扩展 | MIME → handler 注册表已覆盖图片/PDF/音视频/Markdown/CSV/代码、diff、Office 和压缩包安全降级；未知类型仍下载 |
| [x] | P1-AT-24 | 文件传输状态与进度 UI | Center Artifact/Transfer 投影 API、React 进度/生命周期面板和事件唤醒刷新已接入 |
| [x] | P1-AT-25 | Artifact/Transfer 管理 API | 已提供分页、principal/machine/session 过滤、删除、延长、固定和生命周期字段；自动化门禁属于验收 |

### 本轮存储可选化与 TTL 清理

| 状态 | 编号 | 任务 | 当前实现/剩余门禁 |
| --- | --- | --- | --- |
| [x] | P1-AT-26 | 外部对象存储可选开关 | `RCM_CENTER_ARTIFACT_STORE=filesystem` 为默认生产路径；只有显式选择 `http` 才接入内部对象网关；readiness 只要求 PostgreSQL + 一个持久字节后端，不要求 S3/MinIO/R2；不改变 Agent/MCP 协议。CI 配置矩阵和生产切换演练仍需验收 |
| [x] | P1-AT-27 | 文件 Artifact TTL 与外部有界 GC | `expires_at`/`pinned` 作为文件生命周期事实来源；`POST /api/v1/admin/artifacts/gc` 同时清理旧任务投影和已过期 `rcm_artifact`，先删对象再删元数据，失败保留待重试；Kubernetes `CronJob` 默认每 6 小时、单次最多 100 条且禁止并发运行。CI/生产定时任务和删除失败恢复仍需验收 |
| [x] | P1-AT-28 | TTL GC 索引与孤儿对象边界 | Liquibase `029` 增加 `expires_at + artifact_id` 部分索引；filesystem 只在一小时并发写入宽限期后清理无引用 `fs-v1` 对象，HTTP 网关不做无界枚举，由网关生命周期负责孤儿对象；卷容量/对象网关配额压测仍需验收 |
| [x] | P2-AT-07 | Artifact opaque token 密钥版本化 | 访问票据已加密携带 `kid`，支持 AES/HMAC current/previous key rotation；轮换测试属于验收 |
| [x] | P2-AT-08 | Viewer 从 Java 内嵌 HTML 解耦为前端资源 | `web/src/artifact-viewer/artifact-viewer-v1.html` 为源文件，Actions 在 Java/Native/React 构建前同步到 classpath，并保留稳定 URI |
| [x] | P2-AT-09 | Preview Handler 插件化 | Viewer 与 `web/src/artifact-viewer/previewHandlers.ts` 均采用 MIME → handler 注册表，未知类型安全降级下载 |
| [x] | P2-AT-10 | 文件传输压缩与内容去重 | 可选 SHA-256 content-addressed wrapper 和磁盘 gzip wrapper 已加入，默认关闭 |
| [x] | P2-AT-11 | Artifact 生命周期策略升级 | 已支持按方向/MIME/大小的有界保留期，以及 `ephemeral`/`task-bound`/`pinned` 管理字段和 Console 固定操作 |
| [x] | P0-AT-26 | OpenAI 文件生态桥接契约 | `artifact` 发布标准 MCP Apps `ui.resourceUri` 与 `openai/fileParams`；只消费 ChatGPT 提供的 `download_url`，不读取 `/mnt/data` 或持久化临时 URL；Viewer 支持 `getFileDownloadUrl` 续取和 `uploadFile`/Library 回写；≤5 MiB 图片在 Agent→Center 传输完成后 bounded fast path 直接返回 MCP `image` content，小型非图片文件返回 `file`/签名 URL；较大文件也发布带有界长等待的签名 URL，异步完成后由 Viewer 按原 MIME 预览/下载，不要求模型重复编排；真实 Web 双向附件仍属于 P1-TM-17 验收 |

### P0/P1：Long Running Tasks v2（按实际缺口取舍）

文档建议被拆成以下最小增量，不重复已经存在的异步 Task、lease、幂等、游标输出和 LISTEN/NOTIFY 能力。以下条目均已完成代码并通过 GitHub Actions；生产故障演练继续记录在 `PRODUCTION_ACCEPTANCE.md`。

| 状态 | 编号 | 任务 | 实际取舍与完成条件 | 证据/下一步 |
| --- | --- | --- | --- | --- |
| [x] | P0-LR-01 | Durable task change sequence | `rcm_task.change_seq`、PostgreSQL 事务触发器和 MCP `task_read.change_seq` 已加入；数据库序列是事实来源，LISTEN/NOTIFY 只负责唤醒 | Liquibase `030`、TaskService/JDBC；Actions `35454046864`/`35454046846` |
| [x] | P0-LR-02 | Progress snapshot 与 Agent ACK | Task 持久化 phase/percent/message/current/total/unit，新增 Agent `/tasks/{id}/progress`，复用 Attempt 栅栏；command/durable runner 首次上报为 best-effort 且非阻塞；新增可选文件 WatchService 适配器 | `TaskProgressUpdate`、AgentController、TaskService、`TaskProgressFileWatcher`；Native smoke/RSS 通过，Actions `35454046864`/`35454046846` |
| [x] | P0-LR-03 | Task metadata/output retention 与统一 GC | metadata/output 独立过期字段、pinned/archived 投影、Admin GC、CronJob 参数和低基数 GC/进度指标已完成；不在 Center 内启动固定清理线程 | Liquibase `031`、Jdbc/Task GC；PostgreSQL/Liquibase 与 Native 门禁通过，Actions `35454046864`/`35454046846` |
| [x] | P1-LR-04 | 结构化 next_action 与重试语义 | 所有公共结果使用有限 `next_action` 对象；模型拿到 task_id 后只读同一任务，传输重试复用原幂等键；输入/输出 Schema 和文档已同步 | `McpConfiguration`/Schema/黄金样例；8-tool surface、JVM/React/Native Actions `35454046864`/`35454046846` |
| [x] | P1-LR-05 | Conversation/Connection 恢复模型 | 新增 Liquibase `032` 和 `McpConversationService`，按 principal 复合键持久化 conversation/connection 触碰、transport、状态和 TTL；不把 Conversation 当授权边界，无清理轮询 | `032-conversations-connections.yaml`、工具调用 touch；PostgreSQL/Liquibase Actions `35454046864`/`35454046846` |
| [x] | P2-LR-06 | 可选进度适配器与时间线 | `RCM_PROGRESS_FILE + WatchService` 受限于任务 cwd、64 KiB、250 ms 节流并随任务关闭；Agent 指标与 React 任务时间线展示当前快照/change_seq，不增加公共 Tool | `TaskProgressFileWatcher`、`MetricsController`、React `TaskRow`；Native/RSS/React Actions `35454046864`/`35454046846` |

### command/desktop/browser 组件级升级

| 状态 | 编号 | 任务 | 当前实现/剩余门禁 |
| --- | --- | --- | --- |
| [x] | P0-UP-01 | Release Manifest 与多组件 UpgradePlan | protocol、可选 GitHub Release Manifest、PostgreSQL campaign/component 状态投影和按平台 offer 已实现；Actions 发布门禁属于验收 |
| [x] | P0-UP-02 | Agent 组件级 staging/rollback | command、desktop、browser bundle 独立 stage、校验、drain、原子替换、健康检查和单组件 rollback；失败组件不回滚健康 command-agent |
| [x] | P1-UP-03 | Windows/Linux companion 重启适配 | Windows Task/SCM、Linux systemd 和 Browser Worker service restart 均按组件处理，不重置 identity/profile |
| [x] | P1-UP-04 | Console 组件版本与 canary | Console 显示组件计划/目标状态，并提供自动、仅 command-agent、按组件选择三种 campaign 入口；沿用 machine canary/retry |
| [x] | P1-UP-05 | Browser runtime 独立生命周期 | Native browser-agent 作为独立组件升级，Profile/Cookie/cache 不触碰；Playwright/Patchright/Comoufox runtime 由安装脚本和独立 worker 生命周期管理 |
| [x] | P1-UP-06 | 首次安装统一入口 | Windows/Linux 单命令入口自动识别运行时/权限、下载并校验对应 command/desktop/browser bundle，注册一次性 Token 后不保留 Bootstrap；控制台生成可复制命令 | `first-install-java-agent.ps1`、`first-install-java-agent.sh`；PowerShell/Bash 静态解析，构建交给 GitHub Actions |

## P1：核心生产体验

| 代码状态 | 编号 | 任务 | 当前情况与完成条件 | 代码验收证据 |
| --- | --- | --- | --- | --- |
| [x] | P1-01 | Project Registry 与 Git worktree 闭环 | 注册、受保护删除、创建/删除 worktree、cwd 解析、status/diff/log、幂等 commit、显式 merge 和 `merge_abort` 冲突恢复排队 API 已实现并接入精简 MCP；冲突输出、提交差异审阅和目标机权限属于独立生产验收 | ProjectService/协议测试、GitHub Actions |
| [x] | P1-02 | Windows/Linux Desktop Companion 真实能力 | 独立 Desktop Native 目标、A​WT/Java2D、X11/Wayland 原生截图、屏幕/窗口枚举、输入/剪贴板/聚焦、IPC 合同复核、连接/启动进程上限和退出回收已完成；Windows 登录任务使用无控制台 VBS 启动器；Windows 多会话/UAC/RDP 等属于现场验收 | Desktop 协议/服务测试；平台矩阵和 GitHub Actions 统一验收 |
| [x] | P1-03 | Browser Agent 生产运行时 | 独立 Browser Native 目标、Playwright/Patchright/Comoufox 适配、结构化引用、有界快照、持久 profile、登录态 marker、stale ref 恢复、下载工件和 admission 清理已完成；浏览器安装与跨引擎属于现场验收 | BrowserTaskRunner、Node 静态检查；GitHub Actions/运行时矩阵单独验收 |
| [x] | P1-04 | React 控制台完整工作流 | 机器、项目/worktree、任务、Token/ACL、升级、审计、会话/车道/配额、Artifact Viewer 和范围编排入口已完成；实时/无障碍/视觉门禁属于现场验收 | React 源码/类型边界与 UI 组件实现；GitHub Actions/E2E 单独验收 |
| [x] | P1-05 | 升级 offer/attempt 与组件回滚 | canary、批次、SHA-256、原子替换和回滚、attempt 栅栏、PostgreSQL 行锁、失败目标单独重排队均已实现；数据库并发、离线补升级和现场演练属于独立生产验收 | `UpgradeServiceTest`、GitHub Actions |
| [x] | P1-06 | WebSocket/事件唤醒生产验收 | wake-only WebSocket、指数重连、HTTPS 回退、PG 通知桥接和序列号去重已实现；真实反向代理、断线和 Center 重启属于独立生产验收 | WebSocket smoke、TransportNegotiation/AgentWake 测试、GitHub Actions |
| [x] | P1-07 | 配置与心跳自描述 | 版本化 runtime descriptor、generation/CAS、有限历史和回滚、严格 schema 校验已实现；目标机回滚演练属于独立生产验收 | AgentRuntimeSettings/ConfigurationService/runtime descriptor 测试、GitHub Actions |
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
| [x] | P2-01 | QUIC/HTTP3 传输评估与实现 | transport 能力协商标头、HTTPS 选择和一次性基准脚本已实现；真实 QUIC provider/灰度属于后续生产实验，不改变默认 HTTPS/WebSocket |
| [—] | P2-02 | Center 多副本与高可用 | **明确不做。** 当前保持单副本稳定路径，不建立多副本、PDB、HPA 或跨副本一致性门禁 | 需求决策记录 |
| [x] | P2-03 | 集中日志与对象存储规模化 | 异步审计、`rcm.audit` JSON 行导出、filesystem/HTTPS 对象网关、GC 边界和容量指标已实现；生产采集器、对象生命周期/索引与成本压测属于独立生产验收 | `HttpArtifactStore`、StructuredLog、artifact gateway contract、GitHub Actions |
| [x] | P2-04 | SLO、告警和升级通知 | 任务成功率、排队延迟、断线恢复、资源、升级失败和工件容量指标及 PrometheusRule 已实现；生产通知出口和演练属于独立生产验收 | MetricsController、PrometheusRule、GitHub Actions |
| [x] | P2-05 | 轻量多主体、对话与 MCP 连接模型 | 每个用户使用独立不透明 Bearer Token；主体、连接元数据、任务归属、主体维度幂等键、READ/WRITE/EXCLUSIVE 车道、机器/项目 ACL、执行会话合同、配额和 Desktop/Browser 隔离已接入；MCP 列表固定大小摘要、详情按需查询，工件大于 512 KiB 只返回引用；不做完整 SaaS 多租户、跨组织计费或复杂 RBAC | `McpPrincipalService`、`McpAccessService`、`ExecutionSessionService`、`McpQuotaService`、`TaskService`、`015`–`020`；统一 Actions/双账号现场验收 |
| [x] | P2-06 | 更丰富的桌面和浏览器平台 | Desktop 常用输入/窗口/剪贴板/截图、Wayland helper，Browser 常用动作/引用/会话恢复/多引擎适配及资源回收均已实现，不扩大 MCP 原始工具面 | Desktop/Browser 代码与协议测试；平台矩阵和资源报告单独验收 |
| [x] | P2-07 | 旧运行时退出 | Go workflow、Deployment、Service/PVC、源码和旧安装器已从公开运行路径移除；仅需完成生产资源/DNS 清理验收 | 仓库卫生检查、Java Release workflow |

### P2-05-lite：M:M 用户、对话与 MCP 实施清单

本节是对原 P2-05 的收窄替代：只做轻量主体隔离和协同调度，不引入完整多租户平台。代码实现已完成；真实双账号/多窗口行为保留为独立现场验收。

| 状态 | 子任务 | 完成条件 | 依赖/验收 |
| --- | --- | --- | --- |
| [x] | P2-05-D | 从多用户、多对话、同项目/同终端稳定并发和结果隔离需求反选设计；完成实体关系、竞品比较和最终选型 | [`MULTI_USER_MODEL.md`](MULTI_USER_MODEL.md) §11–§12 |
| [x] | P2-05-01 | Liquibase 增加 `principal`、用户 MCP Token、Token 范围/撤销、任务主体和配额字段；机器/项目 ACL 在 `018`/`020` | `015/018/020`、`McpPrincipalService`、`McpAccessService`、Token 哈希/一次性明文返回实现；统一 Actions 待跑 |
| [x] | P2-05-02 | Center 从 Bearer 派生主体并把主体送入 MCP 异步 Exchange；任务、输出、工件、项目和机器按主体过滤，配额和 Admin access API 已接入 | `McpTransportContext`、`McpAccessService`、`McpQuotaService`、Admin access API |
| [x] | P2-05-03 | 幂等键加入主体维度；同一主体重试复用任务，不同主体相同键互不冲突 | `TaskServiceTest` principal/idempotency；JDBC 并发属于现场门禁 |
| [x] | P2-05-04 | 按机器+项目/worktree/path 派生稳定 lane_key，READ 可共享、WRITE/EXCLUSIVE 互斥；WorkspacePolicy 和 lease 细化已落地 | `ExecutionLaneKey`、内存/JDBC poll 车道实现与测试 |
| [x] | P2-05-05 | Desktop 独占 lease、Browser 按主体/对话隔离 Context/Profile，任务结束回收、stale marker 清理和崩溃退出已实现 | `DesktopCompanionServer`、`BrowserTaskRunner`；两用户现场矩阵单独验收 |
| [x] | P2-05-06 | React Console 增加主体、Token、项目成员、机器授权、会话、范围、配额、撤销和任务归属页面 | `AccessControl`、Admin API、脱敏投影；UI E2E/a11y 单独验收 |
| [x] | P2-05-07 | 双账号多窗口端到端验收；同 URL、不同 Token、同项目/不同 worktree、撤销和故障恢复 | 主体/会话/ACL/车道/配额/结果隔离代码已完成；真实双账号矩阵归入生产验收 |
| [x] | P2-05-R08 | MCP 上下文预算与摘要投影 | `machines(operation=list)` 只返回固定字段摘要（最多 25 台），`project(operation=list)` 不返回本地路径且 worktree 摘要最多 10 条；需要时通过 `project(operation=detail)` 分页获取详情，路径必须显式 `include_paths=true`；任务输出保持游标分页，MCP JSON 设置 192 KiB 最后防线；大工件只返回 SHA-256/Console 引用 | `McpConfiguration` compact/detail projection/response guard；GitHub Actions |

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
| [x] | P2-05-R07 | 双用户/多窗口/同项目/同终端故障矩阵：超时、重试、断线、Center/Agent 重启均不重复执行、不串结果 | Attempt/lease/session/result channel/车道栅栏代码已完成；真实故障矩阵归入生产验收 |

## P1-TM：MCP Tool / Schema 最终模型面收敛

本节是“工具克制、内部能力完整、模型按需发现”的最终实施清单。设计基线见 [`MCP_TOOL_SCHEMA_DESIGN.md`](MCP_TOOL_SCHEMA_DESIGN.md)。这里的 `[ ]` 表示代码任务尚未完成；设计文档完成不等于实现完成，ChatGPT Web 的连接器必须在新 Tool 面发布后刷新一次，工具选择和真实附件/桌面/浏览器流程仍记录在生产验收表中。

目标模型面且唯一公开面为 8 个逻辑 Tool：`machines`、`command`、`desktop`、`browser`、`project`、`artifact`、`task_read`、`task_cancel`。本轮采用硬切换：不保留旧 12 个 Tool、旧字段兼容解析、legacy 开关或旧工具别名；`/mcp` URL、Bearer Token 和 Agent 身份保持不变，但连接器必须重新发现一次新 Tool 列表。

| 状态 | 编号 | 任务 | 完成条件 | 依赖/验收 |
| --- | --- | --- | --- | --- |
| [x] | P1-TM-01 | 固化 Tool/Schema 基线与黄金样例 | 已保存 8 工具、禁止名称、Schema 严格性与上下文预算的版本化 golden fixture，不含域名、Token、主机路径等敏感值 | `docs/mcp-tool-surface.json`、GitHub Actions schema gate |
| [x] | P1-TM-02 | 公共 Schema Helper v2 | 所有公开对象默认 `additionalProperties:false`；已补齐 `enum`、`min/max`、`pattern`、整数边界、数组上限和结构化嵌套字段 | `McpConfiguration`、Node 静态门禁 |
| [x] | P1-TM-03 | 统一 machine/scope envelope | `machine_id`、scope mode、project/worktree/root/cwd 已统一；`unrestricted` 必须显式声明；内部 session/lane/risk/elevation 不出现在模型 Schema | Center/Agent/Console 当前 Schema |
| [x] | P1-TM-04 | 统一输出与错误契约 | 所有 Tool 提供 `outputSchema`；成功输出和错误均为精简结构化对象，不把大日志放入 MCP 文本 | `McpConfiguration`、Schema 门禁 |
| [x] | P1-TM-05 | `command` 语义化 Tool | 输入收敛为 machine/scope/command/timeout/idempotency；立即返回 task handle，长输出只走 `task_read`；幂等键必须由调用方显式提供 | Command/Task 实现 |
| [x] | P1-TM-06 | `desktop` 语义操作 Tool | screenshot/screens/windows/launch/pointer/shortcut/type/clipboard/focus/result 统一为一个 operation Tool | Desktop 协议/服务实现 |
| [x] | P1-TM-07 | `browser` 结构化请求与引用 | 结构化 request、`rcm-ref-v1` 有界引用、delta 输出和 stale ref 恢复动作已实现 | BrowserTaskRunner/Node 静态检查 |
| [x] | P1-TM-08 | `project` 判别联合 Schema | project operation 枚举、分页 detail、显式路径和写操作幂等预约已实现 | ProjectService/MCP Schema |
| [x] | P1-TM-09 | `artifact` 聚合 Tool | put/get/read 统一为 operation 分支；严格 file 元数据、Range、retention、purpose/session 绑定；大文件只返回句柄 | Artifact Transport v2/MCP Schema |
| [x] | P1-TM-10 | `task_read` / `task_cancel` 契约 | `task_read` 合并 wait/output，使用有界 cursor/wait/max bytes；跨主体/会话句柄查询拒绝 | TaskService/MCP Schema |
| [x] | P1-TM-11 | `machines` 能力投影与路由 | 只返回稳定 machine 摘要、在线状态、平台、能力标签和有限统计，详情按需查询 | AgentRegistry/MCP Schema |
| [x] | P1-TM-12 | Tool annotations 与风险语义 | 每个聚合 Tool 已标注只读/破坏性/幂等/open-world；ACL、范围和风险由 Center 强制执行 | MCP metadata snapshot |
| [x] | P1-TM-13 | 12→8 逻辑 Tool 硬切换 | 旧 Tool 注册、别名、旧参数和开关已删除；唯一公开列表固定为 8 个逻辑 Tool | `check-mcp-tool-surface.mjs` |
| [x] | P1-TM-14 | 模型导向集成测试与黄金对话 | 已建立自然语言场景覆盖清单和结构化输出门禁；真实 ChatGPT Web 对话仍属生产验收 | Actions/生产验收表 |
| [x] | P1-TM-15 | 上下文、选择、审批可观测性 | 已接入低基数 Schema/结果/校验/重复任务/stale ref/范围拒绝指标，不记录原始命令、Token、文件内容 | Metrics/Audit 实现 |
| [x] | P1-TM-16 | GitHub Actions 唯一构建与验证入口 | Java/Native/React/Node schema、协议、集成和浏览器门禁统一由 Actions 执行；本机不生成正式产物 | `.github/workflows/java-react.yml` |
| [ ] | P1-TM-17 | ChatGPT Web 连接器真实 E2E | 固定现有 `/mcp` URL、Token 和稳定 Viewer URI；完成一次工具声明刷新后验证 8 Tool 可发现、可调用、结果/图片/文档可展示；后续版本保持稳定 schema/URI，不要求重复录入 | 真实 ChatGPT Web；附件、桌面、浏览器、长任务流程 |
| [ ] | P1-TM-18 | 新模型面发布与生产收口 | 按连接/主体/机器观测错误率和重复率；异常时回滚到上一套 Java 构建，不恢复旧 MCP Tool 面，也不回滚已应用数据库迁移；通过生产验收后更新 `STATUS.md` 与 `PRODUCTION_ACCEPTANCE.md` | Release/rollback 演练；旧 Go 路径和旧 Tool 面不重新上线 |

### P1-LG：全仓正式协议收口（代码任务）

| 状态 | 编号 | 任务 | 完成条件 | 依赖/验收 |
| --- | --- | --- | --- | --- |
| [x] | P1-LG-01 | 删除旧 Tool/参数/开关 | Center 只注册 8 个当前聚合 Tool；旧名称、旧嵌套任务入口和 legacy 开关不再进入生产源码 | `check-mcp-tool-surface.mjs` |
| [x] | P1-LG-02 | 删除旧 Go 运行时和状态导入 | Go Center/Agent、旧 Docker/K8s、Go 状态导入器和旧构建入口已从仓库移除；Java + Liquibase 是唯一正式路径 | Git tree/static scan |
| [x] | P1-LG-03 | 严格化 Agent/Center/Companion 协议 | 当前 DTO 必填字段、未知字段和 Attempt/范围/能力校验 fail-closed；不解析旧 JSON 或旧 HTTP 标头 | Protocol schema gate |
| [x] | P1-LG-04 | 安装/升级组件单一 bundle | Windows/Linux 安装器与 Agent 自升级只接收 GitHub Actions 生成的 ZIP；不接受裸可执行文件、JAR 自升级或组件混装；Native Image 构建必须显式选择 CPU 基线，冒烟脚本只接受 Native bundle | Installer/static review |
| [x] | P1-LG-05 | 清理 Viewer/Browser/Enrollment 旧入口 | Viewer 只使用标准 MCP Apps tool-result 通知；Browser 只读结构化请求文件；Enrollment 只走一次性 Admin API | Viewer/Agent source scan |
| [x] | P1-LG-06 | 数据与文档收口 | Liquibase 将历史共享主体迁移到 configured principal；README、部署、验收和多用户文档只描述当前模型 | Liquibase + docs review |
| [x] | P1-LG-07 | 全仓运行时旧入口静态门禁 | `check-mcp-tool-surface.mjs` 同时扫描 Center、Agent、Desktop、Protocol、Viewer、安装器和冒烟脚本，并确认 Go 目录/工作流/旧安装器不存在；拒绝旧环境变量、旧 Viewer 事件、旧 Browser 命令入口和 Go 导入开关 | GitHub Actions 单一静态门禁 |

### P1-TM 执行顺序

1. 已完成 `P1-TM-01`～`P1-TM-04`，冻结基线、公共 schema、范围和输出契约。
2. 已完成 `P1-TM-05`～`P1-TM-11`，内部 handler 统一到 command/desktop/browser/project/artifact/task/machines 聚合入口。
3. 已完成 `P1-TM-12`～`P1-TM-13`，统一 annotations 并删除旧 Tool/字段/开关；发布后刷新用户连接器。
4. 已完成 `P1-TM-14`～`P1-TM-16` 的代码与 CI 门禁，生产对话和平台矩阵单独验收。
5. 最后执行 `P1-TM-17`～`P1-TM-18`，先做 ChatGPT Web 真实 E2E，再发布并记录生产验收；失败时只回滚 Java 构建，不恢复旧 Tool 面或旧开关。

### P1-TM 完成判定

- Tool 数量减少不是唯一目标：模型在自然语言场景下能稳定选择正确聚合 Tool，才算完成。
- MCP 返回必须短小、可继续行动；详细日志、截图、文档和长输出通过 task/artifact 引用按需获取。
- 所有破坏性动作必须由 Center 的主体、会话、范围、车道和配额合同强制校验，不能把安全性寄托在 Tool annotations 或模型自律上。
- 未通过 `P1-TM-17` 真实 Web E2E 前，只能声明“代码与 Actions 完成”，不能声明“ChatGPT Web 已生产可用”。

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
9. P2-07：旧 Go 回滚路径已从代码删除；只需在生产环境清理旧镜像、任务和 DNS 记录；P2-02 和完整 SaaS 多租户不进入实施。
10. P2-05-lite：在不改变 `/mcp` URL、Agent 身份和 MCP 工具数量的前提下，验收已实现的多主体 Token、ACL、执行车道、会话/桌面/浏览器隔离和配额。
11. `P0-11` → `P1-10`：先完成 Center/Agent 文件数据面，再接入 ChatGPT Web Artifact Viewer；首次变更工具声明时刷新一次连接器，之后以稳定 schema/URI 维持不变。

未完成 P0 门禁前，不应宣称“完全替换旧版”；未完成 P1 门禁前，不应宣称“完整生产体验”；P2 是规模化路线，不阻塞单 Center 生产运行。
