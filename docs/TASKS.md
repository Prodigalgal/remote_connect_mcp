# RCM 生产任务清单

更新时间：2026-09-14（Asia/Shanghai）

本文是 Remote Connect MCP 的可持续任务清单。任务只有在代码、CI 和目标环境验收证据齐全后才可以勾选；“部分完成”保持未勾选，避免把骨架实现误认为生产能力。

需求基线：[`REQUIREMENTS.md`](REQUIREMENTS.md)；当前实现证据：[`STATUS.md`](STATUS.md)；架构约束：[`ARCHITECTURE.md`](ARCHITECTURE.md)；异步契约：[`ASYNC_CONTRACT.md`](ASYNC_CONTRACT.md)。

## 状态规则

- `[x]` 已完成：实现、自动化检查和必要的目标环境验收均有证据。
- `[ ]` 待办：尚未实现，或实现后仍缺少生产验收。
- `部分完成` 不是独立状态；对应任务仍保持 `[ ]`，直到剩余项和验收全部完成。
- 每项完成后，在“验收证据”列补充 CI run、测试、部署版本或目标机验证记录，再将 `[ ]` 改为 `[x]`。
- 不在本机编译 Java、Native Image、React 或正式安装包；构建证据必须来自 GitHub Actions。

## P0：平台必须可靠

| 状态 | 编号 | 任务 | 当前情况与完成条件 | 验收证据 |
| --- | --- | --- | --- | --- |
| [x] | P0-01 | 固定 MCP 地址和多机器路由 | `/mcp`、Bearer 和按 machine ID 路由已可用；后续不因 Center/Agent/Console 升级改变连接器地址 | v0.1.21 MCP/health/ready 验收 |
| [x] | P0-02 | 机器注册与凭据分层 | 一次性 Enrollment Token、独立 Agent Token、稳定 MCP Token、独立 Admin Token 已实现；Enrollment 不写入长期配置 | 注册与身份测试、生产 Secret |
| [ ] | P0-03 | 异步任务全链路恢复 | 已有幂等、租约、Attempt、旧 attempt 回传栅栏、取消、输出游标和 LISTEN/NOTIFY；仍需完成 Center 重启、Agent 断线、重复重试、长任务和高并发正式演练 | 需补充故障演练报告和重启后任务状态证据 |
| [x] | P0-04 | PostgreSQL + Liquibase 唯一事实来源 | 生产数据库、Liquibase `001`–`011`、旧 Go 状态导入和迁移 Job 已验证；内存仅用于测试/短期唤醒 | PostgreSQL/Liquibase CI 与生产迁移记录 |
| [ ] | P0-05 | 任务与工件的持久化边界 | 已完成 `ArtifactStore` 抽象、独立持久卷文件对象、原子写入/读取校验、旧 `artifact_data` 懒迁移和显式 GC API；仍需完成生产卷备份/恢复、孤儿对象扫描、保留策略演练和 CI/目标环境验收 | 对象存储适配、迁移/恢复、生命周期测试 |
| [ ] | P0-06 | 执行范围与权限合同 | 已增加 project/worktree/path/workspace/unrestricted 会话模型；unrestricted 必须显式授权；任务持久化 machine、host、scope、capability、预算、过期和 lease；Center 与 Agent 双重校验；合同预算可进一步收紧 Agent 的输出/工件/时长上限 | 仍需 GitHub Actions 编译、数据库迁移、绕过测试和目标机回归 |
| [ ] | P0-07 | Agent 资源硬限制 | 已加入任务级进程树/墙钟监督、CPU 时间与 Linux RSS 可选硬边界，并保留输出/磁盘/并发上限；仍需补 Windows Job Object/cgroup 部署策略、总预算与目标机压测 | Linux cgroup/Windows Job Object 或等效实现、目标机压测 |
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
| [ ] | P1-01 | Project Registry 与 Git worktree 闭环 | 注册、创建/删除和 cwd 解析已有；补齐 commit、diff、变更预览、显式 merge、冲突和权限回归 | 项目/Agent 目标机 Git 测试 |
| [ ] | P1-02 | Windows/Linux Desktop Companion 真实能力 | 基础截图、屏幕枚举、启动、输入和剪贴板已有；补齐 Windows 多会话/UAC/RDP、Linux X11/Wayland、多显示器和会话失效恢复 | 真实目标机矩阵、截图/输入工件 |
| [ ] | P1-03 | Browser Agent 生产运行时 | Worker 监管、结构化引用、有界快照、下载工件和清理已有；补齐目标浏览器安装、持久 profile、登录态、stale ref 恢复和 Playwright/Patchright/Comoufox 回归 | 目标机浏览器矩阵、长任务和清理报告 |
| [ ] | P1-04 | React 控制台完整工作流 | 基础机器、项目、任务、令牌、升级页面已有；补齐范围/会话/桌面/浏览器任务编排、审计详情、分页/虚拟化、实时状态、无障碍和视觉回归 | Console E2E、a11y、视觉 CI |
| [ ] | P1-05 | 升级 offer/attempt 与兼容回滚 | canary、批次、SHA-256、原子替换和回滚已有；补齐迟到报告/CAS、版本兼容矩阵、离线补升级和失败暂停/重排队 | 多 Agent 并发升级、回滚和兼容测试 |
| [ ] | P1-06 | WebSocket/事件唤醒生产验收 | wake-only WebSocket、指数重连、HTTPS 回退和 PG 通知桥接已有；补齐真实反向代理、多副本、序列号、断线和 Center 重启演练 | 代理/多副本故障演练 |
| [ ] | P1-07 | 配置与心跳自描述 | generation、并发和退避热更新已有；补齐能力、范围、资源、浏览器/桌面状态的版本化自描述和热更新回滚 | Agent 心跳 schema、配置回滚测试 |
| [ ] | P1-08 | 终端与子 Agent 生命周期 | 默认一个 command-agent 身份，桌面/浏览器作为子组件；补齐多物理 Agent 的显式隔离、互斥、回收、崩溃拉起和 Center 视图 | 主机多 Agent、进程树和 Token 隔离测试 |
| [ ] | P1-09 | 审计与错误可解释性 | 记录请求来源、范围、风险、审批、重试、升级和失败原因；敏感字段脱敏且可按 task ID 追踪 | 审计查询、脱敏抽样和故障报告 |

### P1 完成门禁

- [ ] P1-G1：至少一台 Windows 和一台 Linux 目标机完成 Desktop/Browser 真实操作回归。
- [ ] P1-G2：控制台可以从选择机器开始，完成范围选择、任务提交、日志查看、工件查看和取消。
- [ ] P1-G3：升级活动在在线、离线、迟到报告和启动失败情况下都能安全收口。
- [ ] P1-G4：反向代理下的 WebSocket、长轮询和 Center 重启均能恢复，不依赖固定间隔轮询。

## P2：规模化增强

| 状态 | 编号 | 任务 | 当前情况与完成条件 | 验收证据 |
| --- | --- | --- | --- | --- |
| [ ] | P2-01 | QUIC/HTTP3 传输评估与实现 | 当前未实现；只有在 WebSocket/HTTPS 已证明存在瓶颈且收益明确时引入 | 基准、灰度和回退报告 |
| [ ] | P2-02 | Center 多副本与高可用 | 当前生产保持单副本稳定路径；补齐多副本、PDB、HPA、NetworkPolicy、租约和通知一致性 | K8s 故障切换与长任务演练 |
| [ ] | P2-03 | 集中日志与对象存储规模化 | P0 先完成工件存储抽象；本项补齐集中日志、对象生命周期、GC、检索和成本控制 | 存储/日志压测和保留策略 |
| [ ] | P2-04 | SLO、告警和升级通知 | 已有基础 metrics/Prometheus 告警；补齐任务成功率、排队延迟、断线恢复、资源和升级失败 SLO 面板/通知 | 监控规则、演练和通知记录 |
| [ ] | P2-05 | 多用户/多租户权限模型 | 当前不是核心交付；仅在确有组织隔离需求时设计租户、角色、审计和配额 | 权限模型评审和隔离测试 |
| [ ] | P2-06 | 更丰富的桌面和浏览器平台 | 扩展桌面环境、浏览器版本和平台适配，但不扩大 MCP 原始工具数量 | 平台兼容矩阵和资源报告 |
| [ ] | P2-07 | 旧 Go 回滚路径退出 | P0/P1 兼容窗口、数据库恢复和 Java 回滚验证完成后，再归档 Go workflow、Deployment、Service/PVC 和旧代码 | 变更审批、回滚窗口关闭记录 |

## 当前最短生产路径

按依赖关系，下一轮应依次处理：

1. `P0-06` 执行范围与权限合同；
2. `P0-05` 工件存储与生命周期；
3. `P0-03`、`P0-07`、`P0-10` 可靠性、资源和离线 Agent 验收；
4. `P1-01` 至 `P1-06` 的真实项目、桌面、浏览器、控制台和升级流程；
5. `P1-07` 至 `P1-09` 的热更新、生命周期和审计；
6. 最后进入 P2 的 QUIC、HA、集中日志和旧 Go 退出。

未完成 P0 门禁前，不应宣称“完全替换旧版”；未完成 P1 门禁前，不应宣称“完整生产体验”；P2 是规模化路线，不阻塞单 Center 生产运行。
