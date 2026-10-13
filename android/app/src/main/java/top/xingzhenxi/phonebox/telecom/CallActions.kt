package top.xingzhenxi.phonebox.telecom

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import top.xingzhenxi.phonebox.appGraph

/** 通知按钮（拒接/挂断）的接收端；接听走 InCallActivity */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val id = intent.getStringExtra(CallActions.EXTRA_CALL_ID)
        val coordinator = context.appGraph.callCoordinator
        // 只认自己的两个 action，且 id 必须是当前存在的通话，防止陈旧通知误操作
        if (action !in setOf(CallActions.ACTION_DECLINE, CallActions.ACTION_HANGUP) || id == null || !coordinator.isLive(id)) {
            Log.w(TAG, "忽略通话动作：$action")
            return
        }
        if (action == CallActions.ACTION_DECLINE) coordinator.decline(id) else coordinator.hangup(id)
    }

    private companion object { const val TAG = "PB.Call" }
}

object CallActions {
    const val ACTION_ANSWER = "top.xingzhenxi.phonebox.action.CALL_ANSWER"
    const val ACTION_DECLINE = "top.xingzhenxi.phonebox.action.CALL_DECLINE"
    const val ACTION_HANGUP = "top.xingzhenxi.phonebox.action.CALL_HANGUP"
    const val EXTRA_CALL_ID = "call_id"

    fun broadcast(context: Context, action: String, id: String): PendingIntent = PendingIntent.getBroadcast(
        context, (action + id).hashCode(),
        Intent(context, CallActionReceiver::class.java).setAction(action).putExtra(EXTRA_CALL_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
