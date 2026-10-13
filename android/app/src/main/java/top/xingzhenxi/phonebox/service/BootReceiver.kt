package top.xingzhenxi.phonebox.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import top.xingzhenxi.phonebox.appGraph
import top.xingzhenxi.phonebox.diag.DiagType

/**
 * 开机和升级后自启。不处理 LOCKED_BOOT_COMPLETED：SIP 口令和 phonebox 令牌在凭证加密（CE）存储里，
 * 用户首次解锁前读不到，那时起服务也注册不上；等 BOOT_COMPLETED（解锁后）再起即可。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val reason = if (action == Intent.ACTION_BOOT_COMPLETED) "开机" else "应用升级"
        context.appGraph.diagLog.log(DiagType.Service, "收到${reason}广播")
        KeepAliveService.ensureRunning(context, reason)
    }
}
