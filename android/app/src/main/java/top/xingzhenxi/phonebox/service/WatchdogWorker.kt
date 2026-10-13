package top.xingzhenxi.phonebox.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import top.xingzhenxi.phonebox.appGraph
import top.xingzhenxi.phonebox.diag.DiagType
import java.util.concurrent.TimeUnit

/** 15 分钟一次的兜底：服务心跳过期（或本进程里根本没有服务）就尝试拉起 */
class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.appGraph
        val last = graph.heartbeat.last()
        val ka = graph.keepAlive
        if (ka != null && !Watchdog.isStale(System.currentTimeMillis(), last)) return Result.success()
        if (ka != null) {
            graph.diagLog.log(DiagType.Watchdog, "心跳过期，补一次心跳")
            ka.heartbeat("看门狗")
        } else {
            graph.diagLog.log(DiagType.Watchdog, "服务不在，尝试拉起")
            KeepAliveService.ensureRunning(applicationContext, "看门狗")
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "keepalive-watchdog"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
