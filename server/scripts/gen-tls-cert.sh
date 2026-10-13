#!/usr/bin/env bash
# 生成 Asterisk TLS 用的自签长期证书，打印 SHA-256 指纹给 App 向导填写。init.sh 会自动调用。
# 用法：gen-tls-cert.sh [--out 目录] [--days 天数] [--rsa] <域名> [额外 SAN ...]
#   --out  默认 <server>/secrets/tls（容器里挂成 /etc/asterisk/keys）
#   额外 SAN 例：IP:192.168.1.10 DNS:nas.lan（局域网直连也想通过校验时用）
#   默认 EC P-256（握手快、证书小）；--rsa 改用 RSA 3072
#   已存在 asterisk.crt/asterisk.key 时不覆盖，只打印现有指纹；想换证书先把旧文件移走
set -euo pipefail

OUT="$(cd "$(dirname "$0")/.." && pwd)/secrets/tls"
DAYS=7300
ALG=ec
DOMAIN=""
EXTRA=()
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT="${2%/}"; shift ;;
    --days) DAYS="$2"; shift ;;
    --rsa) ALG=rsa ;;
    -*) echo "未知参数：$1" >&2; exit 2 ;;
    *) if [ -z "$DOMAIN" ]; then DOMAIN="$1"; else EXTRA+=("$1"); fi ;;
  esac
  shift
done
[ -n "$DOMAIN" ] || { echo "用法：gen-tls-cert.sh [--out 目录] <域名> [DNS:名字|IP:地址 ...]" >&2; exit 2; }
# 只放行域名/IP 字符，防止拼进 openssl 参数时出岔子
[[ "$DOMAIN" =~ ^[A-Za-z0-9.-]+$ ]] || { echo "域名不合法：$DOMAIN" >&2; exit 2; }
[[ "$DAYS" =~ ^[0-9]+$ ]] || { echo "--days 必须是数字" >&2; exit 2; }
SAN="DNS:$DOMAIN"
for s in ${EXTRA[@]+"${EXTRA[@]}"}; do
  [[ "$s" =~ ^(DNS:[A-Za-z0-9.-]+|IP:[0-9a-fA-F.:]+)$ ]] || { echo "SAN 不合法：$s（格式 DNS:名字 或 IP:地址）" >&2; exit 2; }
  SAN="$SAN,$s"
done

CRT="$OUT/asterisk.crt"; KEY="$OUT/asterisk.key"
mkdir -p "$OUT"
if [ -e "$CRT" ] || [ -e "$KEY" ]; then
  echo "证书已存在，不覆盖：$CRT"
else
  umask 077
  if [ "$ALG" = ec ]; then KEYOPT=(-newkey ec -pkeyopt ec_paramgen_curve:prime256v1)
  else KEYOPT=(-newkey rsa:3072); fi
  openssl req -x509 "${KEYOPT[@]}" -nodes -days "$DAYS" -sha256 \
    -subj "/CN=$DOMAIN" -addext "subjectAltName=$SAN" \
    -keyout "$KEY.tmp" -out "$CRT.tmp" 2>/dev/null
  chmod 600 "$KEY.tmp"; chmod 644 "$CRT.tmp"
  mv "$KEY.tmp" "$KEY"; mv "$CRT.tmp" "$CRT"
  echo "已生成：$CRT（有效期 $DAYS 天，SAN=$SAN）"
fi
openssl x509 -in "$CRT" -noout -subject -enddate -ext subjectAltName 2>/dev/null | sed 's/^/  /'
FP=$(openssl x509 -in "$CRT" -noout -fingerprint -sha256 | cut -d= -f2)
echo "证书 SHA-256 指纹（填进 App 向导）：$FP"
