#!/bin/sh
# call-log.sh <in|out> <号码> <disposition> <billsec> [tag Base64]
DIR="$1"; NUM="$2"; DISP="$3"; BILL="${4:-0}"
TAG=$(printf '%s' "${5:-}" | base64 -d 2>/dev/null || true)
DATA=/var/log/asterisk/phonebox
mkdir -p "$DATA"
# Perl 从 shell 环境得到的是 UTF-8 字节；先解码成字符再经 UTF-8 文件层输出，避免中文双重转码。
DIR="$DIR" NUM="$NUM" DISP="$DISP" BILL="$BILL" TAG="$TAG" DATA="$DATA" perl -MJSON::PP -MEncode=decode,FB_DEFAULT -e '
  my $u=sub { decode("UTF-8", $_[0] // "", FB_DEFAULT) };
  my $row={ts=>time, dir=>$u->($ENV{DIR}), number=>$u->($ENV{NUM}), disposition=>$u->($ENV{DISP}), billsec=>0+($ENV{BILL}||0), tag=>$u->($ENV{TAG})};
  open my $f, ">>:encoding(UTF-8)", "$ENV{DATA}/calls.jsonl" or die $!;
  print $f JSON::PP->new->utf8(0)->canonical->encode($row), "\n";
'
