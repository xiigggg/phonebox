package top.xingzhenxi.phonebox.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Process
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import top.xingzhenxi.phonebox.BuildConfig

enum class ShizukuState { NotRunning, NoPermission, Ready }

object ShizukuStatus {
    fun current(): ShizukuState = when {
        !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> ShizukuState.NotRunning
        Shizuku.isPreV11() -> ShizukuState.NotRunning
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuState.NoPermission
        else -> ShizukuState.Ready
    }
}

/** App 侧：按需绑定 shell UserService，用完解绑（非 daemon，不常驻） */
class ShizukuShell(private val context: Context, private val log: (String) -> Unit = {}) {
    private val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellService::class.java.name))
        .processNameSuffix("shell")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)
        .daemon(false)

    /** 经 shell 静默安装；[apk] 由调用方打开，本方法负责关闭 */
    suspend fun installApk(apk: android.os.ParcelFileDescriptor, size: Long): ExecResult = try {
        withBound { svc -> ExecResult.parse(runCatching { svc.installApk(apk, size) }.getOrElse { "-1\t${it.javaClass.simpleName}: ${it.message.orEmpty()}" }) }
    } finally {
        runCatching { apk.close() }
    }

    private suspend fun <T> withBound(block: (IShellService) -> T): T {
        val attempts = mutableListOf<String>()
        val (svc, conn) = try {
            ShizukuFailure.retryOnce(1_500, onFailure = { n, e ->
                val line = ShizukuFailure.logLine(ShizukuFailure.stepOf(e), e, ShizukuSnapshot.capture(), attempt = n)
                attempts += line
                log(line)
            }) { bindOnce() }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            throw ShizukuBindException(attempts, e)
        }
        if (attempts.isNotEmpty()) log("重试绑定成功")
        try {
            return withContext(Dispatchers.IO) { block(svc) }
        } finally {
            runCatching { Shizuku.unbindUserService(args, conn, true) }
        }
    }

    /**
     * 首次绑定要由 Shizuku 拉起一个 app_process，冷启动在慢机上可能要好几秒，
     * 原来 10 秒偏紧，放宽到 15 秒；失败时解绑，避免残留连接影响重试。
     */
    private suspend fun bindOnce(): Pair<IShellService, ServiceConnection> {
        val deferred = CompletableDeferred<IShellService>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null && binder.pingBinder()) deferred.complete(IShellService.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        try {
            Shizuku.bindUserService(args, conn)
            return withTimeout(15_000) { deferred.await() } to conn
        } catch (e: Throwable) {
            runCatching { Shizuku.unbindUserService(args, conn, true) }
            throw e
        }
    }

    /** 依次执行模板，返回每条的结果；Shizuku 不可用时抛异常由调用方提示 */
    suspend fun execAll(templates: List<ShellTemplate>): List<Pair<ShellTemplate, ExecResult>> {
        if (templates.isEmpty()) return emptyList()
        val uid = Process.myUid()
        return withBound { svc ->
            templates.map { t -> t to ExecResult.parse(runCatching { svc.exec(t.name, uid) }.getOrElse { "-1\t${it.javaClass.simpleName}: ${it.message.orEmpty()}" }) }
        }
    }
}
