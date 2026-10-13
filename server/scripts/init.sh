#!/usr/bin/env bash
# 首次部署 / 改了 .env 之后运行：生成随机口令（已有的不动）、TLS 证书、渲染 Asterisk 配置、建数据目录。
# 可重复执行。口令只写进 secrets/，屏幕上不打印；要看 PIN：cat secrets/passcode
set -euo pipefail
cd "$(dirname "$0")/.."
SERVER=$(pwd)

if [ ! -f .env ]; then
  cp .env.example .env
  echo "已从 .env.example 复制出 .env，请先按注释修改（至少 SIP_DOMAIN、PHONEBOX_ORIGIN），再重新运行本脚本。"
  exit 1
fi
command -v openssl >/dev/null || { echo "需要 openssl" >&2; exit 1; }
command -v python3 >/dev/null || { echo "需要 python3" >&2; exit 1; }
set -a; . ./.env; set +a

DATA_DIR=${DATA_DIR:-./data}
SEC=secrets
umask 077
mkdir -p "$SEC" "$SEC/asterisk-etc"
chmod 700 "$SEC"

# ---- 随机口令：文件存在就不重新生成，保证重复执行不改已配好的客户端 ----
rand() { openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c "$1"; }
pin() { python3 -c 'import secrets; print(f"{secrets.randbelow(10**6):06d}")'; }
new_secret() {  # new_secret <文件> <生成命令...>
  local f="$SEC/$1"; shift
  if [ -s "$f" ]; then return; fi
  "$@" > "$f.tmp" && mv "$f.tmp" "$f"
  echo "  生成 $f"
}
echo "== 口令（已有的保持不变）"
new_secret ami-secret rand 32
new_secret passcode pin
new_secret wake_key rand 32
new_secret voicemail-pin pin
if [ ! -s "$SEC/sip.env" ]; then
  { echo "SIP_APP_PASS=$(rand 24)"; echo "SIP_EXT2_PASS=$(rand 24)"; } > "$SEC/sip.env.tmp" && mv "$SEC/sip.env.tmp" "$SEC/sip.env"
  echo "  生成 $SEC/sip.env（App 分机 ${APP_EXT:-101} 和第二分机的 SIP 口令）"
fi

# ---- TLS 证书 ----
echo "== TLS 证书"
# shellcheck disable=SC2086
scripts/gen-tls-cert.sh --out "$SEC/tls" "${SIP_DOMAIN:?.env 里要填 SIP_DOMAIN}" ${TLS_EXTRA_SAN:-}

# ---- 渲染 Asterisk 配置 ----
echo "== 渲染 Asterisk 配置到 $SEC/asterisk-etc"
PB_HOST=${PHONEBOX_BIND:-127.0.0.1}
[ "$PB_HOST" = 0.0.0.0 ] && PB_HOST=127.0.0.1
PHONES="PJSIP/${APP_EXT:-101}"
[ -n "${EXT2:-}" ] && PHONES="$PHONES&PJSIP/$EXT2"
GEN="$SEC/derived.env"
{
  echo "PHONES=$PHONES"
  echo "PHONEBOX_URL=http://$PB_HOST:${PHONEBOX_PORT:-18989}"
  echo "AMI_SECRET=$(cat "$SEC/ami-secret")"
  echo "VM_PIN=$(cat "$SEC/voicemail-pin")"
} > "$GEN"
python3 scripts/render.py asterisk/templates "$SEC/asterisk-etc" .env "$SEC/sip.env" "$GEN"
rm -f "$GEN"

# ---- 数据目录 ----
echo "== 数据目录 $DATA_DIR"
umask 022
mkdir -p "$DATA_DIR"/{records,mast,model,phonebox/state,asterisk/log,asterisk/spool,asterisk/db}
touch "$DATA_DIR/records/sms.jsonl" "$DATA_DIR/records/calls.jsonl"

echo
echo "完成。接下来：docker compose up -d（或 podman compose up -d）"
echo "  网页/App 登录 PIN：cat $SEC/passcode"
echo "  App 分机 ${APP_EXT:-101} 的 SIP 口令：grep SIP_APP_PASS $SEC/sip.env"
echo "  短信唤醒密钥（填进 App）：cat $SEC/wake_key；唤醒号码用 scripts/set-wake.sh 设置"
