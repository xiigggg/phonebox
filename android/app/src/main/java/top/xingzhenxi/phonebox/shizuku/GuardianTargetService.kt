package top.xingzhenxi.phonebox.shizuku

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import top.xingzhenxi.phonebox.appGraph
import top.xingzhenxi.phonebox.diag.DiagType
import top.xingzhenxi.phonebox.service.KeepAliveService

/**
 * Guardian 拉起 App 的唯一入口。导出但要求 DUMP 权限：shell 有，普通 App 拿不到。
 * 自己先进前台，满足 startForegroundService 的 5 秒约束，再拉 KeepAliveService，随即退出。
 */
class GuardianTargetService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "守护进程拉起", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("正在恢复常驻连接")
            .build()
        runCatching { startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
        val reason = intent?.getStringExtra(GuardianPolicy.EXTRA_REASON)?.take(40) ?: "未注明"
        GuardianController.get(this).recordLaunch(reason)
        if (appGraph.keepAlive != null) {
            // 同进程里服务明明在：是判活误报，别借机重连（重连会清零退避）
            appGraph.diagLog.log(DiagType.Guardian, "Guardian 误报服务不在（$reason），服务在运行，忽略")
        } else {
            appGraph.diagLog.log(DiagType.Guardian, "Guardian 拉起服务（$reason）")
            KeepAliveService.ensureRunning(this, "Guardian")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private companion object {
        const val CHANNEL_ID = "guardian"
        const val NOTIFICATION_ID = 1101
    }
}
