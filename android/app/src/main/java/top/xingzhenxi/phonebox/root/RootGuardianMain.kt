package top.xingzhenxi.phonebox.root

import top.xingzhenxi.phonebox.BuildConfig
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Root Guardian：由 `su` 用 app_process 拉起的 root 进程，不依赖 Shizuku，App 被杀、被冻结都不影响它。
 *
 * - 每 5 秒看一眼 App 进程：连续两次不在就拉起；被冻结（cgroup.freeze=1 或 SIGSTOP）就解冻；
 * - 每 60 秒用 dumpsys 确认常驻服务还在（进程在但服务没了的情况），要连续两次完整输出都看不到才算；
 * - 开关就是 App 私有目录里的 enabled 文件：只有 App 自己和 root 能写，删掉它守护进程就退出；
 * - 状态写回同目录的 status 文件，属主改成 App 的 uid，App 读它显示状态。
 * 这里没有 Android Context，全是纯 JVM 代码加固定命令；不接收任何外部输入（目录参数也只认固定路径）。
 */
object RootGuardianMain {
    private const val PKG = BuildConfig.APPLICATION_ID
    const val TARGET = "$PKG/.shizuku.GuardianTargetService"
    private const val TICK_MS = 5_000L
    private const val SERVICE_CHECK_EVERY = 12
    @Volatile private var heldLock: java.nio.channels.FileLock? = null
    /** 上次找到的 App pid：还活着就只看它一个，不必每 5 秒把 /proc 下上千个进程全扫一遍（真机上占约 1% CPU） */
    private var knownPid = 0

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(RootGuardianPaths.dir(PKG))
        // 同一时刻只留一个：App 和开机模块都可能来拉。锁必须一直被静态字段引用着：
        // 只放在局部变量里会被 GC 回收、文件随之关闭，锁就释放了（真机上因此跑出过两个守护进程）
        heldLock = runCatching { RandomAccessFile(File(dir, RootGuardianPaths.LOCK), "rw").channel.tryLock() }.getOrNull()
        if (heldLock == null) exitProcess(0)
        val owner = runCatching { Files.getAttribute(dir.toPath(), "unix:uid") as Int }.getOrDefault(-1)
        val st = GuardianState(pid = android.os.Process.myPid())
        var tick = 0
        var missing = 0
        while (File(dir, RootGuardianPaths.ENABLED).exists()) {
            st.lastCheckAt = System.currentTimeMillis()
            val pids = knownPid.takeIf { it > 0 && isPkg(it, PKG) }?.let(::listOf) ?: pidsOf(PKG)
            knownPid = pids.firstOrNull() ?: 0
            if (pids.isEmpty()) {
                missing++
                if (missing >= 2) {
                    launch(st, "Root：进程不在")
                    missing = 0
                }
            } else {
                missing = 0
                pids.forEach { if (unfreeze(it)) st.thaws++ }
                if (tick % SERVICE_CHECK_EVERY == 0 && serviceMissingTwice()) launch(st, "Root：服务不在")
            }
            writeStatus(dir, owner, st)
            tick++
            Thread.sleep(TICK_MS)
        }
        st.running = false
        writeStatus(dir, owner, st)
        exitProcess(0)
    }

    private fun launch(st: GuardianState, reason: String) {
        val code = exec(listOf("am", "start-foreground-service", "-n", TARGET, "--es", "reason", reason)).first
        st.lastLaunchAt = System.currentTimeMillis()
        st.lastReason = if (code == 0) reason else "$reason（拉起失败 $code）"
        st.launches++
    }

    /** 主进程名正好等于包名的那些 pid（:shell、:guardian 这类子进程不算） */
    private fun pidsOf(pkg: String): List<Int> = File("/proc").listFiles().orEmpty().mapNotNull { f ->
        f.name.toIntOrNull()?.takeIf { isPkg(it, pkg) }
    }

    /** pid 还在且仍是本应用主进程（pid 可能被系统回收给别的进程） */
    private fun isPkg(pid: Int, pkg: String): Boolean =
        runCatching { File("/proc/$pid/cmdline").readBytes() }.getOrNull()?.let { String(it).substringBefore('\u0000') == pkg } == true

    /** 解冻并返回是否真的冻着：cgroup v2 freezer（AOSP）与 SIGSTOP（部分厂商的冻结实现）两条都看 */
    private fun unfreeze(pid: Int): Boolean {
        var thawed = false
        val cg = runCatching { File("/proc/$pid/cgroup").readLines() }.getOrNull()
            ?.firstOrNull { it.startsWith("0::") }?.let(CgroupPath::freezeFile)?.let(::File)
        if (cg != null && runCatching { cg.readText().trim() }.getOrNull() == "1") {
            runCatching { cg.writeText("0") }.onSuccess { thawed = true }
        }
        val state = runCatching { File("/proc/$pid/stat").readText() }.getOrNull()?.let(CgroupPath::stateOf)
        if (state == 'T') {
            exec(listOf("kill", "-CONT", pid.toString()))
            thawed = true
        }
        return thawed
    }

    private fun serviceMissingTwice(): Boolean {
        repeat(2) { i ->
            val (code, out) = exec(listOf("dumpsys", "activity", "services", "$PKG/.service.KeepAliveService"))
            if (code != 0 || !out.contains("ACTIVITY MANAGER SERVICES") || out.contains("ServiceRecord{")) return false
            if (i == 0) Thread.sleep(10_000)
        }
        return true
    }

    private fun exec(argv: List<String>): Pair<Int, String> = try {
        val p = ProcessBuilder(argv).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        if (!p.waitFor(15, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            -1 to ""
        } else p.exitValue() to out
    } catch (e: Exception) {
        -1 to ""
    }

    /**
     * status 只在第一次创建时设属主和 SELinux 标签（照抄目录的 app_data_file 加 App 的 MLS 类别），
     * 之后原地覆盖：root 新建的文件默认标签是 s0，App 进程按 MLS 规则读不到；rename 会把好标签换掉。
     */
    private fun writeStatus(dir: File, owner: Int, st: GuardianState) {
        val f = File(dir, RootGuardianPaths.STATUS)
        runCatching {
            if (!f.exists()) {
                f.createNewFile()
                if (owner >= 0) Files.setAttribute(f.toPath(), "unix:uid", owner)
                val ctx = exec(listOf("stat", "-c", "%C", dir.path)).second.trim()
                if (CgroupPath.isAppDataContext(ctx)) exec(listOf("chcon", ctx, f.path))
            }
            RandomAccessFile(f, "rw").use { raf ->
                val bytes = st.encode().toByteArray()
                raf.setLength(0)
                raf.write(bytes)
            }
        }
    }
}

/** 守护进程与 App 之间唯一的数据：几行 key=value 文本 */
data class GuardianState(
    var pid: Int = 0,
    var running: Boolean = true,
    var lastCheckAt: Long = 0,
    var lastLaunchAt: Long = 0,
    var lastReason: String = "",
    var launches: Int = 0,
    var thaws: Int = 0,
) {
    fun encode() =
        "pid=$pid\nrunning=$running\nlastCheckAt=$lastCheckAt\nlastLaunchAt=$lastLaunchAt\nlaunches=$launches\nthaws=$thaws\nlastReason=${lastReason.replace('\n', ' ')}\n"

    companion object {
        fun decode(s: String): GuardianState {
            val m = s.lineSequence().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            return GuardianState(
                pid = m["pid"]?.toIntOrNull() ?: 0,
                running = m["running"] == "true",
                lastCheckAt = m["lastCheckAt"]?.toLongOrNull() ?: 0,
                lastLaunchAt = m["lastLaunchAt"]?.toLongOrNull() ?: 0,
                lastReason = m["lastReason"].orEmpty().take(60),
                launches = m["launches"]?.toIntOrNull() ?: 0,
                thaws = m["thaws"]?.toIntOrNull() ?: 0,
            )
        }
    }
}

object RootGuardianPaths {
    const val ENABLED = "enabled"
    const val STATUS = "status"
    const val LOCK = "lock"
    fun dir(pkg: String) = "/data/user/0/$pkg/files/root-guardian"
}

/** /proc 解析，纯函数便于单测 */
object CgroupPath {
    /** "0::/apps/uid_10490/pid_123" → /sys/fs/cgroup/apps/uid_10490/pid_123/cgroup.freeze；路径里不许有 .. */
    fun freezeFile(line: String): String? {
        val path = line.removePrefix("0::").trim()
        if (!path.startsWith("/") || ".." in path) return null
        return "/sys/fs/cgroup$path/cgroup.freeze"
    }

    private val APP_CTX = Regex("^u:object_r:app_data_file:s0(:c[0-9]+(,c[0-9]+)*)?$")

    /** 只把 chcon 用在 App 数据目录的标签上，别的一概不认 */
    fun isAppDataContext(ctx: String): Boolean = APP_CTX.matches(ctx)

    /** /proc/<pid>/stat 第 3 个字段；进程名在括号里、可能带空格，所以从最后一个 ')' 往后取 */
    fun stateOf(stat: String): Char? = stat.substringAfterLast(')', "").trim().firstOrNull()
}
