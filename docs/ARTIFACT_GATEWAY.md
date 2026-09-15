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
  `X-RCM-Object-Key` 和 `X-RCM-SHA256`；成功返回 `200`、`201` 或 `204`。
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

生产必须使用 HTTPS；HTTP 仅允许 loopback 开发测试。网关不可用时，Center
就绪检查应失败或切回已验证的 filesystem 后端，不能悄悄把工件放回数据库。
