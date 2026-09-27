# 组件发布与机器更新

日常入口是推送到 GitHub `main`。三个工作流各自检测变更；未命中对应组件时直接跳过构建。

| 变更 | 构建与发布 | 后续动作 |
| --- | --- | --- |
| `java/center`、Center Dockerfile、Artifact Viewer | Center | GitOps 只更新 Center 与迁移 Job |
| `java/agent`、`desktop`、`browser`、安装脚本与浏览器运行时 | Agent | 发布 Agent/桌面/浏览器组件；Center 发现版本后分批唤醒 Agent |
| `web/` | Console | GitOps 只更新 Console |
| `java/protocol` 或 Gradle 公共配置 | Center 和 Agent | 两个组件各自完整测试和构建 |

主分支产物是预发布版，GitOps 晋级 staging。PR 运行 `change-checks.yml` 变更检查，不重复执行主分支的发布构建。稳定生产发布使用组件工作流的版本 Tag 或手动触发；不再有单独的“全量发布”工作流。沿用 `java-vX.Y.Z` Agent Tag 和现有资产名，避免中断 Center 升级目录及已安装 Agent。

Center 每 5 分钟查看已发布的 Agent Release。只有目标平台的 ZIP 和校验文件齐全、且没有其他进行中或暂停的活动时，才为在线且版本落后的机器创建活动。首台为 canary，随后每批 3 台；任务唤醒是即时的。离线机器不阻塞这一轮，重连后按实际版本自动补更；手工活动仍可显式包含离线机器。升级下载有总时限和停滞时限；Center 定期将过期的派发、下载或安装状态收敛为带原因的失败，暂停活动，避免盲目重复启动 Helper。确认机器状态后在控制台重试该机；若旧活动只剩离线目标，可取消旧活动，让自动发现继续，离线机上线后会重新纳入。staging 可跟随预发布；生产只跟随稳定版。这由 `RCM_CENTER_AGENT_AUTO_UPGRADE_ENABLED` 和 `RCM_CENTER_AGENT_AUTO_UPGRADE_INCLUDE_PRERELEASE` 控制。

工作流的 Summary 记录源码 SHA、发布版本、镜像摘要及 GitOps 提交；Center/Console 晋级前逐项校验目标清单中的不可变摘要，Center 还校验版本环境变量。这证明产物已发布且期望配置已提交，**不等于**运行中已就绪。发布后用同一个只读入口核验实际运行状态：

```bash
python3 scripts/verify-release.py center vX.Y.Z --url https://<center-host>
python3 scripts/verify-release.py console vX.Y.Z --url https://<console-host> --source-sha <40位源码SHA>
RCM_VERIFY_ADMIN_TOKEN=<管理员令牌> python3 scripts/verify-release.py agent vX.Y.Z --url https://<center-host>
```

Center 校验 `/api/v1/version` 与 `/api/v1/readyz`；Console 校验镜像内的 `/release.json` 版本及源码 SHA；Agent 分页读取实际注册机器版本和升级活动，默认只有**全部机器在线且版本一致**才通过。要单独确认可达机器，显式加 `--online-only`，结果会标明覆盖范围。尚未填写 `--url` 的 GitOps 校验只证明已固定摘要，输出中运行态仍是 `unverified`。

Browser 的 Camoufox Node 包和浏览器本体在首次安装或 `deploy-desktop-browser.ps1` 迁移时安装。当前 Helper 的普通组件升级只处理 Native ZIP；浏览器运行时更新还需要单独完成，不能把 Native 升级成功等同于整个 Camoufox 运行时已更新。
