# RCM 当前状态

更新时间：2026-09-18（Asia/Shanghai）

本文记录仓库代码与当前集群只读探针能够证明的状态。产品需求基线见 [`docs/REQUIREMENTS.md`](REQUIREMENTS.md)，代码/生产分离的任务清单见 [`docs/TASKS.md`](TASKS.md)，M:M 用户/对话/MCP 设计见 [`docs/MULTI_USER_MODEL.md`](MULTI_USER_MODEL.md)，逐项生产证据见 [`docs/PRODUCTION_ACCEPTANCE.md`](PRODUCTION_ACCEPTANCE.md)；未通过生产门禁的内容不会标记为“已上线”。

## 结论

Java 25 Center/Agent 与 React 控制台已经完成既有 v0.1.28 生产发布；当前开发批次已补齐 Artifact Transport v2：`artifact_put`/`artifact_get`、流式对象存储、Agent 原子收发、短期签名文件对象 URL、Content-Range/HTTP Range 断线续传、状态机/ACK、幂等预约、资源配额和安全边界，并完成会话/车道/桌面/浏览器隔离代码。本轮又补齐了 Session 配额事务锁、MCP Apps 标准 `ui.*` 元数据与标准 tool-result 事件、Viewer Range/Office/archive/diff handler、Artifact 管理 API、签名密钥 current/previous + kid 轮换、独立 Viewer 前端源和 Release Manifest 驱动的 command/desktop/browser 组件升级；全仓旧运行时/旧入口硬切换也已完成，新增代码仍需 GitHub Actions 与目标环境验收。

## 已完成实现与历史证据

| 领域 | 当前实现 | 证据 |
| --- | --- | --- |
| Java 工程 | `protocol`、`center`、`agent` Gradle 多模块，Java 25 toolchain | GitHub Actions `34745635544`：JVM、Liquibase、备份恢复、React、Linux amd64/arm64、Windows amd64 全部成功 |
| MCP | Streamable HTTP `/mcp`、Bearer 校验、8 个聚合工具、分页结果 | 当前 Java Center `/mcp` 契约；连接器刷新后只发现新工具面 |
| 任务可靠性 | 异步入队、幂等键、租约、取消、输出游标、断线重连、有界 spool、无超时任务恢复；过期租约区分可恢复持久任务与不可安全重放的定时任务，任务 attempt 栅栏阻断断线后的旧进程重投；PostgreSQL 按任务摘要路由 `LISTEN/NOTIFY` 事件唤醒，高频输出只唤醒等待同一任务的请求，截止时间返回快照，不运行固定行读取循环 | Java Agent/Center 单元测试与 GitHub Actions 门禁 |
| 注册与身份 | 一次性 Enrollment Token，注册后换取每 Agent 日常 Token；身份文件原子写入 | Agent/Center 测试通过 |
| 配置热更新 | Center 下发 generation、长轮询等待时间和并发槽位；Agent 原子落盘并只接受更新代次；每次心跳携带版本化 runtime descriptor（单任务及 Agent 总进程预算、资源能力、scope_mode 与桌面/浏览器配置及会话状态），Center 以固定大小 JSONB 投影保存；管理员可将配置历史作为新 generation 回滚 | `AgentRuntimeSettingsTest`、`AgentConfigurationServiceTest`、runtime descriptor protocol/registry tests |
 | Desktop | 独立 `desktop` Native 目标的用户会话 companion；截图/区域截图、屏幕枚举/窗口枚举、启动、单击/双击/右击、移动指针、拖拽、组合按键、剪贴板、窗口聚焦和文本输入通过受保护 loopback IPC；伴侣退出时终止其登记的 GUI 子进程；Linux AWT/Java2D/X11 与 Wayland helper 只进入 Desktop Native 包；Windows 登录任务使用 `wscript.exe //B //NoLogo` 直接启动 VBS，避免 PowerShell 黑框；连接、会话和启动进程均有界 | Desktop 协议/服务测试；Windows 多会话/UAC、Wayland/多显示器现场矩阵待统一 Actions/生产验收 |
| Browser | 独立 `browser` Native 目标的单任务 supervisor；command-agent 负责 Center 生命周期、超时、日志和工件，browser-agent 启动本机 Playwright/Patchright/Comoufox Worker；支持 hover/check/select/wait-for-selector/历史导航/文本读取、结构化引用、有界快照、截图/下载、持久会话 marker、stale ref 恢复和 profile/进程回收 | `BrowserTaskRunner`、Node 静态检查和资源边界；目标浏览器安装、跨引擎/跨平台和真实 Web E2E 待统一验收 |
| 升级 | Center canary/批次状态机，HTTPS + SHA-256，Agent Helper 原子替换和回滚；offer/status 与 detached helper result 支持可选升级 attempt，迟到报告不会覆盖更新尝试；PostgreSQL offer/control/status 路径现在按 campaign/target 行锁串行化，避免并发 Agent 重复领取同一尝试；升级编排使用有界分段机器快照（最多 10000 台），不会因 Admin 单页 200 台而漏掉离线目标；Release Manifest、独立 component staging/drain/rollback、Browser runtime 独立 profile/cache 和 Console 组件选择器已接入 | `UpgradeServiceTest`；`.github/workflows/java-release.yml` 静态校验 |
| 控制台 | React/Vite 经典后台布局，机器、项目/worktree、任务、令牌、升级、审计和设置页面；任务编排支持 command/desktop/browser、项目/worktree/path/unrestricted 显式范围、风险/提权/会话与幂等键；项目卡片支持有确认的 status/diff/log/commit/merge/merge-abort；全局搜索、机器在线筛选、任务状态筛选和任务输出 16 KiB 游标分页查看；机器、项目、任务、升级、审计列表按 `has_more` 增量加载；实时刷新与“下一页”并发时使用请求代次栅栏，升级页支持只重排队单个失败目标；Admin Token 只在当前标签页内存 | v0.1.28 GitHub Actions React 构建与生产 Console 路由验收通过；稳定 Release `java-v0.1.28` 与生产 digest 已收敛 |
| 数据库 | PostgreSQL 适配器与 Liquibase `001`–`029` changelog；内存模式仍用于协议回归；发布工作流带 PostgreSQL 16 服务容器集成、备份和恢复门禁；`012`–`025` 覆盖审计、运行时自描述、配置历史、主体/Token、执行车道、会话/通道、ACL、Artifact Transport v2，`026` 增加组件升级计划/状态，`027` 增加 Artifact lifecycle policy/pinned，`029` 增加 TTL GC 索引；文件保留由外部 CronJob 触发有界 GC，不运行 Center 定时清理线程 | Liquibase 资源/迁移单元测试通过；CI `PostgresIntegrationTest` 会覆盖注册、心跳自描述、项目/worktree、主体幂等任务、车道租约、会话、输出续传和工件往返，随后执行 custom-format dump/restore |
| Artifact Transport v2 | `file_transfer` 协议、`020` Liquibase 元数据表、filesystem/HTTP 流式字节后端、Agent GET/PUT、真实路径/哈希校验、8 MiB Content-Range/HTTP Range 续传、partial spool 持久 reservation、状态机/ACK、幂等预约、MCP `artifact_put`/`artifact_get`/`artifact_read`、稳定 Viewer 资源和 `expires_at` 生命周期已完成；文件内容不进入任务 JSON 或 MCP 文本；外部对象存储不是硬性依赖 | 本轮代码/测试完成；统一 GitHub Actions、跨重启故障矩阵、CronJob 实例和 ChatGPT Web 附件渲染属于后续门禁 |
| 发布脚本 | Java JVM 构建、Native Image 门禁脚本、Windows/Linux Agent 安装器、Java Center/Agent JVM/Native 烟测脚本，以及 Windows/Linux WebSocket wake 烟测 | 当前只做静态校验；Native Image、完整原生烟测、Agent RSS 资源报告和仓库卫生扫描交给 GitHub Actions，Windows/Linux 安装器均支持 CI 平铺 ZIP + 旁路库 |

## 已实现代码与待验收边界

1. Native Image：v0.1.28 正式 tag Release 已完成；Linux amd64/arm64、Windows amd64 原生构建、原生烟测、SPDX SBOM、Sigstore keyless 签名、OIDC Artifact Attestation 和 Agent 资源门禁均由 GitHub Actions 完成。Windows/Linux 发布物为包含旁路运行库的平铺 ZIP；Windows ARM64 不在发布矩阵。开发机不执行编译。
2. PostgreSQL：生产库使用独立 `remote_connect_mcp_prod` schema/database，并由 Liquibase 管理当前数据模型；工件抽象为 `ArtifactStore`，新写入走独立持久卷文件对象或有界 HTTPS 对象网关，数据库只保存 key、大小、MIME 和 SHA-256。Artifact Transport v2 的状态机、分块偏移、配额和 GC 字节指标代码已完成；卷备份恢复、高并发容量、Center 重启和正式恢复演练属于生产门禁。
3. Browser Agent：Java Agent 已提供独立 Browser Native 目标和参考 `scripts/browser-worker.mjs`，可按环境加载 Playwright/Patchright/Comoufox，并支持 CSS/`rcm-ref-v1`/role/label/placeholder/text/test-id 结构化定位；快照最多返回 64 个有界引用，独立 profile 保存脱敏 origin/path，引用失效返回一次有界 snapshot 建议；结果包含有界动作摘要、截图/下载工件以及脱敏网络/控制台/页面错误，并有进程/profile 回收。目标平台浏览器安装和跨浏览器回归属于统一验收。
4. Desktop Agent：截图/区域截图、屏幕/窗口枚举、输入、剪贴板和 Windows 窗口聚焦已具备；command-agent 原子发布伴侣 scope 策略，伴侣在 IPC 前复核真实路径和合同过期时间，并在重启时回收 GUI 子进程；Linux AWT/Java2D/X11 与 Wayland native helper 均有明确代码路径。Windows 多会话/UAC、Wayland、多显示器输入和跨桌面回归属于统一验收。
5. 构建拆分：`command-agent`、`desktop-companion`、`browser-agent` 已分别建立 Gradle Native 目标、Docker artifact 目标和 Release ZIP/校验流程；GitHub Actions 已通过 Linux amd64/arm64、Windows amd64 的四目标编译与烟测，9 台登记 Agent 的 v0.1.28 安装/升级回归已完成。
6. 长连接：已实现可选 WebSocket wake-only 通道、带单调序列号的重复/乱序提示去重、客户端指数重连和 HTTPS 长轮询；PostgreSQL 模式新增跨 Center 副本的 Agent 唤醒与任务等待 `LISTEN/NOTIFY` 桥接（best-effort，丢失时由请求截止时间和下一次显式读取补偿）；Agent/Center 已加入有界传输能力协商和明确 HTTPS 选择标头，并提供一次性 HTTP/1.1/HTTP/2/可选 HTTP/3 探针和回退说明，真实 QUIC provider 仍未启用。
7. Project Registry/Git worktree：已实现按 Agent 归属的项目注册、受保护项目删除、项目根/仓库路径边界、异步 `git worktree add/remove`、幂等键和项目/worktree 任务 cwd 解析；精简 MCP `project` 工具已接入摘要列表、分页 `detail`、status/diff/log/幂等 commit/显式 merge/`merge_abort` 冲突恢复排队；Center 不读取仓库内容，Agent 仍执行最终真实路径与权限校验。提交/差异审阅、冲突输出和实际目标机 Git/权限回归仍待补齐。
8. 控制台：机器、项目/worktree、任务、Token/ACL、会话/车道/配额、升级、审计和设置页面均已接入；任务编排覆盖 command/desktop/browser、项目/worktree/path/unrestricted、工作区策略和车道模式；Artifact Viewer 支持图片/PDF/媒体/文本预览及通用下载，大文件只保留引用；列表有界分页，MCP 摘要有 192 KiB 防线。实时推送、审计详情和无障碍/视觉回归属于统一 CI/现场门禁。
9. 可观测性：Java Center 提供 Admin 鉴权的有界 `/metrics` 和脱敏日志约定；已新增任务成功/失败率、队列深度/最老等待、过期租约、Agent 在线率、升级失败率、工件容量和审计背压指标，并提供 PrometheusRule 模板和可选 `rcm.audit` JSON 行导出（见 `docs/OBSERVABILITY.md`）。生产已通过私有 GitOps 接入 Prometheus ServiceMonitor 与 Center/数据库/Agent/升级告警；集中日志接收器、SLO 面板和升级失败通知仍需现场接入与演练。
10. 执行合同：已在协议、MCP/Admin 请求、任务持久化和 Agent 本地路径校验中加入 `project/worktree/path/workspace/unrestricted` 范围、machine/host/capability、预算、过期和幂等意图字段；Agent 会把合同预算作为输出、工件和操作时长的更窄上限，桌面 companion 进入 IPC 前也复用同一 cwd 合同校验；P0-06 代码与 CI 已完成，目标机绕过测试归入独立生产验收。

## 当前生产阻塞（已用只读探针确认）

- 集群上下文为 `kubernetes-admin@sg-osaka-dualstack`；Java v0.1.28 Center、Console、PostgreSQL 和迁移 Job 均已运行，旧 Go Center 不再承载流量；旧 Deployment/Service/PVC 的删除仍是 P2-07 生产清理项，不作为运行时回滚路径。
- 生产 Java HTTPRoute 已切换到当前 Java Service；真实域名与 Token 只保存在私有 GitOps/Secret，不写入公开仓库。
- Argo CD 生产 Application 的同步操作已成功；集群控制面偶发 `http2: client connection lost`，可能导致 status 短暂显示旧 revision，需继续观察而不是误判为业务故障。
- 当前 9 台登记 Agent 均在线并已通过 v0.1.28 canary/批次升级和真实命令闭环；本次活动 9/9 completed、0 failed、0 pending。`local-cmcc-debian` 另完成稳定 v0.1.29 Linux GUI canary，Desktop/Browser 能力已在 Center 自描述中收敛；Windows Desktop 交互、离线领取和安装失败/回滚仍需单独的维护窗口演练。

## 资源占用说明

- 本次重启后看到的数 GiB Java 进程来自 Native Image 编译器（命令行包含
  `--image-args-file`），不是目标机常驻 Agent；当时 Center/Agent 两个编译进程的工作集约为
  3.8 GiB/6.2 GiB，Gradle 守护进程约 340 MiB。它们已停止，当前工作区没有 Java 构建进程。
- 原因是 Native Image 编译峰值高且旧调用同时提交 Center、Agent；脚本和 GitHub Actions 现已使用
  `--no-parallel` 串行编译。今后不在开发机或目标宿主机编译 Native Image。
- 运行时预算与编译峰值分开：Java command-agent 不依赖 Spring，空闲只有一条有界 HTTPS 长轮询请求；默认并发 1、Browser Worker
  默认 1、单任务输出
  64 MiB、聚合 spool 64 MiB。Native Agent 的目标 RSS 必须在 CI/目标平台用同一版本实测；当前没有把
  编译进程的内存数字冒充运行时测量。Go 基线仓库内 Linux Agent 文件大小为 6,537,378 字节，但这
  只是磁盘体积，也不能替代同场景 RSS 对比。
- 每个 command/browser 任务现在有独立进程树监督：默认最多 32 个后代进程，可选墙钟、累计 CPU 时间和 Linux `/proc` RSS 上限；超限终止整棵树并回传明确失败原因。command 任务和 browser-agent supervisor 共享 Agent 级总进程预算（`REMOTE_CONNECT_MCP_AGENT_MAX_TOTAL_CHILD_PROCESSES`，默认随并发增长但封顶 256，允许 1–4096），动态进程树扩展后自动释放；desktop-companion 使用自己的 IPC/GUI 启动上限和 shutdown 回收，不进入 command-agent 总预算。资源监督只在任务运行期间存在，不增加空闲轮询；Windows RSS 仍需后续 Job Object/目标机门禁补齐。
- Agent dispatch 对同一 task ID 使用原子 `putIfAbsent` fence；Center/网络重试在旧 runner 仍存在时只丢弃重复响应，不覆盖 Future 或重复执行任务。该保护不替代 Center 的租约/Attempt 真相源，重启和断线演练仍待目标环境验收。
- `scripts/smoke-java-agent.sh/.ps1` 在 Agent 在线和任务闭环期间采样工作集峰值；Release/迁移工作流
  会先按 256 MiB 默认预算校验，再将 JSON 作为私有 Actions 工件上传，不放入公开 Release；
  该门禁只约束 Native Agent 常驻烟测，不把单次编译峰值直接当作宿主机硬限制。
- 当前代码已将 Native 构建拆为 `command-agent`、`desktop-companion`、`browser-agent` 三个目标；三者只通过
  `protocol` 共享线协议和桌面 IPC 记录，不互相声明运行时模块依赖。命令 Agent 使用 `agent.lock` 单实例，桌面
  companion 使用独立锁并限制连接/启动进程，Browser Agent 按任务启动且由 `MAX_BROWSER_WORKERS` 限制。GitHub Actions
  通过边界静态门禁、拆分构建、Native 烟测和运行时回收门禁；9 台登记 Agent 的安装与升级已验收，真实桌面/浏览器场景仍需专门回归。

- 全仓硬切换静态门禁已覆盖 Center/Agent/Desktop/Protocol、Viewer、安装器和冒烟脚本：旧 Tool/字段/环境变量、旧 Viewer 事件、Browser 命令入口、共享 Enrollment、Go 导入开关和 JAR 冒烟回退均会直接失败；当前只保留一次性 Enrollment API、显式 CPU 基线的 Native ZIP bundle 和 8 个聚合 Tool。历史 Liquibase changelog 中的旧主体字面量仅作为不可变迁移输入，由 028 统一规范化，不是运行时兼容分支。

## 下一步生产顺序

1. 统一触发一次 GitHub Actions：Java/Native/React、Liquibase/PostgreSQL、Node 静态和故障矩阵；本机不编译。
2. 在维护窗口演练离线领取、安装失败、失败重排队和回滚，并验证 v2 文件分块/断线/重启/容量边界。
3. 在真实反向代理环境验收 WebSocket wake、LISTEN/NOTIFY、长任务、Center 重启、ChatGPT Web 双向附件和 Viewer；不实施 P2-02 多副本和完整 SaaS 多租户。
4. 完成 PostgreSQL/工件恢复和多主体双账号验收后，删除旧部署资源并完成 DNS/Secret 清理；不恢复旧 Go 或旧 MCP Tool 面。
