package top.xingzhenxi.phonebox.roles

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/** 本机 SIM 卡收到的短信/彩信提示。渠道静默：本机卡只是兜底，主号的短信走 phonebox */
object LocalSmsNotifier {
    const val CHANNEL_ID = "local_sim"
    private const val MMS_ID = 3900

    fun createChannel(context: Context) {
        val ch = NotificationChannel(CHANNEL_ID, "本机卡", NotificationManager.IMPORTANCE_LOW).apply {
            description = "本机 SIM 卡收到的短信，静默提示"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun postSms(context: Context, from: String, body: String, ts: Long) {
        createChannel(context)
        val b = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_email)
            .setContentTitle("本机卡 · $from")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setWhen(ts)
            .setShowWhen(true)
            .setSilent(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(android.R.drawable.sym_action_email)
                    .setContentTitle("本机卡").setContentText("收到一条短信").build(),
            )
            .setContentIntent(openList(context))
        notify(context, (from + ts).hashCode(), b.build())
    }

    fun postMms(context: Context) {
        createChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_email)
            .setContentTitle("本机卡")
            .setContentText("收到彩信，暂不支持")
            .setSilent(true)
            .setAutoCancel(true)
            .build()
        notify(context, MMS_ID, n)
    }

    private fun openList(context: Context) = PendingIntent.getActivity(
        context, 0, Intent(context, LocalSmsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notify(context: Context, id: Int, n: Notification) {
        // 没有通知权限时 notify 会被系统静默丢弃，这里不再额外处理
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id, n) }
    }
}
