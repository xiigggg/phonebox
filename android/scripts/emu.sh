#!/usr/bin/env bash
# 无头模拟器启停：scripts/emu.sh start|stop [avd名，默认 pb36]
# 注意：部分 Linux 宿主上其它 -gpu 模式会让 qemu 段错误，swangle_indirect 最稳
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[ -f "$ROOT/.toolchain-env" ] && source "$ROOT/.toolchain-env"
AVD="${2:-pb36}"

case "${1:-}" in
  start)
    nohup emulator -avd "$AVD" -no-window -no-audio -no-boot-anim -no-snapshot \
      -gpu swangle_indirect >"/tmp/emu-$AVD.log" 2>&1 &
    adb wait-for-device
    for _ in $(seq 1 180); do
      [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && { echo "$AVD 已启动"; exit 0; }
      sleep 2
    done
    echo "$AVD 启动超时，日志见 /tmp/emu-$AVD.log" >&2; exit 1
    ;;
  stop)
    # 逐个关闭所有在跑的模拟器，避免遗留占内存
    for s in $(adb devices | awk '/^emulator-/{print $1}'); do adb -s "$s" emu kill || true; done
    for _ in $(seq 1 30); do adb devices | grep -q '^emulator-' || { echo "模拟器已关闭"; exit 0; }; sleep 1; done
    echo "模拟器未能在 30 秒内关闭" >&2; exit 1
    ;;
  *) echo "用法: $0 start|stop [avd名]" >&2; exit 2 ;;
esac
