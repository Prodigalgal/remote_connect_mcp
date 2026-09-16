# RCM 生产验收记录

更新时间：2026-09-16（Asia/Shanghai）

本文记录目标环境验收，不改变 [`TASKS.md`](TASKS.md) 中的代码状态。代码任务在实现和 CI 完成后即可标 `[x]`；只有这里的生产证据才会把对应能力标记为生产“通过”。所有地址、Token、数据库连接和机器敏感属性均不写入本文。

## 验收范围与原则

- 只验收已经部署的生产版本；本轮代码分支没有直接发布或写入生产。
- 生产探针优先使用健康、认证、协议、数据库状态和事件唤醒等无副作用检查。
- 任务闭环只使用固定 `printf` 输出，不读取文件、不修改配置、不启动额外服务。
- WebSocket、升级、Desktop/Browser 和故障演练若缺少安全的目标条件，记录为“待验收”，不使用 CI 结果替代。

## 本轮 P0/P1/P2 逐项结论（生产证据，不等同于代码状态）

| 优先级 | 项目/门禁 | 结论 | 当前证据或未决条件 |
| --- | --- | --- | --- |
| P0 | G1 范围合同 | 通过 | Linux/Windows cwd、环境变量伪造、越界路径和幂等重试均已实机验证 |
| P0 | G2 重启/重试 | 部分通过 | Agent 停止/启动期间 durable 任务只执行一次；Center 重启与 ChatGPT 重试需维护窗口 |
| P0 | G3 长任务/工件/资源 | 部分通过 | 128 KiB 分页、超时终态、durable 完成和 32 子进程硬上限均已通过；工件及 RSS/CPU 极限仍待 capability/压测条件 |
| P0 | G4 固定 `/mcp` 多机路由 | 通过 | 9 台在线 Agent 经同一 MCP 会话完成 `command_start`/`task_wait` |
| P1 | P1-01 项目注册与 worktree | 通过 | 项目注册、Git 读操作、worktree 创建/删除和清理闭环见下文 |
| P1 | P1-02 Desktop Companion | 部分通过 | `local-cmcc-debian` 已完成真实 screens/截图路由；`local-ly-windows11` 已安装 desktop bundle、SYSTEM Agent 和按用户登录触发的 Companion 任务，但当前无交互登录会话，Windows 截图/输入仍待用户会话 |
| P1 | P1-03 Browser Agent | 部分通过 | `local-cmcc-debian` 与 `local-ly-windows11` 均完成真实 `navigate`；snapshot/截图/下载、持久会话和更多浏览器引擎仍待补充 |
| P1 | P1-04 React Console | 部分通过 | Center 后端提交/日志/取消通过；React E2E、工件页面、a11y/视觉待验收 |
| P1 | P1-05 升级与回滚 | 部分通过 | `v0.1.28` canary+批次已覆盖 9/9 在线目标且失败 0；离线领取、启动失败和回滚仍未演练 |
| P1 | P1-06 WebSocket/唤醒 | 待验收 | CI smoke 已通过；生产反向代理握手、断线和 Center 重启待窗口 |
| P1 | P1-07 配置/心跳自描述 | 通过 | 一台 Linux Agent 完成 generation 0→1→2→3 热更新/回滚；旧 generation 写入 HTTP 400，心跳已收敛到最终代次 |
| P1 | P1-08 生命周期/子 Agent | 待验收 | 现有目标只有 command/durable_tasks；多物理 Agent 隔离/崩溃拉起待目标 |
| P1 | P1-09 审计/错误解释 | 通过 | 正常任务与超时失败任务均有审计关联、来源和可读错误；脱敏抽样通过 |
| P2 | P2-01 QUIC/HTTP3 | 待验收 | 当前生产仍为 HTTPS/WebSocket；无真实 QUIC provider 灰度 |
| P2 | P2-02 Center HA | 不做 | 需求决策保持单副本稳定路径 |
| P2 | P2-03 日志/对象存储 | 待验收 | 代码与 CI 具备；生产采集器、生命周期和成本压测未执行 |
| P2 | P2-04 SLO/告警 | 待验收 | 指标与 PrometheusRule 存在；通知出口和告警演练未执行 |
| P2 | P2-05-lite 多主体/对话/连接 | 待实施 | 已完成关系设计；用户 Token、主体 ACL、执行车道和 Desktop/Browser 会话隔离尚未进入生产验收；完整 SaaS 多租户仍不做 |
| P2 | P2-06 桌面/浏览器增强 | 部分通过 | Linux/Windows 均已完成独立 command/desktop/browser 包部署和浏览器基线；Windows 桌面交互与更多浏览器工件场景仍待真实登录会话 |
| P2 | P2-07 Go 路径退出 | 待验收 | Java 已为生产路径；Go 兼容清理仍需独立生产变更 |

## 2026-09-15 Agent v0.1.26 五机滚动验收

Java Agent `v0.1.26` 已通过 GitHub Actions Native Release（构建与签名均在 GitHub Actions 完成），并重新安装到本批次的五台目标终端。Center 仍保持现有 Java 生产版本；本次只更新 Agent，不改变 ChatGPT/MCP 连接器地址。

| 终端 | 平台 | 运行方式 | 结果 |
| --- | --- | --- | --- |
| `local-cmcc-debian` | Linux arm64 | systemd | 在线；固定 `echo` 命令完成，Attempt 1，退出码 0 |
| `local-cmcc-zz-jr` | Linux amd64 | systemd | 在线；固定 `echo` 命令完成，Attempt 1，退出码 0 |
| `local-home-pve` | Linux amd64 | systemd | 在线；固定 `echo` 命令完成，Attempt 1，退出码 0 |
| `local-ly-windows11` | Windows amd64 | SYSTEM 级 Task Scheduler（隐藏 PowerShell 启动器） | 在线；固定 `echo` 命令完成，Attempt 1，退出码 0 |
| `local-zzp-laptop-windows` | Windows amd64 | SYSTEM 级 Task Scheduler（隐藏 PowerShell 启动器） | 在线；固定 `echo` 命令完成，Attempt 1，退出码 0 |

本批次只执行无副作用的标记命令，并通过 Center 事件等待获取终态；五台 Agent 的版本、架构、在线状态和命令输出均已核对。Windows 目标不再注册旧 SCM 服务，Linux 目标继续使用 systemd。旧 Go 进程未运行。

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
| 固定 `/mcp` 多机命令路由 | 通过 | 9 台在线 Agent 通过同一 MCP 会话分别执行固定标记命令；9 个任务均 `completed`、Attempt 1、退出码 0，输出标记一致 |
| 范围合同与环境变量伪造 | 通过 | Linux/Windows path 合同允许目录内 cwd，拒绝 `..` 越界；伪造 `PWD`/默认 cwd 环境变量未改变实际工作目录；相同幂等键重试返回同一任务 |
| 长输出与超时边界 | 部分通过 | 128 KiB 输出通过有界分页；1 秒合同超时的 `sleep` 任务以失败/退出码 143 收口；子进程上限探针触发了保护但被误归类为 `Stream closed`，command/browser 路径已改为优先报告资源违规，待 CI/发布后复验；截图、下载和极限 RSS/CPU 压测待具备对应 capability/维护条件 |
| Console 路由 | 通过 | 生产 Console 首页返回 HTTP 200 |
| WebSocket 生产握手 | 待验收 | CI smoke 已通过；尚未使用真实 Agent Token 在生产反向代理下做有效握手、断线与重连演练 |
| Desktop 真实操作 | 待验收 | 当前生产在线 Agent 仅声明 command/durable_tasks，尚无可验收的 Desktop 会话 |
| Browser 真实操作 | 待验收 | 当前生产在线 Agent 未声明 browser capability，未执行真实浏览器任务 |
| 范围合同绕过 | 通过 | Linux/Windows path 合同、cwd 越界、环境变量伪造和幂等重试已完成目标机演练；project/worktree 与符号链接边界仍需项目目标补充 |
| Center/Agent 重启恢复 | 部分通过 | Agent 停止/启动期间 durable 任务只执行一次并恢复在线；Center 重启和 ChatGPT 重试仍需可回滚维护窗口 |
| 升级在线/离线/失败回滚 | 部分通过 | `v0.1.22` 活动以 1 台 canary、批次 2 启动；4 台在线 Agent 全部完成、失败 0，5 台离线目标保持 pending，待重连自动领取；失败重排队/回滚仍待专门演练 |
| 对象网关、日志采集和保留策略 | 待验收 | 代码与 CI 已具备；生产采集器、对象生命周期和成本压测尚未执行 |
| SLO 告警通知 | 待验收 | PrometheusRule 模板和指标已存在；通知出口与演练尚未执行 |
| QUIC/HTTP3 | 待验收 | 当前只启用 HTTPS/WebSocket 能力协商与回退；真实 provider/灰度未启用 |

## 2026-09-15 P1-01 项目注册与 Git worktree 验收

在 `local-cmcc-debian` 上创建了仅用于验收的临时 Git 仓库，并通过同一条
`/mcp` 会话执行完整项目闭环。测试目录为 Agent 上的 `/tmp/rcm-p1-project`，
验收结束后已通过 Agent 任务删除；Center 项目登记也已删除，没有留下生产项目。

| 步骤 | 结果 | 证据 |
| --- | --- | --- |
| MCP `project register` / `project list` | 通过 | 登记返回项目并在机器过滤列表中可见；项目 ID `project_c8bb…` |
| 项目级 `git_status` | 通过 | 任务 `task_d125…`、`task_db14…`、`task_d77e…` 均 Attempt 1、退出码 0 |
| 项目级 `git_diff(stat)` | 通过 | 任务 `task_2e3b…`、`task_287a…` 均完成，未把仓库内容传回 Center |
| 项目级 `git_log` | 通过 | 任务 `task_e13d…`、`task_a609…` 完成，输出仅为有界文本 |
| `worktree_create(HEAD)` | 通过 | worktree `worktree_0882…`，Agent 任务 `task_bf8b…` 完成，状态变为 `ready` |
| worktree 范围内 `git_status` | 通过 | 任务 `task_a58c…`，工作目录解析到 `.rcm-worktrees/worktree_0882…`，退出码 0 |
| `worktree_remove` | 通过 | 任务 `task_b907…` 完成，状态变为 `removed` |
| 项目删除与临时目录清理 | 通过 | Center 登记删除；清理任务 `task_d5ac…` 完成、退出码 0 |

结论：P1-01 的注册、列表、项目/worktree 范围解析、Git 读操作、worktree
创建/删除、异步终态和清理闭环通过生产验收。提交/merge/冲突恢复属于独立的
有副作用演练，未在真实仓库执行。

## 2026-09-15 P1-09 审计与脱敏抽样

通过 Admin API 提交了一条固定的 `printf rcm-audit-probe` 任务（路径范围为
`/tmp`），任务完成且退出码为 0。按任务 ID 查询到 3 条审计事件（创建与状态
变化），事件类型和来源字段完整；对返回的审计投影执行敏感词扫描，未发现
`Bearer`、Token、密码、Secret 或私钥字段。该抽样不读取生产文件，也不改变
配置。

| 能力 | 结果 | 证据 |
| --- | --- | --- |
| 任务创建/状态审计关联 | 通过 | `task_7d11…`，3 条事件，`task.created`/`task.state` |
| 审计投影脱敏 | 通过 | 返回字段扫描结果为 clean |
| 故障解释字段 | 通过 | 受控 `sleep 5`/1 秒合同超时任务 `task_591e…` 返回失败、退出码 143、明确错误；审计来源含 `console` 与 `agent` |

## 2026-09-15 P1-04/P1-07 控制台后端与配置读取验收

| 能力 | 结果 | 证据 |
| --- | --- | --- |
| 控制台任务提交/日志/取消后端链路 | 通过 | `task_5559…` 先进入 queued；日志端点 HTTP 200；取消返回 `cancel_requested`，终态为 `canceled` |
| 控制台工件查看 | 待验收 | 本次使用 command 任务，按合同不产生工件；需要 Desktop/Browser 任务完成后再验收工件下载与 SHA-256 |
| Agent 配置读取/自描述 | 通过 | 3 台在线 Agent 的配置端点均 HTTP 200；目标 Agent 热更新后心跳 runtime descriptor 已收敛到 generation 3，且不含密钥 |
| 配置热更新/回滚 | 通过 | `local-cmcc-debian` generation 0→1→2 后回滚为 generation 3；Agent 心跳已看到 generation 3，旧 generation=2 写入返回 HTTP 400 |

结论：P1-04 的 Center 后端提交、日志、取消闭环通过；React 浏览器 E2E、无障碍/视觉回归
和工件页面仍未验收。P1-07 的配置读取、generation/CAS、热更新、Agent 心跳收敛和回滚已通过一台目标机验收；P1-09 审计与错误解释的正常/失败样本也已通过。

补充：生产 WebSocket 路由可达；使用无效 Agent 凭据连接后由服务端以
`PolicyViolation` 关闭，未形成未授权通道。有效 Agent Token 的 `ready`、断线重连和
任务 `wake` 仍需在维护窗口内验收，因此 P1-06 继续保持“待验收”。

## 2026-09-15 部分通过项分析与处理

本轮对仍处于“部分通过”的项目逐项复核，区分“代码缺陷”“生产条件不足”和“尚未获维护窗口”三类原因：

| 项目 | 原因 | 处理与下一步 |
| --- | --- | --- |
| P0-G2 重启/重试 | Agent 重启期间 durable 任务已证明只执行一次；Center 重启会影响统一路由，ChatGPT 重试还需要真实 Web 端链路，当前没有可回滚维护窗口 | 保持生产版本不变；安排窗口后先做 Center 单副本重启、事件游标恢复，再做 ChatGPT 重试与重复提交校验 |
| P0-G3 长任务/工件/资源 | 长输出、超时和 durable 已通过；子进程上限探针确实触发保护，但输出流被强制关闭后 command/browser runner 先报告了 `Stream closed`，掩盖了资源违规；生产默认 RSS/CPU 为可选配置且当前未启用，Desktop/Browser 工件能力也不在在线 Agent capability 中 | 已在 `CommandRunner` 和 `BrowserTaskRunner` 调整错误优先级，资源违规优先于输出流异常；仅完成代码修复和 `git diff --check`，不得据此宣称生产已修复；待 GitHub Actions、发布和同样的 64 子进程探针复验，再评估 RSS/CPU profile |
| P1-04 React Console | Center 后端提交、日志、取消已通过；浏览器端 E2E、工件查看和视觉/a11y 需要真实浏览器会话及对应 Desktop/Browser Agent | 不扩大 MCP 工具面；待具备前端发布版本和浏览器 capability 后执行最小 E2E、键盘可达性、错误态和工件下载校验 |
| P1-05 升级/回滚 | 历史在线 canary 有成功记录；离线领取、安装失败、失败重排队和回滚会改变 Agent 版本/运行状态，不能在无窗口时对生产注入故障 | 先保持现有升级活动只读观察；维护窗口内使用一台非关键 Agent 做离线领取和失败回滚，再扩大到批次策略验收 |

这意味着本轮已经把可安全执行的验收和一个真实代码缺陷收敛完成；剩余项目不是用更多只读请求可以替代的，需要对应平台、真实浏览器或明确维护窗口。

### P0-G3 子进程保护探针详情

在 `local-cmcc-debian` 上提交了受限的 64 子进程任务（任务 ID `task_432f…`，合同超时 30 秒）。Agent 仍保持在线，任务未成功执行并返回了通用 `Stream closed`，而运行时描述仍显示 process-tree、最大子进程 32。该结果证明保护动作发生，但错误解释不满足验收要求，因此 P0-G3 保持“部分通过”。

代码修复已将 `CommandRunner` 与 `BrowserTaskRunner` 的判断顺序改为：先读取
`ProcessResourceSupervisor.violation()`，再处理输出泵异常；这样在强制终止导致管道关闭时，
Center 能收到 `task process tree exceeded 32 processes` 之类的可解释原因。修复尚未构建、
发布或在生产复验，构建必须走 GitHub Actions。

## 2026-09-15 P2-03/P2-04 可观测性只读探针

生产 `/metrics` 返回 HTTP 200，Prometheus 文本中可见任务、队列、Agent 在线率、
工件容量、审计背压和升级失败率等指标（例如 `remote_connect_mcp_tasks_queue_depth`、
`remote_connect_mcp_machines_online_ratio`、`remote_connect_mcp_artifact_bytes`）。
同一 Admin Token 调用事件等待端点返回 HTTP 200，`changed=true`，说明事件游标可被
唤醒；响应未发现凭据字段。

结论：P2-04 的指标和事件唤醒生产探针通过，但通知出口、SLO 面板和告警演练仍待执行；
P2-03 的代码路径已存在，生产日志采集器、对象生命周期和成本压测仍待执行。

## 代码与 CI 回归证据

合并提交 `8e4839f`、稳定 tag `java-v0.1.22` 已在 GitHub Actions 完成大规模回归：

- 综合 CI `34937165824`：Go 测试/vet、四平台兼容构建、事件驱动检查和仓库卫生，成功；
- Java/React `34937165828`：PostgreSQL/Liquibase、JVM Ubuntu/Windows、React 构建、Linux amd64/arm64 Native 构建、MCP 冒烟和 Agent RSS 门禁，成功；
- 稳定 Native Release `34938856822`：Linux amd64/arm64、Windows amd64 Center/Agent/Desktop/Browser 资产、校验和、SBOM 与签名，成功；
- 本机没有执行 Java、Gradle、Native Image、React 或正式安装包构建。

## 发现与后续闭环

1. 生产基础链路已通过 GitHub Actions 发布并经 GitOps 收敛到不可变的 `v0.1.22`；已复验 `total/has_more`、MCP 工具发现、健康/就绪和新 Center PVC。
2. 生产命令闭环已通过一次低风险验证；第一次验证脚本因换行转义产生误报，修正后第二次按字节校验通过，不代表其他故障场景已经验收。
3. 下一批应在维护窗口内执行 Center/Agent 重启、断线恢复、长任务取消、资源超限、升级失败/回滚、离线领取和 WebSocket 反向代理演练；当前升级活动已完成，没有遗留 pending 目标。
4. Desktop/Browser 需要至少一台 Windows 和一台 Linux 目标机具备对应 capability，再执行截图、输入、会话恢复、浏览器 profile 和工件清理回归。

## 2026-09-16 v0.1.28 稳定发布与生产复验

本轮针对升级分配期间发现的 JDBC 行锁等待完成修复、发布和生产复验。Center
仍保持单一 JDBC/Hikari 数据访问层；没有在运行时混用 JDBC 与 R2DBC。Liquibase
继续在独立 migration Job 中使用 JDBC，后续若切换 R2DBC 必须作为整套应用数据层的
独立大版本迁移。

| 项目 | 结果 | 证据 |
| --- | --- | --- |
| 代码修复 | 通过 | `82510e7` 将升级 offer 的活动行锁改为 `FOR UPDATE SKIP LOCKED`；竞争心跳拿不到分配锁时立即返回，由下一次事件/心跳重试，避免占满 Hikari 连接 |
| GitHub Actions 稳定构建 | 通过 | tag `java-v0.1.28` 的 Java Native Release `35043403122` 成功；Linux amd64/arm64、Windows amd64、Center/Agent/Console 镜像、SBOM、签名和 smoke 全部通过；本机未编译 |
| GitOps/Argo 发布 | 通过 | GitOps 提交 `eee62d2`；应用 revision 收敛到该提交并报告 `Synced/Healthy/Succeeded`；Center、Console、migration Job 均使用 v0.1.28 不可变 digest |
| Center 健康与就绪 | 通过 | `/api/v1/healthz`、`/api/v1/readyz`、`/api/v1/version` 均 HTTP 200，版本为 `v0.1.28`，持久化为 PostgreSQL |
| MCP 协议面 | 通过 | Bearer 鉴权下 `initialize` 与 `tools/list` 均 HTTP 200，返回 SSE，会话建立成功，精简工具面保持 9 个；无 Bearer 的 `/mcp` 返回 401 |
| Admin metrics 脱敏 | 通过 | `/metrics` 使用 Admin 鉴权返回 200，18 个指标名、约 3.9 KiB；敏感词扫描未发现 Token/Authorization 内容 |
| 数据库连接稳定性 | 通过 | 生产只读 `pg_stat_activity`：9 个连接、1 active、0 lock wait、0 idle-in-transaction；Center 最近日志无连接池耗尽或异常 |
| Agent 升级批次 | 通过 | 新建 v0.1.28 活动，9/9 目标完成、0 failed、0 pending；canary 成功后自动放行后续批次；原 v0.1.27 暂停活动已取消并保留审计记录 |
| v0.1.28 命令闭环 | 通过 | 一台在线 Agent 执行 `/tmp` path 范围内固定 smoke 命令，状态 completed、Attempt 1、exit code 0、输出 18 字节，并通过事件游标获得终态 |
| v0.1.28 资源硬上限 | 通过 | 同一 Agent 在 `/tmp` 受控启动 64 个短时子进程，任务以 exit code 143 收口，错误明确为 `task process tree exceeded 32 processes`，未出现 `Stream closed` 误归类 |

本轮结论：JDBC 锁竞争的生产缺陷已经闭环，Center 和全部 9 台在线 Agent 已运行
`v0.1.28`。P0-G2 的 Center 重启/ChatGPT 重试、P1-02/P1-03 桌面/浏览器真实平台、
P1-06 有效 WebSocket 断线恢复，以及离线/失败/回滚升级仍按前述条件保持待验收；这些
不是本轮只读健康检查可以替代的项目。

### 2026-09-16 后续只读复核

在不修改生产资源、不重启 Center、不注入升级故障的前提下再次复核：

| 项目 | 结果 | 证据 |
| --- | --- | --- |
| Gateway 路由 | 通过 | Java Center 与 Console 的生产 HTTPRoute 均被 Gateway 接受；健康、就绪和版本端点继续 HTTP 200 |
| MCP 工具发现 | 通过 | Streamable HTTP 会话可建立，`tools/list` 返回 9 个精简工具：`machines_list`、`machine_info`、`project`、`desktop`、`browser`、`command_start`、`task_wait`、`task_output`、`task_cancel` |
| 认证边界 | 通过 | 未携带凭据访问 `/mcp`、Admin API 和 `/metrics` 均返回 HTTP 401；已认证调用未返回敏感内容 |
| 事件驱动等待 | 通过 | 使用当前事件游标等待 1 秒返回 `changed=false`，未使用固定间隔轮询；事件游标保持连续 |
| Agent 收敛 | 通过 | 9/9 Agent `online=true` 且版本为 `v0.1.28`；运行时 `max_child_processes=32`、`resource_enforcement=process-tree` |
| 数据库空闲状态 | 通过 | PostgreSQL 只读复核为 `1 total / 1 active / 0 lock wait / 0 idle in transaction` |
| 幂等重试 | 通过 | 同一幂等键连续提交两次固定 `printf` 任务，Center 返回同一 `task_id`；最终状态 completed、Attempt 1、exit code 0 |
| 运行日志 | 通过 | 最近 15 分钟 Center/Console 日志无 ERROR、Exception、OutOfMemory 或连接池耗尽记录 |
| 桌面/浏览器条件 | 未通过验收 | 当前 9 台 Agent 均只声明 `command,durable_tasks`，桌面会话和浏览器会话均不可用；因此不把工具已注册误判为能力已上线 |

本次复核没有改变待验收边界：需要真实 Windows 登录桌面、浏览器运行时、有效
WebSocket 反向代理握手、离线/失败/回滚升级及 Center 重启的项目，仍需安排专门目标和
维护窗口后再验收。

### 2026-09-16 随机 Agent 现场任务验收

从在线清单随机选择目标，通过真实 `/mcp` 会话完成以下无副作用任务；命令只输出固定
标记或等待，不读取文件、不改变配置：

| 场景 | 结果 | 证据 |
| --- | --- | --- |
| Windows 命令 | 通过 | `echo` 命令 completed，exit code 0，Attempt 1 |
| Windows 长输出 | 通过 | 生成 4,000 行固定文本，共 82,893 字节；按 16 KiB 页读取 6 页，最终游标 82,893，`more=false` |
| Windows 超时 | 通过 | 1 秒合同下运行 8 秒等待命令，状态 failed，错误为 `task duration exceeded 1 seconds`，Attempt 1 |
| Linux arm64 运行中取消 | 通过 | `task_wait` 先观察到 running，再收到 `cancel_requested`；最终状态 canceled、Attempt 1，未留下运行进程 |
| 现场任务清理 | 通过 | 本轮验收任务均已进入终态；复核时 Center `queued=0`、`running=0`，没有遗留执行进程 |

现场验收结论：命令 Agent 的入队、运行、输出分页、超时和运行中取消均可经 MCP
完成闭环；桌面和浏览器因目标未声明对应 capability，继续保持待验收。

### 2026-09-16 Windows Desktop/Browser Agent 部署验收

选择具备 Windows 桌面环境的 `local-ly-windows11` 作为第一台真实目标。部署过程只
使用 GitHub Actions 已发布的 `v0.1.28` Native ZIP，不在本机编译；目标机安装一个
Java command-agent，并在同一安装目录下启用独立 desktop-companion 与 browser-agent，
没有新增 machine ID 或第二套身份。浏览器运行时使用目标机共享的 Playwright/Chromium
缓存，避免 SYSTEM 会话找不到按用户安装的浏览器。

| 项目 | 结果 | 证据与边界 |
| --- | --- | --- |
| Java command-agent 替换 | 通过 | 目标 Agent 上报版本 `v0.1.28`，Center 状态 online；旧 Go Agent 不再承载该身份 |
| Desktop bundle/任务 | 已部署 | `rcm-desktop-companion.exe` 已安装；`RemoteConnectMCPDesktopCompanion` 任务为 Ready、登录用户触发；当前没有交互用户会话，Center 报告 `desktop_session_available=false`，因此未伪造截图/输入通过 |
| Browser bundle/运行时 | 通过 | `rcm-browser-agent.exe`、Node 22、Playwright 1.63.0 和 Chromium 共享缓存已安装；Center 上报 `browser_adapter_configured=true` |
| Browser MCP navigate | 通过 | 真实 `/mcp` `browser` 任务在该目标完成 `navigate https://example.com`，HTTP 200、标题 `Example Domain`，状态 `completed`、Attempt 1、退出码 0；输出 169 字节且无敏感字段 |
| 进程回收 | 通过 | Browser 任务结束后由 command-agent 回收 browser-agent/Node 子进程；目标并发仍为 1，进程树上限 32 |

本节结论：该 Windows 目标已经具备可用的 Browser Agent；Desktop 组件和登录触发链路
已部署，但只有 `LY` 登录到图形会话后才能继续完成 screenshot、screens、launch、click
和键盘输入验收。此前“9 台均无 desktop/browser capability”的历史记录仅代表复核时点，
不覆盖本节之后新增的 canary 目标。

### 2026-09-16 Linux GUI Desktop/Browser Agent canary 复验

本轮使用 GitHub Actions 产出的 arm64 preview `v0.0.0-main.57`（提交
`2868934`，run `35060687497`）作为 canary；三平台 Native、JVM、PostgreSQL/Liquibase、
镜像、SBOM、签名和 release jobs 均成功。本机没有执行 Java、Gradle 或 Native 构建。
目标为 `local-cmcc-debian`（Debian 12 arm64、Plasma/X11），只替换 Desktop Companion
可执行文件和旁路库，保留本机 endpoint、token、policy；升级前已保留回滚副本。

| 项目 | 结果 | 证据与边界 |
| --- | --- | --- |
| Desktop Companion 部署 | 通过 | 新二进制与 CI 资产 SHA-256 一致；`remote-connect-mcp-desktop.service` 重启后 `active`、`MainPID` 正常、无重启计数 |
| Desktop 本机 IPC | 通过 | `screens` 返回 1 个 `1920×1080` 屏幕；`screenshot(screen=0)` 返回有效 PNG，约 6 KiB |
| Desktop Center/MCP screens | 通过 | 真实 `/mcp` 任务经历 `queued → running → completed`，退出码 0，输出 65 字节，屏幕描述与目标一致 |
| Desktop Center/MCP screenshot | 通过 | 真实 `/mcp` 任务完成，生成 `image/png` 工件约 629 KiB，Center 返回工件大小、MIME 和 SHA-256 元数据 |
| Browser Agent 回归 | 通过 | 同一目标真实 `browser navigate https://example.com` 完成，退出码 0，标题 `Example Domain`，输出 168 字节 |
| Center 自描述 | 通过 | machine info 上报 `online=true`、`v0.1.28`、`command,durable_tasks,desktop,browser`，`desktop_session_available=true`、`browser_session_available=true` |
| 资源清理 | 通过 | Desktop 服务保持单进程；Browser 任务结束后无 `rcm-browser-agent`/`browser-worker.mjs` 残留；当前错误日志无新增条目 |

本节结论：Linux GUI canary 的 Desktop screens/截图和 Browser navigate 已通过真实
Center 路由；Windows Desktop 仍需用户登录交互会话后完成输入/启动/截图，Browser 的
snapshot/下载/持久 profile 仍属于后续矩阵。preview 观察通过后已提升为稳定
`v0.1.29`，稳定资产收口见下节。

### 2026-09-16 v0.1.29 稳定 Desktop 资产收口

Linux GUI canary 观察通过后，将同一代码提交 `2868934` 提升为稳定 tag
`java-v0.1.29`。GitHub Actions run `35064539692` 的 prepare、JVM/React、Linux
amd64/arm64、Windows amd64、镜像和 release jobs 全部成功；本机未执行构建。
稳定 arm64 Desktop ZIP 已按 sidecar SHA-256 校验并原子替换 `local-cmcc-debian`，
保留上一版回滚副本。

| 项目 | 结果 | 证据 |
| --- | --- | --- |
| 稳定 Desktop 资产 | 通过 | `remote-connect-mcp-desktop-v0.1.29-linux-arm64.zip` 校验通过；目标二进制 SHA 与资产一致 |
| 服务与回滚 | 通过 | `remote-connect-mcp-desktop.service` 为 `active`、`NRestarts=0`；`desktop-...-v0129` 回滚副本保留 |
| 稳定 MCP screens | 通过 | `/mcp` 任务 `queued → running → completed`，退出码 0，返回 1 个 `1920×1080` 屏幕 |
| 稳定 MCP screenshot | 通过 | `/mcp` 任务完成，返回 `image/png` 工件约 629 KiB，并带 SHA-256 元数据 |
| 稳定 Browser 回归 | 通过 | 同一目标 `navigate https://example.com` 完成，标题 `Example Domain`，退出码 0 |

本节结论：Desktop 的 Linux AWT/Java2D/X11 元数据缺口已在稳定 `v0.1.29` 中闭环，
并经真实 Center 路由复验。稳定版本只替换了 GUI canary；Windows Desktop 仍需真实
交互登录会话完成输入/启动/截图，其他目标的全量升级按后续维护窗口执行。
