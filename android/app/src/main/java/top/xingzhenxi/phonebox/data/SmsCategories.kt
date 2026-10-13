package top.xingzhenxi.phonebox.data

import top.xingzhenxi.phonebox.data.parse.ParsedCard
import top.xingzhenxi.phonebox.net.CallItem
import top.xingzhenxi.phonebox.net.SmsItem

/** 信息页顶部筛选条；顺序即显示顺序 */
enum class SmsCategory(val label: String) {
    All("全部"),
    Code("验证码"),
    Notice("通知"),
    Carrier("运营商"),
    Finance("金融"),
    Express("快递"),
    Travel("出行"),
    Marketing("广告营销"),
    Spam("骚扰拦截"),
}

object SmsCategories {
    private val optOut = listOf("退订", "回T", "拒收")
    private val personal = Regex("(?:\\+86)?1[3-9]\\d{9}")

    /**
     * 一条短信归哪一类；null 表示个人短信，只在"全部"里出现。
     * 以服务端 kind 为准，cat 只用来区分两种骚扰：sms-notify.sh 的"骚扰"是关键词判出的退订类营销，
     * "骚扰（模型）"是模型判定，后者和号码被 NAS 拦截的一起进骚扰拦截。
     */
    fun classify(s: SmsItem, blockedNumbers: Set<String> = emptySet()): SmsCategory? = when {
        s.cat.startsWith("骚扰（") || s.cat.startsWith("骚扰(") -> SmsCategory.Spam
        blockedNumbers.isNotEmpty() && normalize(s.from) in blockedNumbers -> SmsCategory.Spam
        s.cat == "骚扰" || s.kind == "骚扰" -> SmsCategory.Marketing
        s.kind == "验证码" -> SmsCategory.Code
        s.kind == "运营商" -> SmsCategory.Carrier
        s.kind == "金融" -> SmsCategory.Finance
        s.kind == "快递" -> SmsCategory.Express
        s.kind == "出行" -> SmsCategory.Travel
        // 服务端只在卡片规则命中时才给 kind=快递；sms-notify.sh 按关键词判出的 cat=快递 被它落成"通知"，这里补回来
        (s.kind == "通知" || s.kind.isEmpty()) && s.cat == "快递" -> SmsCategory.Express
        // 个人短信先于退订规则：朋友说一句"快递被拒收了"不能被当成营销静音
        s.kind == "个人" -> null
        // 客户端补充：服务端只给了兜底的"通知"、正文又带退订字样的，算营销
        optOut.any { s.body.contains(it) } -> SmsCategory.Marketing
        s.kind == "通知" -> SmsCategory.Notice
        // 老数据没有 kind：手机号当个人，其余当通知
        personal.matches(s.from.trim()) -> null
        else -> SmsCategory.Notice
    }

    /** 不弹声音的类：营销和骚扰拦截 */
    fun isQuiet(s: SmsItem, blockedNumbers: Set<String> = emptySet()): Boolean =
        classify(s, blockedNumbers).let { it == SmsCategory.Marketing || it == SmsCategory.Spam }

    /** NAS 拦截过的来电号码；短信从这些号码来也算骚扰拦截 */
    fun blockedNumbers(calls: List<CallItem>): Set<String> =
        calls.filter { it.dir != "out" && it.block }.map { normalize(it.number) }.filter { it.isNotEmpty() }.toSet()

    fun normalize(n: String): String = n.trim().removePrefix("+86").removePrefix("0086")

    /** 某分类下可见的短信；"全部"默认不含骚扰拦截，由设置开关决定 */
    fun filter(sms: List<SmsItem>, category: SmsCategory, blocked: Set<String>, showSpamInAll: Boolean): List<SmsItem> =
        sms.filter { inCategory(classify(it, blocked), category, showSpamInAll) }

    fun inCategory(c: SmsCategory?, category: SmsCategory, showSpamInAll: Boolean): Boolean = when (category) {
        SmsCategory.All -> c != SmsCategory.Spam || showSpamInAll
        else -> c == category
    }

    /** 每个分类的未读条数 */
    fun unreadCounts(sms: List<SmsItem>, blocked: Set<String>, showSpamInAll: Boolean, isUnread: (SmsItem) -> Boolean): Map<SmsCategory, Int> {
        val unreadCats = sms.filter(isUnread).map { classify(it, blocked) }
        return SmsCategory.entries.associateWith { cat -> unreadCats.count { inCategory(it, cat, showSpamInAll) } }
    }

    /** 验证码置顶卡片只在这两个分类下出现 */
    fun showsCodeCards(category: SmsCategory) = category == SmsCategory.All || category == SmsCategory.Code

    /**
     * 列表摘要行：验证码、快递、动账直接显示关键字段；其他卡片用服务端 summary，再没有就用正文。
     * [parsed] 是客户端解析结果，解析失败（None）时退回服务端。
     */
    fun summaryLine(s: SmsItem, parsed: ParsedCard): String = when (parsed) {
        is ParsedCard.VerificationCode -> "验证码 ${parsed.code}"
        is ParsedCard.ExpressDelivery -> "取件码 ${parsed.code}"
        is ParsedCard.BankTransaction -> "${direction(parsed)} ¥${parsed.amount}"
        else -> when {
            !s.code.isNullOrEmpty() && s.codeLabel.isNotEmpty() -> "${s.codeLabel} ${s.code}"
            s.summary.isNotEmpty() -> s.summary
            else -> s.body
        }
    }

    fun direction(b: ParsedCard.BankTransaction): String = when (b.income) {
        true -> "收入"
        false -> "支出"
        null -> b.type
    }
}
