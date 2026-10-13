package top.xingzhenxi.phonebox.notify

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 有上限的已读集合，按加入顺序淘汰最旧的；纯逻辑，便于测试 */
class BoundedKeySet(initial: List<String> = emptyList(), private val cap: Int = DEFAULT_CAP) {
    private val keys = LinkedHashSet<String>()

    init { initial.forEach(::add) }

    /** 返回是否有变化 */
    fun add(key: String): Boolean {
        if (key in keys) return false
        keys.add(key)
        while (keys.size > cap) keys.remove(keys.first())
        return true
    }

    operator fun contains(key: String) = key in keys
    fun toList(): List<String> = keys.toList()
    val size get() = keys.size

    companion object { const val DEFAULT_CAP = 2000 }
}

/**
 * 已读短信键与通知偏好，存 SharedPreferences（按行拼接保序；StringSet 不保序没法淘汰）。
 * [baselineTs] 首次创建时记下当时时间：在这之前的历史短信一律视为已读，免得装好就满屏未读。
 */
class ReadStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sms_read", Context.MODE_PRIVATE)
    private val set = BoundedKeySet(prefs.getString(K_KEYS, "").orEmpty().split('\n').filter { it.isNotEmpty() })
    private val _read = MutableStateFlow(set.toList().toSet())
    val read: StateFlow<Set<String>> = _read.asStateFlow()

    val baselineTs: Long = prefs.getLong(K_BASE, 0L).takeIf { it > 0 }
        ?: (System.currentTimeMillis() / 1000).also { prefs.edit().putLong(K_BASE, it).apply() }

    fun isUnread(key: String, ts: Long): Boolean = ts >= baselineTs && key !in _read.value

    @Synchronized
    fun markRead(keys: Collection<String>) {
        var changed = false
        keys.forEach { if (set.add(it)) changed = true }
        if (!changed) return
        // commit：进程被杀前点「已读」也要落盘
        prefs.edit().putString(K_KEYS, set.toList().joinToString("\n")).commit()
        _read.value = set.toList().toSet()
    }

    var spamMode: SpamNotifyMode
        get() = prefs.getString(K_SPAM, null)?.let { runCatching { SpamNotifyMode.valueOf(it) }.getOrNull() } ?: SpamNotifyMode.Silent
        set(v) { prefs.edit().putString(K_SPAM, v.name).apply(); _spam.value = v }

    private val _spam = MutableStateFlow(spamMode)
    val spamModeFlow: StateFlow<SpamNotifyMode> = _spam.asStateFlow()

    /** 信息页"全部"是否也列出骚扰拦截短信；默认不列 */
    var showSpamInAll: Boolean
        get() = prefs.getBoolean(K_SPAM_IN_ALL, false)
        set(v) { prefs.edit().putBoolean(K_SPAM_IN_ALL, v).apply(); _spamInAll.value = v }

    private val _spamInAll = MutableStateFlow(showSpamInAll)
    val showSpamInAllFlow: StateFlow<Boolean> = _spamInAll.asStateFlow()

    private companion object {
        const val K_KEYS = "keys"
        const val K_BASE = "baseline_ts"
        const val K_SPAM = "spam_mode"
        const val K_SPAM_IN_ALL = "spam_in_all"
    }
}
