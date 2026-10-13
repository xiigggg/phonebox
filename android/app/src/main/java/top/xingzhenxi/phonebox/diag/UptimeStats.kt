package top.xingzhenxi.phonebox.diag

data class Transition(val ts: Long, val online: Boolean, val reason: String)

data class Drop(val ts: Long, val reason: String)

/**
 * 注册在线/离线的状态转换序列（只记变化点），算近 24 小时在线比例。
 * 纯 Kotlin，持久化交给调用方（[encode]/[decode]）。
 */
class UptimeStats(private val windowMs: Long = 24 * 3600_000L) {
    private val items = ArrayList<Transition>()

    val transitions: List<Transition> get() = items.toList()

    /** 状态没变就不记，避免心跳刷屏 */
    fun record(ts: Long, online: Boolean, reason: String) {
        if (items.lastOrNull()?.online == online) return
        items += Transition(ts, online, Redact.text(reason))
        prune(ts)
    }

    fun isOnline(): Boolean = items.lastOrNull()?.online == true

    /** 观测区间为 [max(窗口起点, 首个记录), now]；没有任何记录时返回 null */
    fun onlineRatio(now: Long): Double? {
        if (items.isEmpty()) return null
        val start = now - windowMs
        val from = maxOf(start, items.first().ts)
        if (now <= from) return if (isOnline()) 1.0 else 0.0
        var online = 0L
        for (i in items.indices) {
            val t = items[i]
            if (!t.online) continue
            val segStart = maxOf(t.ts, from)
            val segEnd = minOf(items.getOrNull(i + 1)?.ts ?: now, now)
            if (segEnd > segStart) online += segEnd - segStart
        }
        return online.toDouble() / (now - from)
    }

    fun lastDrop(): Drop? {
        for (i in items.indices.reversed()) {
            val t = items[i]
            if (!t.online && i > 0 && items[i - 1].online) return Drop(t.ts, t.reason)
        }
        return null
    }

    /** 窗口外的记录只保留最后一条，用来确定窗口起点时的状态 */
    private fun prune(now: Long) {
        val start = now - windowMs
        val firstInside = items.indexOfFirst { it.ts >= start }
        val keepFrom = if (firstInside == -1) items.size - 1 else firstInside - 1
        if (keepFrom > 0) items.subList(0, keepFrom).clear()
    }

    fun encode(): String = items.joinToString("\n") { "${it.ts}|${if (it.online) 1 else 0}|${it.reason.replace('\n', ' ')}" }

    companion object {
        fun decode(s: String?, windowMs: Long = 24 * 3600_000L): UptimeStats {
            val u = UptimeStats(windowMs)
            s?.lineSequence()?.forEach { line ->
                val p = line.split('|', limit = 3)
                val ts = p.getOrNull(0)?.toLongOrNull() ?: return@forEach
                u.items += Transition(ts, p.getOrNull(1) == "1", p.getOrNull(2).orEmpty())
            }
            return u
        }
    }
}
