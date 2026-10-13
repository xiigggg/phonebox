package top.xingzhenxi.phonebox.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.material3.Icon
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.ui.Modifier
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import top.xingzhenxi.phonebox.config.AppConfig
import top.xingzhenxi.phonebox.shizuku.HealthScreen
import top.xingzhenxi.phonebox.ui.diag.DiagScreen

enum class TopDestination(val label: String) { Messages("信息"), Calls("通话"), Settings("设置") }

/** 顶层导航：NavigationSuiteScaffold 按窗口宽度自动在底栏和侧边栏之间切换 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScaffold(config: AppConfig, onEditSettings: () -> Unit) {
    var current by rememberSaveable { mutableStateOf(TopDestination.Messages) }
    var showDiag by rememberSaveable { mutableStateOf(false) }
    var showHealth by rememberSaveable { mutableStateOf(false) }
    if (showHealth) {
        HealthScreen(onBack = { showHealth = false })
        return
    }
    if (showDiag) {
        DiagScreen(onBack = { showDiag = false })
        return
    }
    // 键盘弹出时收起底栏：否则内容区底边已在底栏之上，safeDrawing 的 ime 高度从屏幕底算起，
    // 输入框和键盘之间会多出底栏那一截；底栏在时它已经处理了导航栏 inset，内容区不必再留
    val imeVisible = WindowInsets.isImeVisible
    val adaptive = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())
    val layoutType = if (imeVisible && adaptive == NavigationSuiteType.NavigationBar) NavigationSuiteType.None else adaptive
    NavigationSuiteScaffold(
        layoutType = layoutType,
        navigationSuiteItems = {
            TopDestination.entries.forEach { d ->
                item(
                    selected = d == current,
                    onClick = { current = d },
                    icon = { Icon(iconFor(d), contentDescription = null) },
                    label = { Text(d.label) },
                )
            }
        },
    ) {
        Box(if (layoutType == NavigationSuiteType.NavigationBar) Modifier.consumeWindowInsets(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)) else Modifier) {
        when (current) {
            TopDestination.Messages -> MessagesScreen()
            TopDestination.Calls -> CallsScreen()
            TopDestination.Settings -> SettingsScreen(config, onEditSettings, onOpenDiag = { showDiag = true }, onOpenHealth = { showHealth = true })
        }
        }
    }
}

private fun iconFor(d: TopDestination): ImageVector = when (d) {
    TopDestination.Messages -> AppIcons.Message
    TopDestination.Calls -> AppIcons.Phone
    TopDestination.Settings -> AppIcons.Settings
}
