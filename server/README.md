# 电话信箱服务端（server/）

在一台常开的 Linux 主机（NAS、小主机、树莓派等，x86_64 或 arm64）上用 Docker 跑两个容器：

| 容器 | 作用 |
|---|---|
| `asterisk` | Asterisk 20（Ubuntu 24.04 apt 包）+ chan_quectel。4G 模块收发短信、接打电话；手机 App 经 SIP（推荐 TLS）注册成分机 |
| `phonebox` | 网页和 App 接口：短信 / 通话记录、发短信、黑白名单、号码识别（PixelTelo 离线号码库）、垃圾短信打分（PixelText 离线模型）、SSE 实时推送 |
| `caddy`（可选） | HTTPS 反代示例，`--profile caddy` 才启用 |

```
4G 模块(EC20) ──USB──> asterisk ──CURL /api/lookup──> phonebox <──HTTPS(反代)── App / 浏览器
                         │  └─ 写 records/*.jsonl ─────────┘
                         └──SIP/TLS + SRTP──> App 分机、其他 SIP 客户端
```

两个容器都用 host 网络：SIP 的 NAT 处理和 RTP 端口段这样最简单；AMI 只听 `127.0.0.1`，phonebox 默认也只听 `127.0.0.1`。

## 目录

```
docker-compose.yml          asterisk + phonebox；caddy 用 profile 可选
docker-compose.ec20.yml     4G 模块串口映射（接了模块再启用）
.env.example                全部可配置项
scripts/init.sh             生成口令、TLS 证书，渲染配置，建数据目录（可重复执行）
scripts/gen-tls-cert.sh     自签长期证书，打印 SHA-256 指纹
scripts/set-wake.sh         设置短信唤醒号码和密钥
scripts/update-data.sh      更新号码库和垃圾短信模型（建议每周 cron）
asterisk/                   Dockerfile、entrypoint、配置模板、拨号计划调用的脚本
phonebox/                   server.py、smsrules.py、网页前端、自动化测试
caddy/Caddyfile.example     反代示例
UPGRADE.md                  从旧版 / 补丁版升级
```

运行后生成（都在 `.gitignore` 里，不要提交）：

- `.env`：你的配置。
- `secrets/`：口令、PIN、TLS 证书、渲染好的 Asterisk 配置。目录权限 700。
- `${DATA_DIR}`（默认 `./data`）：通话 / 短信记录、号码库、模型、Asterisk 日志、语音信箱、astdb（黑白名单和唤醒设置存在这里）。

## 部署

### 1. 准备硬件

- 4G 模块：移远 EC20 / EC25 等 chan_quectel 支持的型号，要支持语音（VoLTE）和 USB 串口音频（或 UAC）。插上后 `ls /dev/ttyUSB*` 一般能看到 ttyUSB0~3，EC20 通常 ttyUSB1 是音频口、ttyUSB2 是 AT 口。
- 能打电话、收短信的 SIM 卡。部分运营商需要先开通 VoLTE。
- 主机装好 Docker（带 compose 插件）或 Podman（`podman compose`）。另外需要 `bash`、`openssl`、`python3`、`curl`。

没有 4G 模块也能先部署，SIP 和网页都能用，只是 `quectel show devices` 显示设备未连接。

### 2. 生成配置

```sh
cd server
cp .env.example .env
vi .env              # 至少改 SIP_DOMAIN、PHONEBOX_ORIGIN；在 NAT 后要外网用就填 EXTERNAL_ADDRESS
scripts/init.sh
```

init.sh 会：

1. 随机生成 AMI 口令、两个分机的 SIP 口令、网页 / App 登录 PIN（6 位）、短信唤醒密钥、语音信箱 PIN，写进 `secrets/`。**已存在的不会重新生成**，所以改了 `.env` 之后可以放心重跑。
2. 生成自签 TLS 证书（EC P-256，20 年），打印 SHA-256 指纹。
3. 用 `.env` 渲染 `asterisk/templates/*.tmpl` 到 `secrets/asterisk-etc/`。
4. 建数据目录。

口令不会打印在屏幕上，需要时自己看：

```sh
cat secrets/passcode                  # 网页 / App 登录 PIN
grep SIP_APP_PASS secrets/sip.env     # App 分机口令
cat secrets/wake_key                  # 短信唤醒密钥
```

接了 4G 模块的话，在 `.env` 里取消 `COMPOSE_FILE=docker-compose.yml:docker-compose.ec20.yml` 的注释，并核对 `QUECTEL_AUDIO` / `QUECTEL_DATA`。

### 3. 启动

```sh
docker compose up -d --build        # 或 podman compose up -d --build
```

asterisk 镜像要从 GitHub 克隆 chan_quectel 并编译，第一次构建要几分钟。需要代理或国内镜像时：

```sh
docker compose build --build-arg HTTPS_PROXY=http://代理:端口 --build-arg HTTP_PROXY=http://代理:端口
# apt 慢可以在 .env 里设 APT_MIRROR=http://mirrors.tuna.tsinghua.edu.cn/ubuntu
```

改了 `.env` 之后：重跑 `scripts/init.sh`，再 `docker compose up -d --force-recreate asterisk`（重启会断开正在进行的通话）。

### 4. 检查

```sh
A() { docker compose exec -T asterisk asterisk -rx "$1"; }
A "pjsip show transports"      # 应有 transport-udp 和 transport-tls（SIP_TCP=yes 时还有 tcp）
A "quectel show devices"       # 有模块时 State 应为 Free；没模块时显示 Not connected
A "module show like quectel"   # chan_quectel.so Running
curl -s http://127.0.0.1:18989/health                     # ok
openssl s_client -connect 127.0.0.1:5061 </dev/null 2>/dev/null | openssl x509 -noout -fingerprint -sha256
scripts/gen-tls-cert.sh sip.example.com                   # 证书已存在时只打印指纹，两个指纹应一致
```

想测一下通话：在 `.env` 设 `TEST_ECHO=yes`，重跑 init.sh 并重建 asterisk，用任意 SIP 客户端以 App 分机经 TLS 注册后拨 `*43`，能听到自己的声音即正常。测完改回 `no`。

### 5. 路由器与安全

从外网用需要端口转发。**建议只暴露 TLS**：

| 用途 | 协议 | 外网端口 | 转到 |
|---|---|---|---|
| SIP TLS | TCP | 任选一个高位端口（= `.env` 的 `SIP_TLS_PUBLIC_PORT`） | 主机:`SIP_TLS_PORT` |
| RTP | UDP | `RTP_START`-`RTP_END` | 主机同样范围 |
| 网页 / App 接口 | TCP | 443（或你的反代端口） | 反代 |

- 不要转发 UDP/TCP 5060。公网 SIP 扫描器 24 小时不停地猜口令，明文 UDP 是主要目标。
- `APP_TLS_ONLY=yes` 只让 Asterisk **呼叫** App 分机时固定走 TLS；Asterisk（PJSIP）没有按传输限制**注册**的选项，所以它**不能**阻止有口令的人从 UDP 注册（本机实测：开启后 UDP REGISTER 仍返回 200 OK）。真正挡住明文注册靠的是上一条：路由器不转发 5060；局域网内也不想留 UDP 时，用宿主机防火墙挡掉 `SIP_UDP_PORT`，或把 `SIP_BIND` 只绑到不对外的地址。
- SIP 口令是 24 位随机串；不要改成弱口令。
- 限速封禁：Asterisk 的 `security` 日志写在 `${DATA_DIR}/asterisk/log/messages.log`，可以用 fail2ban 的 `asterisk` 过滤器读它，对连续认证失败的来源封禁（fail2ban 跑在宿主机上，动作用 iptables/nftables）。路由器支持的话，也可以对 TLS 端口做每 IP 新建连接限速，或只允许本国 IP。
- 容器每小时检查一次 `messages.log`，超过 100MB 只留最后 10MB，防止被扫描刷满磁盘。
- `/api/lookup`、`/api/sms-score` 不需要登录，只允许本机访问；反代上也要对公网屏蔽（示例 Caddyfile 已做）。
- AMI 只监听 127.0.0.1，并且只允许本机连接。

### 6. 反代（网页和 App 接口）

phonebox 本身是明文 HTTP，默认只听 `127.0.0.1:18989`，需要反代加 HTTPS。

- 用自带的 Caddy：`cp caddy/Caddyfile.example caddy/Caddyfile`，改域名，`docker compose --profile caddy up -d`。Caddy 会自动申请证书（需要 80/443 可从公网访问）。
- 用已有的反代：反代到 `127.0.0.1:18989`，并对 `/api/lookup*`、`/api/sms-score*` 返回 404。`/api/events` 是 SSE 长连接，不要开响应缓冲（Nginx 要 `proxy_buffering off`；Caddy 默认就会立即刷新，可写 `flush_interval -1` 保险）。
- `.env` 的 `PHONEBOX_ORIGIN` 必须和浏览器 / App 访问的地址完全一致（协议、域名、端口），否则网页登录和 App 换令牌都会被拒（403）。
- 可选的 App 内更新：把 `version.json` 和 APK 放进 `${DATA_DIR}/app-update/`，Caddy 示例里的 `/app-update/` 会托管它们。

### 7. 短信唤醒

Android 系统可能会杀掉 App 的后台连接。App 分机离线时有来电，Asterisk 会经 4G 模块给 App 所在手机发一条 `PBWAKE:<时间>:<HMAC>` 短信，App 收到后拉起服务重新注册，Asterisk 最多等 `WAKE_WAIT` 秒再振铃。两次唤醒至少间隔 `WAKE_INTERVAL` 秒。

```sh
scripts/set-wake.sh <App 所在手机的号码>    # 写入 astdb 的唤醒号码和 secrets/wake_key
scripts/set-wake.sh --off                  # 关闭
```

然后把 `secrets/wake_key` 的内容填进 App 的唤醒设置。每条唤醒短信按运营商短信计费。

### 8. 号码库与模型更新

```sh
scripts/update-data.sh            # 号码库 + 模型 + 短信规则；已是最新就什么都不做
```

下载后先校验 sha256（号码库还会检查条目数），再原子替换；只有模型变了才重启 phonebox，并检查 `/health`。不运行也能用，只是没有离线号码标签和垃圾短信打分。

每周一次的 cron 示例（`crontab -e`）：

```
17 4 * * 1  /path/to/server/scripts/update-data.sh >> /path/to/server/data/update.log 2>&1
```

用 Podman 时在 crontab 里加 `COMPOSE="podman compose"`。

**联网查号（隐私提示）**：离线号码库没收录的来电号码，phonebox 默认会把**号码本身**发给 PixelTelo 的公开查询接口（`https://pixeltelo.api.mystery0.vip/api/v2/query`，2 秒超时，失败放行，结果缓存在 `state/telo-cache.json`）；打开网页通话记录时也会在后台补查未缓存的号码。不想把号码发给第三方，在 `.env` 里设 `PHONEBOX_TELO_URL=off`，再 `docker compose up -d` 让 phonebox 重建即可，此时只用离线库和黑白名单。

## 推送通知（可选）

收到短信时，验证码强提醒、普通短信静默推送、骚扰短信不推。在 `.env` 里选一种：

| NOTIFY_KIND | NOTIFY_URL 示例 | 说明 |
|---|---|---|
| `none` | | 不推送，只在网页和 App 里看 |
| `bark` | `https://api.day.app/<设备 key>` | iOS Bark |
| `ntfy` | `https://ntfy.sh/<主题>` | 主题名要够长够随机；自建 ntfy 时 `NOTIFY_TOKEN` 填访问令牌 |
| `generic` | 你的通知网关 | POST JSON `{source,event_id,title,body}`，`NOTIFY_TOKEN` 非空时带 Bearer |

改完重跑 init.sh 并重建 asterisk 容器。注意：推送会把短信正文发给推送服务。

## 自动化测试

```sh
python3 -m unittest -v phonebox/test/test_server.py
```

测试会临时起两个容器（假 AMI 和 phonebox，host 网络，端口 38443/35038），结束后删除。数据全是虚构号码，PIN 每次随机。拉镜像慢可以设 `PB7_IMAGE=<镜像站>/library/python:3.12-slim`。

## 常见问题

- **asterisk 容器反复重启，日志说没有渲染好的配置**：先运行 `scripts/init.sh`。在 SELinux 主机（Fedora/RHEL）上，compose 文件里的挂载已经带 `z` 标签。
- **`quectel show devices` 显示 Not connected**：没接模块，或者没启用 `docker-compose.ec20.yml`，或者 ttyUSB 编号变了（插拔后会变）。建议写 udev 规则把音频口、AT 口固定成稳定的名字，再填进 `.env`。
- **App 换令牌 / 网页登录返回 403**：`PHONEBOX_ORIGIN` 和实际访问地址不一致。
- **App TLS 注册不上**：确认路由器转发的是 TCP、`SIP_TLS_PUBLIC_PORT` 等于外网端口、App 里填的指纹和 `gen-tls-cert.sh` 打印的一致。换证书要先把 `secrets/tls/` 里的旧文件移走再跑 init.sh，并在 App 里更新指纹。
- **能注册、没声音**：RTP 端口段没转发，或者 `EXTERNAL_ADDRESS` / `LOCAL_NET` 没填对。
- **登录被锁**：15 分钟内错 5 次锁 15 分钟。
- **改了配置没生效**：配置每次容器启动时从 `secrets/asterisk-etc/` 重建，要先跑 init.sh 再重建容器；直接在容器里改 `/etc/asterisk` 会在重启后丢失。

## 已知限制

- chan_quectel 固定在上游一个 commit（见 `asterisk/Dockerfile`），只在 x86_64 上实际构建过；arm64 按同样步骤应该可以，但没有实测。
- 没有 4G 模块的环境下，只验证了 SIP / TLS / SRTP / 回声测试、网页接口、短信脚本和唤醒逻辑（唤醒用 `WAKE_METHOD=stub`）；真实收发短信和通话依赖你的模块和 SIM 卡。
- phonebox 的 SSE 每个连接占一个线程，最多 8 个连接。
