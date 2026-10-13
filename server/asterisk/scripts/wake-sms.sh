#!/bin/sh
# wake-sms.sh <quectel|stub> <设备> <号码>：给 App 所在手机发唤醒短信，由拨号计划 [pb-wake] 调用。
# 短信正文：PBWAKE:<unix秒>:<HMAC-SHA256(wake_key, "PBWAKE:<unix秒>") 十六进制前 16 位>
# 密钥从 astdb phonebox/wake_key 现读，不经命令行参数，避免出现在进程列表里。
METHOD=$(printf '%s' "$1" | tr -cd 'a-z')
DEV=$(printf '%s' "$2" | tr -cd 'a-z0-9')
NUM=$(printf '%s' "$3" | tr -cd '0-9+')
LOG=/var/log/asterisk/phonebox
[ -n "$NUM" ] || exit 0
KEY=$(asterisk -rx "database get phonebox wake_key" 2>/dev/null | sed -n 's/^Value: //p')
if [ -z "$KEY" ]; then
  echo "wake-sms: 未设置 phonebox/wake_key，不发送" >&2
  exit 0
fi
TS=$(date +%s)
MAC=$(TS="$TS" KEY="$KEY" perl -MDigest::SHA=hmac_sha256_hex -e 'print substr(hmac_sha256_hex("PBWAKE:$ENV{TS}", $ENV{KEY}), 0, 16)')
MSG="PBWAKE:$TS:$MAC"
case "$METHOD" in
  quectel) asterisk -rx "quectel sms $DEV $NUM \"$MSG\"" >/dev/null 2>&1 ;;
  stub) mkdir -p "$LOG"; echo "$TS $DEV $NUM $MSG" >> "$LOG/wake-stub.log" ;;
  *) echo "wake-sms: 未知发送方式 $METHOD" >&2 ;;
esac
