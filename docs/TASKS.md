# RCM 生产任务清单

更新时间：2026-09-15（Asia/Shanghai）

本文是 Remote Connect MCP 的可持续任务清单。任务只有在代码、CI 和目标环境验收证据齐全后才可以勾选；“部分完成”保持未勾选，避免把骨架实现误认为生产能力。

需求基线：[`REQUIREMENTS.md`](REQUIREMENTS.md)；当前实现证据：[`STATUS.md`](STATUS.md)；架构约束：[`ARCHITECTURE.md`](ARCHITECTURE.md)；异步契约：[`ASYNC_CONTRACT.md`](ASYNC_CONTRACT.md)。

## 状态规则

- `[x]` 已完成：实现、自动化检查和必要的目标环境验收均有证据。
- `[ ]` 待办：尚未实现，或实现后仍缺少生产验收。
- `[~]` 实施中：已进入当前开发队列，但实现或验收尚未闭环。
- `[—]` 明确不做：经需求决策从当前路线移除，不再作为交付门禁。
- `部分完成` 不是独立状态；对应任务仍保持 `[ ]`，直到剩余项和验收全部完成。
- 每项完成后，在“验收证据”列补充 CI run、测试、部署版本或目标机验证记录，再将 `[ ]` 改为 `[x]`。
- 不在本机编译 Java、Native Image、React 或正式安装包；构建证据必须来自 GitHub Actions。

## 当前阶段：选定 P2 实施，随后进入目标环境回归

截至 `461c35d`，P0/P1 的主要协议、Center/Agent/Console 主流程和兼容实现已经完成，选定的 P2-01/03/04/06/07 已进入实现；对应 GitHub Actions 已通过 JVM、PostgreSQL/Liquibase、React、Native 和 Go 兼容门禁。当前仍为 `[ ]` 的项目，很多不是“没有代码”，而是还缺少目标机器、生产拓扑或平台差异的回归证据；因此不会因为 CI 绿灯就自动勾选。P2-02 和 P2-05 已按需求决策明确不做。

| 层级 | 当前判断 | 剩余工作 |
| --- | --- | --- |
| P0 | 核心可靠性与安全实现基本完成 | Center/Agent 重启、断线与重试、资源硬限额、工件卷备份恢复、离线升级等目标环境门禁 |
| P1 | 主流程已具备，平台特性待实测 | Windows/Linux Desktop 与 Browser、Git/Console/升级/长连接真实矩阵，以及无障碍、视觉和代理故障验收 |
| P2 | P2-01/03/04/06/07 已进入实施队列；P2-02/05 按决策移除 | QUIC/HTTP3 评估、集中日志与对象存储规模化、SLO/告警、桌面/浏览器平台增强和 Go 回滚路径退出 |

当前证据基线：Java/React workflow `34923427070`、综合 CI `34923427006` 均成功；事件驱动检查、仓库敏感信息扫描和浏览器脚本静态检查均通过。

## P0：平台必须可靠

| 状态 | 编号 | 任务 | 当前情况与完成条件 | 验收证据 |
| --- | --- | --- | --- | --- |
| [x] | P0-01 | 固定 MCP 地址和多机器路由 | `/mcp`、Bearer 和按 machine ID 路由已可用；后续不因 Center/Agent/Console 升级改变连接器地址 | v0.1.21 MCP/health/ready 验收 |
| [x] | P0-02 | 机器注册与凭据分层 | 一次性 Enrollment Token、独立 Agent Token、稳定 MCP Token、独立 Admin Token 已实现；Enrollment 不写入长期配置 | 注册与身份测试、生产 Secret |
| [ ] | P0-03 | 异步任务全链路恢复 | 已有幂等、租约、Attempt、旧 attempt 回传栅栏、取消、输出游标和 LISTEN/NOTIFY；Agent 对重复 poll 回传增加原子 `putIfAbsent` dispatch fence，避免覆盖正在运行的 Future；仍需完成 Center 重启、Agent 断线、重复重试、长任务和高并发正式演练 | `AgentRuntimeTest.duplicateTaskRegistrationKeepsTheFirstRunner`、GitHub Actions；仍需补充故障演练报告和重启后任务状态证据 |
| [ ] | P0-04 | PostgreSQL + Liquibase 唯一事实来源 | 生产数据库、Liquibase `001`–`011`、旧 Go 状态导入和迁移 Job 已验证；新增 `012` 审计表、`013` Agent 运行时描述与 `014` 配置历史已加入源码，待随本轮发布迁移 | PostgreSQL/Liquibase CI 与生产迁移记录 |
| [ ] | P0-05 | 任务与工件的持久化边界 | 已完成 `ArtifactStore` 抽象、独立持久卷文件对象、原子写入/读取校验、旧 `artifact_data` 懒迁移和显式 GC API；GC 现在会在一小时并发写入宽限期后扫描未被 PostgreSQL 元数据引用的文件；仍需完成生产卷备份/恢复、保留策略演练和 CI/目标环境验收 | 对象存储适配、迁移/恢复、生命周期测试 |
| [ ] | P0-06 | 执行范围与权限合同 | 已增加 project/worktree/path/workspace/unrestricted 会话模型；unrestricted 必须显式授权；任务持久化 machine、host、scope、capability、预算、过期和 lease；Center 与 Agent 双重校验，桌面 companion IPC 也复用同一 cwd/真实路径校验；unrestricted Agent 现在允许并正确复核更窄的 per-task path/workspace 合同；合同预算还会按 Agent runtime descriptor 的输出、子进程、CPU/RSS/时长上限继续收窄，凭据形态环境变量在持久化和启动两侧均过滤 | `DesktopCompanionServerTest`、GitHub Actions；仍需数据库迁移、绕过测试和目标机回归 |
| [ ] | P0-07 | Agent 资源硬限制 | 已加入任务级进程树/墙钟监督、CPU 时间与 Linux RSS 可选硬边界，并保留输出/磁盘/并发上限；Linux 可选绑定预创建 cgroup v2，Windows 采用有界进程树/SCM 配置路径；command/desktop/browser 共享 Agent 级总进程预算，超额任务 fail-closed 并在退出时释放名额 | GitHub Actions 编译、Linux cgroup/Windows 等效策略、目标机压测 |
| [x] | P0-08 | 隐私、密钥和仓库卫生 | 公开仓库使用模板值；真实域名、Token、Secret 和私有 GitOps 留在受保护环境；日志/指标有脱敏约定 | 仓库扫描、CI hygiene、私有部署检查 |
| [x] | P0-09 | GitHub Actions 构建和可安装包 | Java/Native/React/安装包、SBOM、签名和烟测由 GitHub Actions 完成；开发机不编译 | Java Release workflow、Native smoke、RSS gate |
| [ ] | P0-10 | 全部已登记 Agent 的恢复 | 升级活动默认把未显式指定的全部登记 Agent（含离线）写入持久目标集；离线 Agent 下次心跳自动领取同一 offer，仍需完成真实在线清单、升级活动和任务闭环 | Agent 在线清单、升级活动和任务闭环记录 |

### P0 完成门禁

- [ ] P0-G1：范围合同不能被改变 cwd、环境变量或任务重试绕过。
- [ ] P0-G2：Center/Agent 重启、连接中断和 ChatGPT 重试不会产生重复执行。
- [ ] P0-G3：长输出、截图、下载和无超时任务在配额内运行，超限有明确终态。
- [ ] P0-G4：所有在线和恢复后的 Agent 都能在固定 `/mcp` 下被正确路由。

## P1：核心生产体验

| 状态 | 编号 | 任务 | 当前情况与完成条件 | 验收证据 |
| --- | --- | --- | --- | --- |
| [ ] | P1-01 | Project Registry 与 Git worktree 闭环 | 注册、受保护删除、创建/删除 worktree 和 cwd 解析已有；已增加受项目/工作树合同约束的 status、diff、log、幂等 commit、显式 merge 和 `merge_abort` 冲突恢复排队 API，并接入精简 MCP `project` 操作；冲突输出、提交差异审阅和目标机权限回归仍待补齐 | 项目/Agent 目标机 Git 测试 |
| [ ] | P1-02 | Windows/Linux Desktop Companion 真实能力 | 基础截图、屏幕枚举、启动、输入和剪贴板已有；IPC 已复用 Center 合同的 scope/真实路径/过期检查，并限制连接、启动进程和工件大小；补齐 Windows 多会话/UAC/RDP、Linux X11/Wayland、多显示器和会话失效恢复 | 真实目标机矩阵、截图/输入工件 |
| [ ] | P1-03 | Browser Agent 生产运行时 | Worker 监管、结构化引用、有界快照、下载工件和清理已有；补齐目标浏览器安装、持久 profile、登录态、stale ref 恢复和 Playwright/Patchright/Comoufox 回归 | 目标机浏览器矩阵、长任务和清理报告 |
| [ ] | P1-04 | React 控制台完整工作流 | 基础机器、项目、任务、令牌、升级和审计页面已有；机器页可展示 Agent runtime 自描述；范围/会话/桌面/浏览器任务编排已接入，MCP/Console 对图片结果使用有界内联策略；Admin 列表统一返回 `total/has_more`，MCP 项目列表支持有界 offset/limit；机器、项目、任务、升级和审计页已按页增量加载，实时刷新与下一页请求使用代次栅栏避免旧响应污染；仍需补实时状态、无障碍和视觉回归 | Console E2E、a11y、视觉 CI |
| [ ] | P1-05 | 升级 offer/attempt 与兼容回滚 | canary、批次、SHA-256、原子替换和回滚已有；offer/status 已携带可选 attempt 并拒绝迟到状态覆盖；PostgreSQL campaign/target 行锁已串行化 offer、控制和状态更新；管理员可只重排队单个失败目标，不重复成功目标；仍需补齐数据库并发验证、版本兼容矩阵、离线补升级和失败暂停/重排队现场演练 | 多 Agent 并发升级、回滚和兼容测试 |
| [ ] | P1-06 | WebSocket/事件唤醒生产验收 | wake-only WebSocket、指数重连、HTTPS 回退和 PG 通知桥接已有；补齐真实反向代理、多副本、序列号、断线和 Center 重启演练 | 代理/多副本故障演练 |
| [ ] | P1-07 | 配置与心跳自描述 | 已加入版本化 runtime descriptor：generation、并发、单任务及 Agent 总进程预算、输出/资源预算、scope_mode、桌面/浏览器配置与会话状态随心跳发送并持久化到 Center；配置更新支持可选 `expected_generation` CAS、有界历史和单调 generation 回滚；当前明确支持 schema 1，旧 Agent 缺失总预算字段时使用有界默认值，未知未来 schema 在协议边界拒绝而不猜测新语义；仍需目标机回滚演练 | Agent 心跳 schema、配置回滚测试 |
| [ ] | P1-08 | 终端与子 Agent 生命周期 | 默认一个 command-agent 身份，桌面/浏览器作为子组件；command-agent 的桌面直启进程已纳入有界预算，并在 Agent 关闭/升级/JVM shutdown hook 中回收；仍需补齐多物理 Agent 的显式隔离、互斥、崩溃拉起和 Center 视图 | `DesktopProcessBudgetTest`、GitHub Actions；仍需主机多 Agent、进程树和 Token 隔离测试 |
| [ ] | P1-09 | 审计与错误可解释性 | 已增加有界异步审计队列、PostgreSQL `rcm_audit_event`、Admin 分页查询和 Console 审计页，记录任务状态/尝试、工件、Git、配置与升级事件且不写入命令/凭据；任务创建已区分 `mcp` 与 `console` 来源，任务/升级/控制器错误在落库和返回前统一脱敏，已加入管理员确认后的有界保留清理入口；仍需接入更细的审批来源、脱敏抽样和真实故障报告 | 审计查询、保留清理、脱敏抽样和故障报告 |

### P1 完成门禁

- [ ] P1-G1：至少一台 Windows 和一台 Linux 目标机完成 Desktop/Browser 真实操作回归。
- [ ] P1-G2：控制台可以从选择机器开始，完成范围选择、任务提交、日志查看、工件查看和取消。
- [ ] P1-G3：升级活动在在线、离线、迟到报告和启动失败情况下都能安全收口。
- [ ] P1-G4：反向代理下的 WebSocket、长轮询和 Center 重启均能恢复，不依赖固定间隔轮询。

## P2：规模化增强

| 状态 | 编号 | 任务 | 当前情况与完成条件 | 验收证据 |
| --- | --- | --- | --- | --- |
| [~] | P2-01 | QUIC/HTTP3 传输评估与实现 | 已补齐 transport 能力协商标头、HTTPS 选择与兼容回退、基准脚本；未证明收益前不改变默认 HTTPS/WebSocket 路径，真实 QUIC provider 和灰度仍待验证 | 基准、灰度和回退报告 |
| [—] | P2-02 | Center 多副本与高可用 | **明确不做。** 当前保持单副本稳定路径，不建立多副本、PDB、HPA 或跨副本一致性门禁 | 需求决策记录 |
| [~] | P2-03 | 集中日志与对象存储规模化 | 已有异步审计、可选 `rcm.audit` JSON 行导出、filesystem/HTTPS 对象网关、GC 边界和容量指标；仍需接入生产采集器、对象生命周期/索引与成本压测 | 存储/日志压测和保留策略 |
| [~] | P2-04 | SLO、告警和升级通知 | 已有基础 metrics/Prometheus 接口；补齐任务成功率、排队延迟、断线恢复、资源和升级失败 SLO 指标、规则与通知出口 | 监控规则、演练和通知记录 |
| [—] | P2-05 | 多用户/多租户权限模型 | **明确不做。** 当前维持单管理域，不设计租户、角色、配额和组织隔离模型 | 需求决策记录 |
| [~] | P2-06 | 更丰富的桌面和浏览器平台 | 已有受控桌面 companion 和 Browser Worker；补齐常用交互、平台适配、会话恢复和兼容性自描述，不扩大 MCP 原始工具面 | 平台兼容矩阵和资源报告 |
| [~] | P2-07 | 旧 Go 回滚路径退出 | Java 已是生产路径；先把 Go 自动发布/部署降为兼容归档，再在 P0/P1 回归通过后移除旧 workflow、Deployment、Service/PVC 和旧代码 | 变更审批、回滚窗口关闭记录 |

## 当前最短生产路径

当前先推进已选 P2，再进行大规模目标环境回归；P0/P1 现场门禁仍是生产声明的前置条件：

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
