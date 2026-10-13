#!/bin/sh
# messages.log 防爆：超过 100MB 只保留最后 10MB（公网 SIP 扫描能在几天内把日志刷到上百 GB）
F=/var/log/asterisk/messages.log
[ -f "$F" ] || exit 0
s=$(stat -c %s "$F")
[ "$s" -gt 104857600 ] || exit 0
tail -c 10485760 "$F" > "$F.tmp" && cat "$F.tmp" > "$F" && rm -f "$F.tmp"
echo "$(date '+%F %T') trimmed $s bytes" >> /var/log/asterisk/log-guard.log
