#!/bin/bash
# 每次启动都用镜像自带配置重建 /etc/asterisk，再覆盖 init.sh 渲染好的文件：模板 + .env 是唯一来源，不会残留手改。
set -e
LOCAL=/etc/asterisk-local
find /etc/asterisk -mindepth 1 -maxdepth 1 ! -name keys -exec rm -rf {} +
cp -a /etc/asterisk.dist/. /etc/asterisk/
if [ -d "$LOCAL" ] && ls "$LOCAL"/*.conf >/dev/null 2>&1; then
  cp "$LOCAL"/*.conf /etc/asterisk/
  [ -f "$LOCAL/phonebox.env" ] && cp "$LOCAL/phonebox.env" /etc/asterisk/phonebox.env
  chmod 600 /etc/asterisk/*.conf /etc/asterisk/phonebox.env 2>/dev/null || true
else
  echo "entrypoint: $LOCAL 里没有渲染好的配置，先在宿主机运行 scripts/init.sh" >&2
  exit 1
fi
mkdir -p /var/log/asterisk/phonebox /var/log/asterisk/cdr-csv /var/lib/asterisk/db /var/spool/asterisk
# 公网 SIP 扫描曾把 messages.log 刷到上百 GB：后台每小时检查一次
( while sleep 3600; do /etc/asterisk/scripts/log-guard.sh; done ) &
exec /usr/sbin/asterisk -f
