# 更新记录

## 0.3.1（未发布）

- 通话爆音：4G 模块经 USB 送来的语音电平过高（实测语音段约 -9.5 dBFS），服务端模板默认 rxgain=-3（约 -9.5 dB）。已有部署：改 quectel.conf 的 rxgain 后执行 quectel reload gracefully。
- Root Guardian 只盯已知的 App 进程，不再每 5 秒扫描全部进程（真机上原先约占 1% CPU）。

## 0.3.0（2026-10）

**修复**
- 注册失败后重试链会断开，导致 App 长时间离线（真机上一次离线数小时，直到网络再次切换）：注册状态改为每次失败都是新值，心跳闹钟发现"未注册且没有待重试"时重新注册。
- Guardian 误判常驻服务不在、频繁强制重连（ColorOS 上 dumpsys 偶发返回残缺输出）：要连续两次完整输出都看不到才算不在；拉起入口发现服务在运行时只记日志、不重连。

**新增**
- Root 增强（KernelSU / Magisk / APatch）：用 Root 一键修复、Root Guardian 守护进程（被杀即拉起、被冻结即解冻）、开机模块 `phonebox-root-<版本>.zip`。见 docs/root.md。
- 诊断页"短信首达延迟"：NAS 收到短信到本机弹出通知的时间差，区分实时 / 补拉 / 推送唤醒。
- UnifiedPush 推送唤醒（可选，默认关）：服务端只给 SSE 没送达的设备推送；App 分机离线来电时，拨号计划同时发推送唤醒。见 docs/push.md。
- 新的应用图标。

**文档**
- 保活分层说明；"在家掉线、出门就好"的回环 NAT 排查。

升级：App 用同一签名直接覆盖安装。服务端 `git pull` 后重跑 `scripts/init.sh`（会重新渲染拨号计划），再 `docker compose up -d --build`；不用推送可以什么都不配。
