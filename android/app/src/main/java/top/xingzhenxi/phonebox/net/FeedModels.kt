package top.xingzhenxi.phonebox.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `/api/feed` 的结构，字段与 server.py（read_jsonl 原始行 + smsrules.analyze_sms / analyze_call）一一对应。
 * 服务端随时可能加字段或缺字段，所以全部给默认值。ts 是 Unix 秒。
 */
@Serializable
data class Feed(
    val sms: List<SmsItem> = emptyList(),
    val calls: List<CallItem> = emptyList(),
    val versions: FeedVersions = FeedVersions(),
)

@Serializable
data class SmsItem(
    val ts: Long = 0,
    val from: String = "",
    val body: String = "",
    /** sms-notify.sh 的粗分类：验证码 / 骚扰 / 骚扰（模型）/ 运营商 / 金融 / 快递 / 其他 */
    val cat: String = "",
    /** urgent / normal / none */
    val level: String = "",
    /** analyze_sms 的最终分类：骚扰 / 验证码 / 快递 / 金融 / 运营商 / 出行 / 个人 / 通知 */
    val kind: String = "",
    val card: String = "",
    val code: String? = null,
    @SerialName("code_label") val codeLabel: String = "",
    val summary: String = "",
    val sig: String = "",
)

@Serializable
data class CallItem(
    val ts: Long = 0,
    val number: String = "",
    /** in / out */
    val dir: String = "in",
    /** Asterisk CDR(disposition)：ANSWERED / NO ANSWER / BUSY / FAILED */
    val disposition: String = "",
    val billsec: Int = 0,
    val tag: String = "",
    val ntype: String = "",
    val block: Boolean = false,
    val loc: String = "",
    val src: String = "",
)

@Serializable
data class FeedVersions(
    val rules: String = "",
    val mast: String = "",
    val model: String = "",
)

object FeedJson {
    val json = Json {
        ignoreUnknownKeys = true
        // 服务端偶尔给 null（如 code），按缺省值处理
        coerceInputValues = true
        explicitNulls = false
    }

    fun parse(raw: String): Feed = json.decodeFromString(Feed.serializer(), raw)
    fun encode(feed: Feed): String = json.encodeToString(Feed.serializer(), feed)
    fun parseSms(raw: String): SmsItem = json.decodeFromString(SmsItem.serializer(), raw)
    fun parseCall(raw: String): CallItem = json.decodeFromString(CallItem.serializer(), raw)
}
