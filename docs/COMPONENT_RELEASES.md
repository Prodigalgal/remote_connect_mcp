# 组件化 CI/CD

RCM 的生产构建分成三个独立发布单元：Center、Agent、Console。产物和
GitOps 晋级只触及发生变化的组件；不再因为 Center 的普通修复重新编译
Agent 或 React Console。

## 自动触发

| 工作流 | 主要路径 | 发布标记 | 部署动作 |
| --- | --- | --- | --- |
| `java-center-release.yml` | `java/center`、Center 依赖的 `protocol`、Center Native Dockerfile、内嵌 Artifact Viewer | `center-vX.Y.Z` | 只更新 `center.yaml` 和 `migration-job.yaml` |
| `java-agent-release.yml` | `java/agent`、`desktop`、`browser`、Agent 安装脚本和 `protocol` | `java-vX.Y.Z` | 发布 command/desktop/browser bundle；由 Center 的升级活动按组件分发 |
| `react-console-release.yml` | `web/` | `console-vX.Y.Z` | 只更新 `console.yaml` |

主分支提交会先运行一个很小的变更检测 Job。没有命中对应路径时，后续
构建 Job 直接跳过；不会启动 GraalVM Native Image 或 Docker 构建。主分支
产生的版本是可丢弃的预发布版本，GitOps 默认晋级 staging。生产版本通过
对应工作流的 `workflow_dispatch` 输入版本和 `deploy=true` 生成。

## Agent 内部边界

Agent 工作流仍然把三个 Native bundle 一起作为一个 Agent 发布活动验收，
但 ZIP 和运行时目录始终独立：

- `remote-connect-mcp-agent-*`：headless command-agent；
- `remote-connect-mcp-desktop-*`：桌面伴侣；
- `remote-connect-mcp-browser-*`：Browser supervisor。

Agent Release 会继续使用 `java-vX.Y.Z` 标记，因为 Center 的 Release Catalog
已经以该前缀发现 command-agent，并从同一个 Release Manifest 读取 Desktop /
Browser 组件。Center-only 和 Console-only 标记不会进入 Agent Catalog。

## 共享契约

修改 `java/protocol`、Gradle 根配置或 Native 构建基线时，Center 和 Agent
的选择性 CI 都会运行。这些改动属于跨组件契约变更；数据库迁移、MCP
工具 schema 或破坏性 HTTP/Agent 协议变更应使用手动的
`Java Full Release (manual)` 工作流做一次全量 Native、镜像和 GitOps 验收。

全量工作流不再监听普通 `main` 提交或标签，避免与三个组件工作流重复构建。
它只在明确选择 `workflow_dispatch` 时执行。

## 版本与回滚

- Center 镜像：`remote-connect-mcp-center-java:center-vX.Y.Z`；GitOps 使用摘要固定部署。
- Agent 镜像和 Release：继续使用 `java-vX.Y.Z`，以兼容现有 Center 升级目录。
- Console 镜像：`remote-connect-mcp-console:console-vX.Y.Z`；GitOps 使用摘要固定部署。

组件之间的 URL、MCP 连接器地址、Agent 身份和 Token 不变。回滚时只需把
对应组件的 GitOps 镜像摘要恢复到上一版本；Agent 的 Desktop/Browser 失败
也不会回滚健康的 command-agent。
