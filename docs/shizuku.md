# Shizuku 体检与 Guardian

## 一键修复实际执行的命令

全部由 `shizuku/ShellService`（Shizuku UserService，shell 身份 uid 2000）按 `ShellTemplate` 固定模板以 argv 执行，不经 shell 解释；包名写死为本应用，uid 取 App 自己的 `Process.myUid()` 并校验在应用 uid 段。只执行体检为"需修复"的项。

在 Android 16 模拟器（官方 Shizuku v13.6.0，adb 方式启动）上实测执行，全部返回 0：

```
cmd deviceidle whitelist +top.xingzhenxi.phonebox
cmd appops set top.xingzhenxi.phonebox RUN_ANY_IN_BACKGROUND allow
cmd netpolicy add restrict-background-whitelist <本应用 uid>
cmd appops set top.xingzhenxi.phonebox SYSTEM_ALERT_WINDOW allow
cmd role add-role-holder --user 0 android.app.role.DIALER top.xingzhenxi.phonebox
cmd role add-role-holder --user 0 android.app.role.SMS top.xingzhenxi.phonebox
cmd role add-role-holder --user 0 android.app.role.CALL_REDIRECTION top.xingzhenxi.phonebox
pm grant top.xingzhenxi.phonebox android.permission.READ_LOGS
```

另有模板 `cmd appops set PKG USE_FULL_SCREEN_INTENT allow`（模拟器上该项本来就正常，未触发）。`pm install -r` 只留了接口（`ShellCommand.installUpdate`），未实现。

注意：
- 授予 READ_LOGS 会改变进程 gid，系统立即以 PERMISSION_CHANGE 杀掉本应用进程，所以它被放在最后单独执行，之前的结果先写进诊断日志。界面上有提示。
- 读取全部设备日志时 Android 13+ 会弹"允许访问所有设备日志"确认框，每次进程启动后首次读取都会弹；选"不允许"则只能看到本进程自己的日志，被杀日志为空。
- 通知权限没有对应模板，只能用户手动打开（体检页给"去设置"）。

## ColorOS 注意事项

- ColorOS 的"权限监控"可能拦截 Shizuku 执行的 appops/pm 命令。修复失败时去 开发者选项 打开"禁止权限监控"（英文 Disable system optimization）再试。
- 不要关闭 USB 调试和开发者选项，否则 Shizuku 会随机停止。
- 免 root 的 Shizuku 每次重启手机都要重新激活，Guardian 也随之停止；社区有开机后自动激活、挂了自动重启的 Shizuku 分支可选。
- 手动开关清单（自启动、关联启动、完全后台行为、电池不优化、横幅和锁屏通知、最近任务锁定）的深链组件名来自 AutoStarter / dontkillmyapp 等社区资料，**未在真机验证**；找不到或无法打开时退回应用详情页。

## Guardian

- daemon 模式的 UserService，进程名 `top.xingzhenxi.phonebox:guardian`，uid shell。每 60 秒执行 `dumpsys activity services top.xingzhenxi.phonebox/.service.KeepAliveService`，没有 ServiceRecord 就执行 `am start-foreground-service -n top.xingzhenxi.phonebox/.shizuku.GuardianTargetService --es reason 服务不在`。
- 判活选 dumpsys 而不是心跳时间戳：shell 读不到 App 私有目录；App 心跳闹钟约 9 分钟一次，3 分钟无心跳判定会频繁误拉。代价是"服务记录还在但进程被冻结"的情况看不出来。
- GuardianTargetService 导出并要求 `android.permission.DUMP`。实测：普通 App（android/testapps/guardian-probe）启动得到 SecurityException；`adb shell am start-foreground-service` 成功。
- 注意 `adb root` 会让 adbd 重启并带走 adb 方式启动的 Shizuku 服务（Guardian 一起停）。
