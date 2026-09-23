# 组件发布与机器更新

日常入口是推送到 GitHub `main`。三个工作流各自检测变更；未命中对应组件时直接跳过构建。

| 变更 | 构建与发布 | 后续动作 |
| --- | --- | --- |
| `java/center`、Center Dockerfile、Artifact Viewer | Center | GitOps 只更新 Center 与迁移 Job |
| `java/agent`、`desktop`、`browser`、安装脚本与浏览器运行时 | Agent | 发布 Agent/桌面/浏览器组件；Center 发现版本后分批唤醒 Agent |
| `web/` | Console | GitOps 只更新 Console |
| `java/protocol` 或 Gradle 公共配置 | Center 和 Agent | 两个组件各自完整测试和构建 |

主分支产物是预发布版，GitOps 晋级 staging。PR 运行 `change-checks.yml` 变更检查，不重复执行主分支的发布构建。稳定生产发布使用组件工作流的版本 Tag 或手动触发；不再有单独的“全量发布”工作流。沿用 `java-vX.Y.Z` Agent Tag 和现有资产名，避免中断 Center 升级目录及已安装 Agent。

Center 每 5 分钟查看已发布的 Agent Release。只有目标平台的 ZIP 和校验文件齐全，且该版本尚未创建过升级活动时，才为版本落后的机器创建活动。首台为 canary，随后每批 3 台；任务唤醒是即时的，无需 Agent 定时查询。失败会暂停活动，控制台“更新”页可检查和恢复。离线机器包含在活动中，重新上线后继续。staging 可跟随预发布；生产只跟随稳定版。这由 `RCM_CENTER_AGENT_AUTO_UPGRADE_ENABLED` 和 `RCM_CENTER_AGENT_AUTO_UPGRADE_INCLUDE_PRERELEASE` 控制。

Browser 的 Camoufox Node 包和浏览器本体在首次安装或 `deploy-desktop-browser.ps1` 迁移时安装。当前 Helper 的普通组件升级只处理 Native ZIP；浏览器运行时更新还需要单独完成，不能把 Native 升级成功等同于整个 Camoufox 运行时已更新。
