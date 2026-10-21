#!/system/bin/sh
# 开机（late_start service 阶段）后拉起 Root Guardian。
# 只在 App 自己建过开关文件时才启动：那个文件在 App 私有目录里，只有 App 本身和 root 能写，
# 等于“这台机器上装着的这个包明确要求了守护”。不做任何别的事。
PKG=@PKG@
MAIN=@MAIN@
FLAG=/data/user/0/$PKG/files/root-guardian/enabled

until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 5; done
# App 的数据在凭据加密存储里，用户首次解锁后才看得到；最多等 2 小时
i=0
while [ ! -f "$FLAG" ]; do
  i=$((i + 1))
  [ "$i" -gt 720 ] && exit 0
  sleep 10
done

APK=$(pm path "$PKG" 2>/dev/null | head -n 1 | cut -d: -f2)
case "$APK" in
  /data/app/*/base.apk) ;;
  *) exit 0 ;;
esac
CLASSPATH="$APK" setsid app_process /system/bin "$MAIN" </dev/null >/dev/null 2>&1 &
