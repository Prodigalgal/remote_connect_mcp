# HTTP 工件网关契约

Center 的 `HttpArtifactStore` 是一个很小的内部对象网关客户端，不依赖
S3 SDK，也不把大块数据写入 PostgreSQL。它只使用以下三个同源 HTTPS 路径：

```text
PUT    {base_url}/v1/objects/{url-encoded-object-key}
GET    {base_url}/v1/objects/{url-encoded-object-key}
DELETE {base_url}/v1/objects/{url-encoded-object-key}
```

`{url-encoded-object-key}` 是整个 opaque key 的单个 URL 路径段，不能把 `/`
解码成网关外部路径。当前 Center 生成的 key 形如
`http-v1/<task-digest>/<artifact-digest>.blob`，网关不应依据 task digest
推断权限或业务含义。

## 请求与响应

- `PUT` 请求体是原始字节，带 `Content-Type: application/octet-stream`、
  `X-RCM-Object-Key`、`X-RCM-SHA256` 和 `X-RCM-Expected-Bytes`；成功返回 `200`、`201` 或 `204`。
  Center 使用 JDK 流式 BodyPublisher，不能手工设置受限的 `Content-Length` 标头，
  网关应以 `X-RCM-Expected-Bytes` 作为预期大小并同时校验实际请求体。
- `GET` 成功返回 `200` 和原始字节；不存在返回 `404`。Center 会把响应限制在
  64 MiB 内，并再次计算 SHA-256，网关不能返回不完整或改变内容的 200。
- `DELETE` 成功返回 `200`、`202` 或 `204`；重复删除可返回 `404`。
- 配置了 `RCM_CENTER_ARTIFACT_HTTP_TOKEN` 时，三个请求都带
  `Authorization: Bearer <token>`。网关日志必须脱敏该标头。

网关应在自身侧完成 ACL、加密、保留期、版本化、容量配额和访问审计。Center
不会调用列举接口，也不会对远端执行无界 GC；`ArtifactStore.sweepOrphans`
在 HTTP 后端返回零，孤儿对象由网关生命周期策略或单独维护任务处理。

## Center 配置

```text
RCM_CENTER_ARTIFACT_STORE=http
RCM_CENTER_ARTIFACT_HTTP_BASE_URL=https://objects.example.invalid/rcm
RCM_CENTER_ARTIFACT_HTTP_TOKEN=<从 Secret/env 注入>
RCM_CENTER_ARTIFACT_HTTP_TIMEOUT_SECONDS=30
```

文件传输的数据流还受 `RCM_CENTER_TRANSFER_STALL_TIMEOUT_SECONDS` 保护。它只限制
“已经建立连接后连续无读取进展”的时间（默认 120 秒，允许 5 秒至 1 小时），不替代
传输的绝对生命周期上限。Center 按 4 MiB 或 1 秒节流写入 `bytes_transferred`，因此控制台
可以显示有界的进度而不会为每个数据块执行一次数据库写入。

Agent→Center 大文件还可使用 `HEAD /agent/v1/transfers/{id}/content` 查询已确认偏移，
随后以 `Content-Range: bytes start-end/total` 分块上传。Center 将 partial spool 放在
`RCM_CENTER_TRANSFER_SPOOL_ROOT/resume` 下，只有完整 SHA-256 校验并提交对象后才删除；
请求重试会复用该偏移。Web→Agent 方向使用相同 transfer_id 绑定的 HTTP `Range`，因此
代理或 Agent 重启不会重复写入已经确认的字节。

生产必须使用 HTTPS；HTTP 仅允许 loopback 开发测试。网关不可用时，Center
就绪检查应失败或切回已验证的 filesystem 后端，不能悄悄把工件放回数据库。
