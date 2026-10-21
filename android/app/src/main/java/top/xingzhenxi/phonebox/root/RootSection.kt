package top.xingzhenxi.phonebox.root

import android.content.Context
import android.os.Process
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.xingzhenxi.phonebox.appGraph
import top.xingzhenxi.phonebox.diag.DiagType
import top.xingzhenxi.phonebox.shizuku.HealthMapping
import top.xingzhenxi.phonebox.shizuku.HealthResult
import top.xingzhenxi.phonebox.shizuku.ShellTemplate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 体检页里的 Root 区：有 root 时一键修复走 su（不用 Shizuku），并提供 Root Guardian 开关。
 * 没有 root 时只显示一行说明，不打扰只用 Shizuku 的用户。
 */
@Composable
fun RootSection(ctx: Context, results: List<HealthResult>, onFixed: () -> Unit, section: @Composable (String, @Composable () -> Unit) -> Unit) {
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<RootInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val guardian = remember { RootGuardianController.get(ctx) }
    val g by guardian.status.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { info = withContext(Dispatchers.IO) { RootShell.info() } }
    LaunchedEffect(g.enabled) { while (g.enabled) { delay(5_000); guardian.refresh() } }

    section("Root（KernelSU / Magisk / APatch）") {
        val i = info
        when {
            i == null -> Muted("正在检测 root…（首次会弹授权框）")
            !i.available -> {
                Muted("没有可用的 root，或本应用未获授权。有 root 时这里的修复和守护不需要 Shizuku，重启后也不用重新激活。")
                TextButton(onClick = { scope.launch { info = withContext(Dispatchers.IO) { RootShell.info(force = true) } } }) { Text("重新检测") }
            }
            else -> {
                Field("root 来源", i.provider)
                FilledTonalButton(
                    enabled = !busy && HealthMapping.fixesFor(results).isNotEmpty(),
                    onClick = {
                        busy = true
                        scope.launch {
                            message = withContext(Dispatchers.IO) { fixWithRoot(ctx, results) }
                            busy = false
                            onFixed()
                        }
                    },
                ) { Text(if (busy) "修复中…" else "用 Root 一键修复") }
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Root Guardian", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = g.enabled, enabled = !busy, onCheckedChange = { on ->
                        busy = true
                        scope.launch { withContext(Dispatchers.IO) { guardian.setEnabled(on) }; busy = false }
                    })
                }
                val st = g.state
                Field("运行状态", when {
                    !g.enabled -> "未启用"
                    g.running && st != null -> "运行中（pid ${st.pid}，最近检查 ${fmt(st.lastCheckAt)}）"
                    else -> "未运行（root 授权被收回，或 KernelSU 尚未加载？）"
                })
                if (st != null && g.enabled) {
                    Field("累计", "拉起 ${st.launches} 次 · 解冻 ${st.thaws} 次")
                    Field("最近拉起", if (st.lastLaunchAt > 0) "${fmt(st.lastLaunchAt)} · ${st.lastReason}" else "无")
                }
                Muted("root 身份的守护进程：每 5 秒检查一次本应用，被杀就拉起、被冻结就解冻。和 Shizuku 版 Guardian 二选一即可。重启手机后由本应用或开机模块（phonebox-root 模块）重新拉起；KernelSU 用 late-load 模式时，要等管理器加载完 root 才能恢复。")
            }
        }
    }
}

private fun fixWithRoot(ctx: Context, results: List<HealthResult>): String {
    val log = ctx.appGraph.diagLog
    // READ_LOGS 会让系统重启本进程，放最后（理由同 Shizuku 修复）
    val (last, first) = HealthMapping.fixesFor(results).partition { it == ShellTemplate.GrantReadLogs }
    val res = RootShell.execAll(first + last, ctx.packageName, Process.myUid())
    res.forEach { (t, e) -> log.log(DiagType.Root, if (e.ok) "${t.name} 退出码 0" else "${t.name} 退出码 ${e.code}：${e.output.take(120)}") }
    val bad = res.count { !it.second.ok }
    return if (bad == 0) "已执行 ${res.size} 条修复命令" else "${res.size} 条命令中 $bad 条失败，详见 连接诊断 里的 Root 日志"
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
