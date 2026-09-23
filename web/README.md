# React 控制台

这是 RCM 控制台的独立 React/Vite 应用，通过 Java Center 的 `/api/v1/admin`
接口管理机器、任务、连接凭证和更新活动。

依赖安装、生产构建和产物校验只在 GitHub Actions 中执行。开发机不运行
`pnpm install`、`pnpm build` 或本地开发服务器，避免把前端构建峰值带到宿主机。
`pnpm build` 还包含 CI-only 门禁，在 GitHub Actions 之外会在 TypeScript/Vite 启动前退出。

推送 `main` 后，只有 `web/` 发生变化才运行 `console-release.yml` 构建并发布 Console；PR 使用 `change-checks.yml` 检查。部署使用 Actions 生成的镜像。

生产环境只部署 Actions 产出的 `dist/` 静态文件；不要把 Center、Admin Token、Agent
Token 或任何真实机器信息写入前端构建产物。
