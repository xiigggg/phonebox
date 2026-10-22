package top.xingzhenxi.phonebox.ui.main

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import top.xingzhenxi.phonebox.BuildConfig
import top.xingzhenxi.phonebox.R
import top.xingzhenxi.phonebox.appGraph
import top.xingzhenxi.phonebox.config.AppConfig
import top.xingzhenxi.phonebox.data.FeedSnapshot
import top.xingzhenxi.phonebox.data.SyncMode
import top.xingzhenxi.phonebox.sip.RegState
import top.xingzhenxi.phonebox.ui.login.ReloginDialog

/** 状态点的三种颜色：在线、在恢复、不可用；深浅色下都够醒目 */
private val Online = Color(0xFF22A06B)
private val Pending = Color(0xFFE5A100)
private val Offline = Color(0xFFD0453B)

@Composable
fun SettingsScreen(config: AppConfig, onEditSettings: () -> Unit, onOpenDiag: () -> Unit, onOpenHealth: () -> Unit) {
    var relogin by remember { mutableStateOf(false) }
    if (relogin) ReloginDialog(onDismiss = { relogin = false })
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)
        StatusCard(config)
        Group {
            NavRow(SettingsIcons.Pulse, "连接诊断", "在线比例、掉线原因、被杀记录、短信首达延迟", onOpenDiag)
            Divider()
            NavRow(SettingsIcons.Battery, "后台保活体检", "Root / Shizuku 一键修复、守护进程、推送唤醒", onOpenHealth)
        }
        top.xingzhenxi.phonebox.ui.blocking.BlockingSettings()
        top.xingzhenxi.phonebox.roles.RolesCard()
        top.xingzhenxi.phonebox.contacts.ContactsCard()
        top.xingzhenxi.phonebox.update.UpdateCard()
        SectionTitle("账号")
        Group {
            NavRow(SettingsIcons.Server, "连接设置", "${config.sip.host}:${config.sip.port} · ${config.sip.transport} · 分机 ${config.sip.username}", onEditSettings)
            Divider()
            NavRow(SettingsIcons.Key, "重新登录 phonebox", config.phonebox.baseUrl.ifBlank { "未配置" }) { relogin = true }
        }
        About()
    }
}

/** 顶部状态卡：一眼看出来电通道（SIP）和短信通道（事件流）现在通不通 */
@Composable
private fun StatusCard(config: AppConfig) {
    val graph = LocalContext.current.appGraph
    val reg by graph.sipEngine.registration.collectAsStateWithLifecycle()
    val feedFlow = remember {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        graph.phonebox.flatMapLatest { it?.feedSync?.state ?: flowOf(null) }
    }
    val feed: FeedSnapshot? by feedFlow.collectAsStateWithLifecycle(initialValue = null)
    val (sipColor, sipText) = when (reg) {
        RegState.Ok -> Online to "已注册，可接来电"
        RegState.Progress -> Pending to "正在注册…"
        is RegState.Failed -> Offline to "未注册，正在重试"
        RegState.Cleared, RegState.None -> Offline to "未运行"
    }
    val (smsColor, smsText) = when {
        !config.phonebox.hasSession || feed == null -> Offline to "未登录"
        feed?.sessionExpired == true -> Offline to "登录已失效"
        feed?.mode == SyncMode.Streaming -> Online to "实时"
        feed?.mode == SyncMode.Polling -> Pending to "重连中，期间轮询补拉"
        else -> Pending to "连接中…"
    }
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                // 自适应图标（mipmap）painterResource 读不了，直接叠它的背景层和前景层
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp))) {
                    Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, modifier = Modifier.matchParentSize())
                    Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.matchParentSize())
                }
                Column {
                    Text("电话信箱", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                }
            }
            StatusLine("来电", sipColor, sipText)
            StatusLine("短信", smsColor, smsText)
        }
    }
}

@Composable
private fun StatusLine(label: String, color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun About() {
    val ctx = LocalContext.current
    SectionTitle("关于")
    Group {
        NavRow(SettingsIcons.Code, "项目主页", "开源（GPL-3.0），问题和建议请到 GitHub") {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
    Text(
        "版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
    )
}

private const val PROJECT_URL = "https://github.com/xiigggg/phonebox"

@Composable
private fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 4.dp))

@Composable
private fun Group(content: @Composable () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) { Column { content() } }
}

@Composable
private fun Divider() = HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 64.dp))

@Composable
private fun NavRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(SettingsIcons.Chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}
