# 从旧版或补丁版升级

适用于：之前按旧文档手工部署（Asterisk 配置直接写在 `config/` 下，phonebox 单独一个 compose），或者在旧版上打过 TLS / 唤醒补丁（`pb-legacy`、`#include pjsip_phonebox.conf` 那一套）。

新版的思路：配置全部由 `.env` + 模板渲染，容器每次启动重建 `/etc/asterisk`，所以**不要把旧的 conf 文件拷过来**，只迁移数据和口令。

## 1. 备份

停容器前先把旧目录整个复制一份（Asterisk 配置目录、日志里的 `phonebox/` 记录目录、phonebox 的 `state/`、`mast.db`、`model/`）。

## 2. 迁移数据

| 旧位置 | 新位置 |
|---|---|
| Asterisk 日志下的 `phonebox/sms.jsonl`、`calls.jsonl` | `${DATA_DIR}/records/` |
| phonebox `state/`（sessions、tokens、contacts、settings、telo-cache） | `${DATA_DIR}/phonebox/state/`（**不要**带 `passcode`，见下） |
| `mast.db`、`mast.version` | `${DATA_DIR}/mast/` |
| `model/`（tflite、vocab、rules.json、版本文件） | `${DATA_DIR}/model/` |
| Asterisk `spool/`（语音信箱） | `${DATA_DIR}/asterisk/spool/` |
| `astdb.sqlite3`（黑白名单、唤醒设置） | `${DATA_DIR}/asterisk/db/astdb.sqlite3` |

astdb 在 Ubuntu 包里默认是 `/var/lib/asterisk/astdb.sqlite3`；旧部署如果没把它挂出来，先在旧容器里导出：`asterisk -rx "database show"` 记下名单，新环境再用 App 或 `database put` 加回去。

## 3. 沿用旧口令（可选）

想让已配置好的客户端不用改，就在第一次运行 init.sh **之前**手动建好这些文件（权限 600），init.sh 看到已存在就不会重新生成：

- `secrets/passcode`：旧 PIN（6 位数字）
- `secrets/ami-secret`：随便，只有 phonebox 用
- `secrets/sip.env`：`SIP_APP_PASS=<App 分机旧口令>` 和 `SIP_EXT2_PASS=<第二分机旧口令>`
- `secrets/tls/asterisk.crt`、`asterisk.key`：旧证书，这样 App 里的指纹不用改

分机号沿用旧值就在 `.env` 里改 `APP_EXT` / `EXT2`。旧版的第三个分机（比如另一台 SIP 话机）新模板里没有，需要的话照着 `pjsip.conf.tmpl` 里 `EXT2` 那段加一段，并把它加进 `init.sh` 里的 `PHONES`。

## 4. 对照旧配置填 .env

| 旧配置 | .env |
|---|---|
| `pjsip.conf` `[transport-udp]` 的 `external_signaling_address` / `local_net` | `EXTERNAL_ADDRESS` / `LOCAL_NET` |
| `[transport-udp]` 的 `external_signaling_port` | `SIP_UDP_PUBLIC_PORT` |
| 补丁的 `PB_TLS_PUBLIC_PORT` | `SIP_TLS_PUBLIC_PORT` |
| 补丁的 `--with-tcp` / `--tls-only-101` | `SIP_TCP=yes` / `APP_TLS_ONLY=yes` |
| 补丁 `[globals]` 的 `PB_APP_EXT`、`PB_WAKE_*` | `APP_EXT`、`WAKE_METHOD`、`WAKE_INTERVAL`、`WAKE_WAIT` |
| `rtp.conf` | `RTP_START` / `RTP_END` |
| `quectel.conf` 的 `audio` / `data` | `QUECTEL_AUDIO` / `QUECTEL_DATA` |
| `sms-notify.sh` 里写死的通知网关地址和令牌 | `NOTIFY_KIND=generic`、`NOTIFY_URL`、`NOTIFY_TOKEN` |
| phonebox 的 `PHONEBOX_ORIGIN` | `PHONEBOX_ORIGIN` |
| phonebox compose 的端口映射 | `PHONEBOX_BIND` / `PHONEBOX_PORT` |

## 5. 切换

1. 停掉旧的 asterisk 和 phonebox 容器（会断开正在进行的通话，挑空闲时间）。
2. `scripts/init.sh`，`docker compose up -d --build`。
3. 按 README 的“检查”一节逐项确认，再让 App 重新注册一次。
4. 旧的号码库 / 模型定时任务换成 `scripts/update-data.sh`。

## 和补丁版的差异

- 来电流程合并成一份：名单（astdb）→ `/api/lookup` → 拦截或振铃，没有 `pb-legacy` 和 `anti-spam` 子程序（名单前置已覆盖）。
- 第二分机也启用了 SDES 乐观 SRTP（客户端不开 SRTP 照样能通）。
- AMI 只监听 127.0.0.1；phonebox 默认只听 127.0.0.1。
- phonebox 的环境变量默认值改成了中性值（AMI `127.0.0.1`、号码库 `/mast/mast.db`、免登录查号只放行 `127.0.0.1`）。
