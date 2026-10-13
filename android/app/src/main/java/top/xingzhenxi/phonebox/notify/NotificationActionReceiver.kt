package top.xingzhenxi.phonebox.notify

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import android.widget.Toast
import androidx.core.app.RemoteInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.xingzhenxi.phonebox.appGraph
import top.xingzhenxi.phonebox.net.SendResult
import top.xingzhenxi.phonebox.net.SmsItem

/**
 * 通知按钮的处理端（不导出，只收本应用的显式 Intent）。
 * 进程被杀后点按钮会把进程拉起来：数据从内存快照取，没有就读 FeedCache；goAsync 保证 10 秒内收尾。
 * 思路参照 PixelText 的 NotificationActionReceiver（Apache-2.0）。
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = context.appGraph
        val pending = goAsync()
        graph.scope.launch {
            try {
                withTimeoutOrNull(9_000) { handle(context.applicationContext, intent) }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(ctx: Context, intent: Intent) {
        val graph = ctx.appGraph
        val inMemory = graph.phonebox.value?.feedSync?.state?.value?.feed?.sms.orEmpty()
        val cached by lazy { graph.feedCache.read()?.sms.orEmpty() }
        val lookup: (String) -> SmsItem? = { key ->
            inMemory.firstOrNull { SmsNotifyRules.key(it) == key } ?: cached.firstOrNull { SmsNotifyRules.key(it) == key }
        }
        val check = withContext(Dispatchers.IO) {
            ActionValidator.check(
                intent.action,
                intent.getIntExtra(NotifyActions.EXTRA_NOTIF_ID, -1),
                intent.getStringExtra(NotifyActions.EXTRA_SMS_KEY),
                lookup,
            )
        }
        val req = when (check) {
            is ActionCheck.Rejected -> { Log.w(TAG, "拒绝通知动作：${check.reason}"); return }
            is ActionCheck.Ok -> check.req
        }
        when (req.action) {
            NotifyActions.COPY_CODE -> {
                copy(ctx, req.sms.code!!)
                markRead(ctx, req)
            }
            NotifyActions.COPY_ALL -> copy(ctx, req.sms.body)
            NotifyActions.MARK_READ -> markRead(ctx, req)
            NotifyActions.REPLY -> reply(ctx, intent, req)
        }
    }

    private fun markRead(ctx: Context, req: ActionRequest) {
        val graph = ctx.appGraph
        graph.readStore.markRead(listOf(req.smsKey))
        graph.smsNotifier.cancel(req.notifId)
    }

    private suspend fun reply(ctx: Context, intent: Intent, req: ActionRequest) {
        val graph = ctx.appGraph
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(NotifyActions.REMOTE_INPUT_KEY)?.toString()?.trim()
        if (text.isNullOrEmpty()) return
        // 冷启动时 AppGraph 要先从 DataStore 读出配置才建得出客户端
        val client = withTimeoutOrNull(3_000) { graph.phonebox.filterNotNull().first() }?.client
        val r = client?.send(req.sms.from, text) ?: SendResult.Failed("phonebox 未配置")
        Log.i(TAG, "通知回复结果：${r.javaClass.simpleName}")
        when (r) {
            SendResult.Ok -> markRead(ctx, req)
            SendResult.SessionExpired -> graph.smsNotifier.postSendFailed(req.sms.from, "登录已失效，请重新登录")
            is SendResult.Rejected -> graph.smsNotifier.postSendFailed(req.sms.from, if (r.reason == "invalid_number") "号码不支持回复" else "内容不合规（${r.reason}）")
            is SendResult.Failed -> graph.smsNotifier.postSendFailed(req.sms.from, "网络或服务器错误")
        }
    }

    companion object {
        private const val TAG = "PB.NotifyAction"

        /** 标记敏感：Android 13+ 的剪贴板预览不显示内容 */
        fun copy(ctx: Context, text: String) {
            val clip = ClipData.newPlainText("短信", text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            } else {
                clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            }
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            // Android 13 起系统自己会弹复制确认，再弹 Toast 就重复了
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show() }
            }
        }
    }
}
