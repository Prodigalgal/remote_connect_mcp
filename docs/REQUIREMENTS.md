# Remote Connect MCP 需求基线

版本：1.1

日期：2026-09-16（Asia/Shanghai）

状态：已确认的产品与工程基线；P2-05-lite 轻量多主体已完成竞品比较与最终选型，代码尚未实施

本文档定义 Remote Connect MCP（RCM）要解决的问题、必须具备的能力、明确的边界和验收标准。它是后续架构、协议、实现和发布决策的上游依据。

- [`docs/ARCHITECTURE.md`](ARCHITECTURE.md)：描述组件如何协作；
- [`docs/ASYNC_CONTRACT.md`](ASYNC_CONTRACT.md)：描述异步任务的协议语义；
- [`docs/TECH_STACK.md`](TECH_STACK.md)：描述语言、数据库、构建和前端选型；
- [`docs/STATUS.md`](STATUS.md)：只记录已经有代码、CI 或生产探针证明的实现状态，不改变本文档的需求。
- [`docs/MULTI_USER_MODEL.md`](MULTI_USER_MODEL.md)：描述轻量多主体、对话、MCP 连接和执行车道模型；不代表当前代码已经实现。

## 1. 产品定位

RCM 不是普通的远程 Shell、通用 RMM、远程桌面软件或 MCP 代理平台。

RCM 是一个面向 ChatGPT Web 的多终端可靠执行平台：ChatGPT 通过一个稳定的 MCP 地址，把自然语言意图转换为带目标、范围、能力和生命周期的任务；Center 负责认证、授权、调度、持久化和升级；目标机器上的 Agent 负责在本机执行并回传有界结果。

```text
ChatGPT Web / MCP client
            |
            | 固定 HTTPS /mcp + Bearer MCP Token
            v
Center（Kubernetes，Java Native Image）
  ├─ MCP 适配层
  ├─ 任务、租约、幂等和输出状态
  ├─ 机器/项目/能力路由
  ├─ Web 管理 API 和 React 控制台
  └─ Agent 升级、审计和指标
            ^
            | Agent 主动外连，事件唤醒，HTTPS 作为可靠补偿
            +-- Host A: command-agent + desktop-companion + browser-agent
            +-- Host B: command-agent
```

## 2. 要解决的原始问题

1. ChatGPT Web 无法直接访问本机、内网或异构服务器。
2. 同步执行命令会受到 MCP/HTTP 请求超时影响，导致模型无法判断任务是否已经启动。
3. 连接中断、服务重启或模型重试不能造成同一命令重复执行。
4. 单纯的终端工具无法覆盖 Windows 用户态桌面和浏览器自动化。
5. 机器数量增加后，不能为每台机器重新创建一个 ChatGPT 插件，也不能把所有机器状态一次性塞进模型上下文。
6. 主要风险是模型误判或误操作，而不是假设可信链路已经被外部攻击者突破；系统需要控制误操作的影响范围和可恢复性。

## 3. 用户和使用流程

### 3.1 ChatGPT 用户

用户只配置一次固定 MCP 连接。之后可以直接用自然语言说明目标机器、项目、路径和任务，不需要手写内部 Agent 地址、项目 ID 或运行时凭据。

典型流程：

1. 选择或描述一台机器；
2. 选择项目、工作区、目录或明确的整机权限；
3. 提交命令、开发、桌面或浏览器任务；
4. 立即获得 `task_id`；
5. 通过有界等待、日志游标或 Web 控制台查看进度；
6. 任务完成后查看摘要、变更、截图或下载工件。

### 3.2 三种范围模式

| 用户意图 | 会话策略 | 说明 |
| --- | --- | --- |
| “连接某台机器处理这个项目” | `project` / `worktree` | 默认推荐；只允许目标项目及其子路径 |
| “连接某台机器，限制在某目录” | `workspace` / `path` | 只允许指定根目录及其子目录 |
| “直接连接这台机器” | `unrestricted` | 必须是明确的整机授权，不因默认值或模型猜测自动获得 |

范围策略在 Center 创建会话时确定，并由 Agent 在本机再次校验。`cwd` 只是任务起点，不得被描述成完整的操作系统沙箱。

### 3.3 管理员

管理员通过 React 控制台完成：

- 生成一次性机器注册令牌；
- 查看机器、Agent、平台、版本、能力和在线状态；
- 注册项目和管理 Git worktree；
- 创建、查看、取消和恢复任务；
- 查看有界日志、图片和工件；
- 选择 Agent 版本并执行 canary/批次升级；
- 暂停、恢复、取消升级活动；
- 查看审计、指标和失败原因。

### 3.4 目标机器

目标机器上的 command-agent 作为系统服务运行，因此即使用户尚未登录，也可以执行命令和无人值守任务。桌面能力仅在用户会话可用时启用；Browser Worker 只在浏览器任务期间存在。

### 3.5 多用户、多对话和多 MCP 连接

一个稳定的 `/mcp` Endpoint 可以同时服务多个 Web 账号、多个对话和多个 MCP 连接：

- Web 账号在 RCM 内映射为 `Principal`，由不透明 Bearer Token 识别；不依赖 ChatGPT
  客户端传来的账号或对话字段；
- 一个 Principal 可以拥有多个 Token、多个对话和多个连接；同一 Token 默认只属于一个
  Principal，不鼓励跨用户共享；
- 对话是上下文和生命周期边界，不直接授予权限；任务、日志和工件按 Principal、项目
  ACL 和任务范围过滤；
- 同一项目/worktree 的写任务使用持久化执行车道串行化，不同 worktree 才允许并行；
- Desktop 使用独占用户会话 lease，Browser 使用按主体/对话隔离的 Profile/Context；
- 该模型是轻量主体隔离，不扩展为完整 SaaS 多租户、跨组织计费或复杂 RBAC。

详细关系、迁移顺序和验收矩阵见 [`MULTI_USER_MODEL.md`](MULTI_USER_MODEL.md)。

### 3.6 稳定并发与结果不串话（高于实现便利）

多用户、多对话的核心目标不是让所有命令同时启动，而是让并发行为可预测、可恢复、可
审计。以下约束是需求本身，任何实现都不能用“模型自己小心一点”替代：

- **主体和结果隔离**：每个任务必须绑定 `principal`、`execution_session`、`task_id` 和
  `attempt`；结果只在请求方明确提供任务句柄且通过 ACL 后返回，不做跨对话广播；
- **同一项目的并行修改**：不同 worktree 可以并行；同一 worktree 的写操作必须进入同一
  持久化车道串行；默认不在同一 checkout 上做乐观并发写入；
- **同一项目的共享状态**：如果用户明确选择共享 worktree，系统必须接受排队和等待，不能
  静默创建第二份状态或自动合并互相冲突的修改；
- **同一环境/终端**：每个任务使用独立进程组、cwd、环境和输出游标；涉及主机全局状态的
  操作使用主机写车道；同一 OS 会话的桌面输入独占 lease；浏览器默认使用独立 Context；
- **长任务和重试**：HTTP 超时、Web 断线、Center/Agent 重启只允许恢复原任务或进入明确
  终态，不能产生第二次逻辑执行；
- **公平和资源**：主体、项目、Agent 和主机都必须有界配额；冲突任务排队，不以无界线程、
  进程或固定频率轮询换取“看起来并发”；
- **最小上下文**：MCP 只返回任务句柄、状态摘要、游标和工件引用，不能把其他对话的
  历史、输出或机器清单自动注入当前对话。

因此，任务目标必须明确选择下列一种工作区策略：`isolated`（默认，写任务使用会话专属
worktree）、`shared_serial`（共享 worktree，写任务串行）或 `host`（整机/环境模式，主机
全局写操作串行）。Center 派生车道和结果路由，模型不能自行指定 `lane_key` 或伪造主体。

## 4. 组件和身份边界

### 4.1 Center

Center 是唯一的公网控制面和持久化事实来源，负责认证、策略、任务状态、能力路由、升级编排和管理界面。Center 不扫描 Agent 文件系统，也不直接代替 Agent 执行目标机命令。

### 4.2 Agent

Agent 是靠近操作系统和项目的执行信任边界，负责本机最终路径校验、子进程管理、输出收集、资源回收、断线恢复和能力适配。

### 4.3 一个物理终端的默认模型

默认一个物理终端只有一个逻辑主机身份和一个 command-agent。桌面和浏览器是该主机身份下的能力进程：

- `command-agent`：系统服务，负责 Center 通道、命令和任务生命周期；
- `desktop-companion`：用户会话进程，负责桌面观察与控制；
- `browser-agent`：按任务启动的浏览器监督进程，可再启动 Playwright、Patchright 或 Comoufox Worker。

桌面伴侣和 Browser Worker 不注册新的机器，不持有独立的 Center Token。确实需要权限或故障域隔离时，才显式运行多个 command-agent 实例，并为每个实例使用独立的 machine ID、Token、状态目录和能力集合。

### 4.4 用户和连接身份层

用户身份与机器身份分开：

- `Principal` 表示 RCM 内部用户或服务主体；它不等同于 ChatGPT 账号，也不由模型文本声明；
- `MCP Token` 只负责把请求映射到 Principal，并携带机器、项目、能力和配额范围；
- `Conversation`、`MCP Connection` 和 `Execution Session` 记录上下文与生命周期，但不能
  单独扩大权限；
- Principal 对机器可以通过显式 machine grant 授权，也可以通过项目成员关系间接获得；
- `Task`、`Attempt`、`Artifact` 和 `Audit Event` 必须保留主体关联；
- Agent Token 仍然只用于 Agent 到 Center 的机器通道，不下发给 ChatGPT 或普通用户。

## 5. 能力要求

### 5.1 命令与开发

- 支持 Shell、脚本、测试、构建、Git 和本机工具链；
- 不做命令白名单，不把所有 Shell 语义重写成自定义语言；
- 仍限制请求大小、超时、并发、输出、子进程树和磁盘占用；
- 结构化文件/Git/检查工具优先，原始 Shell 是必要时使用的有界逃生口；
- 项目任务优先在 Agent 管理的 worktree 中执行，原始 checkout 只有在本机明确接受后才改变。

### 5.2 Desktop

用户态 Desktop Companion 至少支持：

- 应用启动；
- 屏幕和窗口枚举；
- 截图和图片工件；
- 窗口聚焦；
- 点击、拖拽、按键和文本输入；
- 剪贴板读写。

所有桌面动作必须有能力声明、输入/图像上限、用户会话检查、过期句柄检查和明确失败结果。桌面伴侣不可达时，不能伪造成功；command-agent 的无人值守命令能力不能因此被拖垮。

### 5.3 Browser

Browser Agent 只提供少量结构化动作，不暴露 Playwright/Patchright/Comoufox 的全部底层 API。至少支持导航、快照、元素引用、点击、填写、按键、等待、截图、下载、标题和 URL。

- Cookie、Profile、扩展和 CDP 凭据只留在目标机；
- 页面快照和元素引用有节点数、文本、图片和生命周期上限；
- 下载、截图和网络/控制台摘要使用有界工件；
- 引用失效时必须重新观察，不得盲目重放动作；
- 任务超时、取消、Agent 重启或异常时，必须回收整个浏览器子进程树和临时目录；
- 浏览器 Worker 不成为第二台物理机器，也不向 Center 上传凭据。

## 6. 权限与模型误操作防护

### 6.1 授权链

每个会话或任务都应绑定一份由 Center 生成的执行契约，至少包含：

- machine/host/Agent 身份；
- project、workspace、path 或 unrestricted 范围；
- command、desktop、browser 能力；
- 运行用户和网络策略；
- 并发、CPU、内存、输出和时间预算；
- 过期时间、幂等键和风险级别；
- 是否需要一次性权限提升确认。

模型只接触精简的会话摘要和任务 ID，不接触 Admin Token、Agent Token 或完整主机配置。

### 6.2 审批原则

- 低风险、已在会话范围内的普通操作可以自动执行；
- 权限提升或高影响动作才触发一次确认，而不是每条命令都弹窗；
- 发布、部署、批量删除、系统用户/防火墙/服务修改、凭据导出等动作必须有明确的任务意图和风险记录；
- 自动执行模式不能绕过路径、敏感文件、只读会话、输出、资源和生命周期硬限制；
- 每次自动授权仍写入精简审计，不保存完整命令、环境变量和敏感结果。

### 6.3 容器不是强制边界

RCM 不要求所有任务运行在容器或虚拟机里。可信的整机模式可以直接使用宿主机权限；如果未来需要更强隔离，可以增加低权限账户、ACL、系统服务限制、容器或虚拟机后端，但不能改变任务和权限契约。

## 7. 异步任务与可靠性

异步是端到端契约，而不是把同步方法放进线程池。

- 创建任务立即返回 `task_id`；
- 请求生命周期、任务生命周期和日志观察游标彼此独立；
- `idempotency_key` 确保超时重试不会创建第二次执行；
- 任务使用租约和 Attempt 区分首次派发、重新领取和不安全重放；
- Center 重启、Agent 断线或 MCP 请求失败时，原任务继续运行或进入明确的可恢复/失败状态；
- 输出按字节或序列游标分页，不把完整 stdout/stderr 塞进 MCP 上下文；
- 取消必须能终止子进程树，并以明确终态收口；
- 长任务、截图、下载和升级包都使用大小、时间和并发上限；
- 任务状态、租约、Attempt、游标和工件元数据必须持久化。

主要传输优先级：

1. 事件唤醒或 WebSocket；
2. HTTPS 长轮询作为可靠补偿和兼容通道；
3. 在确有收益时增加 QUIC；
4. 固定间隔轮询只能作为明确标记的旧环境兼容回退。

任何通知丢失都必须能够通过任务 ID、版本、游标和下一次显式读取恢复，不能把通知本身当作事实来源。

## 8. 数据、隐私和存储

- 生产 Center 使用 PostgreSQL，Liquibase 管理 schema；
- PostgreSQL 是 Principal、用户 MCP Token 摘要、对话/连接、机器、任务、租约、Attempt、项目、worktree、执行车道、升级和工件元数据的唯一事实来源；
- 内存只保存可丢失的唤醒状态和短期缓存，不能双写或替代数据库；
- 大日志、截图、下载和升级包使用受控工件存储，不放入任务 JSON 或 MCP 文本；
- 文件内容、Cookie、Token、环境变量、私有路径和完整命令不进入指标标签；
- 日志、审计、错误和控制台默认脱敏；
- 浏览器 profile、桌面图像和命令输出只在有明确权限时返回，且必须有界。

## 9. 认证与部署

### 9.1 凭据分层

| 凭据 | 用途 | 生命周期 |
| --- | --- | --- |
| 兼容 MCP Token（owner/shared） | 兼容旧连接器调用 `/mcp`；映射到 `owner/shared-domain` 主体 | 迁移期保留；确认用户 Token 已迁移后由管理员手动撤销 |
| 用户 MCP Token | 把 Web 用户或服务主体映射到 `/mcp` 及其机器/项目/能力范围 | 不透明、可撤销；由 Console/Admin 签发，可设置有效期和配额；不要求 OAuth/JWT |
| Admin Token | React 控制台和 Admin API | 与 MCP Token 分离，手动通过部署环境替换 |
| Enrollment Token | 首次安装、重装或身份恢复 | 一次性、短期、绑定机器名称 |
| Agent Token | Agent 日常连接 Center | 每台 Agent 独立，注册成功后换取 |

生产不使用长期注册令牌，不把 Enrollment Token 写入长期服务配置，也不要求 OAuth 2.1 才能完成基本接入。用户 MCP Token 使用不透明随机值，Center 只保存哈希并支持手动撤销/重新签发；不把 Token 轮换做成普通模型工具。紧急凭据替换通过受保护的环境变量/Kubernetes Secret 和受控重启完成。

### 9.2 公网和宿主机

- Center 使用自有 `remote-connect-mcp-*` 域名和 HTTPS；
- 生产主链路不依赖 Cloudflare Tunnel；Tunnel 只作为没有公网入口时的可选部署方式；
- Agent 只主动连接 Center，不监听公网入站端口；
- Agent 宿主机不需要为 Agent 单独创建 DNS 记录；
- ChatGPT 连接器始终使用固定 `/mcp` URL，Center、Agent、Console 升级不改变连接器配置。

### 9.3 构建和平台

- Center：Java 25 Native Image，部署在 Kubernetes；
- Agent：Java 25 模块化 Native Image，不引入完整 Spring；
- Console：React 19 + TypeScript + Vite，独立静态部署；
- 数据库：PostgreSQL + Liquibase，不使用 Flyway；
- Linux amd64/arm64 和 Windows amd64 是首要原生目标；Windows ARM64 没有可复现的 Native Image 目标时，必须明确使用兼容包；
- Java、Native Image、React 和正式安装包全部由 GitHub Actions 构建；开发机和目标机不执行正式构建。

## 10. 控制台和 MCP 体验

控制台采用经典后台布局，至少提供主体/Token、机器、项目/成员、任务、输出、工件、升级、审计和设置导航。实时状态使用事件连接和有界重连，表格、日志、页面树和机器列表全部分页或虚拟化。用户 Token 只在生成时显示一次，支持范围、有效期、配额和撤销；主体和项目成员页面不把完整密钥写入浏览器持久存储。

MCP 面遵循以下原则：

- 工具按用户任务组织，而不是按内部类或每台机器复制；
- 机器数量不增加工具元数据；
- 目标 machine ID 必须显式、可审计；
- 主体由 Bearer Token 在 Center 侧派生，模型不填写 `principal_id`；对话/连接 ID 只用于关联任务，不作为授权依据；
- 默认返回下一步所需的最小结果；
- 长输出、截图、DOM、错误和列表均有界；
- 新能力优先扩展已有任务/能力协议，只有无法复用时才新增 MCP 工具；
- 新版本不得要求重新创建既有连接器。

## 11. 升级和兼容

升级流程必须支持：

1. GitHub Actions 发布平台和架构匹配的二进制、SHA-256、SBOM 和签名；
2. Center 发现版本和资产覆盖；
3. 管理员选择版本、金丝雀数量和批次大小；
4. Agent 校验资产后原子替换并保留上一版；
5. 启动失败自动回滚；
6. Center 依据 Agent 实际心跳版本判断成功，而不是只看下载请求；
7. 失败目标暂停或重新排队，管理员可以取消活动；
8. 升级不改变 machine ID、Agent Token、服务配置或 ChatGPT MCP URL；
9. Center 至少兼容上一代 Agent，旧 Agent 遇到未知能力时安全失败。

## 12. 非目标

当前版本不把以下内容作为核心交付：

- 代理任意第三方 MCP；
- 强制 OAuth 2.1；
- 强制所有任务容器化；
- 通用远程桌面/RMM 功能；
- 完整多租户 SaaS、复杂 RBAC、跨组织计费和 Center 多副本高可用；
- 把 ChatGPT 的私有账号/对话字段当作 RCM 的可信授权来源；
- 把 ChatGPT 的每条动作审批策略写进 Center；
- 暴露海量 Playwright、桌面或 Shell 底层 API；
- 依赖固定频率轮询；
- 在本机生成 Java、Native Image 或正式前端产物。

## 13. 优先级和验收标准

### P0：平台必须可靠

- 一个固定 MCP 地址可以连接所有已注册机器；
- 机器注册、独立 Agent Token 和一次性 Enrollment Token 可用；
- 任务创建、幂等重试、取消、超时、输出续传和 Center/Agent 重启恢复可验证；
- PostgreSQL + Liquibase 是生产唯一事实来源；
- Agent 空闲资源、并发、输出和子进程数量受控；
- 项目、路径和整机策略不会被模型请求绕过；
- Token、Cookie、环境变量和私有内容不进入日志、指标、错误和公开仓库；
- GitHub Actions 完成目标平台构建和可安装包验证。

### P1：核心生产体验

- Project Registry 和 Git worktree 隔离闭环；
- Windows 用户态 Desktop Companion 完成截图、启动和基本输入；
- Browser Agent 完成 Playwright/Patchright/Comoufox Worker 监管、工件和资源回收；
- React 控制台完成任务、机器、项目、审计和升级流程；
- Agent 版本 canary/批次升级和回滚可用；
- WebSocket 事件唤醒、配置 generation 热更新和心跳自描述在真实反向代理下通过故障演练。

### P2：规模化增强

- QUIC 传输；
- 对象存储和集中日志；
- SLO、告警和升级通知；
- 更丰富的桌面和浏览器平台适配。
- 旧 Go 回滚路径退出。
- 轻量多主体、对话、MCP 连接、项目 ACL、执行车道和 Desktop/Browser 会话隔离（`P2-05-lite`）。

需求决策：当前不做 Center 多副本/高可用和完整多租户 SaaS（原 P2-02 及 P2-05 的完整范围）。新增 `P2-05-lite`，只实现轻量 Principal、用户 Token、项目 ACL、执行车道、配额和桌面/浏览器会话隔离；单 Center、单 MCP Endpoint 和事件驱动唤醒仍保持为生产基线。

## 14. 需求变更规则

新增功能必须先回答三个问题：

1. 它解决的是 ChatGPT 到可靠终端执行链路中的哪一个真实问题？
2. 它能否复用现有 Center/Agent/任务/能力协议，而不扩大 MCP 上下文？
3. 它的权限、资源、隐私、失败恢复和升级兼容边界是否可以被明确验收？

不能回答以上问题的功能不进入 P0/P1；只属于展示、实验或未来扩展的内容必须标记为 P2 或单独项目，不得混入生产基线。
