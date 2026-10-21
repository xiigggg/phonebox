package top.xingzhenxi.phonebox.diag

import android.content.Context

/** 短信是经哪条路到的：事件流实时推来 / 全量拉取补到（重连、心跳、轮询）/ 推送唤醒后拉到 */
enum class ArrivalVia(val label: String) { Stream("实时"), Poll("补拉"), Push("推送唤醒") }

data class Arrival(val nasTs: Long, val delayMs: Long, val via: ArrivalVia)

data class LatencySummary(val count: Int, val p50Ms: Long, val p90Ms: Long, val maxMs: Long, val byVia: Map<ArrivalVia, Int>)

/**
 * 短信“首达延迟”：NAS 收到短信（feed 的 ts，秒）到本机贴出通知的时间差。
 * 这是判断“第一时间收到”有没有改善的唯一客观指标；只存时间和通道，不存号码和正文。
 */
class LatencyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("latency", Context.MODE_PRIVATE)
    private val items = ArrayDeque(decode(prefs.getString(KEY, null)))

    @Synchronized
    fun record(nasTsSec: Long, postedAtMs: Long, via: ArrivalVia) {
        val a = Latency.arrival(nasTsSec, postedAtMs, via) ?: return
        items.addLast(a)
        while (items.size > MAX) items.removeFirst()
        prefs.edit().putString(KEY, encode(items)).apply()
    }

    @Synchronized fun recent(): List<Arrival> = items.toList()
    @Synchronized fun summary(): LatencySummary? = Latency.summarize(items.toList())

    private companion object {
        const val KEY = "arrivals"
        const val MAX = 50
        fun encode(list: Collection<Arrival>) = list.joinToString("\n") { "${it.nasTs}|${it.delayMs}|${it.via.name}" }
        fun decode(s: String?): List<Arrival> = s.orEmpty().lineSequence().mapNotNull { line ->
            val p = line.split("|")
            if (p.size != 3) return@mapNotNull null
            val via = runCatching { ArrivalVia.valueOf(p[2]) }.getOrNull() ?: return@mapNotNull null
            Arrival(p[0].toLongOrNull() ?: return@mapNotNull null, p[1].toLongOrNull() ?: return@mapNotNull null, via)
        }.toList()
    }
}

object Latency {
    /** 超过一天的多半是离线很久后补到的旧短信或时钟错乱，统计进去只会把分布搅乱 */
    private const val MAX_DELAY_MS = 24 * 3600_000L
    /** NAS 的 ts 只精确到秒，手机时钟也可能略快：-2 秒以内按 0 算 */
    private const val CLOCK_SLACK_MS = 2_000L

    fun arrival(nasTsSec: Long, postedAtMs: Long, via: ArrivalVia): Arrival? {
        if (nasTsSec <= 0) return null
        val d = postedAtMs - nasTsSec * 1000
        if (d < -CLOCK_SLACK_MS || d > MAX_DELAY_MS) return null
        return Arrival(nasTsSec, d.coerceAtLeast(0), via)
    }

    fun summarize(list: List<Arrival>): LatencySummary? {
        if (list.isEmpty()) return null
        val sorted = list.map { it.delayMs }.sorted()
        fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
        return LatencySummary(list.size, pct(0.5), pct(0.9), sorted.last(), list.groupingBy { it.via }.eachCount())
    }
}
