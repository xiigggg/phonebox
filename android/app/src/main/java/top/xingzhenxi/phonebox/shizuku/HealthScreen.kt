package top.xingzhenxi.phonebox.shizuku

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import top.xingzhenxi.phonebox.appGraph
import top.xingzhenxi.phonebox.diag.DiagType
import top.xingzhenxi.phonebox.ui.main.AppIcons
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val SHIZUKU_REQUEST = 6006

/** 后台保活体检：Root / Shizuku 一键修复 + ColorOS 手动开关清单 + Guardian 开关 */
@Composable
fun HealthScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var shizuku by remember { mutableStateOf(ShizukuStatus.current()) }
    var results by remember { mutableStateOf(HealthChecker.run(ctx)) }
    var fixing by remember { mutableStateOf(false) }
    var fixMessage by remember { mutableStateOf<String?>(null) }
    var fixFailed by remember { mutableStateOf(false) }
    var bindFailed by remember { mutableStateOf(false) }
    var fixDetails by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val checklist = remember { ColorOsChecklist(ctx) }
    val done by checklist.done.collectAsStateWithLifecycle()
    val guardian = remember { GuardianController.get(ctx) }
    val gStatus by guardian.status.collectAsStateWithLifecycle()

    fun recheck() { shizuku = ShizukuStatus.current(); results = HealthChecker.run(ctx); guardian.refresh() }
    LifecycleResumeEffect(Unit) { recheck(); onPauseOrDispose { } }
    DisposableEffect(Unit) {
        val l = Shizuku.OnRequestPermissionResultListener { _, _ -> recheck() }
        Shizuku.addRequestPermissionResultListener(l)
        onDispose { Shizuku.removeRequestPermissionResultListener(l) }
    }
    LaunchedEffect(gStatus.enabled) { while (gStatus.enabled) { delay(5_000); guardian.refresh() } }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { LazyColumn(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = "返回") }
                Text("后台保活体检", style = MaterialTheme.typography.headlineMedium)
            }
        }
        item {
            val okCount = results.count { it.status == HealthStatus.Ok }
            Section("体检 · ${okCount}/${results.size} 项正常") {
                results.forEach { r -> StatusRow(r.item.title, r.status) }
                when (shizuku) {
                    ShizukuState.Ready -> { FilledTonalButton(
                        enabled = !fixing && HealthMapping.fixesFor(results).isNotEmpty(),
                        onClick = {
                            fixing = true
                            scope.launch {
                                val o = fix(ctx, results)
                                fixMessage = o.message; fixFailed = o.failed; bindFailed = o.bindFailed; fixDetails = o.details; fixing = false
                                recheck()
                            }
                        },
                    ) { Text(if (fixing) "修复中…" else "一键修复") }
                    if (results.any { it.item == HealthItem.ReadLogs && it.status == HealthStatus.NeedsFix }) {
                        Muted("授予读取日志权限时系统会重启本应用，界面会关闭一次，属正常现象，重新打开即可。")
                    } }
                    ShizukuState.NoPermission -> {
                        Muted("Shizuku 已运行，但还没授权给本应用。")
                        FilledTonalButton(onClick = { Shizuku.requestPermission(SHIZUKU_REQUEST) }) { Text("授权 Shizuku") }
                    }
                    ShizukuState.NotRunning -> ShizukuGuide(ctx)
                }
                fixMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (fixDetails.isNotEmpty()) FailureDetails(fixDetails)
                // 连不上 Shizuku 时命令根本没执行，和权限监控无关，提示改为先确认 Shizuku 在运行
                if (fixFailed && bindFailed) Muted("Shizuku 服务没有响应：打开 Shizuku 确认显示“正在运行”，刚激活的话等几秒再试；仍失败请把上面的详情截图。")
                if (fixFailed && !bindFailed) Muted("ColorOS 的权限监控可能拦截了修复：到 开发者选项 打开“禁止权限监控”（英文界面叫 Disable system optimization）后重试。")
                if (results.any { it.item.fix == null && it.status == HealthStatus.NeedsFix }) {
                    Muted("通知权限无法经 Shizuku 修复，请在系统设置里手动打开。")
                    TextButton(onClick = { DeepLinks.open(ctx, ColorOsSwitch.BannerLockscreen) }) { Text("去设置") }
                }
            }
        }
        item {
            top.xingzhenxi.phonebox.root.RootSection(ctx, results, onFixed = { recheck() }) { t, c -> Section(t, c) }
        }
        item {
            Section("ColorOS 手动开关") {
                Muted("系统不提供查询接口，做完后请手动勾选“已完成”。跳转页面未在真机验证，打不开时会退回应用详情页。")
                ColorOsSwitch.entries.forEach { s ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = s in done, onCheckedChange = { checklist.set(s, it) })
                            Text(s.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            if (s.links.isNotEmpty()) TextButton(onClick = { DeepLinks.open(ctx, s) }) { Text("去设置") }
                        }
                        Muted(s.hint)
                    }
                }
            }
        }
        item {
            Section("Guardian 守护进程（实验性）") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(
                        checked = gStatus.enabled,
                        enabled = shizuku == ShizukuState.Ready || gStatus.enabled,
                        onCheckedChange = { guardian.setEnabled(it) },
                    )
                }
                Field("运行状态", when {
                    !gStatus.enabled -> "未启用"
                    gStatus.running -> "运行中（最近检查 ${fmt(gStatus.lastCheckAt)}）"
                    gStatus.connected -> "已连接，未运行"
                    else -> "未连接（Shizuku 未运行？）"
                })
                Field("最近拉起", if (gStatus.lastLaunchAt > 0) "${fmt(gStatus.lastLaunchAt)} · ${gStatus.lastReason}" else "无")
                Muted("每 60 秒检查一次常驻服务，不在就以 shell 身份拉起。手机重启后 Shizuku 会停止，Guardian 也随之停止，需要重新激活 Shizuku 后才恢复。")
            }
        }
        item {
            top.xingzhenxi.phonebox.push.PushSection(ctx) { t, c -> Section(t, c) }
        }
    } }
}

/** 一键修复的结果；[details] 是失败时“详情”区逐行显示的字段 */
private data class FixOutcome(val message: String, val failed: Boolean, val details: List<Pair<String, String>> = emptyList(), val bindFailed: Boolean = false)

/**
 * 只执行需修复项；每条命令的退出码与截短输出进诊断日志（输出里只可能有本包名和系统提示）。
 * READ_LOGS 放最后单独执行：授予它会改变进程 gid，系统随即杀掉本应用进程（PERMISSION_CHANGE），
 * 所以先把其余结果落盘，再授它。
 */
private suspend fun fix(ctx: Context, results: List<HealthResult>): FixOutcome {
    val (last, first) = HealthMapping.fixesFor(results).partition { it == ShellTemplate.GrantReadLogs }
    val log = ctx.appGraph.diagLog
    val shell = ShizukuShell(ctx) { log.log(DiagType.Shizuku, it) }
    fun logExec(t: ShellTemplate, e: ExecResult) =
        log.log(DiagType.Shizuku, if (e.ok) "${t.name} 退出码 0" else ShizukuFailure.execLine(t.name, e.code, e.output))
    val start = ShizukuSnapshot.capture()
    log.log(DiagType.Shizuku, "开始修复：Shizuku 版本=${start.version ?: "未知"}; 服务 uid=${ShizukuFailure.uidLabel(start.uid)}")
    val res = try {
        val r = shell.execAll(first)
        r.forEach { (t, e) -> logExec(t, e) }
        if (last.isNotEmpty()) {
            log.log(DiagType.Shizuku, "授予 READ_LOGS，系统会因此重启本应用")
            delay(800)
        }
        r + shell.execAll(last).onEach { (t, e) -> logExec(t, e) }
    } catch (e: ShizukuBindException) {
        val cause = e.cause ?: e
        val details = ShizukuFailure.fields(ShizukuFailure.stepOf(cause), cause, ShizukuSnapshot.capture()) +
            e.attempts.mapIndexed { i, a -> "第${i + 1}次" to a.removePrefix("第${i + 1}次; ") }
        log.log(DiagType.Shizuku, "两次绑定都失败，放弃")
        return FixOutcome("无法连接 Shizuku 服务（已重试 1 次）", true, details, bindFailed = true)
    }
    val bad = res.filter { !it.second.ok }
    if (bad.isEmpty()) return FixOutcome("已执行 ${res.size} 条修复命令", false)
    val snap = ShizukuSnapshot.capture()
    val details = ShizukuFailure.fields(FailStep.Exec, null, snap) +
        bad.map { (t, e) -> t.name to "退出码 ${e.code}：${e.output.trim().take(200)}" }
    return withContext(Dispatchers.Main) {
        FixOutcome("${res.size} 条命令中 ${bad.size} 条失败，详见下方详情", true, details)
    }
}

@Composable
private fun FailureDetails(details: List<Pair<String, String>>) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(if (open) "收起详情" else "详情") }
    if (open) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        details.forEach { (k, v) ->
            Text("$k：$v", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ShizukuGuide(ctx: Context) {
    val installed = remember {
        runCatching { ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0) }.isSuccess
    }
    Muted(
        if (installed) "Shizuku 已安装但未运行：打开 Shizuku，用“无线调试”或电脑 adb 启动后回到这里。"
        else "未安装 Shizuku。推荐安装 thedjchi 维护的分支 v13.7.0（支持开机连上 Wi-Fi 后自动启动、挂了自动重启），也可以用官方版；安装后用“无线调试”启动。",
    )
    TextButton(onClick = {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/thedjchi/Shizuku/releases")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }) { Text("打开下载页") }
}

@Composable
private fun StatusRow(title: String, status: HealthStatus) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            when (status) {
                HealthStatus.Ok -> "✓ 正常"
                HealthStatus.NeedsFix -> "! 需修复"
                HealthStatus.Unknown -> "? 无法检测"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = when (status) {
                // 主题色是橙色，和 error 红在深色下分不清；“正常”固定用绿色
                HealthStatus.Ok -> androidx.compose.ui.graphics.Color(0xFF22A06B)
                HealthStatus.NeedsFix -> MaterialTheme.colorScheme.error
                HealthStatus.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
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
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Muted(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

private fun fmt(ts: Long): String = if (ts <= 0) "—" else SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(ts))
