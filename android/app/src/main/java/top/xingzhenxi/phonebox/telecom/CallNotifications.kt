package top.xingzhenxi.phonebox.telecom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import top.xingzhenxi.phonebox.service.KeepAliveService
import top.xingzhenxi.phonebox.sip.CallInfo

/** 来电与通话中的 CallStyle 通知 */
object CallNotifications {
    const val CHANNEL_INCOMING = "incoming_call"
    const val INCOMING_ID = 2001

    fun createChannels(context: Context) {
        val ch = NotificationChannel(CHANNEL_INCOMING, "来电", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "来电铃声与全屏来电界面"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 1000, 1000)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun person(caller: CallerLabel) = Person.Builder()
        .setName(listOfNotNull(caller.number.ifEmpty { "未知号码" }, caller.label).joinToString(" "))
        .setImportant(true)
        .build()

    /**
     * 来电通知。铃声靠渠道声音 + FLAG_INSISTENT 循环，取消通知即停，
     * 这样接听/拒接/远端取消三条路径只要撤通知就能保证铃声立刻停。
     * 无全屏权限时仍带 fullScreenIntent：CallStyle 要求有它（或属于前台服务），系统会自动降级为横幅。
     */
    fun incoming(context: Context, call: CallInfo, caller: CallerLabel): Notification {
        val full = PendingIntent.getActivity(
            context, call.id.hashCode(),
            Intent(context, IncomingCallActivity::class.java)
                .putExtra(CallActions.EXTRA_CALL_ID, call.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // 接听走 Activity：用户点击即把应用带到前台，后续提升 microphone 前台类型不受 while-in-use 限制
        val answer = PendingIntent.getActivity(
            context, call.id.hashCode() + 1,
            Intent(context, InCallActivity::class.java)
                .setAction(CallActions.ACTION_ANSWER)
                .putExtra(CallActions.EXTRA_CALL_ID, call.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val decline = CallActions.broadcast(context, CallActions.ACTION_DECLINE, call.id)
        val n = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(caller.number.ifEmpty { "未知号码" })
            .setContentText(listOfNotNull(caller.label, caller.location.ifEmpty { null }).joinToString(" · ").ifEmpty { "来电" })
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(full)
            .setFullScreenIntent(full, true)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person(caller), decline, answer))
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT
        return n
    }

    /** 通话中通知：作为 KeepAliveService 的前台通知发出（CallStyle 要求属于前台服务或带全屏意图） */
    fun ongoing(context: Context, call: CallInfo, caller: CallerLabel): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, InCallActivity::class.java).putExtra(CallActions.EXTRA_CALL_ID, call.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hangup = CallActions.broadcast(context, CallActions.ACTION_HANGUP, call.id)
        val b = NotificationCompat.Builder(context, KeepAliveService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(caller.number.ifEmpty { "未知号码" })
            .setContentText(statusText(call))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person(caller), hangup))
        call.startedAtMillis?.let { b.setWhen(it).setUsesChronometer(true).setShowWhen(true) }
        return b.build()
    }

    private fun statusText(call: CallInfo) = when {
        call.onHold -> "已保持"
        call.startedAtMillis == null -> if (call.incoming) "正在接通…" else "正在呼叫…"
        else -> "通话中"
    }
}
