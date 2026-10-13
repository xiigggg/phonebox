package top.xingzhenxi.phonebox.data

import top.xingzhenxi.phonebox.net.CallItem
import top.xingzhenxi.phonebox.net.SmsItem
import top.xingzhenxi.phonebox.ui.theme.RiskLevel

/** 短信会话：有签名按签名聚合（同一机构可能用多个号码发），否则按发送号码 */
data class Conversation(
    val key: String,
    val title: String,
    val bySignature: Boolean,
    /** 最新在前 */
    val messages: List<SmsItem>,
) {
    val latest: SmsItem get() = messages.first()
}

data class CodeCard(val code: String, val source: String, val ts: Long, val sms: SmsItem)

enum class CallType { Incoming, Outgoing, Missed, Blocked }

sealed interface BlockedEntry {
    val ts: Long
    data class Call(val item: CallItem) : BlockedEntry { override val ts get() = item.ts }
    data class Sms(val item: SmsItem) : BlockedEntry { override val ts get() = item.ts }
}

object Records {
    const val CODE_WINDOW_SECONDS = 600L

    fun conversations(sms: List<SmsItem>): List<Conversation> =
        sms.groupBy { if (it.sig.isNotEmpty()) "sig:${it.sig}" else "num:${it.from}" }
            .map { (key, items) ->
                val sorted = items.sortedByDescending { it.ts }
                val bySig = key.startsWith("sig:")
                Conversation(key, if (bySig) "【${sorted.first().sig}】" else sorted.first().from, bySig, sorted)
            }
            .sortedByDescending { it.latest.ts }

    /** 近 10 分钟的验证码；取件码等其他“码”不算，骚扰短信里的数字也不算 */
    fun codeCards(sms: List<SmsItem>, nowSeconds: Long, windowSeconds: Long = CODE_WINDOW_SECONDS): List<CodeCard> =
        sms.filter {
            !it.code.isNullOrEmpty() && it.codeLabel == "验证码" && !isSpam(it) &&
                it.ts in (nowSeconds - windowSeconds)..nowSeconds
        }
            .sortedByDescending { it.ts }
            .map { CodeCard(it.code!!, it.sig.ifEmpty { it.from }, it.ts, it) }

    fun isSpam(s: SmsItem): Boolean = s.kind == "骚扰" || s.cat.startsWith("骚扰")

    /**
     * 去电看 dir；来电里 block=true 的是 dialplan 直接挂断的；其余按 CDR disposition 判断是否接通。
     * 注意：转语音信箱的来电在 CDR 里也是 ANSWERED，这里无法区分，算作已接。
     */
    fun callType(c: CallItem): CallType = when {
        c.dir == "out" -> CallType.Outgoing
        c.block -> CallType.Blocked
        c.disposition == "ANSWERED" -> CallType.Incoming
        else -> CallType.Missed
    }

    // 与 smsrules.BLOCK_TAGS 对齐，按严重程度拆成两组
    private val dangerTags = setOf("骚扰电话", "疑似欺诈", "诈骗电话", "响一声", "诈骗", "骚扰")
    private val marketingTags = setOf("广告推销", "广告营销", "营销推广", "营销推广电话", "房产中介", "保险理财", "招聘猎头")
    private val businessHints = listOf("快递", "外卖", "送餐", "物流", "企业", "客服", "银行", "出租车", "网约车")
    private val businessNtypes = setOf("企业热线", "服务号")

    fun riskLevel(tag: String, ntype: String = "", block: Boolean = false): RiskLevel = when {
        tag in dangerTags || tag.contains("诈骗") || tag.contains("骚扰") -> RiskLevel.Danger
        tag in marketingTags || tag.contains("营销") || tag.contains("推销") -> RiskLevel.Marketing
        // 拦截了但标签不在已知集合里，宁可按危险显示
        block -> RiskLevel.Danger
        businessHints.any { tag.contains(it) } || ntype in businessNtypes -> RiskLevel.Business
        else -> RiskLevel.Unknown
    }

    fun riskLevel(c: CallItem): RiskLevel = riskLevel(c.tag, c.ntype, c.block)

    fun riskLevel(s: SmsItem): RiskLevel = when {
        isSpam(s) -> RiskLevel.Danger
        s.kind == "快递" -> RiskLevel.Business
        else -> RiskLevel.Unknown
    }

    /** 拦截记录：被挂断的来电 + 被判为骚扰的短信，最新在前 */
    fun blocked(calls: List<CallItem>, sms: List<SmsItem>): List<BlockedEntry> =
        (calls.filter { callType(it) == CallType.Blocked }.map { BlockedEntry.Call(it) } +
            sms.filter(::isSpam).map { BlockedEntry.Sms(it) })
            .sortedByDescending { it.ts }
}
