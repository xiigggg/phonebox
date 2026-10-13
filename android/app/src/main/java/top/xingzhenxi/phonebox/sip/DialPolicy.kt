package top.xingzhenxi.phonebox.sip

import android.content.Context
import android.telephony.TelephonyManager

/** 紧急号码判断抽象出来，便于单元测试 */
fun interface EmergencyChecker {
    fun isEmergency(number: String): Boolean
}

class TelephonyEmergencyChecker(context: Context) : EmergencyChecker {
    private val tm = context.getSystemService(TelephonyManager::class.java)

    override fun isEmergency(number: String): Boolean = try {
        tm?.isEmergencyNumber(number) ?: DialPolicy.isFallbackEmergency(number)
    } catch (e: Exception) {
        // 没有电话功能的设备（平板/部分模拟器）会抛异常，退回内置名单，宁可多拦
        DialPolicy.isFallbackEmergency(number)
    }
}

object DialPolicy {
    private val FALLBACK = setOf("110", "112", "119", "120", "122", "911", "999", "000", "08", "118")

    fun isFallbackEmergency(number: String): Boolean = number in FALLBACK

    /** 只保留 [0-9+*#]；'+' 只允许出现在开头 */
    fun sanitize(raw: String): String {
        val kept = raw.filter { it in '0'..'9' || it == '+' || it == '*' || it == '#' }
        if (kept.isEmpty()) return kept
        return kept.first() + kept.drop(1).replace("+", "")
    }

    /** 返回可以走 SIP 的号码；空号或紧急号码返回 null（紧急号码必须交给蜂窝网络） */
    fun prepare(raw: String, checker: EmergencyChecker): String? {
        val n = sanitize(raw)
        if (n.isEmpty()) return null
        if (checker.isEmergency(n)) return null
        return n
    }
}
