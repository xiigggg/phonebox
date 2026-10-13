#!/usr/bin/env bash
# 设置短信唤醒：把 App 所在手机的号码和 secrets/wake_key 写进 Asterisk 的 astdb。
# 用法：set-wake.sh <手机号>   关闭唤醒：set-wake.sh --off
# 环境变量 COMPOSE 可指定命令（默认自动选 docker compose / podman compose）
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -n "${COMPOSE:-}" ]; then read -ra DC <<<"$COMPOSE"
elif docker compose version >/dev/null 2>&1; then DC=(docker compose)
else DC=(podman compose); fi
ast() { "${DC[@]}" exec -T asterisk asterisk -rx "$1"; }
case "${1:-}" in
  --off) ast "database del phonebox wake_number"; echo "已关闭短信唤醒"; exit 0 ;;
  '') echo "用法：set-wake.sh <手机号> | --off" >&2; exit 2 ;;
esac
NUM=$(printf '%s' "$1" | tr -cd '0-9+')
[[ "$NUM" =~ ^\+?[0-9]{5,20}$ ]] || { echo "号码不合法" >&2; exit 2; }
KEY=$(cat secrets/wake_key)
ast "database put phonebox wake_number $NUM" >/dev/null
ast "database put phonebox wake_key $KEY" >/dev/null
echo "已设置唤醒号码；密钥在 secrets/wake_key，填进 App 的唤醒设置"
