# 传输演进与 QUIC 评估

RCM 的任务协议与传输解耦：任务、输出、工件和升级都使用同一套 JSON 合同；当前正确性路径是 HTTPS 长轮询，WebSocket 只负责 wake-only 唤醒。连接丢失时 Agent 通过指数退避恢复，任务状态仍由 Center 持久化行决定。

## 为什么不直接把 QUIC 设为默认

Java 25 的标准 `HttpClient` 没有通用 HTTP/3 客户端 API。引入 Netty incubator、外部 `curl` 或其他 QUIC provider 会增加 Native Image 反射配置、旁路库和平台兼容面；在没有真实丢包/长 RTT 基准之前，不能把实验传输变成唯一链路。

## 当前实现

- `Envelope` 和 Agent HTTP 客户端不把业务语义绑定到 TCP；
- Agent 请求带有有界的 `X-RCM-Transport-Capabilities`/`X-RCM-Transport-Preferred`
  头，Center 在响应中回传 `X-RCM-Transport-Selected`；未知能力会被忽略，当前
  `/agent/v1/*` 只选择 HTTPS，保证旧 Agent 和未来 provider 都有明确回退；
- `scripts/benchmark-transport.sh` 和 `scripts/benchmark-transport.ps1` 提供一次性 HTTP/1.1、HTTP/2、可选 HTTP/3 探针；
- 探针只读取健康 URL，不输出或接收 Bearer Token，不启动定时任务；
- HTTP/3 不可用、失败或性能不佳时，必须回退到 HTTPS/WebSocket，不得让 Agent 离线。

运行示例：

```bash
RCM_TRANSPORT_URL=https://center.example.invalid/api/v1/healthz \
  bash scripts/benchmark-transport.sh
```

```powershell
$env:RCM_TRANSPORT_URL = 'https://center.example.invalid/api/v1/healthz'
pwsh -File .\scripts\benchmark-transport.ps1
```

## QUIC 进入灰度的门槛

只有在同一 Center、同一 Agent 集合和相同请求样本下，连续多轮证明 HTTP/3 对长 RTT、丢包或尾延迟有稳定收益，才继续实现 provider。灰度必须保留：

1. transport capability negotiation；
2. per-Agent 显式开关和版本兼容检查；
3. HTTPS fallback 和失败原因指标；
4. 连接迁移、重启、取消、长任务和工件上传基准；
5. 回退后不重复执行、不丢任务的演练证据。
