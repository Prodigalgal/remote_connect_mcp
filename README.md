# Remote Control MCP

RCM 让一个人通过 MCP 客户端管理自己的远程机器。Center 负责身份、逐凭证的机器/工具授权、任务和结果；每台机器上的 Agent 按本机账户权限执行命令。需要图形界面时，再启用桌面或浏览器能力。

项目和仓库统一使用 Remote Control MCP / `remote_control_mcp`，安装服务、镜像和配置使用 `remote-control-mcp` 与 `REMOTE_CONTROL_MCP_*`。现有安装的旧环境变量和历史 Release 资产仅在迁移兼容入口中读取，新名称优先；机器身份、凭证和任务协议保持连续。

MCP 地址是 `https://<center-domain>/mcp`。当前公开 7 个工具：`machines`、`command`、`desktop`、`browser`、`artifact`、`task_read`、`task_cancel`。

## 日常使用

1. 在 Console 的“添加机器”生成一次性安装命令，并在目标机器执行。默认只安装命令能力；需要图形操作时选择桌面/浏览器。
2. 在“连接凭证”为 MCP 客户端创建凭证，通过机器 × 工具表格选择访问范围。默认每页 10 台，可搜索、按在线状态筛选，并按机器、工具或当前筛选范围批量勾选；翻页保留已选权限，未启用的工具不可选。凭证明文只显示一次；Center 每次调用都会校验这张凭证的机器/工具权限。
3. 让客户端先用 `machines` 选择机器，再调用所需能力。长任务使用返回的任务 ID 继续读取，不重复提交。

Console“任务记录”展开正在执行的任务后，会持续显示宿主机回传的新日志和进度，完成或折叠后停止跟随。Console 新建任务会自动展开输出；没有日志时显示等待回传，阶段和百分比仅在宿主机提供时显示。长日志优先显示最新部分，任务完成后可从头分页读取。

命令记录默认把可识别的 Unicode 和 UTF-8 字节转义显示为可读文本，可切换原文；复制始终保留原始命令。日志默认按 UTF-8 显示，Windows 旧编码输出可切换 GB18030/GBK 或 UTF-16LE；切换后从原始日志字节重新读取，跨页字符保持完整。

Console 的操作结果统一显示为右上角浮窗，支持关闭、自动消失，悬停或聚焦时暂停消失；通知最多保留三条且合并重复内容，不改变页面布局。后台同步只在连接中断或恢复时通知，持续连接状态由顶部指示显示。凭证明文、安装命令和任务输出仍保留在各自页面中。

### 在 ChatGPT 网页端连接

1. 在 ChatGPT 的 Apps/开发者模式中新建 MCP 应用，服务地址填写 `https://<center-domain>/mcp`，认证选择 **OAuth**。
2. 点击扫描/创建后，ChatGPT 会打开 Center 的授权页。此时到 Console“连接凭证”创建一条新的 RCM 凭证，选择机器和工具，再把它填在 **Center 授权页**，点击“授权并返回 ChatGPT”。不要把 RCM 凭证填进 ChatGPT 的 URL 或作为 OAuth access token。
3. 授权成功后浏览器会回到 ChatGPT，等待工具扫描完成再保存应用。Console 凭证只用于换取 OAuth 令牌；OAuth 和直接 Bearer 调用都受这张凭证的机器/工具权限约束。撤销凭证会使其 OAuth 令牌失效。

完整 MCP 写操作需要 ChatGPT 工作区支持自定义 MCP 应用和相应权限；入口可能因账户方案不同而显示在个人设置或工作区设置中。

`command`、`desktop`、`browser` 和 `artifact(put/get)` 都创建持久任务。需要立即继续做别的事时传 `wait_ms=0`；预计很快完成时传正数，让同一次调用短等有界结果。等待到期不会取消任务，后续统一调用 `task_read(task_id)`。新调用创建新任务；重试同一次调用时显式复用 `idempotency_key`，避免再次执行。

输出默认 16 KiB，可用执行工具的 `limit` 调整，后续用 `task_read(limit=...)` 或 `task_read(tail_bytes=8192)` 分页。只观察状态时用 `task_read(change_seq=..., wait_ms=...)`，默认不重复附带旧日志和图片；指定 `include_output=true` 读取日志，`include_artifact=true` 获取截图。浏览器输出为 `output.data`，`request.include_snapshot=true` 可在操作后一起观察页面；详细诊断用 `task_read(detail=true)` 读取。

`artifact(get/read)` 的 `delivery_mode` 决定返回文件句柄还是内联内容，`wait_ms` 独立决定短等时长。`auto` 默认短等，`async` 默认立即返回；显式 `wait_ms` 优先。内联文本默认 16 KiB，通过 `cursor` / `limit` 继续读取，完整文件始终可下载。字段取舍与详情入口见 [MCP 结果字段](docs/MCP_RESULT_FIELDS.md)。

Agent 默认使用事件唤醒的 HTTPS 长轮询：任务或升级出现时 Center 立即唤醒等待中的连接；空闲时没有固定频率的业务查询。Browser 使用 [Camoufox 官方客户端](https://github.com/daijro/camoufox/tree/main/typescript)，固定客户端版本并下载其配套的浏览器构建，需要目标机安装 Node.js 22.15 及 npm；浏览器只在收到任务时启动，Profile 保留在目标机器。

## 更新

日常入口是推送代码到 GitHub `main`。Center、Agent、Console 各自只在相关路径变化时构建，通过组件检查后自动发布正式版；Center/Console 自动经 GitOps 部署生产并核验实际版本和就绪状态。Center 定期发现 Agent 新版本，先验证首批机器，再分批推进。每台机器最多自动派发 3 次，仅对明确的临时下载故障等待 30 秒、2 分钟后重试；首批失败暂停，后续批次记录单机失败并继续其他机器。离线机器单列为待补更，不占批次名额，上线后自动补更。成功须有新 Agent 在线、目标版本及适用组件的完成证据；本轮完成不代表离线机器已更新。日常更新无需人工创建活动或逐机部署。显式手动预发布仍进入 staging。运行中的版本、就绪状态和机器覆盖率按[组件发布](docs/COMPONENT_RELEASES.md)复核。

首次安装仍须在目标机器执行；现有 Playwright 浏览器安装迁移到 Camoufox 时，使用 `scripts/deploy-desktop-browser.ps1` 更新浏览器运行时。Native Agent 的普通自动升级目前只替换 Native 组件，不会替换 Node 浏览器包。

历史 `java-vX.Y.Z` Release 标记和资产名保持兼容；安装脚本按用途命名为 `first-install-agent` 与 `install-agent`。

## 边界

- Agent 拥有运行账户的宿主机权限。`cwd` 是工作目录，不是沙箱或授权边界。
- Console 签发 MCP 凭证并配置逐机器工具授权；Center 校验凭证和任务归属，操作系统决定 Agent 实际能做什么。
- 并发、超时、输出和资源限制用于稳定运行，不提供文件路径隔离。
- 本机和目标机不构建发布二进制；测试、Native Image 和镜像构建由 GitHub Actions 执行。

当前模型见 [Full-host model](docs/FULL_HOST_MODEL.md)，发布规则见 [组件发布](docs/COMPONENT_RELEASES.md)，其他文档的适用范围见 [文档索引](docs/README.md)。
