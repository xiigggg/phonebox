package top.xingzhenxi.phonebox.roles

import top.xingzhenxi.phonebox.sip.DialPolicy
import top.xingzhenxi.phonebox.sip.EmergencyChecker

enum class RedirectDecision {
    /** 紧急号码：原样交给蜂窝网络 */
    Emergency,
    /** 用户关了“系统拨号走 NAS 线路” */
    Disabled,
    /** SIP 未注册：走蜂窝并提示 */
    Unregistered,
    /** 取消原呼叫，改走 SIP */
    ViaSip,
}

object RedirectPolicy {
    /** 紧急判断放最前：开关、注册状态都不能让紧急号码走 SIP */
    fun decide(raw: String, enabled: Boolean, sipRegistered: Boolean, checker: EmergencyChecker): RedirectDecision {
        val n = DialPolicy.sanitize(raw)
        if (n.isEmpty() || checker.isEmergency(n) || checker.isEmergency(raw)) return RedirectDecision.Emergency
        if (!enabled) return RedirectDecision.Disabled
        if (!sipRegistered) return RedirectDecision.Unregistered
        return RedirectDecision.ViaSip
    }
}
