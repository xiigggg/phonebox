package top.xingzhenxi.phonebox.roles

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.TelephonyManager
import android.widget.Toast
import kotlinx.coroutines.launch
import top.xingzhenxi.phonebox.appGraph
import top.xingzhenxi.phonebox.diag.DiagType
import top.xingzhenxi.phonebox.net.SendResult

/** 拒接蜂窝来电时“用短信回复”。系统只给号码和正文，我们经 phonebox 从 NAS 发出 */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val to = intent?.data?.let(SmsUris::recipient)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (intent?.action != TelephonyManager.ACTION_RESPOND_VIA_MESSAGE || to.isNullOrBlank() || text.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val graph = appGraph
        graph.scope.launch {
            val client = graph.phonebox.value?.client
            val r = client?.send(to, text) ?: SendResult.Failed("phonebox 未配置")
            val ok = r == SendResult.Ok
            if (!ok) graph.diagLog.log(DiagType.Feed, "短信回复发送失败：${r.javaClass.simpleName}")
            android.os.Handler(mainLooper).post {
                Toast.makeText(this@RespondViaMessageService, if (ok) "已经由 NAS 发送短信" else "短信发送失败", Toast.LENGTH_SHORT).show()
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }
}

object SmsUris {
    /** sms:/smsto:/mms:/mmsto: 后面的号码；多个收件人只取第一个（NAS 单发） */
    fun recipient(uri: android.net.Uri): String? =
        uri.schemeSpecificPart?.substringBefore('?')?.split(',', ';')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}
