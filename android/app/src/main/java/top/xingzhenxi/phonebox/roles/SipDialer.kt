package top.xingzhenxi.phonebox.roles

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.TelecomManager
import android.widget.Toast
import kotlinx.coroutines.launch
import top.xingzhenxi.phonebox.appGraph
import top.xingzhenxi.phonebox.diag.DiagType
import top.xingzhenxi.phonebox.sip.DialPolicy
import top.xingzhenxi.phonebox.sip.TelephonyEmergencyChecker
import top.xingzhenxi.phonebox.telecom.CallCoordinator

/** 外部拨号入口（ACTION_CALL、呼叫重定向）共用：紧急号码交系统，其余走 SIP 并打开通话界面 */
object SipDialer {
    fun dialViaSip(context: Context, number: String) {
        val app = context.applicationContext
        val graph = app.appGraph
        graph.scope.launch {
            val id = graph.callCoordinator.dial(number)
            android.os.Handler(app.mainLooper).post {
                if (id == null) {
                    graph.diagLog.log(DiagType.Call, "外部拨号未能经 SIP 拨出")
                    Toast.makeText(app, "无法经 NAS 拨出：SIP 未注册", Toast.LENGTH_SHORT).show()
                } else {
                    runCatching { app.startActivity(CallCoordinator.inCallIntent(app, id)) }
                }
            }
        }
    }

    /** 交给系统 Telecom 用蜂窝网络呼出。需要 CALL_PHONE，DIALER 角色会授予 */
    fun placeCellular(context: Context, number: String): Boolean = try {
        context.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), null)
        true
    } catch (e: SecurityException) {
        false
    }

    /** ACTION_CALL：紧急交系统，其余走 SIP */
    fun handleCall(context: Context, raw: String) {
        val n = DialPolicy.sanitize(raw)
        if (n.isEmpty()) return
        val checker = TelephonyEmergencyChecker(context)
        if (checker.isEmergency(n)) {
            placeEmergency(context, n)
            return
        }
        dialViaSip(context, n)
    }

    /**
     * 紧急号码一律走系统蜂窝网络：有 CALL_PHONE（DIALER 角色会授予）就直接交 Telecom 呼出；
     * 没有就打开系统自带拨号器（TelecomManager.systemDialerPackage）并预填号码，由用户按拨号键。
     * 不写死 com.android.dialer：ColorOS 的系统拨号器包名不同，写死会在那里静默失败。
     */
    fun placeEmergency(context: Context, number: String) {
        if (placeCellular(context, number)) return
        val tm = context.getSystemService(TelecomManager::class.java)
        val dial = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val sysDialer = runCatching { tm.systemDialerPackage }.getOrNull()
        if (sysDialer != null && runCatching { context.startActivity(Intent(dial).setPackage(sysDialer)) }.isSuccess) return
        Toast.makeText(context.applicationContext, "无法打开系统拨号器，请用系统电话拨打 $number", Toast.LENGTH_LONG).show()
    }
}
