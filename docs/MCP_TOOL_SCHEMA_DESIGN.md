# MCP Tool 与 Schema 设计

版本：v0.2 设计稿

日期：2026-09-18（Asia/Shanghai）

状态：设计已批准，Java 代码正在按硬切换方案实施；尚未在本机编译或部署

## 结论

RCM 应采用“**模型看到少量能力入口，Center/Agent 内部完成路由**”的设计。外部不暴露每台机器、每个 Agent 或每个底层 API 一个 Tool；同一能力内部使用严格的 `operation` 枚举和分支 Schema。

开发人员建议的方向成立。本轮采用硬切换：旧 `machines_list`、`command_start` 等 Tool 不再注册，也不保留旧参数或 legacy 开关；固定 `/mcp` URL 和 Agent 身份保持不变。ChatGPT Web 使用 OAuth access token，Codex/CLI 使用直接 Bearer；认证方式不改变工具数量，连接器只需在模型面变化后重新发现一次新的 8 个逻辑入口。

目标模型面如下：

```text
RCM MCP
├── machines       机器发现：list / info
├── command        Shell、脚本、构建和系统命令
├── desktop        截图、窗口、输入和桌面应用
├── browser        导航、观察、定位、交互和下载
├── project        项目、worktree 和 Git 操作
├── artifact       文件上传、下载和读取引用
├── task_read      等待和读取有界输出
└── task_cancel    取消任务
```

这 8 个入口不是 8 个物理进程。Center 根据机器能力把请求路由到一个主 `command-agent` 以及它管理的 `desktop-companion`、`browser-agent`。模型只需要知道“目标主机能否执行、桌面交互、浏览器自动化或文件传输”，不需要知道内部 Agent 数量。

## 同类产品的做法

| 类型 | 代表做法 | 对模型的体验 | 对 RCM 的启发 |
| --- | --- | --- | --- |
| 细粒度浏览器 Tool | Playwright MCP 把导航、快照、点击、填写、标签页等拆成许多结构化 Tool，并用 accessibility snapshot 产生短期元素引用 | 单步确定性强，模型容易复用 `ref`；但 Tool 列表和快照上下文会变大 | 保留“快照 → 引用 → 动作”的闭环，但不照搬几十个 Tool；把操作收进 `browser` 的有限枚举，并按需返回快照/差量 |
| 单一 Computer Tool | OpenAI/Anthropic 的 computer-use 采用一个综合入口，动作是 screenshot、click、type、scroll、keypress 等联合类型，动作后回传截图 | Tool 选择非常简单，适合未知 GUI；依赖视觉和坐标，误点成本高 | `desktop` 采用一个入口和语义动作；优先窗口/控件句柄，只有无可访问性信息时才使用坐标 |
| 高层浏览器任务 | Browser Use 同时提供 `browser_task + monitor_task` 的整段任务模式，以及本地逐动作模式 | 整段任务的上下文最少，但中间步骤、失败原因和可控性较弱；逐动作模式更可审计 | RCM 默认逐动作、有句柄和任务状态；未来可增加受限的高层 `run`，不能取代可观察的基础动作 |
| 远程开发运行时 | WebCodex 以 Server/Runner、ProjectGrant、持久 Job 和 Console 为核心，Tool 只是进入开发环境的方式 | 模型不需要理解机器进程拓扑，长任务和项目边界由后端维持 | RCM 的 Center/Agent、项目范围、长任务、工件和 Console 方向是正确的，应继续把状态放在后端而不是 MCP 文本 |
| MCP 风险标注 | MCP 的 `readOnlyHint`、`destructiveHint`、`idempotentHint`、`openWorldHint` 是客户端决策提示，不是授权合同 | 标注有助于审批 UX，但错误标注不能替代安全控制 | Center 必须做确定性的 ACL、范围、车道、资源和风险校验；annotation 只负责提示和减少误审批 |

Playwright MCP 官方文档同时强调了 accessibility snapshot 的 LLM 友好性和 MCP 相对 CLI 更高的 Tool/快照成本；这正好说明 RCM 应吸收它的引用机制，而不是复制它的 Tool 数量。Browser Use 的“高层任务 + 逐动作”双模式也说明两种抽象可以共存，但默认入口必须先保证可观察和可恢复。

## 当前实现的问题

当前 Java `McpConfiguration` 的能力已经完整，但模型面 Schema 仍偏开发者视角：

| 位置 | 当前问题 | 对模型的影响 |
| --- | --- | --- |
| `project` | 一个 Tool 承载 12 类操作，字段全部平铺 | `operation` 与字段之间没有条件约束，模型容易同时填无关字段 |
| `desktop` | 一个 Tool 承载截图、窗口、指针、键盘、剪贴板和启动 | 参数多，底层坐标/API 细节压过“控制桌面”的意图 |
| `browser` | 公共参数只有一个 JSON 字符串 `command` | 模型看不到可用操作、定位方式和返回物类型 |
| `command_start` 等 | 重复暴露 `lane_mode`、`session_id`、`risk`、`workspace_policy` 等内部字段 | 调度实现细节污染 Tool Schema，模型可能错误地伪造会话/车道 |
| 通用 Schema helper | 主要只有 `type`、`description`、`required` | 缺少 `enum`、长度/数值边界、互斥字段和条件必填 |
| 输出 | 部分结果只有文本 JSON，结构化输出不统一 | 模型无法稳定判断下一步是等待、读游标还是读取工件 |
| Tool annotations | 混合读写 Tool 只能使用一个静态 annotation | 截图、机器查询等低风险动作可能与写操作一样触发审批 |

### 认证元数据

当 Center 启用 OAuth 时，每个需要认证的 Tool 在 `_meta.securitySchemes` 中镜像
`{ "type": "oauth2", "scopes": [...] }`；Java MCP SDK 当前没有顶层
`securitySchemes` builder，因此使用 OpenAI Apps SDK 规定的 `_meta` 兼容镜像，不把静态
Bearer/API Key 声明给 ChatGPT。未启用 OAuth 时不发布这段元数据，直接 Bearer 客户端仍由
Center 传输层校验。缺少凭据时 Center 通过 `WWW-Authenticate: Bearer resource_metadata=...`
触发 OAuth 发现；工具业务权限仍由 Principal scope/ACL 强制执行，元数据不是授权依据。

旧 Go 版本值得保留的优点是：工具数量少、参数类型清楚、每个结果都给出下一步、命令和任务天然异步。新设计应保留这些优点，不直接复制 Go 版本缺少项目、浏览器和 Artifact 的限制。

## 方案比较与选择

| 方案 | 模型面 | 优点 | 主要问题 | 结论 |
| --- | --- | --- | --- | --- |
| A：维持当前 12 个 Tool，仅补 Schema | `machines_list`、`machine_info`、`command_start` 等 | 改动最小 | Tool 名称偏实现；项目、Desktop、Browser 的 Schema 仍容易膨胀 | **否决，不进入实现** |
| B：8 个逻辑 Tool，内部用 `operation` 分支 | `machines`、`command`、`desktop`、`browser`、`project`、`artifact`、`task_read`、`task_cancel` | 数量克制，意图清晰，能保留项目/文件能力；可给取消单独的破坏性 annotation | 需要一次工具名称硬切换和连接器刷新 | **选定为最终模型面** |
| C：5 个超级 Tool，把项目/文件/任务全部塞进 `command` | `machines`、`tasks`、`command`、`desktop`、`browser` | Tool 数量最少 | Schema 分支过宽，读写 annotation 混杂，文件和 Git 语义会污染命令 Tool | 不采用 |

选择 B 的原因是：Tool 数量已经足够小，但不会为了追求“5”而把不相干的项目、Artifact 和任务状态硬塞进命令入口。`task_cancel` 单独保留尤其重要，它既能保持破坏性提示准确，也能避免模型把“读取任务”误判为写操作。

## Schema 总原则

### 1. Tool 只表达意图，不表达实现

Tool 名称使用稳定的能力词：`command`、`desktop`、`browser`。不出现 `playwright_click`、`windows_user32`、`systemctl`、`powershell`、`browser-worker` 或具体机器名。

Tool description 使用固定四段信息，控制在两句话内：

```text
Use when: 何时调用。
Prerequisite: 需要的机器能力或前置结果。
Returns: 返回 task/artifact/摘要中的哪一种。
Next: 长任务下一步调用哪个 Tool。
```

属性 description 只说明语义、范围和默认值，不重复实现细节。所有公共输入统一：

- `additionalProperties: false`；
- 字符串有 `minLength`、`maxLength`，命令、路径、URL 分别限制长度；
- 数值有 `minimum`、`maximum`；
- 离散值使用 `enum`，不在 description 中手写一串“可选值”而不落 Schema；
- 可选字段默认省略，不要求模型发送 `null`；
- 不使用 `$ref` 作为第一版公共 Schema，避免 ChatGPT Web/旧 MCP 客户端解析不完整；内部 Java 代码可以复用 Schema builder。

### 2. 目标和范围必须容易填写

保持 `machine_id` 为执行类 Tool 的顶层必填字段，因为它是模型从 `machines` 结果复制的最稳定句柄。范围放入一个小型 `scope` 对象：

```json
{
  "machine_id": "machine-…",
  "scope": {
    "mode": "project",
    "project_id": "project-…",
    "worktree_id": "worktree-…",
    "cwd": "src"
  }
}
```

`scope.mode` 枚举为 `auto`、`project`、`worktree`、`path`、`workspace`、`unrestricted`。条件规则如下：

- `auto`：使用机器策略；模型通常不需要填写；
- `project`：必须有 `project_id`；
- `worktree`：必须有 `project_id` 和 `worktree_id`；
- `path`：必须有绝对 `root`；
- `workspace`：使用机器声明的 workspace 或显式 `root`；
- `unrestricted`：只有用户明确要求整机操作时才能填写，不能由模型默认猜测。

`session_id`、`lane_mode`、`workspace_policy`、内部 `risk` 和主体标识不再作为模型公共字段。Center 从 MCP 连接、主体、对话和操作类型派生它们；旧字段和旧参数不再接受。

### 3. 失败重试和异步必须可发现

数据库层仍然必须为所有会改变状态的请求建立幂等键，但模型面不应强迫每次随机生成一个键。推荐规则是：调用方可以显式提供格式为 `^[A-Za-z0-9._:-]{8,128}$` 的 `idempotency_key`；省略时，Center 使用主体、MCP 连接、工具名、规范化参数指纹和短期去重窗口生成服务端键，并在结果中返回该键。跨较长时间重试或用户明确要求“继续同一个操作”时，模型复用返回的键或 `task_id`。这样既保留可靠重试，又减少模型因为忘记复制随机键而重复执行的概率。

所有启动类 Tool 立即返回 `task_id`，不在 MCP 请求中等待长任务。`wait_ms` 只允许短等待，默认 `0`；长任务使用 `task_read` 搭配 `change_seq`。结果只保留一个结构化 `next_action`，不输出长篇操作说明。

### 4. 结果使用统一的紧凑结构

所有 Tool 同时提供 `outputSchema` 和单个 JSON 文本块。推荐结果形状：

```json
{
  "kind": "task",
  "task": {
    "id": "task-…",
    "status": "queued",
    "machine_id": "machine-…",
    "attempt": 1
  },
  "next_action": {
    "tool": "task_read",
    "operation": "wait",
    "task_id": "task-…",
    "cursor": 0,
    "change_seq": 1,
    "wait_ms": 20000,
    "reason": "wait_for_change"
  }
}
```

终态任务只返回有界状态、错误码、字节游标和工件句柄。错误使用固定结构，不返回堆栈、环境变量、完整命令或 Token：

```json
{
  "kind": "error",
  "error": {
    "code": "CAPABILITY_UNAVAILABLE",
    "message": "target machine does not advertise browser",
    "retryable": false
  },
  "next_action": {
    "reason": "fix_request"
  }
}
```

## 8 个逻辑 Tool 的设计

### machines

`operation` 为 `list` 或 `info`。`list` 支持 `offset`、`limit`（1–25）、可选 `capability` 和 `online` 筛选；`info` 只要求 `machine_id`。列表只返回 `id/name/os/arch/version/capabilities/scope_mode/online`，路径、运行预算和心跳详情留给 `info`。

机器返回的 `capabilities` 是路由提示，不是新的 Tool 名称。模型据此选择 `desktop`、`browser` 或 `artifact`，不直接选择物理 Agent。

### command

一个入口代表“在指定机器执行 Shell/脚本/构建/系统命令”。输入只保留：`machine_id`、`command`、`scope`、`env`、`timeout_seconds`、`idempotency_key`、可选短 `wait_ms` 和明确的 `requires_elevation`。`env` 的值只能是普通字符串，禁止把凭据放入 MCP 参数。

返回 `kind=task`。模型下一步只需根据用户是否需要立即观察，选择 `task_read(wait)`；不把 stdout 自动塞进当前上下文。

### desktop

保留一个统一入口，但把底层动作提升为语义操作，并使用 `oneOf` 表达条件字段：

```text
screenshot          截图或指定区域截图
screens             枚举屏幕
windows             枚举窗口
launch              启动用户会话中的应用
pointer             click / double_click / right_click / move / drag
shortcut            使用 keys 数组发送组合键，例如 CTRL+ALT+T
type                输入文本
clipboard            read / write
focus               聚焦窗口
result              读取已有桌面任务结果
```

例如快捷键不再使用底层单值 `key`：

```json
{
  "operation": "shortcut",
  "machine_id": "machine-…",
  "keys": ["CTRL", "ALT", "T"],
  "idempotency_key": "desktop-shortcut-1"
}
```

截图返回 `artifact_id` 或受限图片结果；桌面不可用时返回 `DESKTOP_SESSION_UNAVAILABLE`，不能伪造成功。

### browser

不再让模型填写一整段无法发现的 JSON 字符串。公共 Schema 使用结构化 `request`，Center 再转换为 Agent 内部 Worker 协议。推荐分组为：

```text
navigate   open / reload / back / forward
observe    snapshot / title / url / text
interact   click / hover / fill / check / uncheck / select / press
wait       wait / wait_for_selector
artifact   screenshot / download
```

元素只接受 Worker 返回的 `rcm-ref-v1` 引用，或明确的受支持定位类型；不鼓励模型猜测 CSS/XPath。快照返回有界文本和最多固定数量的元素引用，普通动作只返回标题/URL/变化摘要和新增或失效引用，不重复发送整页快照；引用失效时返回 `STALE_BROWSER_REF` 和一次重新观察建议。

浏览器引擎、Profile、Cookie、CDP 地址和 Playwright/Patchright/Comoufox 选择留在 Agent 配置，不能出现在 MCP Schema 或模型上下文。

### project

保留一个项目入口，但 `operation` 使用枚举：`list`、`detail`、`register`、`remove`、`worktree_create`、`worktree_remove`、`git_status`、`git_diff`、`git_log`、`git_commit`、`git_merge`、`git_merge_abort`。通过 `oneOf` 约束每个操作的必填字段：

- 查询操作只要求机器/项目句柄，不要求幂等键；
- 注册、删除、worktree、commit、merge 操作必须经过幂等预约；模型可以提供幂等键，也可以由 Center 生成；
- `git_diff.mode` 仅允许 `stat` 或 `patch`；
- 项目列表默认不返回本地路径，只有 `detail + include_paths=true` 才返回路径；
- merge/commit 等写操作仍由 Center 车道和 ACL 决定，模型不能填写 `lane_key`。

### artifact

合并当前 `artifact_put`、`artifact_get`、`artifact_read` 为一个文件入口，`operation` 为 `put`、`get`、`read`：

- `put`：ChatGPT 文件对象、目标机器、目标路径；
- `get`：机器源路径、展示名称；
- `read`：`artifact_id` 和 `transfer_id` 二选一；
- 文件内容永远不进入 MCP 文本，只返回句柄、大小、MIME、SHA-256、状态和 Viewer/下载引用；
- `overwrite` 默认为 `false`，`expected_bytes` 允许 `0`，并继续执行哈希和原子落盘校验。

### task_read

合并只读的 `task_wait` 和 `task_output`，`operation` 为 `wait` 或 `output`：

- `wait`：`task_id`、可选 `cursor`、`wait_ms`（0–20000）；
- `output`：`task_id`、`cursor`、`limit`（0–65536）；
- 统一返回 `task`、`output{cursor,next_cursor,more,text}` 和有限字段的 `next_action{tool,operation,task_id,artifact_id,transfer_id,cursor,change_seq,offset,limit,wait_ms,reason}`；
- `next_action` 只是模型可复制的提示，不是新的权限来源；Center 仍按当前 Bearer、主体、会话和任务句柄重新授权；
- 默认只返回一页，不把完整日志放进模型上下文。

### task_cancel

单独保留取消入口，只有 `task_id` 和可选短 `reason`。它作为破坏性 Tool 单独标记，避免 `task_read` 因混合了取消而被模型/客户端当成写操作。

## 硬切换实施顺序

1. 直接注册 8 个逻辑 Tool，删除旧 Tool 注册、旧参数公开 Schema 和 legacy 开关。
2. `browser` 只接受结构化 `request`；旧 JSON 字符串不再解析。
3. 从公共 Schema 删除 `session_id`、`lane_mode`、`workspace_policy`、risk/elevation 等内部字段，Center 内部自动派生。
4. 为 `desktop` 使用 `shortcut`、`pointer`、`clipboard` 等语义 operation，内部映射到平台协议。
5. 全量工具提供 `outputSchema` 和精简 JSON 文本；新构建发布后刷新 ChatGPT Web 连接器。

### 从模型使用角度的最终偏好

如果由模型自己选择，我更偏好以下调用循环：

```text
machines(list)                  # 只找目标能力，不拉完整详情
  -> command / desktop / browser / artifact
  -> 返回短句柄和结构化 next_action
  -> task_read(wait 或 output)   # 只在需要时观察
  -> artifact(read)              # 需要文件、截图或下载时才取
```

我不希望每次调用都填写内部 session、车道、风险、Agent 名称、随机幂等键，也不希望每次浏览器点击都重新收到完整 DOM/截图。模型最需要的是稳定的 `machine_id`、`project/worktree` 句柄、短期浏览器引用、清晰枚举和可恢复的任务句柄；其余信息应由 Center/Agent 自动生成或按需返回。

### 后续：基于证据调整，而不是继续加 Tool

只根据以下指标决定是否拆分读写入口：

- Tool 选择错误率；
- Schema 总字节数和每轮 MCP 上下文增量；
- 结构化参数校验失败率；
- 无必要审批率；
- `STALE_BROWSER_REF`、范围选择错误和重复任务率。

如果混合 `desktop` 或 `project` 导致审批明显过多，只增加一个读/写边界 Tool（例如 `desktop_observe`），不把每个底层动作拆成单独 Tool。

## 验收门槛

- `tools/list` 的 Schema 不包含真实 Token、Cookie、Profile、完整主机路径或内部 Agent 名称；
- 模型只看 `machines` 的摘要就能选择 command/desktop/browser/artifact；
- 缺少能力时返回固定错误码和下一步，不出现长错误堆栈；
- 同一逻辑请求重试命中显式或 Center 生成的幂等预约，不会产生第二个任务；
- `command`、`desktop`、`browser`、`artifact` 都立即返回任务句柄，长任务不占用 MCP 请求；
- 快照、日志、文件和图片均通过游标/句柄按需获取；
- ChatGPT 连接器只需保留原 URL/Token 并重新发现一次 Tool 列表，不提供旧 Tool 调用兼容；
- 第一阶段只需要 GitHub Actions 构建和真实 Web E2E，不允许本机构建产物。

设计已批准，下一步修改 Java `McpConfiguration`、输出 Schema、浏览器请求适配器和对应单元/集成测试；本机不构建，生产工具面在 GitHub Actions 产物发布后一次性切换。
