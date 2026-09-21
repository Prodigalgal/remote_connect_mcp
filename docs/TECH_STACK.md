# Remote Connect MCP 技术选型

需求上游基线：[`docs/REQUIREMENTS.md`](REQUIREMENTS.md)。本文只记录为满足该基线所选择的技术和运行时边界，不把技术选型本身当作产品需求。

本文记录 RCM 的 Java 25/React 实现与生产选型。Java/React 是唯一运行时路径；所有构建和验证统一由 GitHub Actions 执行，Native Image、数据库、路由和切换事实以发布验收和生产探针为准。

## 1. 目标和边界

目标是保持一个稳定的 `/mcp` 入口，同时把 Center、控制台和 Agent 解耦演进：

- Center 负责 MCP Gateway、机器/Agent 注册、任务队列、租约、审计、升级和控制台 API；
- 一个物理终端默认只向 Center 注册一个 command-agent 身份；desktop-companion 和 browser-agent 是同身份的本机子组件，不注册第二个 machine ID；确需隔离时才运行多个独立 command-agent；
- Center 和 Agent 都用 Java 25 编写，生产发布优先使用平台原生二进制；
- 控制台是独立 React 应用，静态构建后由独立 Web 容器/CDN 部署；
- 浏览器能力由 Java Agent 统一编排，Playwright/Patchright/Comoufox 只作为本机适配器，不把浏览器 Cookie、CDP 凭据或完整页面树上传到 Center；
- 保持 MCP 工具面精简、有界，新增能力优先通过任务类型和 capability 协商实现，而不是复制大量工具。

明确不在本阶段引入：微服务拆分、Kafka/NATS、把 OAuth 设为所有客户端唯一入口、代理其他 MCP、Next.js SSR、微前端、STOMP、直接把 QUIC 作为唯一传输，或一次性暴露完整桌面/浏览器底层 API。ChatGPT Web 的 OAuth 2.0/PKCE 桥接与直接 Bearer 客户端同时受支持。

### 当前实施状态（2026-09-11）

- 已建立 `java/` Gradle 多模块实现：`protocol`、`center`、`agent`、`desktop`、`browser`；后三个 Agent 目标分别产出 command-agent、desktop-companion、browser-agent Native Image；协议记录、边界校验、异步 MCP、健康/版本探针和注册/长轮询接口均可测试，并已锁定 Liquibase/PostgreSQL 依赖；
- Center 注册表与任务队列已支持内存和 PostgreSQL 两种适配路径：`postgres` 模式通过独立 Liquibase changelog 管理 Agent、任务、输出游标和有界工件；一个进程只选择其中一种，生产只允许 PostgreSQL，内存适配器仅用于协议回归和开发。
- Java command-agent 已有自包含 Native 运行时、原子身份文件、一次性注册换取日常 Token、断线指数退避、401 自动重新注册、虚拟线程命令执行、有界磁盘 spool/异步上传与无超时进程恢复、用户会话 Desktop IPC 客户端和 Browser Agent 监管；desktop/browser 目标分别隔离 AWT 和浏览器适配器生命周期；command-agent 与 desktop-companion、browser-agent 只通过 `protocol` 共享线协议和桌面 IPC 记录，command/browser 任务使用有界进程预算，desktop-companion 使用独立的 GUI 进程回收边界，避免把不需要的实现和运行库交叉打包；
- 已建立 `web/` React/Vite 控制台并接入 Admin API 的机器/任务分页读取、取消和真实升级活动，Admin Token 只驻留当前 React 内存；
- Java Center/Agent 是生产 Center 的唯一实现；Java 已实现注册、心跳、异步任务、工件、项目/worktree 和 Center 控制的升级编排；升级活动在 PostgreSQL 模式通过 Liquibase `005-upgrades`、`006-agent-config` 和 `007-projects-worktrees` 持久化，`008-agent-name-unique` 约束并发注册的同名身份；Agent 侧普通任务输出还受单任务与聚合 spool 双重上限保护；
- Agent 配置已支持带 generation 的长轮询等待时间、事件唤醒和并发槽位热更新；心跳 runtime descriptor 同时公布单任务与 Agent 级总进程预算；配置原子写入状态目录，输出上限、Token 和工作区边界仍保持启动时约束；
- JVM 测试、Center/Agent JAR 构建、React 生产构建，以及 Linux amd64/arm64、Windows amd64 的 Center/Agent/desktop/browser Native Image 和 MCP 烟测已由 GitHub Actions `34795775084` 重新验证；当前不在开发机执行构建或测试。正式 tag 的签名、Release 资产和目标机安装/升级回归仍由发布门禁负责。

## 2. 选型总表

| 层 | 主选 | 关键原因 | 暂不选 |
| --- | --- | --- | --- |
| Center 语言 | Java 25 | 虚拟线程、成熟的 TLS/HTTP/进程/服务生态，便于和 Agent 共享协议模型 | Java 21（除非部署环境无法提供 25） |
| Center Web | Spring Boot 4.x + Spring MVC + Tomcat | 生态完整、管理端/安全/指标/测试成熟；阻塞式任务查询可直接使用虚拟线程 | WebFlux 作为默认模型、Spring Cloud 全家桶 |
| MCP | 官方 MCP Java SDK 2.x，Streamable HTTP | 不手写 JSON-RPC，遵循工具发现和能力协商 | 自研 MCP 协议、为每台机器复制工具 |
| Center 数据访问 | Spring JDBC `JdbcClient` + 明确 SQL | 队列、租约、幂等、CAS 更新都需要可见 SQL 和事务边界；避免 ORM 隐式行为 | JPA/Hibernate 作为核心队列存储 |
| Center 数据库 | PostgreSQL（版本锁定在部署清单） | 事务、行锁、JSONB、LISTEN/NOTIFY 和运维工具成熟 | 生产继续依赖单个 JSON/PVC 文件 |
| 数据迁移 | Liquibase | 版本化 changelog、上下文/前置条件、SQL 预览和回滚审计完整 | 启动时无条件自动改表 |
| 工件存储 | `ArtifactStore` 抽象；默认是独立持久卷上的原子文件对象，内部 HTTPS 对象网关为可选后端，S3 兼容适配器保留扩展位 | 图片、日志、升级包与 PostgreSQL 元数据分离，按 key、大小和 SHA-256 校验；文件 TTL 由外部有界 GC 执行 | 将大工件塞进任务 JSON 或 PostgreSQL `BYTEA`，或把外部对象存储做成硬依赖 |
| Agent 语言 | Java 25 模块化 JDK 应用 | 不带 Spring，原生镜像小、启动快、跨平台边界清晰 | Agent 引入完整 Spring 容器 |
| Agent 通道 | JDK `HttpClient` 25 秒长轮询 + 原始 WebSocket 唤醒；断线指数退避 | 无额外网络栈依赖，HTTPS/TLS 和断线重试可控，健康路径不刷固定请求 | 首版直接绑定 QUIC |

| Desktop | 独立 Java `desktop` Native 目标 + 用户会话伴侣 IPC（AWT/平台适配层） | 服务与 GUI 权限分离，command-agent 不加载 AWT | 让 SYSTEM 服务假装拥有用户桌面 |
| Browser | 独立 Java `browser` Native 目标 + 本机适配器 SPI | command-agent 仅负责 Center 和上限，browser-agent 单任务监管 Playwright/Patchright/Comoufox | 将 Node Worker 注册成第二台 Agent |
| Java 原生构建 | GraalVM Native Image 25.x（或 Liberica NIK 25.x，版本锁定） | 生成无需 JVM 的平台可执行文件，降低启动和常驻内存 | 运行时依赖用户预装 JDK |
| Java 构建 | Gradle Wrapper + Kotlin DSL + version catalog | Center/Agent 共用依赖约束，原生构建和多模块任务统一 | 手工安装 Maven/Gradle |
| 前端 | React 19 + TypeScript + Vite | 控制台是鉴权后的 SPA，独立静态部署，开发和生产构建边界清晰 | Create React App、为控制台引入 SSR |
| 前端数据 | TanStack Query + Zustand | 服务端缓存和本地 UI 状态分离；任务状态可失效刷新 | 所有数据放一个全局 store |
| UI 系统 | Tailwind CSS + Radix Primitives + 自有 design tokens | 便于细化视觉系统、无障碍和响应式，不被模板主题锁死 | 直接套用未经治理的后台模板 |
| UI 质量 | Storybook + Playwright + axe-core | 组件隔离、视觉回归、键盘/无障碍门禁 | 只做人工截图验收 |

## 3. Center 详细设计

### 3.1 运行时和模块

Center 使用一个 Gradle 多模块构建，推荐边界如下：

```text
center/
  center-app             # Spring Boot 启动、配置、健康检查
  center-mcp             # MCP Java SDK 适配和精简工具注册
  center-http            # /api/v1、/agent/v1、/mcp、WebSocket
  center-domain          # Machine、Agent、Task、Lease、Attempt、Artifact
  center-persistence     # JdbcClient、事务、Liquibase、PostgreSQL
  center-upgrade         # 发布包、SHA-256/签名、批次和回滚
  center-observability   # Micrometer、OTel、审计脱敏
  protocol               # JSON Schema、版本、契约测试夹具
```

Spring MVC/Tomcat 负责 HTTP、SSE 和 WebSocket，生产打开虚拟线程；MCP、Agent 和 Admin 控制器均返回异步结果，JDBC 等阻塞集成在可关闭的虚拟线程执行器中运行。虚拟线程用于高并发等待，不替代数据库连接池、并发上限或任务租约。长时间命令不占用 MCP HTTP 请求，仍然走持久化异步任务。

MCP 层使用官方 Java SDK 的 Streamable HTTP 传输，固定挂载 `/mcp`。MCP Token 继续使用 Bearer；Admin Token 只用于控制台/API，Agent Token 和一次性 Enrollment Token 分开。升级 Center、数据库或前端不得改变 `/mcp` URL 和既有工具契约。

### 3.2 持久化和一致性

- **单一权威存储**：生产 Center 只使用 PostgreSQL + Liquibase。Java 的 memory adapter 仅用于协议回归和开发，不与 PostgreSQL 同时启用，也不双写。
- **内存只做加速，不做事实来源**：连接唤醒表、任务等待条件和短生命周期请求状态可以留在进程内存；进程重启后从 PostgreSQL 重建。不得把任务租约、Attempt、Token 摘要、输出游标、工件或升级状态只放在缓存中。
- **不新增 Redis/Kafka 作为第二状态层**：当前规模优先使用 PostgreSQL 行锁、共享缓冲区、Hikari 连接池和 `LISTEN/NOTIFY`；确有读热点时采用有界、可失效的本地 L1，并以数据库版本/事件失效，缓存丢失不影响正确性。
- 任务创建、幂等键、租约领取、Attempt、状态机和输出游标全部由 PostgreSQL 事务保证；
- 使用 `SELECT ... FOR UPDATE SKIP LOCKED` 或等价 CAS 语句实现多 Agent 领取，Center 副本增加前不引入额外消息队列；
- `LISTEN/NOTIFY` 只作为唤醒提示，不能替代数据库状态，断线后仍能靠版本/游标补偿；
- 输出、截图、升级包使用对象 key + SHA-256 + 大小 + MIME 元数据；当前 Center 通过独立持久卷文件对象落盘，数据库不再写入新的大块 `BYTEA`；
- `RCM_CENTER_ARTIFACT_STORE=filesystem` 时使用同一 PVC/专用数据卷，写入采用临时文件加原子替换，读取再次校验大小与 SHA-256；`RCM_CENTER_ARTIFACT_STORE=http` 时通过 HTTPS 内部对象网关读写同一套 opaque key，网关 Token 只经 Secret/env 注入。默认不要求外部对象存储；任一后端接入 `ArtifactStore` 后都不改变任务或 Agent 协议；
- 每个文件工件保存独立 `expires_at`/`pinned` 生命周期元数据，签名 URL TTL 只控制访问票据有效期。Kubernetes CronJob 或外部维护平台按固定低频触发有界 GC；Center 不运行定时轮询线程，后端删除失败时保留元数据等待下一次重试；
- Liquibase changelog 使用 Git 管理的 master YAML + 版本化 YAML/SQL 变更集，必须可回放、可 `update-sql` dry-run，并为 PostgreSQL 集成测试提供 Testcontainers 夹具；
- 生产由独立 Kubernetes migration Job 执行 `validate/update`，应用只校验已安装的 schema 版本；禁止多个 Center Pod 同时在启动阶段抢迁移锁；
- 每个变更集设置唯一 `id/author`、`labels/contexts` 和必要的 preconditions，危险 DDL 先在影子数据库执行 rollback 演练；
- 协议回归可使用内存适配器，但生产配置必须显式选择 PostgreSQL 和持久字节后端；外部对象存储只是可选的 `http`/未来 S3 适配，不是生产硬性依赖。

Java Center 生产版本只使用当前 PostgreSQL + Liquibase 数据模型；发布前完成备份和 migration Job，不内置旧状态导入或旧 Token 迁移入口。

### 3.3 连接层演进

连接抽象统一为：

```text
Transport -> Envelope(version, agent_id, host_id, capability, generation, sequence)
          -> Register / Heartbeat / Poll / Task / Output / Artifact / Upgrade
```

Java Agent 默认使用 25 秒 HTTPS 长轮询，事件到达即返回，服务端 deadline 结束空闲请求；当前已加入只传递 wake 提示的原始 WebSocket 旁路，复用 Agent
身份并在断线时重新建立连接；后续再把同一 Envelope、心跳、序列号和幂等语义扩展到真正的长连接任务流，
最后评估 QUIC。QUIC 只能作为可选 Transport SPI，不能改变任务协议，也不能让 Agent 因 QUIC 不可用而离线。

### 3.4 可观测性

指标只记录计数、延迟、连接、任务状态、版本和错误类别；命令、路径、环境变量、Token、Cookie、截图内容不进指标标签。日志使用 JSON，默认脱敏；审计记录 actor（例如 `mcp`、`console`、`agent`）、machine/agent ID、task ID、结果和时间线，命令正文按策略摘要化。

## 4. Agent 详细设计

### 4.1 核心模块

Agent 不引入 Spring，模块边界保持小而明确：

```text
agent/
  agent-core        # 生命周期、身份、心跳、配置 generation、能力协商
  agent-transport   # JDK HttpClient、HTTPS 长轮询、WebSocket、失败退避
  agent-command     # ProcessBuilder、输出 spool、租约续期和恢复
  agent-desktop     # 用户会话截图、应用启动、窗口/输入扩展点
  agent-browser     # Browser Agent supervisor、profile 生命周期、工件上传
  agent-updater     # 下载、SHA-256/签名、原子替换、回滚
  agent-platform    # systemd、Windows Task Scheduler、ACL 和路径实现
```

Agent 以 Java record/不可变配置表示协议对象，使用显式 JSON Schema 生成或校验模型。动态反射、脚本化配置和任意类加载不放入核心路径，以减少 Native Image reachability metadata。

### 4.2 一个终端多个 Agent

同一 `host_id` 可以按需启用：

- `command`：系统服务，可在未登录时执行无人值守任务；
- `desktop`：独立 `rcm-desktop-companion` 用户会话进程，拥有当前桌面和用户态应用权限；
- `browser`：独立 `rcm-browser-agent` 单任务进程，再拉起受控适配器。

默认 command/desktop/browser 共用一个 Center Agent 身份，companion/Browser Agent 只通过本机 ACL/临时环境和任务文件
工作；确需隔离时才为多个实例使用不同的 `agent_id`、一次性 Token、状态目录和
capability。Center 只通过 `host_id` 归组，不把 host 组当作权限主体。

### 4.3 Desktop Agent

第一版提供 `screenshot`、`screens`、`launch`、`click`、`drag`、`key`、`type`、`clipboard_read`、
`clipboard_write` 和 `focus` 十类有界动作：

- Windows/Linux：用户会话 companion 使用 AWT/平台图形后端处理多显示器截图和有限输入；截图最大 8 MiB，输入参数有长度/坐标边界；窗口聚焦在 Windows 使用受限的 User32 调用，Linux 无窗口管理器时明确返回不可用；
- 无图形会话或 companion 不可达时返回结构化失败，不伪造成功；
- 应用启动使用 `ProcessBuilder`，明确继承/清理环境变量，禁止将 Token 传给子进程；
- 截图作为 PNG 工件引用或图片内容返回，MCP 不返回 Base64 文本。

### 4.4 Browser Agent

Browser Agent 只向 Center 暴露结构化动作，不暴露 Playwright 全部 API；每个任务的结构化
请求写入短生命周期文件，结果清单只能引用 Agent 为该任务创建的工件目录：

1. `playwright-java`：使用官方 Playwright Java API，支持 Chromium/Firefox/WebKit；
2. `patchright-node`：Java Agent 启动并监管本机 Node/TypeScript Worker，Worker 只在本机通过命名管道/Unix socket/受 ACL 保护的 loopback 通信；
3. `comoufox-node`：沿用同一 Worker 协议，浏览器 profile、Cookie、扩展、代理和 CDP 端口永不上传 Center。

Worker 不是第二个物理 Agent，不注册第二台机器；command-agent 负责 Center 生命周期、并发和结果上传，browser-agent 负责单个适配器进程的启动、stdout 透传和退出回收。结果清单由 command-agent 校验 MIME、路径、大小和 SHA-256 后才上传单个工件，适配器异常或越界均 fail-closed。Browser MCP 工具优先返回 accessibility snapshot、元素引用、URL/title、网络/控制台摘要，设置字数、节点数、截图和下载大小上限。

## 5. Java 原生二进制与发布

### 5.1 构建方式

使用 GraalVM Native Image 25.x 或 Liberica NIK 25.x，版本写入 Java 构建约束文件和构建容器 digest。Center、command-agent、desktop-companion 和 browser-agent 的正式发布物为平台二进制；JVM jar 只作为 CI 诊断产物，不作为运行时后备：

```text
 remote-connect-mcp-center-vX.Y.Z-linux-amd64.zip    # Center ELF + Native Image .so 运行库
 remote-connect-mcp-center-vX.Y.Z-linux-arm64.zip    # Center ELF + Native Image .so 运行库
 remote-connect-mcp-agent-vX.Y.Z-linux-amd64.zip    # command-agent ELF + Native Image .so 运行库
 remote-connect-mcp-agent-vX.Y.Z-linux-arm64.zip    # command-agent ELF + Native Image .so 运行库
 remote-connect-mcp-agent-vX.Y.Z-windows-amd64.zip  # command-agent rcm-agent.exe + Native Image DLLs
 remote-connect-mcp-desktop-vX.Y.Z-linux-amd64.zip / remote-connect-mcp-desktop-vX.Y.Z-linux-arm64.zip
 remote-connect-mcp-desktop-vX.Y.Z-windows-amd64.zip      # desktop-companion + Native Image runtime
 remote-connect-mcp-browser-vX.Y.Z-linux-amd64.zip / remote-connect-mcp-browser-vX.Y.Z-linux-arm64.zip
 remote-connect-mcp-browser-vX.Y.Z-windows-amd64.zip      # browser-agent + Native Image runtime
```

每个发布资产附带 SHA-256、SBOM、构建元数据和签名。Linux/Windows Agent 必须把可执行文件与同一构建生成的 `.so`/DLL 一起打包，不能把裸可执行文件当作完整运行包。Native Image 是针对具体 OS/CPU 架构的构建产物，不能把一个 Linux 二进制当作跨平台包；CI 使用匹配架构 runner/容器分别编译和冒烟测试，不做未经验证的交叉编译。

当前发布门禁只接受 Linux amd64/arm64 与 Windows amd64 Native Image。Windows ARM64
没有受支持且可复现的 GraalVM/NIK 25 Native Image 目标，因此不进入发布矩阵，不能把交叉编译结果标成原生二进制；若未来纳入，必须增加独立的 Native Image 构建、服务安装、升级和回滚验收。

### 5.2 Native Image 约束

- 生产代码避免运行时反射、动态代理、扫描 classpath 和任意资源加载；
- 所有 JSON、ServiceLoader、JNA、Playwright driver 和资源路径纳入 reachability metadata，并运行 native tests；
- CI 同时执行 JVM 单元测试、native 单元测试、协议契约测试、MCP conformance、断线恢复测试；
- Native Image 失败时不能退回“开发机能跑的 JVM”而继续发布，必须修 metadata 或明确使用 jpackage 后备档；
- 原生升级采用临时文件、校验、原子替换和失败回滚，保留上一版本可启动文件。

### 5.3 服务安装

- Linux：systemd 直接运行 Agent/Center 二进制，状态目录和 Token 文件使用 `0600`；
- Windows：命令 Agent 由内置 Task Scheduler 以 SYSTEM 身份承担开机启动、失败重启和升级控制语义，Agent 本体仍是 Java Native Image（控制台程序不直接伪装 SCM ServiceMain）；Desktop companion 由用户会话启动独立的 `rcm-desktop-companion` Native Image，并与 command-agent 共用同一状态目录下的受保护 IPC 端点和单一 Center 身份；
- 安装脚本只负责下载、校验、写入配置和注册服务，不把令牌打印到普通日志；
- Center 的 Agent 升级按平台、架构和 capability 分批，不改变 `machine_id`、`agent_id` 或 ChatGPT MCP 连接器。

## 6. React 控制台详细设计

### 6.1 工程结构

```text
web/
  console/              # Vite React SPA
  packages/ui/          # 设计系统和可复用组件
  packages/api-client/  # OpenAPI 生成的类型安全客户端
  packages/tokens/      # 颜色、字号、间距、阴影、动效 token
  packages/test-utils/  # MSW、Storybook、Playwright fixtures
```

主选 React 19 + TypeScript + Vite。React 官方已经将 Vite/Parcel/Rsbuild 作为从零构建的可选路线；RCM 控制台是鉴权后台，不需要 SEO 和服务器组件，因此不引入 Next.js SSR。生产执行 `vite build`，由 Nginx/Caddy 或对象存储 CDN 提供静态文件，生产环境不需要 Node 常驻。

### 6.2 UI/UX 治理

- 所有颜色、间距、层级、圆角、字体、动效和状态色先进入 tokens，组件不直接写散落的魔法值；
- Radix primitives 负责键盘、焦点、弹层、菜单和无障碍语义；Tailwind 只作为 token 的组合层，复杂组件保留明确 CSS；
- 控制台采用经典后台布局：侧边栏、面包屑、全局搜索、机器/Agent 分组、任务时间线、工件预览、升级批次和审计；
- 状态必须区分 online、degraded、offline、draining、upgrade_pending、desktop_unavailable 等，不用单一颜色表达所有故障；
- 桌面端和窄屏至少保证任务查看、日志分页、令牌复制/下载、升级暂停等关键流程可用；
- Storybook 维护组件状态矩阵，Playwright 做关键流程和视觉回归，axe-core 做自动无障碍门禁；
- 所有表格使用分页/虚拟滚动，默认不加载完整日志、完整页面树或所有历史工件，避免控制台本身制造上下文和网络压力。

### 6.3 前后端边界

前端只调用版本化 `/api/v1`，通过 OpenAPI 生成 TypeScript 类型和客户端；任务实时更新使用 Admin
事件长轮询（变更序号 + 按需刷新），异常时才有界退避重试。认证使用 Secure、HttpOnly、SameSite
Cookie 或显式短期会话，不把 Admin Token 放在 localStorage，也不把任何 Center/Agent Token 编译进静态资源。

生产路由建议让控制台和 API 使用同源域名（`/console` 与 `/api`），由反向代理转发到独立 React 静态站和 Java Center；这样默认不需要开放宽泛 CORS。前端可以独立发布和回滚，API 版本由 Center 控制。

## 7. 迁移顺序和验收门槛

### 阶段 A：协议冻结和并行实现

1. 从 Java protocol 类型提取 JSON Schema/OpenAPI 夹具，固定 `/mcp`、`/api/v1`、`/agent/v1` 和 Bearer 语义；
2. Java Center 实现注册/机器/任务查询，并以 Java protocol 做协议契约测试；
3. Java Center 使用 PostgreSQL/Testcontainers，完成 Liquibase changelog 和迁移回滚演练。

### 阶段 B：Agent 和能力

1. Java `command-agent` 先替换 Linux amd64/arm64，再替换 Windows amd64；
2. 独立 `desktop-companion` 以用户会话身份接入，完成截图、启动、断线恢复和工件校验；
3. 独立 `browser-agent` 先监管 Playwright Worker，随后加入 Patchright/Comoufox 适配；
4. Center、command-agent、desktop-companion 和 browser-agent 按同一 release manifest 整体发布，不保留旧运行时窗口。

### 阶段 C：控制台和传输

1. React 控制台先只读接入，再接入任务、令牌、升级和审计写操作；
2. WebSocket wake-only 已在原生 Windows Center 上验证连接、ping/pong、任务 wake 和 HTTPS 回退；当前只要求单 Center 生产路径继续完成断线、重连、序列号和幂等验收；
3. QUIC 先使用 `docs/TRANSPORT.md` 中的一次性基准和显式回退，只有真实收益和可维护 provider 均成立时才加入，不改变任何 MCP 工具和任务模型。

### 发布闸门

- MCP conformance：工具发现、Schema、Bearer、错误码和图片工件；
- 任务可靠性：超时、长任务、重复重试、Center 重启、Agent 断线、输出续传和取消；
- 多 Agent：同一 host_id 下 command/desktop/browser 权限隔离，machine_id/token 不复用；
- 原生包：四平台构建、SHA-256/签名/SBOM、服务安装/卸载、升级回滚；
- 资源预算：Center 在 1C1G 基线下的 RSS、Agent 空闲 RSS、启动时间、心跳延迟和数据库连接数；
- 安全：Token 不出现在日志、指标、前端包、错误正文和工件元数据中；路径、环境变量、浏览器 profile 和桌面会话在 Agent 本机最终校验。

## 8. 参考的官方资料

- [Java 25 Virtual Threads](https://docs.oracle.com/en/java/javase/25/core/virtual-threads.html)
- [GraalVM Native Image](https://www.graalvm.org/latest/reference-manual/native-image/)
- [Spring Boot Native Applications](https://docs.spring.io/spring-boot/reference/packaging/native-image/introducing-graalvm-native-images.html)
- [MCP Java SDK Server](https://java.sdk.modelcontextprotocol.io/latest/server/)
- [React 从零构建应用](https://react.dev/learn/build-a-react-app-from-scratch)
- [Vite 生产构建](https://vite.dev/guide/build)
- [Playwright 支持的语言](https://playwright.dev/docs/languages)
