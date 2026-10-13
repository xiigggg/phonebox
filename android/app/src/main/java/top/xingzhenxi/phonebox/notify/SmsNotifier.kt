package top.xingzhenxi.phonebox.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import top.xingzhenxi.phonebox.MainActivity
import top.xingzhenxi.phonebox.net.SmsItem

/**
 * 短信与系统提示通知。按钮与渠道规则见 [SmsNotifyRules]；
 * 结构参照 PixelText 的 SmsNotificationHelper（Apache-2.0，github.com/Pixel-Tailor-CN/PixelText）。
 */
class SmsNotifier(context: Context) {
    private val app = context.applicationContext
    private val nm = app.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(SmsChannel.Code.id, "验证码", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "验证码短信，横幅提醒，可一键复制"
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
                NotificationChannel(SmsChannel.Normal.id, "短信", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
                NotificationChannel(SmsChannel.Spam.id, "骚扰短信", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "被判为骚扰的短信，静默不打扰；可在设置页的“拦截设置”里改为不通知"
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                    setShowBadge(false)
                },
                NotificationChannel(SmsChannel.System.id, "系统提示", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "需要重新登录、发送失败等"
                },
            ),
        )
    }

    fun post(sms: SmsItem, spamMode: SpamNotifyMode, blockedNumbers: Set<String> = emptySet()) {
        val spec = SmsNotifyRules.spec(sms, spamMode, blockedNumbers) ?: return
        val key = SmsNotifyRules.key(sms)
        val id = SmsNotifyRules.notificationId(key)
        val publicVersion = NotificationCompat.Builder(app, spec.channel.id)
            .setSmallIcon(android.R.drawable.sym_action_email)
            .setContentTitle(spec.publicTitle)
            .setContentText(spec.publicText)
            .build()
        val b = NotificationCompat.Builder(app, spec.channel.id)
            .setSmallIcon(android.R.drawable.sym_action_email)
            .setContentTitle(spec.title)
            .setContentText(spec.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(spec.text))
            .setWhen(sms.ts * 1000)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
        spec.actions.forEach { b.addAction(action(it, id, key)) }
        notify(id, b.build())
        Log.i(TAG, "sms notified id=$id posted=${System.currentTimeMillis()}")
    }

    private fun action(a: SmsAction, id: Int, key: String): NotificationCompat.Action {
        val intent = actionIntent(app, NotifyActions.actionOf(a), id, key)
        val title = when (a) {
            is SmsAction.CopyCode -> "复制 ${a.code}"
            SmsAction.CopyAll -> "复制"
            SmsAction.Reply -> "回复"
            SmsAction.MarkRead -> "已读"
        }
        // requestCode 区分同一通知的不同按钮，否则 PendingIntent 会被合并
        val req = id * 4 + NotifyActions.ALL.indexOf(intent.action)
        if (a == SmsAction.Reply) {
            // RemoteInput 要求可变：系统要把输入的文字填进 Intent
            val pi = PendingIntent.getBroadcast(app, req, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val input = RemoteInput.Builder(NotifyActions.REMOTE_INPUT_KEY).setLabel("回复短信").build()
            return NotificationCompat.Action.Builder(0, title, pi)
                .addRemoteInput(input)
                .setAllowGeneratedReplies(false)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                // 回复会用真实号码发出短信：锁屏时必须先解锁，不依赖各家系统对锁屏回复的默认策略
                .setAuthenticationRequired(true)
                .build()
        }
        val pi = PendingIntent.getBroadcast(app, req, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Action.Builder(0, title, pi).build()
    }

    fun cancel(id: Int) = nm.cancel(id)

    fun cancelForKeys(keys: Collection<String>) = keys.forEach { nm.cancel(SmsNotifyRules.notificationId(it)) }

    fun postSessionExpired() = system(ID_SESSION, "需要重新登录 phonebox", "登录已失效，短信和通话记录暂停同步。打开应用重新登录。")

    fun clearSessionExpired() = nm.cancel(ID_SESSION)

    fun postSendFailed(to: String, reason: String) =
        system(ID_SEND_FAILED, "短信没有发出", "发给 $to 失败：$reason")

    private fun system(id: Int, title: String, text: String) {
        val n = NotificationCompat.Builder(app, SmsChannel.System.id)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        notify(id, n)
    }

    private fun notify(id: Int, n: Notification) {
        // 没有通知权限时 notify 会抛 SecurityException；静默跳过，信息页仍能看到
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return
        runCatching { nm.notify(id, n) }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        app, 0, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "PB.Notify"
        const val ID_SESSION = 3001
        const val ID_SEND_FAILED = 3002

        /** 显式 Intent 指向不导出的接收器；只带通知 id 和短信键 */
        fun actionIntent(context: Context, action: String, notifId: Int, smsKey: String): Intent =
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(action)
                .setPackage(context.packageName)
                .putExtra(NotifyActions.EXTRA_NOTIF_ID, notifId)
                .putExtra(NotifyActions.EXTRA_SMS_KEY, smsKey)
    }
}
