package top.xingzhenxi.phonebox.diag

import android.content.Context

/** [UptimeStats] 的持久化包装：进程被杀后在线时长不能清零，否则诊断页看不出问题 */
class UptimeStore(context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val prefs = context.applicationContext.getSharedPreferences("uptime", Context.MODE_PRIVATE)
    private val stats = UptimeStats.decode(prefs.getString(KEY, null))

    @Synchronized
    fun record(online: Boolean, reason: String, ts: Long = clock()) {
        if (stats.isOnline() == online && stats.transitions.isNotEmpty()) return
        stats.record(ts, online, reason)
        prefs.edit().putString(KEY, stats.encode()).apply()
    }

    @Synchronized fun isOnline() = stats.isOnline()
    @Synchronized fun lastTransitionTs(): Long = stats.transitions.lastOrNull()?.ts ?: 0
    @Synchronized fun onlineRatio(): Double? = stats.onlineRatio(clock())
    @Synchronized fun lastDrop(): Drop? = stats.lastDrop()

    private companion object { const val KEY = "transitions" }
}
