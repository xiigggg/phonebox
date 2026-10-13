#!/bin/sh
# sms-notify.sh <发件人> <正文 Base64>：由拨号计划 [quectel-incoming] sms 调用。
# 本地正则 + phonebox 的 PixelText 离线模型分类，写 sms.jsonl，再按等级推送；正文不经过任何云端模型。
# 推送方式见 /etc/asterisk/phonebox.env（由 init.sh 按 .env 渲染）：
#   NOTIFY_KIND=none     不推送（只进网页和 App）
#   NOTIFY_KIND=generic  POST JSON {source,event_id,title,body} 到 NOTIFY_URL，NOTIFY_TOKEN 非空时带 Bearer
#   NOTIFY_KIND=bark     POST JSON {title,body,group} 到 NOTIFY_URL（形如 https://api.day.app/<设备 key>）
#   NOTIFY_KIND=ntfy     发布到 NOTIFY_URL（形如 https://ntfy.sh/<主题>），NOTIFY_TOKEN 非空时带 Bearer
FROM="$1"
BODY=$(printf '%s' "$2" | base64 -d 2>/dev/null) || exit 1
NOTIFY_KIND=none; NOTIFY_URL=""; NOTIFY_TOKEN=""; PHONEBOX_URL="http://127.0.0.1:8080"
[ -f /etc/asterisk/phonebox.env ] && . /etc/asterisk/phonebox.env
DATA=/var/log/asterisk/phonebox
mkdir -p "$DATA"

# ---- 分类 ----
CAT="其他"
echo "$BODY" | grep -qE '验证码|动态码|动态密码|校验码|交易密码|登录密码|verification|code is' && CAT="验证码"
[ "$CAT" = "其他" ] && echo "$BODY" | grep -qE '退订|回T|回复T|TD退|拒收|取件码.*退' && CAT="骚扰"
[ "$CAT" = "其他" ] && echo "$FROM" | grep -qE '^100(86|10|00|01)|^10657' && CAT="运营商"
[ "$CAT" = "其他" ] && echo "$BODY" | grep -qE '银行|信用卡|借记卡|尾号|交易|还款|账单|余额变动' && CAT="金融"
[ "$CAT" = "其他" ] && echo "$BODY" | grep -qE '快递|取件|包裹|驿站|签收|派送' && CAT="快递"

ESC=$(printf '%s' "$BODY" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | tr '\n\r\t' '   ')

# ---- PixelText 离线模型打分：非验证码类，score>=0.7 判骚扰 ----
SCORE=""
if [ "$CAT" != "验证码" ]; then
  SCORE=$(curl -fsS -m 3 -X POST "$PHONEBOX_URL/api/sms-score" \
    -H "Content-Type: application/json" -d "{\"text\":\"$ESC\"}" 2>/dev/null \
    | sed -n 's/.*"score": *\([0-9.]*\).*/\1/p')
  if [ -n "$SCORE" ] && awk "BEGIN{exit !($SCORE>=0.7)}" 2>/dev/null; then
    CAT="骚扰（模型）"
  fi
fi

# ---- 推送等级：urgent=强提醒, normal=静默推送, none=不推 ----
LEVEL="normal"
[ "$CAT" = "验证码" ] && LEVEL="urgent"
case "$CAT" in 骚扰*) LEVEL="none";; esac

TS=$(date +%s)
echo "$(date '+%F %T') cat=$CAT level=$LEVEL score=${SCORE:-NA} len=${#BODY}" >> /var/log/asterisk/sms-meta.log
# shell 环境中的中文是 UTF-8 字节；显式解码后再写入 UTF-8 JSONL，避免双重转码。
TS="$TS" FROM="$FROM" CAT="$CAT" LEVEL="$LEVEL" BODY="$BODY" DATA="$DATA" perl -MJSON::PP -MEncode=decode,FB_DEFAULT -e '
  my $u=sub { decode("UTF-8", $_[0] // "", FB_DEFAULT) };
  my $row={ts=>0+$ENV{TS}, from=>$u->($ENV{FROM}), cat=>$u->($ENV{CAT}), level=>$u->($ENV{LEVEL}), body=>$u->($ENV{BODY})};
  open my $f, ">>:encoding(UTF-8)", "$ENV{DATA}/sms.jsonl" or die $!;
  print $f JSON::PP->new->utf8(0)->canonical->encode($row), "\n";
'

[ "$LEVEL" = "none" ] && exit 0
[ -n "$NOTIFY_URL" ] || exit 0
TITLE="【静默】[$CAT] $FROM"
[ "$LEVEL" = "urgent" ] && TITLE="【重要】$FROM"
AUTH=""
[ -n "$NOTIFY_TOKEN" ] && AUTH="Authorization: Bearer $NOTIFY_TOKEN"
case "$NOTIFY_KIND" in
  generic)
    curl -fsS -m 8 -X POST "$NOTIFY_URL" -H "Content-Type: application/json" ${AUTH:+-H "$AUTH"} \
      -d "{\"source\":\"sms\",\"event_id\":\"sms-$TS-$$\",\"title\":\"$TITLE\",\"body\":\"$ESC\"}" >/dev/null 2>&1 ;;
  bark)
    LV=passive; [ "$LEVEL" = "urgent" ] && LV=timeSensitive
    curl -fsS -m 8 -X POST "$NOTIFY_URL" -H "Content-Type: application/json" \
      -d "{\"title\":\"$TITLE\",\"body\":\"$ESC\",\"group\":\"sms\",\"level\":\"$LV\"}" >/dev/null 2>&1 ;;
  ntfy)
    # 用 JSON 发布（POST 到服务器根地址），标题里的中文不必做 RFC 2047 编码
    PRI=2; [ "$LEVEL" = "urgent" ] && PRI=5
    curl -fsS -m 8 -X POST "${NOTIFY_URL%/*}" -H "Content-Type: application/json" ${AUTH:+-H "$AUTH"} \
      -d "{\"topic\":\"${NOTIFY_URL##*/}\",\"title\":\"$TITLE\",\"message\":\"$ESC\",\"priority\":$PRI}" >/dev/null 2>&1 ;;
esac
exit 0
