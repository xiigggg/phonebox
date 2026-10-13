package top.xingzhenxi.phonebox.notify

import top.xingzhenxi.phonebox.data.FeedSync
import top.xingzhenxi.phonebox.data.Records
import top.xingzhenxi.phonebox.data.SmsCategories
import top.xingzhenxi.phonebox.net.SmsItem
import top.xingzhenxi.phonebox.net.SmsRules

/** 通知按钮。验证码短信只给「复制 123456」，其余短信「复制」「回复」「已读」 */
sealed interface SmsAction {
    data class CopyCode(val code: String) : SmsAction
    data object CopyAll : SmsAction
    data object Reply : SmsAction
    data object MarkRead : SmsAction
}

enum class SmsChannel(val id: String) {
    Code("sms_code"),
    Normal("sms_normal"),
    Spam("sms_spam"),
    System("system"),
}

/** 骚扰短信的处理方式，用户可在设置页的“拦截设置”里切换 */
enum class SpamNotifyMode { Silent, Off }

/** 一条短信通知的纯数据描述；Android 侧 [SmsNotifier] 照此组装，便于单元测试 */
data class SmsNotifySpec(
    val channel: SmsChannel,
    val title: String,
    val text: String,
    /** 锁屏上显示的版本：不含正文、不含验证码 */
    val publicTitle: String,
    val publicText: String,
    val actions: List<SmsAction>,
)

object SmsNotifyRules {
    fun isCode(s: SmsItem): Boolean = !s.code.isNullOrEmpty() && s.codeLabel == "验证码" && !Records.isSpam(s)

    /** 号码格式与服务端 /api/send 一致才给回复按钮；签名类服务号（如 106 开头）格式合法也照发 */
    fun canReply(s: SmsItem): Boolean = SmsRules.check(s.from, "x") == null

    fun actions(s: SmsItem): List<SmsAction> = when {
        isCode(s) -> listOf(SmsAction.CopyCode(s.code!!), SmsAction.MarkRead)
        else -> buildList {
            add(SmsAction.CopyAll)
            if (canReply(s)) add(SmsAction.Reply)
            add(SmsAction.MarkRead)
        }
    }

    /** [blockedNumbers]：来电被 NAS 拦截过的号码，它发来的短信在信息页归"骚扰拦截"，通知也要一致地静默 */
    fun channel(s: SmsItem, blockedNumbers: Set<String> = emptySet()): SmsChannel = when {
        // 广告营销（含客户端按"退订"补判的）也走骚扰渠道，静默不响
        Records.isSpam(s) || SmsCategories.isQuiet(s, blockedNumbers) -> SmsChannel.Spam
        isCode(s) -> SmsChannel.Code
        else -> SmsChannel.Normal
    }

    /** 返回 null 表示不发通知 */
    fun spec(s: SmsItem, spamMode: SpamNotifyMode, blockedNumbers: Set<String> = emptySet()): SmsNotifySpec? {
        val ch = channel(s, blockedNumbers)
        if (ch == SmsChannel.Spam && spamMode == SpamNotifyMode.Off) return null
        val sender = if (s.sig.isNotEmpty()) "【${s.sig}】" else s.from
        return SmsNotifySpec(
            channel = ch,
            title = if (ch == SmsChannel.Code) "验证码 ${s.code} · $sender" else sender,
            text = s.body,
            publicTitle = "电话信箱",
            publicText = if (ch == SmsChannel.Code) "新验证码" else "新短信",
            actions = actions(s),
        )
    }

    /** 由短信键派生稳定的通知 id，避开常驻(1001)与来电(2001)等固定 id */
    fun notificationId(smsKey: String): Int = 100_000 + (smsKey.hashCode() and 0x7fffffff) % 800_000

    fun key(s: SmsItem): String = FeedSync.smsKey(s)
}
