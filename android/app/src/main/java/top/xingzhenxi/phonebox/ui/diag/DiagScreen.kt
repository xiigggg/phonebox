package top.xingzhenxi.phonebox.ui.diag

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import top.xingzhenxi.phonebox.appGraph
import top.xingzhenxi.phonebox.data.FeedSnapshot
import top.xingzhenxi.phonebox.data.SyncMode
import top.xingzhenxi.phonebox.diag.DiagType
import top.xingzhenxi.phonebox.diag.ExitReasons
import top.xingzhenxi.phonebox.diag.ExitRecord
import top.xingzhenxi.phonebox.diag.KillLogEntry
import top.xingzhenxi.phonebox.diag.LogcatKillLogSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.xingzhenxi.phonebox.service.KeepAliveService
import top.xingzhenxi.phonebox.sip.RegState
import top.xingzhenxi.phonebox.ui.main.AppIcons
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 连接诊断：给“为什么没收到来电”找证据。只展示事件类型和原因，不含短信内容 */
@Composable
fun DiagScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val graph = context.appGraph
    val reg by graph.sipEngine.registration.collectAsStateWithLifecycle()
    val feedFlow = remember {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        graph.phonebox.flatMapLatest { it?.feedSync?.state ?: flowOf(null) }
    }
    val feed: FeedSnapshot? by feedFlow.collectAsStateWithLifecycle(initialValue = null)
    val config by remember { graph.configRepository.config }.collectAsStateWithLifecycle(initialValue = null)
    val entries by graph.diagLog.entries.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(5_000); now = System.currentTimeMillis() } }
    var exits by remember { mutableStateOf<List<ExitRecord>>(emptyList()) }
    LaunchedEffect(Unit) { exits = ExitReasons.read(context) }
    val killLogSource = remember { LogcatKillLogSource(context) }
    var killLogs by remember { mutableStateOf<List<KillLogEntry>?>(null) }
    LaunchedEffect(Unit) { if (killLogSource.available()) killLogs = withContext(Dispatchers.IO) { killLogSource.read(50) } }
    val ratio = remember(now, reg) { graph.uptime.onlineRatio() }
    val drop = remember(now, reg) { graph.uptime.lastDrop() }

    // 不在 Surface 里时 Text 默认黑色，深色模式下看不清
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { LazyColumn(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = "返回") }
                Text("连接诊断", style = MaterialTheme.typography.headlineMedium)
            }
        }
        item {
            Section("当前状态") {
                Field("SIP 注册", regLabel(reg))
                Field("事件流", feedLabel(feed, config?.phonebox?.hasSession == true))
                Field("近 24 小时在线", ratio?.let { "%.1f%%".format(it * 100) } ?: "暂无数据")
                Field("最近掉线", drop?.let { "${fmt(it.ts)} · ${it.reason}" } ?: "无")
                FilledTonalButton(
                    onClick = { graph.keepAlive?.reconnectNow("诊断页") ?: KeepAliveService.ensureRunning(context, "诊断页") },
                    modifier = Modifier.padding(top = 4.dp),
                ) { Text("立即重连") }
            }
        }
        item {
            Section("短信首达延迟") {
                val sum = remember(now) { graph.latency.summary() }
                if (sum == null) Muted("还没有新短信。NAS 收到短信到本机弹出通知的时间差会记在这里（只记时间，不记内容）。")
                else {
                    Field("最近 ${sum.count} 条", "中位 ${dur(sum.p50Ms)} · 九成 ≤ ${dur(sum.p90Ms)} · 最慢 ${dur(sum.maxMs)}")
                    Field("到达通道", sum.byVia.entries.joinToString(" · ") { "${it.key.label} ${it.value}" })
                    graph.latency.recent().takeLast(5).reversed().forEach { Field(fmt(it.nasTs * 1000), "${dur(it.delayMs)} · ${it.via.label}") }
                    Muted("“补拉”表示实时通道当时不通，是重连或心跳时才拉到的，延迟大多来自这里。")
                }
            }
        }
        item {
            Section("被杀记录（系统）") {
                if (exits.isEmpty()) Muted("没有记录")
                exits.forEach { Field(fmt(it.ts), it.reason) }
            }
        }
        item {
            Section("被杀日志（logcat）") {
                val k = killLogs
                when {
                    k == null -> Muted("未授予 READ_LOGS，可在 设置 → 后台保活体检 里经 Shizuku 授予。")
                    k.isEmpty() -> Muted("日志缓冲里没有本应用被杀或被冻结的记录")
                    else -> k.forEach { Field(it.time, "${it.type} · ${it.reason}") }
                }
            }
        }
        item { Text("诊断日志（最近 50 条）", style = MaterialTheme.typography.titleMedium) }
        val recent = entries.takeLast(50).reversed()
        if (recent.isEmpty()) item { Muted("暂无日志") }
        items(recent) { e ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(fmt(e.ts), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(112.dp))
                Text("${e.type.label} · ${e.reason}", style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Muted("日志只记事件类型和原因，不含短信内容、验证码、口令；号码最多保留后 4 位。") }
    } }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            content()
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(112.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Muted(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

private fun regLabel(r: RegState) = when (r) {
    RegState.Ok -> "已注册"
    RegState.Progress -> "注册中"
    is RegState.Failed -> "失败：${r.reason}"
    RegState.Cleared -> "未配置/已注销"
    RegState.None -> "服务未运行"
}

private fun feedLabel(s: FeedSnapshot?, loggedIn: Boolean) = when {
    !loggedIn || s == null -> "未登录"
    s.sessionExpired -> "登录已失效"
    s.mode == SyncMode.Streaming -> "SSE 实时"
    s.mode == SyncMode.Polling -> if (s.sseUnsupported) "轮询（服务器不支持 SSE）" else "轮询（SSE 重连中）"
    else -> "空闲"
}

private val DiagType.label: String
    get() = when (this) {
        DiagType.Registration -> "注册"
        DiagType.Network -> "网络"
        DiagType.Backoff -> "退避"
        DiagType.Service -> "服务"
        DiagType.Watchdog -> "看门狗"
        DiagType.FgsDenied -> "启动被拒"
        DiagType.Alarm -> "闹钟"
        DiagType.Feed -> "事件流"
        DiagType.Call -> "通话"
        DiagType.Shizuku -> "Shizuku"
        DiagType.Guardian -> "Guardian"
        DiagType.Update -> "更新"
        DiagType.Contacts -> "通讯录"
    }

private fun dur(ms: Long): String = when {
    ms < 10_000 -> "%.1f 秒".format(ms / 1000.0)
    ms < 120_000 -> "${ms / 1000} 秒"
    ms < 7_200_000 -> "${ms / 60_000} 分钟"
    else -> "%.1f 小时".format(ms / 3_600_000.0)
}

private fun fmt(ts: Long): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(ts))
