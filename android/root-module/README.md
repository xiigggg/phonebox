# phonebox Root 模块

KernelSU / Magisk / APatch 通用的极简模块，只有一个 `service.sh`：开机后若电话信箱 App 里打开过 Root Guardian，就以 root 拉起它的守护进程（被杀即拉起、被冻结即解冻）。

不装模块也能用：App 每次启动都会经 `su` 确认守护进程在；模块只是让“重启后、还没打开过 App 之前”也有守护。

由 `./gradlew :app:rootModuleZip` 打包，包名在打包时填入（复刻改了 applicationId 也不用改这里）。
