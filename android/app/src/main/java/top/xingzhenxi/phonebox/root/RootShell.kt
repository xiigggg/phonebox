package top.xingzhenxi.phonebox.root

import top.xingzhenxi.phonebox.shizuku.ExecResult
import top.xingzhenxi.phonebox.shizuku.ShellCommand
import top.xingzhenxi.phonebox.shizuku.ShellTemplate
import java.util.concurrent.TimeUnit

/** su 来自哪家：只用于界面显示，能否使用一律看 `id` 是否为 uid=0 */
data class RootInfo(val available: Boolean, val provider: String)

/**
 * 经 `su -c` 以 root 执行。和 Shizuku 那套共用 [ShellTemplate]：只有写死的模板，参数只有本包名和本 uid，
 * 每个参数单引号转义后再交给 su 的 shell，避免任何拼接注入。
 * KernelSU / Magisk / APatch 第一次调用会弹授权框；用户拒绝或超时都按“不可用”处理。
 */
object RootShell {
    @Volatile private var cached: RootInfo? = null

    /** 阻塞调用，必须在后台线程；[force] 为 true 时不用缓存（用户刚在管理器里授权后重查） */
    fun info(force: Boolean = false): RootInfo {
        if (!force) cached?.let { return it }
        val id = runRaw("id", 20)
        val ok = id.ok && id.output.contains("uid=0")
        val provider = if (!ok) "" else providerOf(runRaw("su -v 2>/dev/null || true", 5).output)
        return RootInfo(ok, provider).also { cached = it }
    }

    fun providerOf(version: String): String = when {
        version.contains("KernelSU", ignoreCase = true) -> "KernelSU"
        version.contains("MAGISK", ignoreCase = true) -> "Magisk"
        version.contains("APatch", ignoreCase = true) -> "APatch"
        version.isBlank() -> "未知"
        else -> version.trim().take(24)
    }

    fun execAll(templates: List<ShellTemplate>, pkg: String, uid: Int): List<Pair<ShellTemplate, ExecResult>> =
        templates.map { t ->
            val argv = ShellCommand.build(t, pkg, uid, pkg)
            t to (argv?.let { run(it) } ?: ExecResult(-2, "参数校验失败"))
        }

    fun run(argv: List<String>, timeoutSec: Long = 15): ExecResult = runRaw(argv.joinToString(" ") { quote(it) }, timeoutSec)

    /** 单引号包住，内部的单引号写成 '\'' —— POSIX shell 下唯一不会被解释的写法 */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 只给本包内写死的命令串用（启动 Root Guardian 需要后台运行和重定向，不能逐项转义） */
    internal fun runRaw(cmd: String, timeoutSec: Long): ExecResult = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            ExecResult(-1, "超时")
        } else ExecResult(p.exitValue(), out.trim().take(500))
    } catch (e: Exception) {
        // 没有 su 时 ProcessBuilder 抛 IOException
        ExecResult(-1, e.javaClass.simpleName)
    }
}
