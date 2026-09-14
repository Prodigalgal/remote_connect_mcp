# RCM 迁移状态

更新时间：2026-09-14（Asia/Shanghai）

本文只记录仓库代码与当前集群只读探针能够证明的状态。产品需求基线见 [`docs/REQUIREMENTS.md`](REQUIREMENTS.md)，分级任务清单见 [`docs/TASKS.md`](TASKS.md)；没有通过生产门禁的内容不会标记为“已上线”。

## 结论

Java 25 Center/Agent 与 React 控制台已经完成 v0.1.21 生产发布；四台在线 Oracle Agent 已完成 v0.1.21 批次升级，五台离线 Agent 暂保留旧版。生产数据库已完成 Liquibase 初始化、旧 Go 状态导入与清理验证；Java Center/Console 已通过 Argo CD 以不可变 digest 部署，旧 Go Center、Deployment 和 PVC 仍保留作回滚点。公网 health/ready、Console Admin API、MCP 入口和 Agent 任务闭环均已验收。当前剩余工作集中在离线 Agent 恢复、真实 Browser/Desktop 场景、QUIC、多副本长连接故障演练、可观测性和升级兼容回归。

## 已完成实现与历史证据

| 领域 | 当前实现 | 证据 |
| --- | --- | --- |
| Java 工程 | `protocol`、`center`、`agent` Gradle 多模块，Java 25 toolchain | GitHub Actions `34745635544`：JVM、Liquibase、备份恢复、React、Linux amd64/arm64、Windows amd64 全部成功 |
| MCP | Streamable HTTP `/mcp`、Bearer 校验、精简工具面、分页结果 | v0.1.15 公网验收已作为兼容基线保留；当前 v0.1.21 继续使用同一 `/mcp` 契约 |
| 任务可靠性 | 异步入队、幂等键、租约、取消、输出游标、断线重连、有界 spool、无超时任务恢复；过期租约区分可恢复持久任务与不可安全重放的定时任务，Go/Java 都按 Agent 范围在下一次 poll 修复，JDBC 修复后的任务 ID 在事务提交后精准唤醒 `task_wait`；Go/Java 两条兼容实现每次真正领取租约都会递增并暴露 `attempt`，Java Agent 在状态/输出/工件回传携带 attempt 栅栏，便于阻断断线后的旧进程重投；旧客户端只在首次派发允许省略 attempt，重新租约后缺少标头也会被拒绝；PostgreSQL 按任务摘要路由 `LISTEN/NOTIFY` 事件唤醒，高频输出只唤醒等待同一任务的请求（可跨 Center 副本），截止时间返回快照，不运行固定行读取循环 | Java Agent/Center 单元测试与 GitHub Actions 门禁 |
| 注册与身份 | 一次性 Enrollment Token，注册后换取每 Agent 日常 Token；身份文件原子写入 | Agent/Center 测试通过 |
| 配置热更新 | Center 下发 generation、长轮询等待时间、兼容退避间隔和并发槽位；Agent 原子落盘并只接受更新代次；每次心跳还携带版本化 runtime descriptor（单任务及 Agent 总进程预算、资源能力、scope_mode 与桌面/浏览器配置及会话状态），Center 以固定大小 JSONB 投影保存；管理员可将配置历史作为新 generation 回滚；旧 descriptor 缺失总预算字段时按有界默认值兼容 | `AgentRuntimeSettingsTest`、`AgentConfigurationServiceTest`、runtime descriptor protocol/registry tests |
| Desktop | 独立 `desktop` Native 目标的用户会话 companion；截图/屏幕枚举、启动、点击/拖拽、组合按键、剪贴板、窗口聚焦和文本输入通过受保护 loopback IPC；伴侣退出时会终止其登记的 GUI 子进程，避免重启留下无界孤儿进程 | `DesktopCompanionClientTest`、协议测试，以及 GitHub Actions `34795775084` 的 Linux amd64/arm64、Windows amd64 Native smoke 通过 |
| Browser | 独立 `browser` Native 目标的单任务 supervisor；command-agent 负责 Center 生命周期、超时、日志和工件，browser-agent 再启动本机 Playwright/Patchright/Comoufox Worker | `BrowserTaskRunnerTest`、`BrowserTaskRunner`，以及 GitHub Actions `34795775084` 的三目标 Native smoke 通过 |
| 升级 | Center canary/批次状态机，HTTPS + SHA-256，Agent Helper 原子替换和回滚；offer/status 与 detached helper result 支持可选升级 attempt，迟到报告不会覆盖更新尝试；PostgreSQL offer/control/status 路径现在按 campaign/target 行锁串行化，避免并发 Agent 重复领取同一尝试；升级编排使用有界分段机器快照（最多 10000 台），不会因 Admin 单页 200 台而漏掉离线目标；发布工作流资产名已与解析器对齐 | `UpgradeServiceTest`；`.github/workflows/java-release.yml` 静态校验 |
| 控制台 | React/Vite 经典后台布局，机器、项目/worktree、任务、令牌、升级、审计和设置页面；任务编排支持 command/desktop/browser、项目/worktree/path/unrestricted 显式范围、风险/提权/会话与幂等键；项目卡片支持有确认的 status/diff/log/commit/merge/merge-abort；全局搜索、机器在线筛选、任务状态筛选和任务输出 16 KiB 游标分页查看；机器、项目、任务、升级、审计列表按 `has_more` 增量加载；实时刷新与“下一页”并发时使用请求代次栅栏，升级页支持只重排队单个失败目标；Admin Token 只在当前标签页内存 | v0.1.21 GitHub Actions React 构建与生产 Console 路由验收通过；本轮新增代码待 CI |
| 数据库 | PostgreSQL 适配器与 Liquibase `001`–`014` changelog；内存模式仍用于协议回归；发布工作流带 PostgreSQL 16 服务容器集成、备份和恢复门禁；`012` 增加异步审计事件表，`013` 持久化 Agent 运行时自描述，`014` 保存有限配置历史以支持回滚；审计保留通过显式、有界 Admin GC 入口执行，不运行定时清理线程 | Liquibase 资源/迁移单元测试通过；CI `PostgresIntegrationTest` 会覆盖注册、心跳自描述、项目/worktree、幂等任务、租约、输出续传和工件往返，随后执行 custom-format dump/restore |
| 发布脚本 | Java JVM 构建、Native Image 门禁脚本、Windows/Linux Agent 安装器、Java Center/Agent JVM/Native 烟测脚本，以及 Windows/Linux WebSocket wake 烟测 | 当前只做静态校验；Native Image、完整原生烟测、Agent RSS 资源报告和仓库卫生扫描交给 GitHub Actions，Windows/Linux 安装器均支持 CI 平铺 ZIP + 旁路库 |

## 部分实现或仍需补齐

1. Native Image：v0.1.21 正式 tag Release 已完成；Linux amd64/arm64、Windows amd64 原生构建、原生迁移烟测、SPDX SBOM、Sigstore keyless 签名、OIDC Artifact Attestation 和 Agent 资源门禁均由 GitHub Actions 完成。Windows/Linux 发布物为包含旁路运行库的平铺 ZIP，旧裸可执行文件保留回退；Windows ARM64 暂保留 JVM/Go 兼容路径。开发机不执行编译。
2. PostgreSQL：生产库已使用独立 `remote_connect_mcp_prod` schema/database 完成 Liquibase 与旧 Go 状态导入，当前保留 9 台 Agent、1403 个任务/输出、0 个工件；v0.1.21 生产迁移 Job 成功。工件已经抽象为 `ArtifactStore`，新写入走独立持久卷文件对象，数据库只保存 key、大小、MIME 和 SHA-256；旧 `artifact_data` 仍保留用于一次性懒迁移。仍需补齐对象保留/GC、卷备份恢复、高并发抢占、Center 重启场景、长输出压测和正式恢复演练。
3. Browser Agent：Java Agent 已提供参考 `scripts/browser-worker.mjs`，可按环境加载 Playwright/Patchright/Comoufox，并支持 CSS/`rcm-ref-v1`/role/label/placeholder/text/test-id 结构化定位；快照最多返回 64 个有界引用，Agent 在独立浏览器 profile 启用时保存脱敏的最近 origin/path 并在下一任务尝试恢复；结果仍包含有界快照、动作结果、截图、下载工件以及脱敏的网络/控制台/页面错误摘要；即使自定义适配器写入结果清单，Agent 也会在发送前再次脱敏。引用失效时 Worker 会返回一次新的有界 snapshot 建议，避免盲目重放；目标平台仍需安装浏览器运行时并完成持久会话和跨浏览器回归。
4. Desktop Agent：基础截图、屏幕枚举、输入、剪贴板和 Windows 窗口聚焦已具备；command-agent 会原子发布伴侣 scope 策略，伴侣在 IPC 前复核真实路径和合同过期时间，并在重启时回收其登记的 GUI 子进程；策略文件缺失或损坏时生产加载路径默认收窄到 Agent 状态目录，不再隐式放大为整机权限；Linux 窗口管理器差异、多显示器真实会话、UAC/权限场景和跨桌面回归仍需专门验收。
5. 构建拆分：`command-agent`、`desktop-companion`、`browser-agent` 已分别建立 Gradle Native 目标、Docker artifact 目标和 Release ZIP/校验流程；GitHub Actions 已通过 Linux amd64/arm64、Windows amd64 的四目标编译与烟测，四台在线 Oracle Agent 的安装/升级回归已完成。
6. 长连接：已实现可选 WebSocket wake-only 通道、带单调序列号的重复/乱序提示去重、客户端指数重连和 HTTPS 长轮询；PostgreSQL 模式新增跨 Center 副本的 Agent 唤醒与任务等待 `LISTEN/NOTIFY` 桥接（best-effort，丢失时由请求截止时间和下一次显式读取补偿）；仍需在真实反向代理/多副本环境完成灰度和故障演练，QUIC 尚未实现。
7. Project Registry/Git worktree：已实现按 Agent 归属的项目注册、受保护项目删除、项目根/仓库路径边界、异步 `git worktree add/remove`、幂等键和项目/worktree 任务 cwd 解析；精简 MCP `project` 工具已接入 status/diff/log/幂等 commit/显式 merge/`merge_abort` 冲突恢复排队；Center 不读取仓库内容，Agent 仍执行最终真实路径与权限校验。提交/差异审阅、冲突输出和实际目标机 Git/权限回归仍待补齐。
8. 控制台：基础管理流程、项目/worktree、全局搜索/基础筛选和有界任务输出查看可用；机器、项目、任务、升级和审计列表已按服务端 `has_more` 增量加载，升级页可只重排队单个失败目标；审计页提供管理员确认后清理一年以前记录的有界入口，MCP 创建的任务与控制台创建的任务在审计来源中分别标记为 `mcp`/`console`，任务编排已覆盖 command/desktop/browser 与显式范围；MCP 只内联不超过 2 MiB 的图片，实时推送、审计详情和无障碍/视觉回归门禁尚未达到生产级完整度。
9. 可观测性：Java Center/Go 基线均提供 Admin 鉴权的有界 `/metrics` 和脱敏日志约定；生产已通过私有 GitOps 接入 Prometheus ServiceMonitor 与 Center/数据库/Agent/升级告警。集中日志、SLO 面板和升级失败通知仍待补齐。
10. 执行合同：已在协议、MCP/Admin 请求、任务持久化和 Agent 本地路径校验中加入 `project/worktree/path/workspace/unrestricted` 范围、machine/host/capability、预算、过期和幂等意图字段；Agent 会把合同预算作为输出、工件和操作时长的更窄上限，桌面 companion 进入 IPC 前也复用同一 cwd 合同校验；P0-06 仍保持未完成，待 GitHub Actions 编译、数据库迁移、绕过测试和目标机回归全部通过后再勾选。

## 当前生产阻塞（已用只读探针确认）

- 集群上下文为 `kubernetes-admin@sg-osaka-dualstack`；Java v0.1.21 Center、Console、PostgreSQL 和迁移 Job 均已运行，旧 Go Center Deployment 已缩容为 0，Service/PVC 仍保留作回滚。
- 生产 Java HTTPRoute 已切换，旧用户路由也已指向 Java Service；真实域名与 Token 只保存在私有 GitOps/Secret，不写入公开仓库。
- Argo CD 生产 Application 的同步操作已成功；集群控制面偶发 `http2: client connection lost`，可能导致 status 短暂显示旧 revision，需继续观察而不是误判为业务故障。
- 当前 9 台登记 Agent 中，4 台在线 Oracle Agent 已通过 v0.1.21 canary/批次升级并完成真实命令闭环，另外 5 台离线，暂不强行改写其状态。离线节点恢复上线后，再按同一升级活动重新核对平台、版本和服务状态。

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
- 每个 Java Agent 任务现在有独立进程树监督：默认最多 32 个后代进程，可选墙钟、累计 CPU 时间和 Linux `/proc` RSS 上限；超限终止整棵树并回传明确失败原因。command/desktop/browser 任务还共享 Agent 级总进程预算（`REMOTE_CONNECT_MCP_AGENT_MAX_TOTAL_CHILD_PROCESSES`，默认随并发增长但封顶 256，允许 1–4096），动态进程树扩展和桌面直启都会占用同一预算，任务结束或进程退出自动释放。资源监督只在任务运行期间存在，不增加空闲轮询；Windows RSS 仍需后续 Job Object/目标机门禁补齐。
- `scripts/smoke-java-agent.sh/.ps1` 在 Agent 在线和任务闭环期间采样工作集峰值；Release/迁移工作流
  会先按 256 MiB 默认预算校验，再将 JSON 作为私有 Actions 工件上传，不放入公开 Release；
  该门禁只约束 Native Agent 常驻烟测，不把单次编译峰值直接当作宿主机硬限制。
- 当前代码已将 Native 构建拆为 `command-agent`、`desktop-companion`、`browser-agent` 三个目标；命令 Agent
  使用 `agent.lock` 单实例，桌面 companion 使用独立锁并限制连接/启动进程，Browser Agent 按任务启动且由
  `MAX_BROWSER_WORKERS` 限制。GitHub Actions 已通过新的拆分构建、Native 烟测和运行时回收门禁；四台在线 Oracle Agent 的安装与升级已验收，真实桌面/浏览器场景仍需专门回归。

## 下一步生产顺序

1. 恢复离线 Agent 后，按 v0.1.21 兼容窗口重新执行 canary/批次升级和真实任务闭环。
2. 在真实多副本/反向代理环境完成 WebSocket wake、LISTEN/NOTIFY、长任务和 Center 重启故障演练；QUIC 仍按需求评估。
3. 为 Browser/Playwright、Desktop companion、多显示器/UAC 场景补齐目标平台回归和脱敏工件验证。
4. 接入集中日志、告警、SLO、升级失败通知和审计留痕；完成 PostgreSQL 恢复演练后再评估移除旧 Go 回滚资源。
