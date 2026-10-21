package top.xingzhenxi.phonebox.root

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.xingzhenxi.phonebox.appGraph
import top.xingzhenxi.phonebox.diag.DiagType
import java.io.File

data class RootGuardianStatus(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val state: GuardianState? = null,
)

/**
 * App 侧开关：开 = 在私有目录建 enabled 文件并经 su 拉起守护进程；关 = 删掉文件，守护进程 5 秒内自行退出。
 * App 每次启动（含开机、被拉起）都会 [ensure] 一次，守护进程已在时 tryLock 失败、新进程立即退出。
 */
class RootGuardianController private constructor(private val app: Context) {
    private val dir = File(app.filesDir, "root-guardian")
    private val flag = File(dir, RootGuardianPaths.ENABLED)
    private val _status = MutableStateFlow(read())
    val status: StateFlow<RootGuardianStatus> = _status.asStateFlow()

    /** 阻塞（会调 su），在后台线程调用；返回 false 表示没有 root */
    fun setEnabled(on: Boolean): Boolean {
        val log = app.appGraph.diagLog
        if (on) {
            if (!RootShell.info(force = true).available) return false
            dir.mkdirs()
            flag.writeText("1")
            val r = start()
            log.log(DiagType.Root, if (r) "打开 Root Guardian" else "打开 Root Guardian，但启动命令失败")
        } else {
            flag.delete()
            log.log(DiagType.Root, "关闭 Root Guardian")
        }
        refresh()
        return true
    }

    /** 开关开着而守护进程不在（重启后、被杀后）就再拉一次 */
    fun ensure() {
        if (!flag.exists() || read().running) return
        if (!RootShell.info().available) return
        if (start()) app.appGraph.diagLog.log(DiagType.Root, "重新拉起 Root Guardian")
        refresh()
    }

    private fun start(): Boolean {
        val cmd = RootGuardianLaunch.command(app.applicationInfo.sourceDir) ?: return false
        return RootShell.runRaw(cmd, 15).ok
    }

    fun refresh() {
        _status.value = read()
    }

    private fun read(): RootGuardianStatus {
        val st = runCatching { GuardianState.decode(File(dir, RootGuardianPaths.STATUS).readText()) }.getOrNull()
        val alive = st != null && st.running && System.currentTimeMillis() - st.lastCheckAt < STALE_MS
        return RootGuardianStatus(enabled = flag.exists(), running = alive, state = st)
    }

    companion object {
        /** 守护进程 5 秒写一次状态；20 秒没更新就当它不在了 */
        private const val STALE_MS = 20_000L

        @Volatile private var instance: RootGuardianController? = null
        fun get(context: Context): RootGuardianController = instance ?: synchronized(this) {
            instance ?: RootGuardianController(context.applicationContext).also { instance = it }
        }
    }
}

object RootGuardianLaunch {
    private val APK = Regex("^/data/app/[A-Za-z0-9_.~=+/-]+/base\\.apk$")
    const val MAIN = "top.xingzhenxi.phonebox.root.RootGuardianMain"

    /**
     * 命令串里唯一的变量是 APK 路径，只认 /data/app/…/base.apk 这种系统安装路径。
     * setsid + 重定向让它脱离 su 会话：su 返回后守护进程继续跑。
     */
    fun command(apkPath: String): String? {
        if (!APK.matches(apkPath) || ".." in apkPath) return null
        return "CLASSPATH=$apkPath setsid app_process /system/bin $MAIN </dev/null >/dev/null 2>&1 &"
    }
}
