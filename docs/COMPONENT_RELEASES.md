# 组件发布与机器更新

日常入口是推送到 GitHub `main`。正在运行的组件发布不会被下一次推送取消；若多次推送排队，GitHub 只保留最新待处理运行。每个组件的 main、Tag 与手动发布共用一个并发队列，避免两个运行争用版本。三个工作流分别从对应组件上次发布成功的源码累计检测变更，覆盖被替换运行中的改动；未命中对应组件时直接跳过构建。发布前固定版本 Tag 到本次源码 SHA；变更检测只认已发布 GitHub Release，预留但未发布的版本不作为成功基线。Center/Console 兼容已有内部基线 Tag。PR 与发布共用同一份组件路径规则。

| 变更 | 构建与发布 | 后续动作 |
| --- | --- | --- |
| `java/center`、Center Dockerfile、Artifact Viewer | Center | GitOps 只更新 Center 与迁移 Job |
| `java/agent`、`desktop`、`browser`、安装脚本与浏览器运行时 | Agent | 发布 Agent/桌面/浏览器组件；Center 发现版本后分批唤醒 Agent |
| `web/` | Console | GitOps 只更新 Console |
| `java/protocol` 或 Gradle 公共配置 | Center 和 Agent | 两个组件各自完整测试和构建 |

主分支构建通过后自动发布正式版，Center/Console 的 GitOps 自动部署生产，Agent 正式版由 Center 自动调谐。组件版本各自递增，不需要人工打 Tag 或再点一次生产发布。版本分配按已有正式 Tag 的数字顺序增加 patch，失败构建已预留的版本也不会覆盖；同一源码重试复用预留版本。PR 运行 `change-checks.yml` 变更检查，不重复执行主分支的发布构建。手动预发布仍可用于 staging；Tag 和手动入口保留给异常恢复。沿用 `java-vX.Y.Z` Agent Tag，新资产统一使用 `remote-control-mcp-*` 名称；Center 仅在新名称不存在时兼容历史资产名称。Agent 发布前核对 9 个平台组件 ZIP 及对应 SHA-256 文件；缺失或不匹配就停止发布。

生产由 Argo CD Application `remote-control-mcp-production` 管理，期望配置位于私有 GitOps 仓库 `Prodigalgal/ircs-prod-config` 的 `remote-control-mcp-production/`，部署到 `remote-control-mcp` 命名空间。Argo CD 自动同步并修复配置漂移，管理 Center、Console、数据库、持久卷声明、HTTPRoute 和监控资源；数据库迁移成功后才启动应用。工作流只更新相关组件的不可变镜像摘要。Agent 运行在各自宿主机上，由 Center 发现 Release 并调用本机 Helper 更新。

生产 MCP 入口为 `https://<center-host>/mcp`，控制台为 `https://<console-host>/console/`；实际域名保存在私有 GitOps 配置。旧域名暂时转发到同一服务，供现有客户端迁移；新增客户端和安装命令使用新域名。更换项目名称不重写历史数据库迁移标识或任务记录，也不重新创建机器身份和凭证。

Agent 在构建前固定源码 Tag，发布时验证该 Tag，不再次请求以旧提交创建版本。若只有发布阶段失败，仍使用同一 Agent 工作流，填写原运行的 `resume_run_id`：它核对本仓库、源码 Tag、所有组件测试与构建结果及制品有效期，复用通过验收的制品，重新生成清单并签名发布。已发布版本不能覆盖；这条恢复路径不重新构建，也不改变日常推送入口。

Center 每 5 分钟查看已发布的 Agent Release，生产按正式版本数字顺序选择最高版本，迟到的旧发布不会遮住新版。只有目标平台的 ZIP、校验文件和有效组件清单齐全，且没有进行中或暂停的活动时，才创建升级活动。清单缺失或无效不能降级成仅更新 Agent；手工仅更新命令能力可提供空组件计划。Desktop/Browser Native 组件只下发给已启用相应能力且满足兼容门槛的机器。

升级验收与容错采用以下固定规则：

- 默认先验证 1 台，首批全部通过后每批推进 3 台。成功要求目标 Agent 在线、版本匹配，且实际下发的每个组件具有 `completed` / `already-current` 证据。Helper 安装回报后最多等待 2 分钟核验；重复回报不会延长核验期限。
- 每台机器、每个版本最多自动派发 3 次（首次加 2 次重试），退避分别为 30 秒、2 分钟，仅重试明确的临时下载故障。现有 Agent 每次派发内部还有最多 3 次短下载重试；这两个上限分别限制派发和 HTTP 下载，不代表最多 3 个网络请求。退避期限、尝试次数和组件证据保存在 PostgreSQL，Center 重启后继续。
- 校验摘要、签名、认证、权限或资产缺失错误不自动重试。派发 5 分钟、下载/安装状态 20 分钟无有效回传会失败；无法确认 Helper 已退出时不再次唤起。需要确认本机状态后人工重试。不同尝试号的迟到回报不改变当前尝试；已验证成功不可被旧失败回报覆盖。
- 首批失败暂停整个活动。首批通过后，普通批次中的单机失败不会阻止健康机器继续；最终有任何本轮失败即为 `failed`，不会按多数成功标为成功。同版本失败机器从后续自动活动排除，避免新建活动重置预算；人工单机重试保留递增尝试号和已完成组件，只允许一个活动运行。
- 开始时离线、或派发前掉线的机器记为 `deferred`，不消耗尝试、首批或批次名额，也不计入本轮验收分母。已派发机器在重启期间短暂离线则等待原租约，不能立即重派。活动进行中重连可加入；活动结束后重连由自动发现创建补更活动，历史活动保留原结果。
- 本轮所有非待补更目标通过才为 `completed`；仍有待补更时 `summary.fully_updated=false`。全部离线的人工请求标为 `deferred`，不是 0/0 成功，也不占活跃活动名额。自动发现只在至少一台待更新机器在线时创建活动。当前在线状态与历史安装结果分别展示；成功机器后来离线不改写成功证据。

自动升级默认启用；`RCM_CENTER_AGENT_AUTO_UPGRADE_ENABLED=false` 可关闭，`RCM_CENTER_AGENT_AUTO_UPGRADE_INCLUDE_PRERELEASE` 控制是否跟随预发布。暂缓维护的机器通过逗号分隔的 `RCM_CENTER_AGENT_AUTO_UPGRADE_EXCLUDED_MACHINE_IDS` 明确排除；暂时离线不需要配置排除。排除不影响人工明确创建的活动。

Agent 版本已相同但适用组件缺少成功安装记录时，Center 仍会自动创建组件补更活动。判断同时核对清单中的组件版本和摘要、该机最新活动的 `completed` / `already-current` 回报；不能用 Agent 版本代替组件证据。旧 Agent 升级后新满足兼容门槛的组件，也会在下一轮调谐中补更。Helper 自己判断已安装版本，补更不重复替换当前 Agent。

启用 GitOps 时，仓库 Variables 提供 `RCM_CENTER_PRODUCTION_URL`、`RCM_CONSOLE_PRODUCTION_URL` 及需要预发布部署时的 `RCM_CENTER_STAGING_URL`、`RCM_CONSOLE_STAGING_URL`。工作流提交不可变镜像摘要后最多等待 10 分钟，自动验证 Center 实际版本与 readyz、Console 实际版本与源码 SHA。未就绪则工作流失败并保留期望配置和错误，不把“提交 GitOps”当成“部署完成”。Agent 的安装结果由 Center 记录，机器当前是否在线须同时查看；已完成的安装结果不会因后来离线被改写。

工作流的 Summary 记录源码 SHA、发布版本、镜像摘要、GitOps 提交及自动运行态核验结果。需要独立审计时，仍使用同一个只读入口：

```bash
python3 scripts/verify-release.py center vX.Y.Z --url https://<center-host>
python3 scripts/verify-release.py console vX.Y.Z --url https://<console-host> --source-sha <40位源码SHA>
RCM_VERIFY_ADMIN_TOKEN=<管理员令牌> python3 scripts/verify-release.py agent vX.Y.Z --url https://<center-host>
```

Center 校验 `/api/v1/version` 与 `/api/v1/readyz`；Console 校验镜像内的 `/release.json` 版本及源码 SHA；Agent 分页读取实际注册机器版本和适用于各机器能力的 Native 组件状态。默认只有**全部机器在线、版本一致且启用 Browser 的机器已验证 Camoufox 运行时**才算完整通过。当前尚无 Camoufox 运行时的自动更新与版本证明，因此这类机器会明确显示 `browser_runtime.verified=false`；若只需要验收 Agent 和 Native 组件，可显式加 `--native-only`，结果的 `coverage` 会标明缩小的范围。要单独确认可达机器，再加 `--online-only`。尚未填写 `--url` 的 GitOps 校验只证明已固定摘要，输出中运行态仍是 `unverified`。

Browser 的 Camoufox Node 包和浏览器本体在首次安装或 `deploy-desktop-browser.ps1` 迁移时安装。当前 Helper 的普通组件升级只处理 Native ZIP；浏览器运行时更新还需要单独完成，不能把 Native 升级成功等同于整个 Camoufox 运行时已更新。

历史 Console 流程可能已经部署了比版本 Tag 更高的镜像版本。切换前核对实际 `/release.json`，必要时一次性设置 `RCM_CONSOLE_VERSION_FLOOR` 为已部署的正式版本，确保自动分配的首个版本不会倒退；新版本 Tag 建立后，后续分配以更高的 Tag 为准。
