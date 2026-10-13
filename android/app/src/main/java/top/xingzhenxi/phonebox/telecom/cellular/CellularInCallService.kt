package top.xingzhenxi.phonebox.telecom.cellular

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 界面要的蜂窝通话快照；不暴露 android.telecom.Call 给 Compose，避免持有过期对象 */
data class CellularCallUi(
    val number: String,
    val state: Int,
    val muted: Boolean,
    val speaker: Boolean,
) {
    val ringing get() = state == Call.STATE_RINGING
    val ended get() = state == Call.STATE_DISCONNECTED
}

/**
 * 成为默认电话应用后，系统的蜂窝通话交给这里显示。只做最小界面；
 * SIP 通话是自管连接，不会进到这个服务（没有声明 INCLUDE_SELF_MANAGED_CALLS），两套界面互不干扰。
 * 铃声由 Telecom 自己响，通知渠道不再发声。
 */
class CellularInCallService : InCallService() {
    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = publish()
        override fun onDetailsChanged(call: Call, details: Call.Details) = publish()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel(this)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        _ui.value = null
        cancelNotification(this)
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        call.registerCallback(callback)
        current = call
        publish()
        if (call.details.state == Call.STATE_RINGING) postIncoming(call)
        else startActivity(CellularCallActivity.intent(this))
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        if (current === call) current = calls.lastOrNull { it !== call }
        publish()
    }

    @Deprecated("API 34 起有新接口，但 minSdk 31 仍要用它")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) = publish()

    private fun publish() {
        val c = current
        if (c == null) {
            _ui.value = null
            cancelNotification(this)
            return
        }
        @Suppress("DEPRECATION") val audio = callAudioState
        val ui = CellularCallUi(
            number = c.details.handle?.schemeSpecificPart.orEmpty(),
            state = c.details.state,
            muted = audio?.isMuted == true,
            speaker = audio?.route == CallAudioState.ROUTE_SPEAKER,
        )
        _ui.value = ui
        if (ui.ended) cancelNotification(this)
        else if (!ui.ringing) postOngoing(ui)
    }

    private fun postIncoming(call: Call) {
        val number = call.details.handle?.schemeSpecificPart.orEmpty()
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("本机卡来电")
            .setContentText(number.ifEmpty { "未知号码" })
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(activityPi(this), true)
            .setContentIntent(activityPi(this))
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person(number), actionPi(this, ACTION_DECLINE), activityPi(this, answer = true)))
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n) }
    }

    private fun postOngoing(ui: CellularCallUi) {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_outgoing)
            .setContentTitle("本机卡通话")
            .setContentText(ui.number.ifEmpty { "未知号码" })
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(activityPi(this))
            // 不用 CallStyle：系统要求它配前台服务或全屏 Intent，否则 notify 直接抛异常把进程带崩
            .addAction(0, "挂断", actionPi(this, ACTION_HANGUP))
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n) }
    }

    companion object {
        const val CHANNEL_ID = "cellular_call"
        private const val NOTIFICATION_ID = 2101
        const val ACTION_DECLINE = "top.xingzhenxi.phonebox.action.CELL_DECLINE"
        const val ACTION_HANGUP = "top.xingzhenxi.phonebox.action.CELL_HANGUP"

        @Volatile private var instance: CellularInCallService? = null
        @Volatile private var current: Call? = null
        private val _ui = MutableStateFlow<CellularCallUi?>(null)
        val ui: StateFlow<CellularCallUi?> = _ui.asStateFlow()

        fun answer() {
            current?.answer(VideoProfile.STATE_AUDIO_ONLY)
            instance?.let { cancelNotification(it) }
        }

        fun reject() { current?.reject(false, null) }

        fun hangup() { current?.disconnect() }

        fun setMuted(muted: Boolean) { instance?.setMuted(muted) }

        @Suppress("DEPRECATION")
        fun setSpeaker(on: Boolean) {
            instance?.setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE)
        }

        private fun person(number: String) = Person.Builder().setName(number.ifEmpty { "未知号码" }).setImportant(true).build()

        private fun createChannel(context: Context) {
            val ch = NotificationChannel(CHANNEL_ID, "本机卡通话", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "蜂窝来电全屏界面；铃声由系统电话服务播放"
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        private fun cancelNotification(context: Context) =
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)

        private fun activityPi(context: Context, answer: Boolean = false) = PendingIntent.getActivity(
            context, if (answer) 1 else 0,
            CellularCallActivity.intent(context).putExtra(CellularCallActivity.EXTRA_ANSWER, answer),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun actionPi(context: Context, action: String) = PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(context, CellularActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** 通知上的拒接/挂断按钮（不导出） */
class CellularActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            CellularInCallService.ACTION_DECLINE -> CellularInCallService.reject()
            CellularInCallService.ACTION_HANGUP -> CellularInCallService.hangup()
        }
    }
}
