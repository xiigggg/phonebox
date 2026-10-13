/*
 * 移植自 PixelText（https://github.com/Pixel-Tailor-CN/PixelText）domain/parser/MessageParser.kt，Apache License 2.0。
 * 改动：规则 JSON 改用 kotlinx.serialization 读取；不做在线更新，只用随包的 assets/pixeltext 下的规则文件；
 * 输出改为 [ParsedCard]，动账额外取尾号和余额；签名优先取正文里的，取不到再用服务端给的 sig。
 */
package top.xingzhenxi.phonebox.data.parse

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.regex.Matcher

class MessageParser(rulesJson: List<String>) {
    private class Rule(
        val targetCard: String,
        val priority: Int,
        val senderEquals: String?,
        val signatureEquals: String?,
        val keywords: List<String>?,
        val dataUsageStatus: DataUsageStatus?,
        val regex: Regex,
    )

    private val senderIndex: Map<String, List<Rule>>
    private val signatureIndex: Map<String, List<Rule>>
    private val keywordRules: List<Rule>
    private val genericRules: List<Rule>

    /** 每个规则文件的 version，按传入顺序 */
    val versions: List<Int>

    init {
        val files = rulesJson.map { json.decodeFromString(RulesFile.serializer(), it) }
        versions = files.map { it.version }
        // 单条规则正则编译失败就跳过，不能让一条坏规则拖垮整个解析器
        val rules = files.flatMap { it.rules }.mapNotNull { r ->
            runCatching {
                Rule(
                    targetCard = r.targetCard,
                    priority = r.priority,
                    senderEquals = r.fastFail?.senderEquals?.takeIf(String::isNotBlank),
                    signatureEquals = r.fastFail?.signatureEquals?.takeIf(String::isNotBlank),
                    keywords = r.fastFail?.keywords?.takeIf { it.isNotEmpty() },
                    dataUsageStatus = r.dataUsageStatus?.let(DataUsageStatus::valueOf),
                    regex = Regex(r.conditions.contentRegex),
                )
            }.getOrNull()
        }.sortedByDescending { it.priority }
        senderIndex = rules.filter { it.senderEquals != null }.groupBy { it.senderEquals!! }
        signatureIndex = rules.filter { it.senderEquals == null && it.signatureEquals != null }.groupBy { it.signatureEquals!! }
        keywordRules = rules.filter { it.senderEquals == null && it.signatureEquals == null && it.keywords != null }
        genericRules = rules.filter { it.senderEquals == null && it.signatureEquals == null && it.keywords == null }
    }

    /**
     * 解析一条短信。正文由发件人任意控制，规则里有 `.*?` 一类写法，超长正文会让匹配耗时按平方增长
     * （JVM 实测 2 万字约 1.8 秒，而且是在界面线程里算），所以只看前 [MAX_CONTENT] 个字；
     * 卡片类短信都远短于这个长度。任何异常都当作解析不出来，不能让列表崩溃。
     */
    fun parse(sender: String, content: String, fallbackSignature: String? = null): ParsedCard =
        runCatching { parseUnsafe(sender, content.take(MAX_CONTENT), fallbackSignature) }.getOrDefault(ParsedCard.None)

    private fun parseUnsafe(sender: String, content: String, fallbackSignature: String?): ParsedCard {
        val signature = extractSignature(content) ?: fallbackSignature?.takeIf { it.isNotBlank() }
        senderIndex[sender]?.forEach { r -> execute(r, content, signature)?.let { return it } }
        if (signature != null) signatureIndex[signature]?.forEach { r -> execute(r, content, signature)?.let { return it } }
        for (r in keywordRules) {
            if (r.keywords!!.any { content.contains(it, ignoreCase = true) }) execute(r, content, signature)?.let { return it }
        }
        for (r in genericRules) execute(r, content, signature)?.let { return it }
        return ParsedCard.None
    }

    private fun execute(rule: Rule, content: String, signature: String?): ParsedCard? {
        val m = rule.regex.toPattern().matcher(content)
        if (!m.find()) return null
        fun g(name: String) = group(m, name)
        return when (rule.targetCard) {
            "TrainTicket" -> ParsedCard.TrainTicket(
                date = g("date") ?: "",
                trainNumber = g("trainNo") ?: "--",
                departureTime = g("departureTime") ?: "--",
                departureStation = g("departureStation") ?: "--",
                arrivalStation = g("arrivalStation") ?: "--",
                details = buildMap {
                    g("passenger")?.let { put("乘车人", it) }
                    g("seatClass")?.let { put("席别", it) }
                    g("seat")?.let { put("座位", it) }
                    g("gate")?.let { put("检票口", it) }
                    g("orderNo")?.let { put("订单号", it) }
                },
            )
            "Flight" -> ParsedCard.Flight(
                date = g("date") ?: "",
                flightNumber = g("flightNo") ?: "--",
                departureCity = g("departureCity") ?: "--",
                departureTime = g("departureTime") ?: "--",
                arrivalCity = g("arrivalCity") ?: "--",
                arrivalTime = g("arrivalTime") ?: "--",
                terminal = g("terminal") ?: "--",
            )
            "BankTransaction" -> {
                val type = g("type") ?: "交易"
                ParsedCard.BankTransaction(
                    type = type,
                    amount = g("amount") ?: "0.00",
                    income = when {
                        incomeKeywords.any { type.contains(it) } -> true
                        expenseKeywords.any { type.contains(it) } -> false
                        else -> null
                    },
                    account = g("account"),
                    balance = g("balance"),
                    isSuccess = g("status") != "失败",
                    errorMessage = g("reason"),
                    details = buildMap {
                        g("date")?.let { put("交易时间", it) }
                        g("details")?.let { put("交易备注", it.trim('（', '）', '(', ')')) }
                    },
                )
            }
            "ExpressDelivery" -> ParsedCard.ExpressDelivery(
                company = g("company") ?: signature ?: "--",
                code = g("code") ?: "--",
                location = g("location") ?: "--",
                time = g("time"),
            )
            "PhoneRecharge" -> ParsedCard.PhoneRecharge(
                amount = g("amount") ?: "0.00",
                details = buildMap {
                    g("date")?.let { put("充值时间", it) }
                    g("balance")?.let { put("当前余额", "${it}元") }
                },
            )
            "MissedCall" -> {
                val phone = g("phoneNumber")?.trim() ?: return null
                if (phone.count(Char::isDigit) !in 7..15) return null
                ParsedCard.MissedCall(phone, g("time"), g("location"))
            }
            "DataUsage" -> {
                val status = rule.dataUsageStatus ?: DataUsageStatus.NORMAL
                val remaining = g("remainingData")
                val used = g("usedData")
                val primary = if (status == DataUsageStatus.EXHAUSTED) null else remaining ?: used ?: return null
                val rawLabel = g("dataLabel") ?: "手机上网流量"
                ParsedCard.DataUsage(
                    status = status,
                    primaryData = primary,
                    dataLabel = when {
                        status == DataUsageStatus.EXHAUSTED -> rawLabel
                        remaining != null -> "$rawLabel · 剩余"
                        else -> "$rawLabel · 已使用"
                    },
                    cutoffTime = g("cutoffTime"),
                    details = buildMap {
                        g("totalData")?.let { put("套餐总量", it) }
                        if (remaining != null) used?.let { put("已使用", it) }
                        g("throttledUsedData")?.let { put("达量限速流量", "已使用$it") }
                        g("throttleThreshold")?.let { put("限速阈值", it) }
                        g("planName")?.let { put("流量类型", it) }
                    },
                )
            }
            "VerificationCode" -> g("code")?.let { ParsedCard.VerificationCode(it, signature) }
            else -> null
        }
    }

    private fun group(m: Matcher, name: String): String? =
        try { m.group(name)?.takeIf { it.isNotBlank() } } catch (_: IllegalArgumentException) { null }

    companion object {
        /** 交给正则的正文上限；长短信（营销、通知）超过它也只是不出卡片，原文照常显示 */
        const val MAX_CONTENT = 600
        private val incomeKeywords = listOf("入账", "收入", "存入", "退款", "退回", "转入")
        private val expenseKeywords = listOf("扣款", "消费", "支出", "支付", "转出", "代收")
        private val startSig = Regex("^【(.*?)】|^\\[(.*?)]")
        private val endSig = Regex("【(.*?)】$|\\[(.*?)]$")
        private val json = Json { ignoreUnknownKeys = true }

        /** 上游规则在前，本项目补充规则在后（优先级本来就低） */
        val ASSETS = listOf("pixeltext/rules.json", "pixeltext/rules-local.json")

        fun extractSignature(content: String): String? {
            startSig.find(content)?.let { return it.groupValues[1].ifEmpty { it.groupValues[2] } }
            endSig.find(content)?.let { return it.groupValues[1].ifEmpty { it.groupValues[2] } }
            return null
        }

        @Volatile private var instance: MessageParser? = null

        /** 随包规则只读一次；解析器本身无状态，全进程共用 */
        fun get(context: Context): MessageParser = instance ?: synchronized(this) {
            instance ?: MessageParser(ASSETS.map { p -> context.assets.open(p).bufferedReader().use { it.readText() } }).also { instance = it }
        }
    }
}

@Serializable
private class RulesFile(val version: Int = 0, val rules: List<RuleJson> = emptyList())

@Serializable
private class RuleJson(
    val id: String,
    @SerialName("target_card") val targetCard: String,
    val priority: Int = 0,
    @SerialName("fast_fail") val fastFail: FastFailJson? = null,
    @SerialName("data_usage_status") val dataUsageStatus: String? = null,
    val conditions: ConditionsJson,
)

@Serializable
private class FastFailJson(
    @SerialName("sender_equals") val senderEquals: String? = null,
    @SerialName("signature_equals") val signatureEquals: String? = null,
    val keywords: List<String> = emptyList(),
)

@Serializable
private class ConditionsJson(@SerialName("content_regex") val contentRegex: String)
