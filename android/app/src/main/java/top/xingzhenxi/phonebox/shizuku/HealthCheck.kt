package top.xingzhenxi.phonebox.shizuku

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

enum class HealthStatus(val label: String) { Ok("正常"), NeedsFix("需修复"), Unknown("无法检测") }

/** 体检项与对应修复模板；fix 为空的只能用户手动处理 */
enum class HealthItem(val title: String, val fix: ShellTemplate?) {
    BatteryOptimization("电池优化白名单", ShellTemplate.DeviceIdleWhitelist),
    BackgroundRestricted("后台运行不受限", ShellTemplate.RunAnyInBackground),
    BackgroundData("后台数据白名单", ShellTemplate.RestrictBackgroundWhitelist),
    FullScreenIntent("全屏来电通知", ShellTemplate.FullScreenIntent),
    Overlay("悬浮窗", ShellTemplate.SystemAlertWindow),
    RoleDialer("默认电话应用", ShellTemplate.RoleDialer),
    RoleSms("默认短信应用", ShellTemplate.RoleSms),
    RoleCallRedirection("呼叫重定向", ShellTemplate.RoleCallRedirection),
    ReadLogs("读取日志（诊断用）", ShellTemplate.GrantReadLogs),
    Notifications("通知权限", null),
}

data class HealthResult(val item: HealthItem, val status: HealthStatus)

/** 系统查询结果到状态的映射，纯函数便于单测 */
object HealthMapping {
    /** null 表示本机系统版本或厂商不支持该查询 */
    fun fromOk(ok: Boolean?): HealthStatus = when (ok) {
        true -> HealthStatus.Ok
        false -> HealthStatus.NeedsFix
        null -> HealthStatus.Unknown
    }

    /** 省流量模式关闭（DISABLED）或已在白名单（WHITELISTED）都不影响后台联网 */
    fun fromRestrictBackground(status: Int): HealthStatus = when (status) {
        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED,
        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> HealthStatus.Ok
        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> HealthStatus.NeedsFix
        else -> HealthStatus.Unknown
    }

    fun fromRole(available: Boolean, held: Boolean): HealthStatus = when {
        !available -> HealthStatus.Unknown
        held -> HealthStatus.Ok
        else -> HealthStatus.NeedsFix
    }

    /** 只修需要修复且有模板的项 */
    fun fixesFor(results: List<HealthResult>): List<ShellTemplate> =
        results.filter { it.status == HealthStatus.NeedsFix }.mapNotNull { it.item.fix }
}

object HealthChecker {
    fun run(context: Context): List<HealthResult> = HealthItem.entries.map { HealthResult(it, check(context, it)) }

    private fun check(ctx: Context, item: HealthItem): HealthStatus = runCatching {
        val pkg = ctx.packageName
        when (item) {
            HealthItem.BatteryOptimization -> HealthMapping.fromOk(ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(pkg))
            HealthItem.BackgroundRestricted -> HealthMapping.fromOk(ctx.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted?.not())
            HealthItem.BackgroundData -> ctx.getSystemService(ConnectivityManager::class.java)
                ?.let { HealthMapping.fromRestrictBackground(it.restrictBackgroundStatus) } ?: HealthStatus.Unknown
            HealthItem.FullScreenIntent -> HealthMapping.fromOk(
                if (Build.VERSION.SDK_INT >= 34) ctx.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() else true,
            )
            HealthItem.Overlay -> HealthMapping.fromOk(Settings.canDrawOverlays(ctx))
            HealthItem.RoleDialer -> role(ctx, RoleManager.ROLE_DIALER)
            HealthItem.RoleSms -> role(ctx, RoleManager.ROLE_SMS)
            HealthItem.RoleCallRedirection -> role(ctx, RoleManager.ROLE_CALL_REDIRECTION)
            HealthItem.ReadLogs -> HealthMapping.fromOk(granted(ctx, Manifest.permission.READ_LOGS))
            HealthItem.Notifications -> HealthMapping.fromOk(granted(ctx, Manifest.permission.POST_NOTIFICATIONS))
        }
    }.getOrDefault(HealthStatus.Unknown)

    private fun granted(ctx: Context, perm: String) = ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun role(ctx: Context, role: String): HealthStatus {
        val rm = ctx.getSystemService(RoleManager::class.java) ?: return HealthStatus.Unknown
        return HealthMapping.fromRole(rm.isRoleAvailable(role), rm.isRoleHeld(role))
    }
}
