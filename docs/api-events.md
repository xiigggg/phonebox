# phonebox 事件流与设备令牌协议

server/phonebox 已实现本文的全部接口；App 的 `net/PhoneboxClient`、`data/FeedSync` 按本文实现，遇到没有这些接口的旧版服务端会自动降级（cookie 登录 + 轮询）。

## 认证

两种方式都接受，客户端自动选择：

| 方式 | 请求头 | 来源 |
|---|---|---|
| 设备令牌（新） | `Authorization: Bearer <token>` | `POST /api/token` |
| 会话 cookie（旧版兼容） | `Cookie: pb_token=<token>` | `POST /login` 的 303 响应 |

客户端登录时先请求 `POST /api/token`；返回 404/405 说明服务端没有升级，改走 `/login`。

### `POST /api/token`：PIN 换长期设备令牌

- 请求：`Content-Type: application/x-www-form-urlencoded`，正文 `code=<PIN>`；带 `X-Phonebox: 1` 和 `Origin`（与 `/api/send` 相同的防 CSRF 约束）。
- 成功：`200`，`{"token": "<随机串，至少 32 字节熵>"}`。服务端**只存令牌的哈希**（与网页会话同样做法），不设过期，或设很长的过期。
- PIN 错：`403`；失败次数过多：`429` + `Retry-After: <秒>`。与 `/login` 共用同一套失败计数和锁定。

### `DELETE /api/token`：吊销当前令牌

- 带 `Authorization: Bearer <token>`，服务端删除对应哈希，返回 `204`。
- 令牌无效或已吊销的请求一律 `401`。

## `GET /api/events`：SSE 事件流

- 认证同上；未认证 `401`。
- 响应 `Content-Type: text/event-stream`，`Cache-Control: no-store`；反向代理（Caddy）需关闭缓冲。
- 事件：

```
id: 1760000500000-1
event: sms
data: {"ts":1760000500,"from":"138000000xx","body":"…","cat":"…","level":"…","kind":"…","card":"…","code":null,"code_label":"","summary":"","sig":""}

id: 1760000501000-2
event: call
data: {"ts":1760000501,"number":"138000000xx","dir":"in","disposition":"NO ANSWER","billsec":0,"tag":"","ntype":"手机","block":false,"loc":"","src":""}

event: ping
data:

```

- `sms` / `call` 的 `data` 与 `/api/feed` 里 `sms[]` / `calls[]` 的单条**完全相同**（同样经过 `analyze_sms` / `analyze_call`），单行 JSON。
- `ping`：服务端每 25 秒发一次，`data` 为空，不带 `id`。客户端读超时 70 秒，两次以上收不到就判定断线。
- `id`：单调递增的字符串，建议 `<毫秒时间戳>-<进程内序号>`。只有 `sms` / `call` 带 `id`。
- 重连：客户端带 `Last-Event-ID: <最后收到的 id>`，服务端补发此后的所有事件（保留最近若干条即可，比如 1 小时或 500 条）；无法判断时可不补发，客户端每次重连前都会先全量拉一次 `/api/feed`，再按 `ts + 内容哈希` 去重。
- 不存在此接口时返回 `404`（旧版服务端），客户端改为每 3 分钟轮询 `/api/feed`。

## `GET /api/feed?since=<ts>`：增量拉取

- `ts` 为 Unix 秒；只返回 `ts >= since` 的条目，结构与全量相同（`{sms, calls, versions}`）。
- 旧服务端忽略该参数、返回全量，客户端两种情况都能处理。
