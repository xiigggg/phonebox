package top.xingzhenxi.phonebox.roles

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.telecom.CallRedirectionService
import android.telecom.PhoneAccountHandle
import android.widget.Toast
import top.xingzhenxi.phonebox.appGraph
import top.xingzhenxi.phonebox.diag.DiagType
import top.xingzhenxi.phonebox.sip.DialPolicy
import top.xingzhenxi.phonebox.sip.RegState
import top.xingzhenxi.phonebox.sip.TelephonyEmergencyChecker

/** 系统拨号器拨出的普通号码改走 NAS 线路；Telecom 对紧急号码本就不调重定向，这里再兜一层 */
class RedirectionService : CallRedirectionService() {
    override fun onPlaceCall(handle: Uri, initialPhoneAccount: PhoneAccountHandle, allowInteractiveResponse: Boolean) {
        val graph = appGraph
        val raw = handle.schemeSpecificPart.orEmpty()
        val decision = RedirectPolicy.decide(
            raw,
            enabled = roleSettings.redirectEnabled.value,
            sipRegistered = graph.sipEngine.registration.value == RegState.Ok,
            checker = TelephonyEmergencyChecker(this),
        )
        when (decision) {
            RedirectDecision.Emergency, RedirectDecision.Disabled -> placeCallUnmodified()
            RedirectDecision.Unregistered -> {
                placeCallUnmodified()
                graph.diagLog.log(DiagType.Call, "系统拨号：SIP 未注册，改用本机网络")
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(applicationContext, "NAS 线路未连接，本次用本机网络拨出", Toast.LENGTH_LONG).show()
                }
            }
            RedirectDecision.ViaSip -> {
                cancelCall()
                graph.diagLog.log(DiagType.Call, "系统拨号改走 NAS 线路")
                SipDialer.dialViaSip(this, DialPolicy.sanitize(raw))
            }
        }
    }
}
