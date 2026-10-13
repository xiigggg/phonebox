package top.xingzhenxi.phonebox.data

import top.xingzhenxi.phonebox.net.CallItem

enum class CallCategory(val label: String) {
    All("全部"), Missed("未接"), Incoming("来电"), Outgoing("去电"), Stranger("陌生"), Blocked("骚扰拦截"),
}

/** 通话页的分类筛选；纯逻辑。isContact 由调用方给（没有通讯录权限时恒为 false，只看标签） */
object CallFilter {
    /** 与 smsrules.BLOCK_TAGS 一致：服务端开了"仅提示不拦截"时 block=false，但带这些标签的仍归骚扰拦截 */
    val BLOCK_TAGS = setOf(
        "骚扰电话", "广告推销", "疑似欺诈", "诈骗电话", "响一声", "广告营销", "营销推广", "营销推广电话",
        "房产中介", "保险理财", "招聘猎头", "诈骗", "骚扰",
    )

    /** smsrules.number_kind 的取值，外加常见写法；这类标签只说明号码形态，不算真正的标记 */
    private val KIND_TAGS = setOf("手机", "固话", "北京固话", "外地固话", "虚拟运营商", "国际号码", "异常长号码", "企业热线", "服务号", "未标记", "未标注")

    fun isBlocked(c: CallItem): Boolean = c.dir != "out" && (c.block || c.tag in BLOCK_TAGS)

    /** 标签只是号码类型：如"手机"，或通话记录原样带回的"138… 手机" */
    fun isKindOnlyTag(c: CallItem): Boolean {
        val t = c.tag.trim()
        if (t.isEmpty() || t == c.ntype) return true
        val rest = t.filterNot { it.isDigit() || it == '+' || it.isWhitespace() }.trim('·', ' ')
        return rest.isEmpty() || rest in KIND_TAGS || (c.ntype.isNotEmpty() && rest == c.ntype)
    }

    fun isStranger(c: CallItem, isContact: Boolean): Boolean = !isContact && !isBlocked(c) && isKindOnlyTag(c)

    /** 拦截优先：拦截又未接的只算拦截；去电不论是否接通都算去电 */
    fun matches(c: CallItem, cat: CallCategory, isContact: Boolean, showBlockedInAll: Boolean = true): Boolean = when (cat) {
        CallCategory.All -> showBlockedInAll || !isBlocked(c)
        CallCategory.Blocked -> isBlocked(c)
        CallCategory.Outgoing -> c.dir == "out"
        CallCategory.Missed -> c.dir != "out" && !isBlocked(c) && c.disposition != "ANSWERED"
        CallCategory.Incoming -> c.dir != "out" && !isBlocked(c) && c.disposition == "ANSWERED"
        CallCategory.Stranger -> isStranger(c, isContact)
    }

    fun filter(calls: List<CallItem>, cat: CallCategory, isContact: (String) -> Boolean, showBlockedInAll: Boolean = true): List<CallItem> =
        calls.filter { matches(it, cat, isContact(it.number), showBlockedInAll) }

    fun counts(calls: List<CallItem>, isContact: (String) -> Boolean, showBlockedInAll: Boolean = true): Map<CallCategory, Int> =
        CallCategory.entries.associateWith { cat -> calls.count { matches(it, cat, isContact(it.number), showBlockedInAll) } }

    /** 未读未接：比上次查看"未接"时最新那条更新的 */
    fun unreadMissed(calls: List<CallItem>, seenTs: Long): Int =
        calls.count { it.ts > seenTs && matches(it, CallCategory.Missed, isContact = false) }

    fun latestMissedTs(calls: List<CallItem>): Long =
        calls.filter { matches(it, CallCategory.Missed, isContact = false) }.maxOfOrNull { it.ts } ?: 0L

    /** 联系人号码归一：去掉 +86/0086 和分隔符，便于和 CDR 号码比较 */
    fun normalize(number: String): String {
        val n = number.filter { it.isDigit() || it == '+' }
        return when {
            n.startsWith("+86") -> n.drop(3)
            n.startsWith("0086") -> n.drop(4)
            else -> n
        }
    }
}
