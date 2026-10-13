package top.xingzhenxi.phonebox.update

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import top.xingzhenxi.phonebox.MainActivity
import top.xingzhenxi.phonebox.appGraph
import top.xingzhenxi.phonebox.notify.SmsChannel
import java.util.concurrent.TimeUnit

/** 每天最多一次的后台检查；只发通知，下载安装留给用户在设置页点 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val mgr = applicationContext.appGraph.updateManager
        if (!mgr.dueForBackgroundCheck()) return Result.success()
        val s = mgr.check()
        if (s is UpdateState.Available) notify(s.manifest)
        return Result.success()
    }

    private fun notify(m: UpdateManifest) {
        val ctx = applicationContext
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(ctx, SmsChannel.System.id)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("有新版本 ${m.versionName.ifEmpty { m.versionCode.toString() }}")
            .setContentText("到 设置 → 应用更新 里安装")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, n)
    }

    companion object {
        private const val NAME = "update-check"
        private const val NOTIFY_ID = 7301

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
