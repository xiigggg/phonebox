# Asterisk 20（Ubuntu 24.04 apt 包）+ chan_quectel（构建时从上游固定 commit 编译）
# x86_64 / arm64 均可构建：模块目录按 /usr/lib/<多架构三元组>/asterisk/modules 自动定位。
# 需要代理时用 --build-arg HTTP_PROXY=... --build-arg HTTPS_PROXY=...（预定义参数，不进镜像）。
ARG BASE=ubuntu:24.04

FROM ${BASE} AS build
# 国内网络可传 --build-arg APT_MIRROR=http://mirrors.tuna.tsinghua.edu.cn/ubuntu；留空用官方源
ARG APT_MIRROR=
RUN if [ -n "$APT_MIRROR" ]; then sed -i "s#http://archive.ubuntu.com/ubuntu#${APT_MIRROR}#g; s#http://security.ubuntu.com/ubuntu#${APT_MIRROR}#g; s#http://ports.ubuntu.com/ubuntu-ports#${APT_MIRROR}-ports#g" /etc/apt/sources.list.d/ubuntu.sources; fi \
 && apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    asterisk asterisk-dev build-essential autoconf automake libtool pkg-config ca-certificates git \
    libsqlite3-dev libasound2-dev \
 && rm -rf /var/lib/apt/lists/*
# 选 IchthysMaranatha/asterisk-chan-quectel：它是 chan_dongle 针对移远 EC20/EC25 的分支，
# 支持串口音频和 UAC，autotools 构建可指定 Asterisk 20；CLI（quectel sms/show devices）与本项目拨号计划一致。
# 固定到 master 的 3d45c7f（2025-03-24，修 GCC 14 编译问题，Ubuntu 24.04 默认 GCC 13/14 均可编）。
ARG CHAN_QUECTEL_REPO=https://github.com/IchthysMaranatha/asterisk-chan-quectel.git
ARG CHAN_QUECTEL_COMMIT=3d45c7f072131296a7e3c1a4faf5bb18751dbd87
WORKDIR /src
RUN git init -q . && git remote add origin "$CHAN_QUECTEL_REPO" \
 && git fetch -q --depth 1 origin "$CHAN_QUECTEL_COMMIT" && git checkout -q FETCH_HEAD \
 && ASTVER=$(dpkg-query -W -f '${Version}' asterisk | sed 's/^[0-9]*://; s/[~+-].*//' | cut -d. -f1,2) \
 && MODDIR=$(echo /usr/lib/*/asterisk/modules) \
 && ./bootstrap && ./configure --with-astversion="$ASTVER" DESTDIR="$MODDIR" && make \
 && mkdir /out && cp chan_quectel.so /out/ && cp etc/quectel.conf /out/quectel.conf.sample

FROM ${BASE}
ARG APT_MIRROR=
RUN if [ -n "$APT_MIRROR" ]; then sed -i "s#http://archive.ubuntu.com/ubuntu#${APT_MIRROR}#g; s#http://security.ubuntu.com/ubuntu#${APT_MIRROR}#g; s#http://ports.ubuntu.com/ubuntu-ports#${APT_MIRROR}-ports#g" /etc/apt/sources.list.d/ubuntu.sources; fi \
 && apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    asterisk asterisk-core-sounds-en-gsm curl perl procps libasound2t64 ca-certificates tzdata \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /out/ /tmp/cq/
RUN cp /tmp/cq/chan_quectel.so /usr/lib/*/asterisk/modules/ \
 && cp /tmp/cq/quectel.conf.sample /etc/asterisk/quectel.conf.sample && rm -rf /tmp/cq
COPY scripts/ /etc/asterisk/scripts/
RUN chmod 755 /etc/asterisk/scripts/*.sh \
 && sed -i 's#^;\?\s*astdbdir\s*=>.*#astdbdir => /var/lib/asterisk/db#' /etc/asterisk/asterisk.conf \
 && grep -q '^astdbdir => /var/lib/asterisk/db' /etc/asterisk/asterisk.conf \
 && cp -a /etc/asterisk /etc/asterisk.dist
COPY entrypoint.sh /entrypoint.sh
RUN chmod 755 /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
