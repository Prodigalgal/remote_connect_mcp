# Go 兼容路径退出说明

Java 25 Center/Agent 已经是主生产实现。Go 代码只保留给尚未完成 P0/P1 现场验收的离线节点作为兼容基线；它不再是 ChatGPT `/mcp` 的生产入口，也不再自动发布镜像或 GitHub Release。

## 当前阶段

- 旧 Go Center 镜像发布 workflow 已移除；
- 旧 Go 发布 workflow 已改为手动触发的 `Legacy Go Compatibility Archive`，只生成临时兼容归档，不创建 Release；
- `ci.yml` 仍保留 Go test/vet/build，避免在 Java 回归窗口内破坏离线节点的恢复能力；
- 公开仓库不包含生产 Deployment、Secret 或真实节点地址，旧部署资源的删除要在 PostgreSQL 备份恢复和 P0/P1 回归门禁通过后执行。

## 最终退出条件

1. 所有登记 Agent 完成 Java 版本升级或明确注销；
2. Center 重启、断线、长任务、工件恢复和失败回滚演练通过；
3. 至少一台 Windows 和一台 Linux 的 Desktop/Browser 矩阵通过；
4. PostgreSQL、工件卷和审计数据完成可恢复备份；
5. 在变更窗口中删除旧 Go Service/PVC/Deployment，并保留一份离线只读归档。

在上述条件满足前，不删除 Go 源码和兼容 CI；这不是 Java 生产依赖，而是可撤销的迁移保险丝。
