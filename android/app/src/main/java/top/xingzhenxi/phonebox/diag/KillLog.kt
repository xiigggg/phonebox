package top.xingzhenxi.phonebox.diag

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import java.util.concurrent.TimeUnit

data class KillLogEntry(val time: String, val type: String, val reason: String)

/**
 * 从 logcat 文本里只挑出与本包有关的被杀/冻结行，并且只留时间、类型、原因——
 * 其他 App 的包名、pid 一律不进结果。
 */
object KillLogFilter {
    // threadtime 格式：MM-dd HH:mm:ss.SSS  uid  pid  tid L tag: message
    private val LINE = Regex("""^(\d\d-\d\d \d\d:\d\d:\d\d)\.\d+\s+(?:\S+\s+)?\d+\s+\d+\s+[VDIWEF]\s+([^:]+?)\s*:\s?(.*)$""")
    private val OPPO_TAG = Regex("(?i)oplus|oppo|coloros|hans|athena|olc")
    private val PKG_LIKE = Regex("""\b[A-Za-z][\w]*(?:\.[\w]+){2,}(?::[\w]+)?\b""")

    fun filter(lines: Sequence<String>, pkg: String, max: Int = 50): List<KillLogEntry> {
        val out = ArrayDeque<KillLogEntry>()
        for (line in lines) {
            val e = parse(line, pkg) ?: continue
            if (out.size >= max) out.removeFirst()
            out.addLast(e)
        }
        return out.reversed()
    }

    fun parse(line: String, pkg: String): KillLogEntry? {
        val m = LINE.find(line) ?: return null
        val (time, tag, body) = m.destructured
        return when (tag) {
            "am_kill" -> fields(body)?.takeIf { isSelf(it.getOrNull(2), pkg) }
                ?.let { KillLogEntry(time, "am_kill", clean(it.getOrElse(4) { "" }, pkg)) }
            "am_proc_died" -> fields(body)?.takeIf { isSelf(it.getOrNull(2), pkg) }
                ?.let { KillLogEntry(time, "am_proc_died", "进程退出 adj=${it.getOrElse(3) { "?" }}") }
            "am_freeze" -> fields(body)?.takeIf { f -> f.any { isSelf(it, pkg) } }
                ?.let { KillLogEntry(time, "am_freeze", "进程被冻结") }
            else -> if (OPPO_TAG.containsMatchIn(tag) && body.contains(pkg)) {
                KillLogEntry(time, "OPPO/${tag.take(24)}", clean(body, pkg))
            } else null
        }
    }

    private fun fields(body: String): List<String>? =
        body.trim().takeIf { it.startsWith("[") && it.endsWith("]") }?.removeSurrounding("[", "]")?.split(',')

    private fun isSelf(proc: String?, pkg: String) = proc != null && (proc == pkg || proc.startsWith("$pkg:"))

    /** 别家包名换成 *，长数字只留后 4 位，截短 */
    internal fun clean(s: String, pkg: String): String =
        Redact.text(PKG_LIKE.replace(s) { if (isSelf(it.value, pkg)) "本应用" else "*" }).trim().take(80)
}

/** 有 READ_LOGS 时读本进程可见的 logcat；Android 13 起系统可能弹“允许访问设备日志”确认框 */
class LogcatKillLogSource(private val context: Context) {
    fun available() = context.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED

    fun read(max: Int = 50): List<KillLogEntry> {
        if (!available()) return emptyList()
        return runCatching {
            val p = ProcessBuilder("logcat", "-d", "-b", "events,system", "-v", "threadtime").redirectErrorStream(true).start()
            val result = p.inputStream.bufferedReader().useLines { KillLogFilter.filter(it, context.packageName, max) }
            p.waitFor(5, TimeUnit.SECONDS)
            result
        }.getOrDefault(emptyList())
    }
}
