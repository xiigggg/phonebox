package top.xingzhenxi.phonebox.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 通话页的本地偏好：未接已查看到哪一条、"全部"里是否显示被拦截来电 */
class CallLogPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("call_log", Context.MODE_PRIVATE)

    private val _seen = MutableStateFlow(prefs.getLong(K_SEEN, 0L))
    val missedSeenTs: StateFlow<Long> = _seen.asStateFlow()

    fun markMissedSeen(ts: Long) {
        if (ts <= _seen.value) return
        prefs.edit().putLong(K_SEEN, ts).apply()
        _seen.value = ts
    }

    private val _showBlocked = MutableStateFlow(prefs.getBoolean(K_SHOW_BLOCKED, true))
    val showBlockedInAll: StateFlow<Boolean> = _showBlocked.asStateFlow()

    fun setShowBlockedInAll(v: Boolean) {
        prefs.edit().putBoolean(K_SHOW_BLOCKED, v).apply()
        _showBlocked.value = v
    }

    private companion object {
        const val K_SEEN = "missed_seen_ts"
        const val K_SHOW_BLOCKED = "show_blocked_in_all"
    }
}
