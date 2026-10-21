# Root 增强（KernelSU / Magisk / APatch）

有 root 时，"设置 → 后台保活体检"顶部会多出 Root 区。它和 Shizuku 那套做的是同一类事，但不用每次开机重新激活 Shizuku。

## 能做什么

| 功能 | 说明 |
|---|---|
| 用 Root 一键修复 | 经 `su -c` 执行和 Shizuku 同一组固定命令模板：电池优化白名单、后台运行、后台数据白名单、全屏通知、悬浮窗、三个默认应用、读取日志。参数只有本包名和本应用 uid，每个参数都单引号转义 |
| Root Guardian | 用 `su` 以 `app_process` 拉起一个 root 守护进程，每 5 秒检查一次本应用：进程不在（连续两次）就拉起，被冻结（cgroup v2 `cgroup.freeze=1`，或被 SIGSTOP）就解冻；每 60 秒确认常驻服务还在 |
| 开机模块 | `phonebox-root-<版本>.zip`，KernelSU / Magisk / APatch 通用。开机后若 App 里开过 Root Guardian，就拉起守护进程。不装也能用：App 每次启动都会经 `su` 确认守护进程在 |

开关和状态都在 App 私有目录 `files/root-guardian/` 里：`enabled` 文件存在 = 开（只有 App 自己和 root 能写），`status` 由守护进程每 5 秒写一次。删掉 `enabled`，守护进程 5 秒内退出。

## 安装

1. 在 KernelSU / Magisk / APatch 管理器里给"电话信箱"授予超级用户权限（KernelSU：超级用户 → 电话信箱 → 打开）。
2. 打开 App：设置 → 后台保活体检 → Root 区，确认显示 root 来源；点"用 Root 一键修复"，再打开 Root Guardian。
3. 可选：在管理器里刷入 Release 里的 `phonebox-root-<版本>.zip`，重启后自动拉起守护进程。
4. Root Guardian 和 Shizuku 版 Guardian 二选一即可，同时开也不冲突（App 侧收到拉起请求时会先确认服务是否真不在）。

## KernelSU LKM late-load 模式

`ksud debug info` 显示 `runtime_mode: late-load` 时，冷启动后 KernelSU 要等你在管理器里手动加载才生效，此前没有 root，模块的开机脚本也不会跑。这种情况下：

- 加载 KernelSU 后，模块的 `service.sh` 会跑并拉起守护进程（`ksud soft-reboot` 同理）；
- 或者打开一次 App，它会自己经 `su` 拉起。

## 实测（Find N2，ColorOS 16 / Android 16，KernelSU LKM）

- `am force-stop` 后 7 秒内被 Root Guardian 拉起（`kill -9` 的情况系统自己 2 秒就粘性重启了）；
- 手动把 App 的 cgroup 冻结（写 `cgroup.freeze=1`）后 2 秒解冻；
- 守护进程写的 `status` 带上了 App 数据目录的 SELinux 类别，App 能读到。

## 没做的

- **Vector / LSPosed 模块**：原计划用 Xposed 在 system_server 里让本应用免冻结、免查杀。实测数据（诊断页的被杀记录、系统 exit-info）显示常驻服务在 ColorOS 16 上基本没被冻结或查杀过，真正的掉线原因是网络（见 README 常见问题里的"回环 NAT"），所以没做。如果你的手机上诊断页显示频繁被冻结，可以再考虑。
- 不改 `oom_score_adj`：系统每次调整优先级都会覆盖，持续写只会白耗电。
