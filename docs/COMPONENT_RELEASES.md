# 组件发布与机器更新

日常入口是推送到 GitHub `main`。正在运行的组件发布不会被下一次推送取消；若多次推送排队，GitHub 只保留最新待处理运行。每个组件的 main、Tag 与手动发布共用一个并发队列，避免两个运行争用版本。三个工作流分别从对应组件上次发布成功的源码累计检测变更，覆盖被替换运行中的改动；未命中对应组件时直接跳过构建。发布前固定版本 Tag 到本次源码 SHA；变更检测只认已发布 GitHub Release，预留但未发布的版本不作为成功基线。Center/Console 兼容已有内部基线 Tag。PR 与发布共用同一份组件路径规则。

| 变更 | 构建与发布 | 后续动作 |
| --- | --- | --- |
| `java/center`、Center Dockerfile、Artifact Viewer | Center | GitOps 只更新 Center 与迁移 Job |
| `java/agent`、`desktop`、`browser`、安装脚本与浏览器运行时 | Agent | 发布 Agent/桌面/浏览器组件；Center 发现版本后分批唤醒 Agent |
| `web/` | Console | GitOps 只更新 Console |
| `java/protocol` 或 Gradle 公共配置 | Center 和 Agent | 两个组件各自完整测试和构建 |

主分支构建通过后自动发布正式版，Center/Console 的 GitOps 自动部署生产，Agent 正式版由 Center 自动调谐。组件版本各自递增，不需要人工打 Tag 或再点一次生产发布。版本分配按已有正式 Tag 的数字顺序增加 patch，失败构建已预留的版本也不会覆盖；同一源码重试复用预留版本。PR 运行 `change-checks.yml` 变更检查，不重复执行主分支的发布构建。手动预发布仍可用于 staging；Tag 和手动入口保留给异常恢复。沿用 `java-vX.Y.Z` Agent Tag 和现有资产名，避免中断 Center 升级目录及已安装 Agent。Agent 发布前核对 9 个平台组件 ZIP 及对应 SHA-256 文件；缺失或不匹配就停止发布。

Agent 在构建前固定源码 Tag，发布时验证该 Tag，不再次请求以旧提交创建版本。若只有发布阶段失败，仍使用同一 Agent 工作流，填写原运行的 `resume_run_id`：它核对本仓库、源码 Tag、所有组件测试与构建结果及制品有效期，复用通过验收的制品，重新生成清单并签名发布。已发布版本不能覆盖；这条恢复路径不重新构建，也不改变日常推送入口。

Center 每 5 分钟查看已发布的 Agent Release，生产按正式版本数字顺序选择最高版本，迟到的旧发布不会遮住新版。只有目标平台的 ZIP、校验文件和有效组件清单齐全，且没有其他进行中或暂停的活动时，才为在线且版本落后的机器创建活动。组件清单缺失或无效会阻止创建，不能静默降级成仅更新 Agent；手工明确选择仅更新命令能力时可提供空组件计划。Desktop/Browser Native 组件只下发给已启用相应能力的机器。首台为 canary，随后每批 3 台；任务唤醒是即时的。离线机器不阻塞这一轮，重连后按实际版本自动补更；手工活动仍可显式包含离线机器。升级下载有总时限和停滞时限；Center 定期将过期的派发、下载或安装状态收敛为带原因的失败，暂停活动，避免盲目重复启动 Helper。确认机器状态后在控制台重试该机；若旧活动只剩离线目标，可取消旧活动，让自动发现继续，离线机上线后会重新纳入。自动升级默认启用；可用 `RCM_CENTER_AGENT_AUTO_UPGRADE_ENABLED=false` 关闭，`RCM_CENTER_AGENT_AUTO_UPGRADE_INCLUDE_PRERELEASE` 控制是否跟随预发布。暂缓维护的机器可通过逗号分隔的 `RCM_CENTER_AGENT_AUTO_UPGRADE_EXCLUDED_MACHINE_IDS` 排除，其他机器继续自动更新；排除不影响人工明确创建的活动。

启用 GitOps 时，仓库 Variables 提供 `RCM_CENTER_PRODUCTION_URL`、`RCM_CONSOLE_PRODUCTION_URL` 及需要预发布部署时的 `RCM_CENTER_STAGING_URL`、`RCM_CONSOLE_STAGING_URL`。工作流提交不可变镜像摘要后最多等待 10 分钟，自动验证 Center 实际版本与 readyz、Console 实际版本与源码 SHA。未就绪则工作流失败并保留期望配置和错误，不把“提交 GitOps”当成“部署完成”。Agent 的安装结果由 Center 记录，机器当前是否在线须同时查看；已完成的安装结果不会因后来离线被改写。

工作流的 Summary 记录源码 SHA、发布版本、镜像摘要、GitOps 提交及自动运行态核验结果。需要独立审计时，仍使用同一个只读入口：

```bash
python3 scripts/verify-release.py center vX.Y.Z --url https://<center-host>
python3 scripts/verify-release.py console vX.Y.Z --url https://<console-host> --source-sha <40位源码SHA>
RCM_VERIFY_ADMIN_TOKEN=<管理员令牌> python3 scripts/verify-release.py agent vX.Y.Z --url https://<center-host>
```

Center 校验 `/api/v1/version` 与 `/api/v1/readyz`；Console 校验镜像内的 `/release.json` 版本及源码 SHA；Agent 分页读取实际注册机器版本和适用于各机器能力的 Native 组件状态。默认只有**全部机器在线、版本一致且启用 Browser 的机器已验证 Camoufox 运行时**才算完整通过。当前尚无 Camoufox 运行时的自动更新与版本证明，因此这类机器会明确显示 `browser_runtime.verified=false`；若只需要验收 Agent 和 Native 组件，可显式加 `--native-only`，结果的 `coverage` 会标明缩小的范围。要单独确认可达机器，再加 `--online-only`。尚未填写 `--url` 的 GitOps 校验只证明已固定摘要，输出中运行态仍是 `unverified`。

Browser 的 Camoufox Node 包和浏览器本体在首次安装或 `deploy-desktop-browser.ps1` 迁移时安装。当前 Helper 的普通组件升级只处理 Native ZIP；浏览器运行时更新还需要单独完成，不能把 Native 升级成功等同于整个 Camoufox 运行时已更新。
