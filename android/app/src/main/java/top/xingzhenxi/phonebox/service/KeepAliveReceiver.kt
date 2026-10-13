package top.xingzhenxi.phonebox.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import top.xingzhenxi.phonebox.appGraph
import top.xingzhenxi.phonebox.diag.DiagType

/**
 * 心跳闹钟和“任务移除后自检”闹钟的落点。服务还活着就直接调它，
 * 不再走 startForegroundService——后台启动前台服务在 Android 12+ 多半被拒。
 */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = context.appGraph
        val ka = graph.keepAlive
        when {
            ka != null && intent.action == ACTION_HEARTBEAT -> ka.heartbeat("闹钟")
            ka != null -> ka.reconnectNow("任务移除后自检")
            else -> {
                graph.diagLog.log(DiagType.Alarm, "闹钟触发但服务不在，尝试拉起")
                KeepAliveService.ensureRunning(context, "闹钟")
            }
        }
    }

    companion object {
        const val ACTION_HEARTBEAT = "top.xingzhenxi.phonebox.action.HEARTBEAT"
        const val ACTION_RESTART = "top.xingzhenxi.phonebox.action.RESTART"

        fun pending(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(context, KeepAliveReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
