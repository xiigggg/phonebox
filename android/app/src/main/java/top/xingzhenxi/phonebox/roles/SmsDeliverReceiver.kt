package top.xingzhenxi.phonebox.roles

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import top.xingzhenxi.phonebox.appGraph
import top.xingzhenxi.phonebox.diag.DiagType
import top.xingzhenxi.phonebox.service.KeepAliveService

/**
 * 默认短信应用才会收到 SMS_DELIVER；系统不再替我们写库，必须自己写进 Telephony.Sms.Inbox。
 * 唤醒短信校验通过时只拉起服务、刷新注册，不入库也不通知。
 */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        if (parts.isEmpty()) return
        // 长短信的多段在同一个广播里，按顺序拼回原文
        val from = parts.first().displayOriginatingAddress.orEmpty()
        val body = parts.joinToString("") { it.displayMessageBody.orEmpty() }
        val sentTs = parts.first().timestampMillis
        val subId = intent.getIntExtra("subscription", -1)

        val graph = context.appGraph
        val settings = context.roleSettings
        when (val r = WakeVerifier.verify(body, settings.wakeKey(), System.currentTimeMillis() / 1000, settings.lastWakeTs())) {
            is WakeResult.Accepted -> {
                settings.setLastWakeTs(r.ts)
                graph.diagLog.log(DiagType.Service, "短信唤醒")
                // SMS_DELIVER 属于允许从后台启动前台服务的豁免场景
                KeepAliveService.ensureRunning(context, "短信唤醒")
                graph.sipEngine.refreshRegistration()
                return
            }
            is WakeResult.Rejected -> graph.diagLog.log(DiagType.Service, "唤醒校验失败：${r.reason}")
            WakeResult.NotWake -> Unit
        }
        store(context, from, body, sentTs, subId)
        LocalSmsNotifier.postSms(context, from, body, System.currentTimeMillis())
    }

    private fun store(context: Context, from: String, body: String, sentTs: Long, subId: Int) {
        val v = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, from)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.DATE_SENT, sentTs)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            if (subId >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, subId)
        }
        val uri = runCatching { context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, v) }.getOrNull()
        if (uri == null) context.appGraph.diagLog.log(DiagType.Service, "本机卡短信写入系统短信库失败")
    }
}

/** 彩信不做解析，只提示 */
class MmsPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        LocalSmsNotifier.postMms(context)
    }
}
