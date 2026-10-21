# 推送唤醒（UnifiedPush，可选）

App 平时靠自己的 SSE 长连接实时收短信。连接断了（被系统杀、网络切换、熄屏断网）时，可以让服务端经 [UnifiedPush](https://unifiedpush.org/) 把它叫醒。默认关闭，不用也不影响其他功能。

## 工作方式

- 推送只是**唤醒信号**：App 收到任何推送都只做两件事——拉起常驻服务（重新注册 SIP、重连事件流），再全量拉一次 `/api/feed` 补出带"复制 / 回复"的通知。消息内容不被信任，也不被读取。
- **只推给没收到的设备**：服务端记录每台设备的 SSE 连接已送达到哪条事件。新短信发布 `PUSH_GRACE` 秒（默认 3）后，只给还没收到它的设备发推送；SSE 活着时什么也不经过第三方。
- **来电**：App 分机离线时，Asterisk 拨号计划调用本机的 `/api/push/wake`，发一条高优先级推送，与短信唤醒并行，任一路发出都会给主叫回铃并等待 `WAKE_WAIT` 秒。
- 推送内容由服务端 `PUSH_CONTENT` 决定：`wake`（只有"有新短信"）/ `preview`（默认）/ `full`（含正文）。目前推送载荷只有 `{"t":"sms"}` 这类类型标记，并按 RFC 8291 加密（已用 RFC 附录 A 的官方向量逐字节核对）。

## 搭建

1. 自建一个 [ntfy](https://ntfy.sh/) 服务器（或用其他 UnifiedPush 推送服务），手机上装 ntfy App 并连到它。
2. 服务端 `.env`：`PUSH_CONTENT` 按需设置；ntfy 只在局域网用 http 时设 `PUSH_UP_ALLOW_HTTP=1`（默认只接受 https 的 endpoint，防止被拿去探测内网）。重建 phonebox 容器。
3. App：设置 → 后台保活体检 → 推送唤醒 → 启用。有多个 distributor 时会弹选择框。状态显示"已注册并上报 phonebox"后，点"发一条测试推送"，诊断日志里应出现"收到推送唤醒（unifiedpush）"。

## 接口

| 接口 | 鉴权 | 说明 |
|---|---|---|
| `POST /api/push/register` | 设备令牌 | `{"provider":"unifiedpush","token":"<endpoint>","pub":"<p256dh>","auth":"<auth>"}`；每台设备最多 3 条，同通道覆盖 |
| `DELETE /api/push/register` | 设备令牌 | 删除本设备的推送注册；吊销设备令牌时也会一并删除 |
| `GET /api/push/status` | 设备令牌 | 本设备的注册、各通道是否可用、最近 10 次发送结果（不含号码和正文） |
| `POST /api/push/test` | 设备令牌 | 给本设备发一条测试推送，30 秒一次 |
| `POST /api/push/wake` | 仅本机（`PHONEBOX_LOOKUP_IPS`） | 给所有设备发来电唤醒，Asterisk 拨号计划用 |

endpoint 返回 404 / 410 时自动删除该注册；其他失败按 1 秒、3 秒退避重试两次。

## 为什么不做小米 / OPPO / vivo / 华为 / 荣耀推送

这些厂商通道能做到"App 被杀也能收到"，但对自建自用的项目基本用不上：小米、OPPO、vivo 要企业开发者账号且应用要上架（未上架只能推测试设备），荣耀要企业认证；华为个人账号可用，但它的 SDK 是闭源的，打进 GPL 项目的发布包不合适，而且只对华为机和装了 HMS Core 的手机有用。所以本项目只保留 UnifiedPush。要接某家，可以照 `push/UnifiedPushChannel.kt` 实现一个 `PushChannel`，并在服务端 `push.py` 的 `SENDERS` 里加对应的发送器。

## 实测状态

- 服务端：推送注册、只推给没收到的设备、来电唤醒、endpoint 失效清理、测试推送限频，都有容器集成测试；本机从零部署后，拨号计划在分机离线时 0.09 秒内发出了唤醒推送（假的推送服务器）。
- App 端：能编译、有单元测试，**还没有和真实 ntfy App 做过端到端实测**。
