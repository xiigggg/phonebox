package top.xingzhenxi.phonebox.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 深链目标：厂商组件或系统 action。用数据而非 Intent 表示，回退逻辑才能脱离 Android 单测 */
sealed interface DeepLink {
    data class Component(val pkg: String, val cls: String) : DeepLink
    /** withPackageExtra：附带 EXTRA_APP_PACKAGE（通知设置页要用） */
    data class Action(val action: String, val withPackageExtra: Boolean = false) : DeepLink
    data object AppDetails : DeepLink
}

/**
 * ColorOS 手动开关清单。组件名来源：AutoStarter（github.com/judemanutd/autostarter）的 OPPO 分支、
 * dontkillmyapp.com 的 OnePlus/OPPO 页及社区整理；ColorOS 12 起包名从 com.coloros.* 迁到 com.oplus.*。
 * 全部未在真机验证，找不到或打不开时一律退回应用详情页。
 */
enum class ColorOsSwitch(val title: String, val hint: String, val links: List<DeepLink>) {
    AutoStart(
        "允许自启动", "应用管理 → 本应用 → 允许自动启动",
        listOf(
            DeepLink.Component("com.oplus.safecenter", "com.oplus.safecenter.startupapp.view.StartupAppListActivity"),
            DeepLink.Component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            DeepLink.Component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            DeepLink.Component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        ),
    ),
    AssociateStart(
        "允许关联启动", "应用管理 → 本应用 → 允许关联启动（部分版本与自启动在同一页）",
        listOf(
            DeepLink.Component("com.oplus.safecenter", "com.oplus.safecenter.startupapp.view.StartupAppListActivity"),
            DeepLink.Component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        ),
    ),
    FullBackground(
        "允许完全后台行为", "耗电管理 → 本应用 → 允许完全后台行为",
        listOf(
            DeepLink.Component("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"),
            DeepLink.Component("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
            DeepLink.Component("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity"),
        ),
    ),
    NoBatteryOptimization(
        "电池不优化", "电池优化列表 → 本应用 → 不优化",
        listOf(DeepLink.Action(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)),
    ),
    BannerLockscreen(
        "横幅和锁屏通知", "通知管理 → 本应用 → 横幅、锁屏通知全部打开",
        listOf(DeepLink.Action(Settings.ACTION_APP_NOTIFICATION_SETTINGS, withPackageExtra = true)),
    ),
    LockInRecents(
        "在最近任务里锁定 App", "打开最近任务，下拉本应用卡片（或点右上角菜单）选“锁定”，卡片出现小锁即可。系统没有对应设置页。",
        emptyList(),
    ),
}

object DeepLinks {
    /** 依次挑出本机存在的候选，最后总带一个应用详情页兜底；清单为空表示无设置页可跳 */
    fun resolve(links: List<DeepLink>, exists: (DeepLink) -> Boolean): List<DeepLink> =
        if (links.isEmpty()) emptyList() else links.filter(exists) + DeepLink.AppDetails

    fun toIntent(link: DeepLink, pkg: String): Intent = when (link) {
        is DeepLink.Component -> Intent().setComponent(ComponentName(link.pkg, link.cls))
        is DeepLink.Action -> Intent(link.action).apply {
            if (link.withPackageExtra) putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
        }
        DeepLink.AppDetails -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 组件存在但未导出时 startActivity 会抛 SecurityException，继续试下一个 */
    fun open(context: Context, switch: ColorOsSwitch): Boolean {
        val pm = context.packageManager
        val pkg = context.packageName
        val chain = resolve(switch.links) { pm.resolveActivity(toIntent(it, pkg), 0) != null }
        for (link in chain) {
            if (runCatching { context.startActivity(toIntent(link, pkg)) }.isSuccess) return true
        }
        return false
    }
}

/** 用户自报“已完成”：系统不提供查询接口，只能本地记 */
class ColorOsChecklist(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("coloros_checklist", Context.MODE_PRIVATE)
    private val _done = MutableStateFlow(ColorOsSwitch.entries.filter { prefs.getBoolean(it.name, false) }.toSet())
    val done: StateFlow<Set<ColorOsSwitch>> = _done.asStateFlow()

    fun set(item: ColorOsSwitch, value: Boolean) {
        prefs.edit().putBoolean(item.name, value).apply()
        _done.value = if (value) _done.value + item else _done.value - item
    }
}
