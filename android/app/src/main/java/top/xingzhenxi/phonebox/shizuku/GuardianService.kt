package top.xingzhenxi.phonebox.shizuku

import android.os.Bundle
import top.xingzhenxi.phonebox.BuildConfig
import kotlin.system.exitProcess

/**
 * Guardian 守护进程：Shizuku daemon 模式的 UserService，shell 身份，App 被杀后仍在。
 * 只做一件事：每 60 秒看一眼常驻服务，不在就拉起。
 *
 * 判活用 `dumpsys activity services`，不用心跳时间戳：shell 读不到 App 私有目录，
 * 而 App 的心跳闹钟约 9 分钟一次，靠回报心跳做 3 分钟判定会频繁误拉；
 * dumpsys 直接反映 AMS 里服务记录是否存在，进程被杀、服务被停都能看到。
 * TODO(以后)：可选的 /api/events 轻量连接（plan 里 Guardian 第 2 项），本阶段不做。
 */
class GuardianService : IGuardianService.Stub() {
    @Volatile private var running = false
    @Volatile private var lastCheckAt = 0L
    @Volatile private var lastLaunchAt = 0L
    @Volatile private var lastReason = ""
    private var thread: Thread? = null

    override fun destroy() = exitProcess(0)

    @Synchronized override fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "PB.Guardian").apply { isDaemon = true; start() }
    }

    @Synchronized override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    override fun status(): Bundle = Bundle().apply {
        putBoolean("running", running)
        putLong("lastCheckAt", lastCheckAt)
        putLong("lastLaunchAt", lastLaunchAt)
        putString("lastReason", lastReason)
    }

    private fun loop() {
        while (running) {
            lastCheckAt = System.currentTimeMillis()
            if (missingTwice()) {
                val r = ExecResult.parse(ShellService.run(GuardianPolicy.launchArgv(PKG, "服务不在")))
                lastLaunchAt = System.currentTimeMillis()
                lastReason = if (r.ok) "服务不在，已拉起" else "服务不在，拉起失败(${r.code})"
            }
            try { Thread.sleep(GuardianPolicy.INTERVAL_MS) } catch (_: InterruptedException) { return }
        }
    }

    /**
     * 连续两次（间隔 10 秒）完整的 dumpsys 都看不到服务才算不在。ColorOS 上 dumpsys 偶尔返回残缺输出，
     * 曾在一天里误判 239 次，每次都把 App 的退避清零、形成重连风暴。
     */
    private fun missingTwice(): Boolean {
        repeat(2) { i ->
            val out = ExecResult.parse(ShellService.run(GuardianPolicy.dumpsysArgv(PKG)))
            if (!out.ok || !GuardianPolicy.isCompleteDump(out.output) || GuardianPolicy.isServiceRunning(out.output, PKG)) return false
            if (i == 0) try { Thread.sleep(10_000) } catch (_: InterruptedException) { return false }
        }
        return true
    }

    private companion object { const val PKG = BuildConfig.APPLICATION_ID }
}

object GuardianPolicy {
    const val INTERVAL_MS = 60_000L
    const val TARGET = ".shizuku.GuardianTargetService"
    const val KEEPALIVE = ".service.KeepAliveService"
    const val EXTRA_REASON = "reason"

    fun dumpsysArgv(pkg: String) = listOf("dumpsys", "activity", "services", "$pkg/$KEEPALIVE")

    fun launchArgv(pkg: String, reason: String) =
        listOf("am", "start-foreground-service", "-n", "$pkg/$TARGET", "--es", EXTRA_REASON, reason)

    /** dumpsys 被打断或超时时输出不完整，不能据此判定服务不在 */
    fun isCompleteDump(dumpsys: String): Boolean = dumpsys.contains("ACTIVITY MANAGER SERVICES")

    /** 服务在时输出含 ServiceRecord{…pkg/.service.KeepAliveService}；不在时只有 (nothing) */
    fun isServiceRunning(dumpsys: String, pkg: String): Boolean =
        dumpsys.lineSequence().any { it.contains("ServiceRecord{") && it.contains("$pkg/$KEEPALIVE") }
}
