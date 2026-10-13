package top.xingzhenxi.phonebox.service

import android.content.Context

/**
 * 服务心跳：每次状态变化和每次闹钟唤醒都写一次。内存里一份给同进程快速判断，
 * SharedPreferences 一份给看门狗——看门狗运行时进程可能是新拉起的，内存值已丢。
 */
class HeartbeatStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("keepalive", Context.MODE_PRIVATE)
    @Volatile private var memory: Long = 0

    fun beat(now: Long = System.currentTimeMillis()) {
        memory = now
        prefs.edit().putLong(KEY, now).apply()
    }

    fun last(): Long = maxOf(memory, prefs.getLong(KEY, 0))

    private companion object { const val KEY = "last_beat" }
}

object Watchdog {
    /** 闹钟约 9 分钟一次，允许错过一次再加点余量 */
    const val STALE_AFTER_MS = 20 * 60_000L

    /** 从没心跳过也算过期；时钟回拨（last 在未来）同样视为过期，宁可多拉一次 */
    fun isStale(now: Long, lastBeat: Long, staleAfterMs: Long = STALE_AFTER_MS): Boolean =
        lastBeat <= 0 || lastBeat > now || now - lastBeat > staleAfterMs
}
