# Java/React 生产发布门禁

产品需求基线：[`docs/REQUIREMENTS.md`](REQUIREMENTS.md)。本文只描述构建、发布和部署验收，不定义新的产品范围。

Java Center/Agent 的目标发布物是 Java 25 Native Image；JVM JAR 只作为诊断和回退包。所有测试、JVM 包、React 资源和 Native Image 均由 GitHub Actions 完成；开发机和目标宿主机不执行任何编译或打包，避免 Native Image 峰值占满内存。

## 构建

提交源码后推送 `main` 或 `java-vX.Y.Z` 标签即可触发工作流：

```text
git push origin <branch>
git tag java-vX.Y.Z
git push origin java-vX.Y.Z
```

`.github/workflows/java-react.yml` 负责 PR/main 的 JVM、React、PostgreSQL、Liquibase 和 Native
门禁；`.github/workflows/java-release.yml` 在 `main` 推送时额外构建并发布不可变的
`java-v0.0.0-main.<run>` 预发布版本，在 `java-vX.Y.Z` 标签时发布稳定版本。两种 Release 都经过
匹配架构 Native Image、烟测、校验、SBOM 和签名；预发布不会移动 GHCR 的 `latest` 标签。开发机
不需要安装或运行 Gradle、JDK、GraalVM、Go、Node 或 pnpm。烟测脚本只接受已经从 Actions 下载的
JAR/Native Image，不会自行触发构建。

两个工作流都会先执行 `scripts/scan-repository-secrets.sh`。检查只输出命中文件名，不会把令牌或
私钥内容写入日志；真实域名、内网地址和部署 Secret 必须留在集群外的私有配置层。

产物名为 `rcm-center`、`rcm-agent`、`rcm-desktop-companion` 和 `rcm-browser-agent`。其中后三个 Agent
目标分别隔离命令、用户桌面和浏览器适配器生命周期；当前仓库的 Linux Native Docker builder 固定为
`ghcr.io/graalvm/native-image-community:25@sha256:0d936f32bb8acb5bc60c41b33e05f064d7a6aaf36b726538296c54949bd4a3c0`；更新构建器时必须同步更新两个 Dockerfile、重新跑全套 Native smoke，并记录新的 digest。当前 GraalVM/NIK 25 的可验证 Native Image
矩阵是 Linux amd64/arm64、Windows amd64；Windows arm64 会被脚本明确拒绝，暂时只能
使用 JVM/Go 兼容包，不能把交叉编译结果当作原生生产物。每个支持目标都必须分别构建
并附带 SHA-256、SBOM 和签名；没有通过 native smoke test 的产物不得发布。Windows Native Image 的
GraalVM 运行时 DLL 会分别与 `rcm-center.exe`、`rcm-agent.exe` 放入各自 bundle，并逐个生成 SHA-256；不能
只分发裸 `.exe`，否则桌面/运行时所需 DLL 可能缺失。Center 与 Agent 不共用 Windows DLL 目录，避免
不同 Native Image 链接结果的同名 `java.dll`/`jvm.dll` 互相覆盖。Linux Native Dockerfile
同样复制完整 `nativeCompile/` 运行目录，使 `libawt`、字体和其他 `.so` 旁路库与对应 ELF
一起进入镜像；不能只把 `rcm-center`/`rcm-agent` 单文件复制到最终层。

仓库保留 `scripts/build-java.*`、`scripts/build-native.*` 和 `scripts/build-all.*` 作为 CI 内部兼容入口；这些脚本在非 GitHub Actions 环境会安全退出，不会在本机启动编译。Native 构建仍由工作流按当前 OS/架构输出 Center 加三个 Agent 二进制、`.sha256`、manifest、SBOM 和安装归档；Linux 会把各目标可能生成的 `.so` 与对应 ELF 放入隔离 bundle，Windows 同样生成隔离 bundle、三个平铺 ZIP 及完整 bundle ZIP。

`java/Dockerfile.*.native` 和 `web/Dockerfile` 同样要求构建参数 `RCM_CI_BUILD=true`；工作流会自动注入，本机直接 `docker build` 会在编译前拒绝执行。

Windows 安装器 `scripts/install-java-agent.ps1` 的 `-BinaryPath` 可直接接收平铺 command-agent ZIP（推荐），也兼容与 DLL 同目录的原始 `rcm-agent.exe`；`-DesktopBinaryPath` 和 `-BrowserBinaryPath` 可分别接收两个独立 companion ZIP/EXE，安装到隔离子目录，避免 `java.dll`/`jvm.dll` 同名覆盖。它会在复制完整 bundle 前停止旧服务和桌面计划任务，避免只替换 exe 造成运行时 DLL 不匹配。

正式发布使用 `.github/workflows/java-release.yml`：推送 `main` 或 `java-vX.Y.Z` Tag 后，CI 先执行
JVM/React 门禁，再在匹配架构的 GitHub-hosted runner（`ubuntu-24.04` 与
`ubuntu-24.04-arm`）上构建并执行 Linux amd64/arm64 Native Image 烟测，随后在 Windows
amd64 runner 上构建 Windows Native Image。Release 同时
上传安装压缩包和按 `remote-connect-mcp-agent-vX.Y.Z-<os>-<arch>` 命名的 Agent
升级资产及 `.sha256`，供 Center 自动升级解析；Linux/Windows 资产都是包含 Agent 可执行文件与
Native Image 运行库的同名平铺 ZIP（旧版本 Linux/Windows 裸可执行文件仍可回退）。Windows 主安装包另外包含 `center/`、`agent/`、`desktop/` 与 `browser/` 四个
隔离 bundle。main 推送会创建预发布 Release，供 Center 版本目录选择；稳定 Tag 会创建正式 Release；
手动运行工作流只构建，不创建 Release。

Linux 完整 tar 包的根目录包含 `install-java-agent.sh` 和匹配版本的
`remote-connect-mcp-agent.service`；从 tar 根目录运行
`REMOTE_CONNECT_MCP_AGENT_BINARY=./agent/rcm-agent ./install-java-agent.sh`，或把平铺 Agent ZIP
设置为 `REMOTE_CONNECT_MCP_AGENT_BINARY`。需要桌面/浏览器能力时，再设置
`REMOTE_CONNECT_MCP_AGENT_DESKTOP_BINARY=./desktop/rcm-desktop-companion.zip` 和
`REMOTE_CONNECT_MCP_AGENT_BROWSER_BINARY=./browser/rcm-browser-agent.zip`；安装器会将两个
companion 放到隔离目录，桌面伴侣仍应由用户会话自启动。安装器会在每个 ZIP 旁存在 `.sha256`
时先校验，再复制 ELF 及其 `.so` 旁路库，并使用包内 systemd 模板（也可用
`REMOTE_CONNECT_MCP_AGENT_SERVICE_FILE` 显式覆盖）。

## PostgreSQL 与 Liquibase

生产 Center 设置（包含 `006-agent-config` 的 Agent 热更新字段）：

```text
RCM_CENTER_PERSISTENCE_MODE=postgres
RCM_CENTER_REQUIRE_DURABLE_STORAGE=true
RCM_CENTER_LIQUIBASE_ENABLED=false
RCM_CENTER_DATABASE_URL=jdbc:postgresql://<host>:5432/remote_connect_mcp
RCM_CENTER_DATABASE_USERNAME=<user>
RCM_CENTER_DATABASE_PASSWORD=<password>
```

Java 发布工作流使用 `java-vX.Y.Z` 作为 Git Tag，但升级活动中填写的版本保持
`vX.Y.Z`。因此生产 Center 默认使用 `RCM_CENTER_RELEASE_TAG_PREFIX=java-`，它只影响
Release URL 的 Tag 路径，不会改变 Agent 原始资产名。若改用普通 `vX.Y.Z` Tag，显式将
该变量设为空字符串。

Center 的 `GET /api/v1/admin/releases?include_prerelease=true` 在控制台进入时或显式刷新时读取
GitHub Releases API，成功结果缓存两分钟并以 `stale=true` 标记过期快照；没有后台固定轮询。目录只
接受 `java-v<semver>` 标签，忽略草稿，并仅返回预期的 Linux amd64/arm64、Windows amd64 Agent
ZIP 与 checksum 是否存在。创建升级活动时，Center 仍会再次按版本/平台下载并校验 `.sha256`，不会
信任前端提交的任意下载地址。

发布工作流的 JVM/React 门禁同时启动临时 PostgreSQL 16 服务容器，执行
`PostgresIntegrationTest`：真实运行 Liquibase、注册/心跳、幂等任务、租约领取、输出游标
续传、断点重放、工件写入和终态更新。GitHub Actions 是该集成门禁的唯一执行入口；开发机
不运行 Gradle 或 PostgreSQL 集成测试，也不会把离线 changelog 解析误报成 PostgreSQL 验收。

### 备份与恢复

正式切换前必须保存 PostgreSQL custom-format 备份，并在独立数据库完成一次恢复演练。仓库提供
跨平台脚本；数据库密码通过 `PGPASSWORD` 或受保护的 `.pgpass` 提供，不要作为命令行参数传入，
脚本不会把密码写入备份元数据：

```bash
export PGHOST=db.example.internal PGPORT=5432
export PGDATABASE=remote_connect_mcp PGUSER=rcm_center PGPASSWORD='从 Secret 临时注入'
bash ./scripts/backup-postgres.sh
```

```powershell
$env:PGHOST = 'db.example.internal'
$env:PGPORT = '5432'
$env:PGDATABASE = 'remote_connect_mcp'
$env:PGUSER = 'rcm_center'
$env:PGPASSWORD = '<从 Secret 临时注入>'
pwsh ./scripts/backup-postgres.ps1
Remove-Item Env:PGPASSWORD
```

脚本在发布备份前会用 `pg_restore --list` 校验归档索引，并生成同名 `.sha256` 和不含凭据的
`.json` 元数据。恢复时先创建隔离目标库，再使用 `pg_restore --exit-on-error --no-owner --no-acl`，
执行 `rcm-center --migrate`/`liquibase validate` 后检查 `rcm_agent`、`rcm_task`、输出游标和工件
表；确认应用读写和 Agent 心跳后才允许切换生产路由。生产 Center 还必须挂载独立持久卷并设置
`RCM_CENTER_ARTIFACT_STORE=filesystem`、`RCM_CENTER_ARTIFACT_ROOT`；`/api/v1/readyz` 会拒绝
缺少持久工件存储的 PostgreSQL 实例。备份目录已加入 `.gitignore`，对象文件还应由卷/对象
存储策略设置加密、保留期和访问审计。

Linux/Windows 原生二进制还会由 GitHub OIDC 生成 Artifact Attestation（工作流同时声明
`id-token: write`、`attestations: write` 和 `artifact-metadata: write`）；下载 Release 资产后，
可使用 `gh attestation verify <binary> -R Prodigalgal/remote_connect_mcp` 校验构建来源。GitHub
仓库必须允许 Actions 写入 attestations；若仓库权限或计划不支持该能力，发布 Job 会失败，
不会降级为“只有 SHA-256 但没有来源证明”的生产 Release。

迁移由单独 Job 执行，不在 Center Pod 启动时抢迁移锁。原生 Center 也支持一次性入口：

```text
rcm-center --migrate
```

该入口只启动 Liquibase、完成 `validate/update` 后退出。变更集位于 `java/center/src/main/resources/db/changelog`，当前为 `001-core`、`002-task-output`、`003-task-state-fields`、`004-artifact-data`、`005-upgrades`、`006-agent-config`、`007-projects-worktrees`、`008-agent-name-unique`、`009-task-lease-index`、`010-execution-contract`、`011-artifact-storage`；仓库不使用 Flyway。

Java Center 的 memory 模式只用于协议回归/开发。生产必须同时设置
`RCM_CENTER_PERSISTENCE_MODE=postgres` 和
`RCM_CENTER_REQUIRE_DURABLE_STORAGE=true`，并设置
`RCM_CENTER_ARTIFACT_STORE=filesystem`、`RCM_CENTER_ARTIFACT_ROOT` 指向持久卷；后者会让 `/api/v1/readyz` 在模式或工件存储错误时返回
503，即使进程本身仍能响应 `/api/v1/healthz`，从而阻止错误实例被 Service 接收流量。

从旧 Go 文件存储切换时，先停止旧 Center 并完整备份其状态目录，再在已完成 Liquibase 的空 PostgreSQL 库上执行：

```text
rcm-center --import-go /path/to/old-center-state-directory
```

也可以把参数指向 `state.json` 本身。导入器按事务写入机器、一次性注册令牌、任务状态、输出游标和有界工件；输出文件以流方式写入 PostgreSQL，超过 64 MiB 的旧日志只保留前缀并标记截断。机器 Agent 的 SHA-256 身份摘要会保留，MCP/Admin Token 仍从环境 Secret 读取，绝不从文件恢复明文。旧的升级活动不会自动导入，切换前应暂停升级活动并在新 Center 重新创建。

## Kubernetes

`deploy/k8s/java-center` 是 Java Center 的模板：

1. 复制 `secret.example.yaml` 到集群外的 Secret 管理流程，填入 MCP/Admin Token 与 PostgreSQL 连接信息；不要把一次性 Enrollment Token 写入 Center Secret，也不要直接提交替换后的 Secret。
2. 在 `kustomization.yaml` 中把镜像改成实际签名的 Center Native Image 镜像。
3. 先运行 Argo CD `PreSync` migration Job，再滚动 Center Deployment。
4. 验收 `/api/v1/healthz`、`/api/v1/readyz`、MCP `initialize`/`tools/list`、Agent 注册/轮询、长任务超时和断线恢复。

### 生产 Overlay 预检

仓库中的 `deploy/k8s/overlays/java-production` 只是一份公开、占位安全的
模板，不应直接同步到集群。将它复制到集群外的私有部署层，替换三个
`remote-connect-mcp-*` 域名、Center/Console 镜像的 SHA-256 digest、Center
版本和外部 Secret 后，先执行：

```bash
bash scripts/validate-java-production-overlay.sh --strict /path/to/private/java-production
kustomize build /path/to/private/java-production > /tmp/rcm-java-rendered.yaml
```

预检会拒绝 `example.invalid`/`replace-me`/旧模板版本、可变镜像 tag、重复或格式错误的
路由主机名，以及内联 Kubernetes Secret；若本机有 `kustomize` 或 `kubectl`，还会检查最终
渲染结果是否仍含占位符并确认镜像按 digest 固定。预检通过也不等于生产切换授权，仍需完成
迁移 Job、旁路路由、ChatGPT Web MCP、Agent canary、备份恢复和回滚验收。

云上双区集群可先使用 `deploy/k8s/overlays/java-production`：它把镜像切换到
`ghcr.io/prodigalgal/remote-connect-mcp-{center-java,console}` 的版本标签、三个
`remote-connect-mcp-*.example.invalid` 占位路由（MCP、Agent、控制台）并启用 Agent WebSocket
唤醒通道。正式同步前必须在集群外的部署参数/私有 overlay 中替换为真实域名，并把镜像标签改为已
验证 Release 的 digest；Secret 管理流程创建 `remote-connect-mcp-java-secrets`，公共 overlay
不得包含真实域名、Token 或数据库密码。

React 控制台使用独立的 `web/Dockerfile` 镜像和 `console.yaml` Deployment。Nginx 只托管静态资源，并把同源 `/api/` 反向代理到 Center；因此 Admin Token 仍只在浏览器内存中，生产无需开启宽泛 CORS。控制台域名与 MCP/Agent 域名分开，均保留 `remote-connect-mcp-*` 前缀。

模板域名均使用 `remote-connect-mcp-*` 示例名；真实域名只应在部署层注入，不进入源码、日志、指标或前端构建产物。

Actions 产出的工件可在需要时下载到临时目录，再运行短时烟测，验证真实 HTTP 启动和 MCP 工具发现；
烟测本身不编译：

```powershell
./scripts/smoke-java.ps1 -CenterBinary <downloaded-center-binary> -Port 18181
```

Linux shell 版本支持 `./scripts/smoke-java.sh /path/to/downloaded/rcm-center`，并会保留 MCP
`Mcp-Session-Id` 再调用 `tools/list`。

若要验证 Agent 的完整生命周期（一次性 Enrollment Token 注册、注册后删除该令牌、使用
identity 文件建立日常心跳、Center 判定在线、创建命令任务并核对输出），Windows 可运行：

```powershell
./scripts/smoke-java-agent.ps1 `
  -CenterBinary ./dist/java-windows-amd64/center/rcm-center.exe `
  -AgentBinary ./dist/java-windows-amd64/agent/rcm-agent.exe `
  -Port 18182
```

该脚本同样只使用内存模式、临时令牌和临时状态目录；成功输出
`registration=ok`、`identity_without_enrollment_token=ok` 和 `command=ok` 后才算原生 Agent
工件通过本地端到端门禁。

Linux 目标使用同一契约：

```bash
bash ./scripts/smoke-java-agent.sh ./rcm-center ./rcm-agent 18183
```

发布工作流会在 Linux amd64/arm64 目标构建后以及 Windows amd64 构建后自动执行 Center
MCP 和 Agent 端到端烟测；任一目标烟测失败都不会上传 Release 工件。
Agent 烟测同时在注册后的空闲/命令阶段采样 Agent 工作集，生成仅含 PID、峰值 RSS 和样本数的
临时 resource JSON；该报告作为 CI 工件保存，不进入公开 Release，也不包含令牌、命令或路径。

烟测使用临时内存模式和临时令牌，检查 `/api/v1/healthz`、MCP
`initialize`/`tools/list`，并在退出时终止临时 Center；不会读取或修改生产 Secret。

## 异步性门禁

- `command_start`、`desktop`、`browser` 只创建任务并返回 `task_id`；不在 MCP 请求线程等待进程完成。
- MCP 使用官方异步 Server，工具处理在专用虚拟线程执行器上调度。
- Agent/Admin Servlet 控制器返回 `CompletableFuture<ResponseEntity<?>>`；认证后的 JDBC、任务写入、输出和工件处理统一提交到可关闭的虚拟线程执行器，容器请求线程只负责解析和挂起响应。
- `/readyz` 的 PostgreSQL 探针同样异步执行；数据库不可达或 Liquibase 核心表不存在时返回 503，避免迁移未完成就接收流量。
- Agent 的进程等待、stdout/stderr drain 和 Center 上传完全分离：子进程只写有界磁盘 spool，HTTP 使用 `sendAsync`，超时线程可以随时终止无输出进程；输出上报按游标幂等并在网络抖动时退避重试。
- 无超时任务使用可恢复日志文件，但仍受 `REMOTE_CONNECT_MCP_AGENT_MAX_OUTPUT_BYTES` 硬上限保护；超过上限会终止该任务、保留前缀并以 `failed + output_truncated` 收口，避免离线期间无限占满磁盘。
- Agent 重连使用指数退避和抖动；Center 租约过期后重新排队，不复制逻辑任务。
- 控制台 API 只加载分页摘要，长输出按 cursor 获取；Admin Token 只保留在当前页面内存。
- Agent 空闲时只保留一个有界 HTTPS 长轮询请求和少量虚拟线程；任务、取消、配置或升级事件到达即返回，服务端 deadline 结束空闲请求。任务并发、输出磁盘上限和 Browser 默认超时均有配置边界。无超时任务额外由独立文件事件看门器监控日志，离线期间超过上限会终止并收口，避免用“可靠恢复”换取无限资源占用。

如果这些门禁未全部通过，发布仍停留在迁移环境，不切换现有 Go Center/Agent 生产流量。

## Agent 资源预算

默认值按 1C/1G 级别终端设计：空闲 Agent 只保持一个最长 25 秒的 HTTPS 长轮询请求，不运行固定 5 秒心跳；事件到达或服务端 deadline 才结束请求，断线时才使用指数退避。`MAX_CONCURRENCY=1` 限制同时子进程数，`MAX_BROWSER_WORKERS=1` 再对浏览器适配器做独立上限；每任务 stdout/stderr 默认为 64 MiB，普通任务共享 `MAX_AGGREGATE_OUTPUT_BYTES` 聚合 spool 上限（默认随并发增长但不超过 256 MiB）；输出上传使用 16 KiB 分片；Browser 任务无显式超时时默认 300 秒，最长 24 小时；durable 日志看门器由 fsnotify/WatchService 文件事件驱动，只有极旧系统没有可等待进程句柄时才保留显式、低频的 5 秒兼容回退。Desktop companion 另有最多 4 个并发 IPC 请求和 16 个活动启动进程，并通过文件锁保证单实例。聚合上限达到时普通任务继续执行并标记输出截断，只有 durable 任务达到其硬上限才会终止，确保节约资源不会把可恢复任务静默杀掉。确需并行时逐台提高并发并观察 RSS、磁盘和 Center 延迟，不建议在小规格主机上直接设置 32 个槽位或 1 GiB 输出上限。
