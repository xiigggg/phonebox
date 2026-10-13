package top.xingzhenxi.phonebox.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import top.xingzhenxi.phonebox.BuildConfig
import top.xingzhenxi.phonebox.appGraph
import top.xingzhenxi.phonebox.diag.DiagType

data class GuardianStatus(
    val enabled: Boolean = false,
    val connected: Boolean = false,
    val running: Boolean = false,
    val lastCheckAt: Long = 0,
    val lastLaunchAt: Long = 0,
    val lastReason: String = "",
)

/**
 * App 侧开关与绑定。开关默认关；打开后以 daemon 方式绑定，App 进程死了守护进程照跑。
 * App 重启后若开关仍开，Shizuku binder 一到就重新绑定（daemon 已在则直接连上同一个）。
 */
class GuardianController private constructor(private val app: Context) {
    private val prefs = app.getSharedPreferences("guardian", Context.MODE_PRIVATE)
    private val _status = MutableStateFlow(GuardianStatus(enabled = prefs.getBoolean(KEY_ENABLED, false)))
    val status: StateFlow<GuardianStatus> = _status.asStateFlow()
    @Volatile private var service: IGuardianService? = null

    private val args = Shizuku.UserServiceArgs(ComponentName(app.packageName, GuardianService::class.java.name))
        .processNameSuffix("guardian")
        .tag("guardian")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)
        .daemon(true)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = binder?.takeIf { it.pingBinder() }?.let { IGuardianService.Stub.asInterface(it) } ?: return
            service = s
            if (prefs.getBoolean(KEY_ENABLED, false)) runCatching { s.start() }
            refresh()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            refresh()
        }
    }

    private val binderListener = Shizuku.OnBinderReceivedListener {
        if (prefs.getBoolean(KEY_ENABLED, false) && ShizukuStatus.current() == ShizukuState.Ready) bind()
    }

    fun init() = Shizuku.addBinderReceivedListenerSticky(binderListener)

    fun setEnabled(on: Boolean): Boolean {
        if (on && ShizukuStatus.current() != ShizukuState.Ready) return false
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        if (on) bind() else {
            runCatching { service?.stop() }
            // remove=true 让 Shizuku 杀掉 daemon 进程，关掉开关后不留残余
            runCatching { Shizuku.unbindUserService(args, conn, true) }
            service = null
        }
        app.appGraph.diagLog.log(DiagType.Guardian, if (on) "打开 Guardian" else "关闭 Guardian")
        refresh()
        return true
    }

    private fun bind() = runCatching { Shizuku.bindUserService(args, conn) }

    fun refresh() {
        val b = runCatching { service?.status() }.getOrNull()
        _status.value = GuardianStatus(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            connected = b != null,
            running = b?.getBoolean("running") == true,
            lastCheckAt = b?.getLong("lastCheckAt") ?: 0,
            // 守护进程内存里的记录在它重启后会丢，所以取它与本地落盘记录里较新的那条
            lastLaunchAt = maxOf(b?.getLong("lastLaunchAt") ?: 0, prefs.getLong(KEY_LAUNCH_AT, 0)),
            lastReason = if ((b?.getLong("lastLaunchAt") ?: 0) > prefs.getLong(KEY_LAUNCH_AT, 0)) b?.getString("lastReason").orEmpty()
            else prefs.getString(KEY_LAUNCH_REASON, "").orEmpty(),
        )
    }

    fun recordLaunch(reason: String, now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAUNCH_AT, now).putString(KEY_LAUNCH_REASON, reason).apply()
        refresh()
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LAUNCH_AT = "last_launch_at"
        private const val KEY_LAUNCH_REASON = "last_launch_reason"

        @Volatile private var instance: GuardianController? = null
        fun get(context: Context): GuardianController = instance ?: synchronized(this) {
            instance ?: GuardianController(context.applicationContext).also { instance = it }
        }
    }
}
