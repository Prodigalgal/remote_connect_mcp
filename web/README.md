# React 控制台

这是 RCM 控制台的独立 React/Vite 应用，通过 Java Center 的 `/api/v1/admin`
接口管理机器、任务、项目/worktree、注册令牌和升级活动。

依赖安装、生产构建和产物校验只在 GitHub Actions 中执行。开发机不运行
`pnpm install`、`pnpm build` 或本地开发服务器，避免把前端构建峰值带到宿主机。
`pnpm build` 还包含 CI-only 门禁，在 GitHub Actions 之外会在 TypeScript/Vite 启动前退出。

提交分支或 `java-vX.Y.Z` 标签后，由仓库根目录的
`.github/workflows/java-react.yml` / `.github/workflows/java-release.yml` 生成并校验
静态资源；部署时使用 Actions 产出的归档或容器镜像。请在 GitHub Actions 运行记录中
查看日志或下载产物。

生产环境只部署 Actions 产出的 `dist/` 静态文件；不要把 Center、Admin Token、Agent
Token 或任何真实机器信息写入前端构建产物。
