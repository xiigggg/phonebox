package top.xingzhenxi.phonebox.roles

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/** 唤醒短信校验结果；Rejected 的原因只进诊断日志，不含正文 */
sealed interface WakeResult {
    /** 不是 PBWAKE 格式，按普通短信处理 */
    data object NotWake : WakeResult
    data class Accepted(val ts: Long) : WakeResult
    data class Rejected(val reason: String) : WakeResult
}

/**
 * 校验 `PBWAKE:<unix秒>:<mac>`，格式与 server/asterisk 的唤醒脚本一致。
 * 发件号码不校验（运营商可能改写），安全性只靠 HMAC + 时间窗 + 单调时间戳防重放。
 */
object WakeVerifier {
    private val PATTERN = Regex("""^PBWAKE:(\d{1,12}):([0-9a-fA-F]{16})$""")
    const val WINDOW_SECONDS = 10 * 60L

    fun mac(key: String, ts: Long): String {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return m.doFinal("PBWAKE:$ts".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(16)
    }

    /** [lastAccepted] 为上次接受的时间戳（没有则 0）；调用方在 Accepted 后自行持久化新值 */
    fun verify(body: String, key: String?, nowSeconds: Long, lastAccepted: Long): WakeResult {
        val m = PATTERN.matchEntire(body.trim()) ?: return WakeResult.NotWake
        if (key.isNullOrEmpty()) return WakeResult.Rejected("未配置唤醒密钥")
        val ts = m.groupValues[1].toLong()
        val expected = mac(key, ts).toByteArray(Charsets.US_ASCII)
        val got = m.groupValues[2].lowercase().toByteArray(Charsets.US_ASCII)
        if (!MessageDigest.isEqual(expected, got)) return WakeResult.Rejected("签名不符")
        if (abs(nowSeconds - ts) > WINDOW_SECONDS) return WakeResult.Rejected("时间戳超出 ±10 分钟")
        if (ts <= lastAccepted) return WakeResult.Rejected("重放")
        return WakeResult.Accepted(ts)
    }
}
