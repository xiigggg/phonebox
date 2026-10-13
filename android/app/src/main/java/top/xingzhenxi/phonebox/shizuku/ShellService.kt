package top.xingzhenxi.phonebox.shizuku

import android.os.Binder
import android.os.ParcelFileDescriptor
import top.xingzhenxi.phonebox.BuildConfig
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * 运行在 Shizuku 拉起的 shell（uid 2000）进程里。包名写死为本应用，
 * 即使有人拿到这个 Binder，也只能对本包执行固定模板。
 */
class ShellService : IShellService.Stub() {
    override fun destroy() = exitProcess(0)

    override fun exec(template: String?, uid: Int): String {
        val t = ShellTemplate.entries.firstOrNull { it.name == template } ?: return "-2\t未知模板"
        // uid 只认 Binder 调用方自己：Binder 是 App 经 Shizuku 直连拿到的，调用方 uid 就是本应用的 uid
        if (!ShellCommand.isCaller(uid, Binder.getCallingUid())) return "-2\t调用方 uid 不符"
        val argv = ShellCommand.build(t, BuildConfig.APPLICATION_ID, uid, BuildConfig.APPLICATION_ID)
            ?: return "-2\t参数校验失败"
        return run(argv)
    }

    override fun installApk(apk: ParcelFileDescriptor?, size: Long): String {
        if (apk == null) return "-2\t缺少文件"
        if (!ShellCommand.isAppUid(Binder.getCallingUid())) return "-2\t调用方不是应用"
        val argv = ShellCommand.installUpdate(size) ?: return "-2\t参数校验失败"
        return try {
            val p = ProcessBuilder(argv).redirectErrorStream(true).start()
            ParcelFileDescriptor.AutoCloseInputStream(apk).use { input -> p.outputStream.use { input.copyTo(it) } }
            val out = p.inputStream.bufferedReader().use { it.readText() }
            if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); "-1\t超时" }
            else "${p.exitValue()}\t${out.trim().take(500)}"
        } catch (e: Exception) {
            "-1\t${e.javaClass.simpleName}"
        }
    }

    companion object {
        /** 只给本文件和 Guardian 用：argv 都是代码里写死的 */
        internal fun run(argv: List<String>, timeoutSec: Long = 15): String = try {
            val p = ProcessBuilder(argv).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { it.readText() }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); "-1\t超时" }
            else "${p.exitValue()}\t${out.trim().take(500)}"
        } catch (e: Exception) {
            "-1\t${e.javaClass.simpleName}"
        }
    }
}

data class ExecResult(val code: Int, val output: String) {
    val ok get() = code == 0

    companion object {
        fun parse(raw: String?): ExecResult {
            val p = raw.orEmpty().split('\t', limit = 2)
            return ExecResult(p[0].toIntOrNull() ?: -1, p.getOrElse(1) { "" })
        }
    }
}
