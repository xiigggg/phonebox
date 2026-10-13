package top.xingzhenxi.phonebox.shizuku

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay

/** 失败发生在哪一步 */
enum class FailStep(val label: String) { Bind("绑定 UserService"), Timeout("绑定超时"), Exec("执行模板") }

/** 出错那一刻的 Shizuku 状态；取不到的字段为 null，不猜 */
data class ShizukuSnapshot(val version: Int?, val uid: Int?, val ping: Boolean, val granted: Boolean?) {
    companion object {
        fun capture() = ShizukuSnapshot(
            version = runCatching { rikka.shizuku.Shizuku.getVersion() }.getOrNull(),
            uid = runCatching { rikka.shizuku.Shizuku.getUid() }.getOrNull(),
            ping = runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false),
            granted = runCatching {
                rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrNull(),
        )
    }
}

/** 绑定两次都失败时抛出，带上每次的失败信息供界面和日志展示 */
class ShizukuBindException(val attempts: List<String>, cause: Throwable) :
    Exception(attempts.lastOrNull() ?: "绑定失败", cause)

object ShizukuFailure {
    fun uidLabel(uid: Int?): String = when (uid) {
        null -> "未知"
        0 -> "0（root）"
        2000 -> "2000（adb/shell）"
        else -> uid.toString()
    }

    fun stepOf(e: Throwable): FailStep = if (e is TimeoutCancellationException) FailStep.Timeout else FailStep.Bind

    /** 详情区逐行显示的字段；消息里只有系统异常文本，不含号码短信等 */
    fun fields(step: FailStep, error: Throwable?, snap: ShizukuSnapshot, template: String? = null): List<Pair<String, String>> = buildList {
        add("步骤" to (if (template != null) "${step.label} $template" else step.label))
        if (error != null) add("异常" to "${error.javaClass.simpleName}: ${error.message.orEmpty().take(200)}")
        add("Shizuku 版本" to (snap.version?.toString() ?: "未知"))
        add("服务 uid" to uidLabel(snap.uid))
        add("pingBinder" to snap.ping.toString())
        add("已授权" to (snap.granted?.toString() ?: "未知"))
    }

    /** 诊断日志一行：key=value 用分号隔开，便于 grep */
    fun logLine(step: FailStep, error: Throwable?, snap: ShizukuSnapshot, template: String? = null, attempt: Int? = null): String =
        (listOfNotNull(attempt?.let { "第${it}次" }) + fields(step, error, snap, template).map { "${it.first}=${it.second}" })
            .joinToString("; ")

    /** 单条命令失败：退出码 + 输出前 200 字（ShellService 把 stderr 并进了输出） */
    fun execLine(template: String, code: Int, output: String): String = "$template 退出码=$code 输出=${output.trim().take(200)}"

    /**
     * 失败后等 [delayMs] 再试一次。Shizuku 刚激活时 UserService 进程可能还没起来，
     * 第一次绑定失败多半是时机问题，再等一下通常就好。
     */
    suspend fun <T> retryOnce(delayMs: Long, onFailure: (attempt: Int, Throwable) -> Unit, block: suspend (attempt: Int) -> T): T {
        val first = try { return block(1) } catch (e: Throwable) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            onFailure(1, e); e
        }
        delay(delayMs)
        return try { block(2) } catch (e: Throwable) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            onFailure(2, e)
            e.addSuppressed(first)
            throw e
        }
    }
}
