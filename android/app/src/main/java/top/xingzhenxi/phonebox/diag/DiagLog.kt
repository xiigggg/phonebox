package top.xingzhenxi.phonebox.diag

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** 只有这些类型能进诊断日志；没有“任意文本”入口，从结构上挡住短信正文 */
enum class DiagType { Registration, Network, Backoff, Service, Watchdog, FgsDenied, Alarm, Feed, Call, Shizuku, Guardian, Update, Contacts, Root, Push }

data class DiagEntry(val ts: Long, val type: DiagType, val reason: String)

/**
 * 内存环形缓冲 + 私有目录落盘（超过 [maxFileBytes] 滚动成 .1，只留一代）。
 * reason 一律经 [Redact] 处理，号码最多留后 4 位。
 */
class DiagLog(
    private val dir: File?,
    private val capacity: Int = 500,
    private val maxFileBytes: Long = 256 * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: Executor = Executors.newSingleThreadExecutor { Thread(it, "PB.Diag") },
    private val logcat: (String) -> Unit = { Log.i(TAG, it) },
) {
    private val buffer = ArrayDeque<DiagEntry>(capacity)
    private val _entries = MutableStateFlow<List<DiagEntry>>(emptyList())
    val entries: StateFlow<List<DiagEntry>> = _entries.asStateFlow()

    private val file get() = dir?.let { File(it, "diag.log") }

    init {
        // 进程被杀后重启，先把上次的尾巴读回来，诊断页才能看到“被杀前发生了什么”
        file?.takeIf { it.exists() }?.let { f ->
            runCatching { f.readLines().takeLast(capacity).mapNotNull(::parse) }.getOrNull()?.let { old ->
                synchronized(buffer) { buffer.addAll(old); _entries.value = buffer.toList() }
            }
        }
    }

    fun log(type: DiagType, reason: String) {
        val e = DiagEntry(clock(), type, Redact.text(reason).replace('\n', ' ').take(200))
        logcat("${e.type} ${e.reason}")
        synchronized(buffer) {
            if (buffer.size >= capacity) buffer.removeFirst()
            buffer.addLast(e)
            _entries.value = buffer.toList()
        }
        val f = file ?: return
        io.execute {
            runCatching {
                f.parentFile?.mkdirs()
                if (f.length() > maxFileBytes) f.renameTo(File(f.parentFile, "diag.log.1"))
                f.appendText(format(e) + "\n")
            }
        }
    }

    fun recent(n: Int): List<DiagEntry> = _entries.value.takeLast(n).reversed()

    companion object {
        const val TAG = "PB.Diag"

        internal fun format(e: DiagEntry) = "${e.ts}\t${e.type.name}\t${e.reason}"

        internal fun parse(line: String): DiagEntry? {
            val p = line.split('\t', limit = 3)
            if (p.size < 3) return null
            val ts = p[0].toLongOrNull() ?: return null
            val type = runCatching { DiagType.valueOf(p[1]) }.getOrNull() ?: return null
            return DiagEntry(ts, type, p[2])
        }
    }
}
