package top.xingzhenxi.phonebox.notify

import top.xingzhenxi.phonebox.net.SmsItem

/** 通知按钮广播的 action 白名单与参数 */
object NotifyActions {
    const val COPY_CODE = "top.xingzhenxi.phonebox.notify.COPY_CODE"
    const val COPY_ALL = "top.xingzhenxi.phonebox.notify.COPY_ALL"
    const val REPLY = "top.xingzhenxi.phonebox.notify.REPLY"
    const val MARK_READ = "top.xingzhenxi.phonebox.notify.MARK_READ"
    val ALL = setOf(COPY_CODE, COPY_ALL, REPLY, MARK_READ)

    const val EXTRA_NOTIF_ID = "notif_id"
    const val EXTRA_SMS_KEY = "sms_key"
    const val REMOTE_INPUT_KEY = "reply_text"

    fun actionOf(a: SmsAction): String = when (a) {
        is SmsAction.CopyCode -> COPY_CODE
        SmsAction.CopyAll -> COPY_ALL
        SmsAction.Reply -> REPLY
        SmsAction.MarkRead -> MARK_READ
    }
}

/** 校验通过的请求：短信从本地缓存取，Intent 里只带短信键，不带任何正文 */
data class ActionRequest(val action: String, val notifId: Int, val smsKey: String, val sms: SmsItem)

sealed interface ActionCheck {
    data class Ok(val req: ActionRequest) : ActionCheck
    data class Rejected(val reason: String) : ActionCheck
}

object ActionValidator {
    /**
     * [lookup] 按短信键在本地已知短信里找；找不到就拒绝。
     * 通知 id 必须正好是该短信键派生的 id，防止拿一个合法键去取消别的通知。
     */
    fun check(action: String?, notifId: Int, smsKey: String?, lookup: (String) -> SmsItem?): ActionCheck {
        if (action == null || action !in NotifyActions.ALL) return ActionCheck.Rejected("unknown_action")
        if (smsKey.isNullOrEmpty() || !smsKey.startsWith("s:") || smsKey.length > 80) return ActionCheck.Rejected("bad_key")
        if (notifId != SmsNotifyRules.notificationId(smsKey)) return ActionCheck.Rejected("bad_notif_id")
        val sms = lookup(smsKey) ?: return ActionCheck.Rejected("unknown_sms")
        if (action == NotifyActions.COPY_CODE && !SmsNotifyRules.isCode(sms)) return ActionCheck.Rejected("not_code")
        if (action == NotifyActions.REPLY && !SmsNotifyRules.canReply(sms)) return ActionCheck.Rejected("not_replyable")
        return ActionCheck.Ok(ActionRequest(action, notifId, smsKey, sms))
    }
}
