# RCM 迁移状态

更新时间：2026-09-12（Asia/Shanghai）

本文只记录仓库代码与当前集群只读探针能够证明的状态。没有通过生产门禁的内容不会标记为“已上线”。

## 结论

Java 25 Center/Agent 与 React 控制台已经形成可独立验收的迁移候选版本，协议、异步任务、桌面伴侣、Browser Worker 入口、一次性注册、热配置和升级编排均已写入代码；历史上曾有本地 JVM/Windows amd64 Native Image 验收记录，但当前策略是不在开发机编译或测试，所有验证由 GitHub Actions 重新执行。当前生产流量仍由旧 Go Center 承载；Java 尚未切换到 Kubernetes。

## 已完成实现与历史证据

| 领域 | 当前实现 | 证据 |
| --- | --- | --- |
| Java 工程 | `protocol`、`center`、`agent` Gradle 多模块，Java 25 toolchain | 过去的构建记录；当前由 GitHub Actions 重跑 |
| MCP | Streamable HTTP `/mcp`、Bearer 校验、精简工具面、分页结果 | 过去的 `initialize`/`tools/list` 烟测记录；当前由 GitHub Actions 重跑 |
| 任务可靠性 | 异步入队、幂等键、租约、取消、输出游标、断线重连、有界 spool、无超时任务恢复；过期租约区分可恢复持久任务与不可安全重放的定时任务 | Java Agent/Center 单元测试通过 |
| 注册与身份 | 一次性 Enrollment Token，注册后换取每 Agent 日常 Token；身份文件原子写入 | Agent/Center 测试通过 |
| 配置热更新 | Center 下发 generation、轮询间隔和并发槽位；Agent 原子落盘并只接受更新代次 | `AgentRuntimeSettingsTest`、`AgentConfigurationServiceTest` |
| Desktop | 同一安装包的用户会话 companion；截图/屏幕枚举、启动、点击/拖拽、组合按键、剪贴板、窗口聚焦和文本输入通过受保护 loopback IPC | `DesktopCompanionClientTest` 与协议测试通过 |
| Browser | Agent 负责生命周期、超时、日志和工件；通过本机适配器命令接入 Worker，以临时 JSON 请求文件传递结构化任务，并支持受目录约束的单工件清单上传 | `BrowserTaskRunnerTest`、`BrowserTaskRunner` |
| 升级 | Center canary/批次状态机，HTTPS + SHA-256，Agent Helper 原子替换和回滚；发布工作流资产名已与解析器对齐 | `UpgradeServiceTest`；`.github/workflows/java-release.yml` 静态校验 |
| 控制台 | React/Vite 经典后台布局，机器、项目/worktree、任务、令牌、升级和设置页面；全局搜索、机器在线筛选、任务状态筛选和任务输出 16 KiB 游标分页查看；Admin Token 只在当前标签页内存 | 过去的 `pnpm build` 记录；当前由 GitHub Actions 重跑 |
| 数据库 | PostgreSQL 适配器与 Liquibase `001`–`008` changelog；内存模式仍用于协议回归；发布工作流带 PostgreSQL 16 服务容器集成、备份和恢复门禁 | Liquibase 资源/迁移单元测试通过；CI `PostgresIntegrationTest` 会覆盖注册、项目/worktree、幂等任务、租约、输出续传和工件往返，随后执行 custom-format dump/restore |
| 发布脚本 | Java JVM 构建、Native Image 门禁脚本、Windows/Linux Agent 安装器、Java Center/Agent JVM/Native 烟测脚本，以及 Windows/Linux WebSocket wake 烟测 | 当前只做静态校验；Native Image、完整原生烟测、Agent RSS 资源报告和仓库卫生扫描交给 GitHub Actions，Windows/Linux 安装器均支持 CI 平铺 ZIP + 旁路库 |

## 部分实现或仍需补齐

1. Native Image：Windows amd64 的 Center/Agent 原生链路此前曾在本机构建并通过烟测；当前策略是不在开发机编译，GitHub Release 工作流已加入 OIDC Artifact Attestation，并改为在匹配架构的 `ubuntu-24.04`/`ubuntu-24.04-arm` runner 上构建和执行 Linux amd64/arm64 烟测，再由 Windows runner 构建 Windows amd64。发布工作流现在会为每个平台生成 SPDX SBOM、对发布包/校验文件/SBOM 生成 Sigstore keyless 签名，并把签名材料随 GitHub Release 发布；正式 tag Release 尚未执行。Windows/Linux Agent 发布物均为包含旁路运行库的平铺 ZIP，旧裸可执行文件保留回退；Windows ARM64 暂保留 JVM/Go 兼容路径。
2. PostgreSQL：CI 已加入真实 PostgreSQL 16 服务容器的迁移/注册/任务/输出/工件往返及 custom-format 备份恢复门禁，并覆盖幂等并发与过期租约恢复；仍需补齐并发抢占、Center 重启场景和长输出压测，生产库恢复演练尚未执行。
3. Browser Agent：Java Agent 已提供参考 `scripts/browser-worker.mjs`，可按环境加载 Playwright/Patchright/Comoufox，并支持 CSS/role/label/placeholder/text/test-id 结构化定位，返回有界快照、动作结果、截图、下载工件以及脱敏的网络/控制台/页面错误摘要；仍需在目标平台安装浏览器运行时并补齐稳定元素引用、持久会话和跨浏览器回归。
4. Desktop Agent：基础截图、屏幕枚举、输入、剪贴板和 Windows 窗口聚焦已具备；Linux 窗口管理器差异、多显示器真实会话、UAC/权限场景和跨桌面回归仍需专门验收。
5. 长连接：已实现可选 WebSocket wake-only 通道、客户端指数重连和 HTTPS 回退；仍需在真实反向代理/多副本环境完成灰度、序列号关联和故障演练，QUIC 尚未实现。
6. Project Registry/Git worktree：已实现按 Agent 归属的项目注册、项目根/仓库路径边界、异步 `git worktree add/remove`、幂等键和项目/worktree 任务 cwd 解析；Center 不读取仓库内容，Agent 仍执行最终真实路径与权限校验。提交/差异审阅、显式合并、实际目标机 Git/权限回归仍待补齐。
7. 控制台：基础管理流程、项目/worktree、全局搜索/基础筛选和有界任务输出查看可用，实时推送、审计详情和无障碍/视觉回归门禁尚未达到生产级完整度。
8. 可观测性：Java Center/Go 基线均提供 Admin 鉴权的有界 `/metrics` 和脱敏日志约定，但还没有在集群接入告警规则、SLO、集中日志和升级失败通知。

## 当前生产阻塞（已用只读探针确认）

- 集群上下文为 `kubernetes-admin@sg-osaka-dualstack`；`remote-connect-mcp` 命名空间当前运行的是旧 Go Center，镜像仍为 `docker.io/speedproxy/remote-connect-mcp-center` 的旧 digest。
- 现有生产 HTTPRoute 的真实域名不记录在仓库；尚未切换到 Java 模板的 `remote-connect-mcp-*` 新路由组合。
- Java 基础 Kustomize 模板仍保留占位值；仓库已新增 `deploy/k8s/overlays/java-production`，使用三个
  `remote-connect-mcp-*.example.invalid` 占位域名并切换 GHCR 镜像标签，但正式 GitOps 仍需在私有
  部署层替换真实域名、把标签替换为已验证 digest、创建集群外 PostgreSQL/Token Secret、执行迁移
  Job 并保留回滚点。
- 两台可调度 Oracle 节点为 arm64；尚未完成 Linux arm64 Java Center Native Image、镜像推送、PostgreSQL 迁移/导入、Agent 灰度、MCP 连接器验收，因此没有执行生产替换。

## 资源占用说明

- 本次重启后看到的数 GiB Java 进程来自 Native Image 编译器（命令行包含
  `--image-args-file`），不是目标机常驻 Agent；当时 Center/Agent 两个编译进程的工作集约为
  3.8 GiB/6.2 GiB，Gradle 守护进程约 340 MiB。它们已停止，当前工作区没有 Java 构建进程。
- 原因是 Native Image 编译峰值高且旧调用同时提交 Center、Agent；脚本和 GitHub Actions 现已使用
  `--no-parallel` 串行编译。今后不在开发机或目标宿主机编译 Native Image。
- 运行时预算与编译峰值分开：Java Agent 不依赖 Spring，空闲只有心跳循环；默认并发 1、单任务输出
  64 MiB、聚合 spool 64 MiB。Native Agent 的目标 RSS 必须在 CI/目标平台用同一版本实测；当前没有把
  编译进程的内存数字冒充运行时测量。Go 基线仓库内 Linux Agent 文件大小为 6,537,378 字节，但这
  只是磁盘体积，也不能替代同场景 RSS 对比。
- `scripts/smoke-java-agent.sh/.ps1` 在 Agent 在线和任务闭环期间采样工作集峰值；Release/迁移工作流
  将 JSON 作为私有 Actions 工件上传，不放入公开 Release，也不把单次 CI 峰值直接当作宿主机硬限制。

## 下一步生产顺序

1. 在匹配架构的 CI/构建机完成 Linux Native Image、native smoke、SHA-256、SBOM、签名和服务安装/回滚验收。
2. 准备独立 PostgreSQL 数据库，执行 Liquibase `validate/update`、旧 Go 状态导入、备份恢复和并发测试。
3. 推送 Center/Agent/Console 镜像并生成私有 GitOps overlay；先以新 Service/域名旁路验收，不覆盖旧 Go Deployment。
4. 用一台非关键 Agent 做注册、命令、断线续传、Desktop/Browser、升级回滚验收；随后按 canary/批次迁移其余 Agent。
5. 通过公网 MCP `initialize`、`tools/list`、ChatGPT Web、控制台、监控和故障演练后，才切换旧 HTTPRoute；保留可回滚的旧 Go 资源与数据库备份。
