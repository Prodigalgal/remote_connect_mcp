# Remote Connect MCP

RCM 让一个人通过 MCP 客户端管理自己的远程机器。Center 负责身份、机器授权、任务和结果；每台机器上的 Agent 按本机账户权限执行命令。需要图形界面时，再启用桌面或浏览器能力。

MCP 地址是 `https://<center-domain>/mcp`。当前公开 7 个工具：`machines`、`command`、`desktop`、`browser`、`artifact`、`task_read`、`task_cancel`。

## 日常使用

1. 在 Console 的“添加机器”生成一次性安装命令，并在目标机器执行。默认只安装命令能力；需要图形操作时选择桌面/浏览器。
2. 在“连接凭证”为自己的 MCP 客户端创建凭证。页面会同时授权该账户访问全部机器；Center 仍逐次验证身份和机器权限。
3. 让客户端先用 `machines` 选择机器，再调用所需能力。长任务使用返回的任务 ID 继续读取，不重复提交。

`command`、`desktop`、`browser` 和 `artifact(put/get)` 都创建持久任务。需要立即继续做别的事时传 `wait_ms=0`；预计很快完成时传正数，让同一次调用短等有界结果。等待到期不会取消任务，后续统一调用 `task_read(task_id)`。`artifact(get)` 的默认 `auto` 会短等，显式 `async` 立即返回文件句柄。查看长日志末尾可用 `task_read(tail_bytes=8192)`，不必从开头逐页读取。

Agent 默认使用事件唤醒的 HTTPS 长轮询：任务或升级出现时 Center 立即唤醒等待中的连接；空闲时没有固定频率的业务查询。Browser 使用 [Camoufox](https://github.com/apify/camoufox-js)，需要目标机安装 Node.js 22 及 npm；浏览器只在收到任务时启动，Profile 保留在目标机器。

## 更新

日常入口是推送代码到 GitHub `main`。Center、Agent、Console 各自只在相关路径变化时构建发布，主分支版本进入 staging。Center 定期发现 Agent 新版本，创建先单台、后分批的升级活动；Agent 收到唤醒后由本机 Helper 安装，失败会暂停活动。生产只跟随稳定 Agent Release，staging 可跟随预发布。

首次安装仍须在目标机器执行；现有 Playwright 浏览器安装迁移到 Camoufox 时，使用 `scripts/deploy-desktop-browser.ps1` 更新浏览器运行时。Native Agent 的普通自动升级目前只替换 Native 组件，不会替换 Node 浏览器包。

历史 `java-vX.Y.Z` Release 标记和资产名保持兼容；安装脚本按用途命名为 `first-install-agent` 与 `install-agent`。

## 边界

- Agent 拥有运行账户的宿主机权限。`cwd` 是工作目录，不是沙箱或授权边界。
- Center 管理主体、机器授权和任务归属；操作系统决定 Agent 实际能做什么。
- 并发、超时、输出和资源限制用于稳定运行，不提供文件路径隔离。
- 本机和目标机不构建发布二进制；测试、Native Image 和镜像构建由 GitHub Actions 执行。

当前模型见 [Full-host model](docs/FULL_HOST_MODEL.md)，发布规则见 [组件发布](docs/COMPONENT_RELEASES.md)，其他文档的适用范围见 [文档索引](docs/README.md)。
