package top.xingzhenxi.phonebox.push

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import top.xingzhenxi.phonebox.appGraph
import top.xingzhenxi.phonebox.net.AdminResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 体检页里的“推送唤醒”区：开关、当前通道、上报状态、发一条测试推送 */
@Composable
fun PushSection(ctx: Context, section: @Composable (String, @Composable () -> Unit) -> Unit) {
    val reg = remember { PushRegistrar.get(ctx) }
    val st by reg.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var testMsg by remember { mutableStateOf<String?>(null) }
    val best = remember(st.enabled) { PushChannels.best(ctx) }

    section("推送唤醒（可选）") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("启用", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = st.enabled, enabled = st.enabled || best != null, onCheckedChange = { on ->
                reg.setEnabled(on)
                val ch = best
                if (on && ch != null) {
                    reg.setProvider(ch.id)
                    ctx.findActivity()?.let(ch::register)
                }
            })
        }
        val ch = PushChannels.byId(st.provider)
        Field("通道", when {
            st.enabled && ch != null -> ch.label
            best != null -> "可用：${best.label}"
            else -> "本机没有可用通道"
        })
        if (st.enabled) {
            Field("状态", when {
                st.uploaded -> "已注册并上报 phonebox"
                st.hasToken -> "已注册，等待上报"
                else -> "注册中…"
            })
            Field("最近唤醒", if (st.lastWakeAt > 0) fmt(st.lastWakeAt) else "无")
            st.lastError?.let { Muted(it) }
            FilledTonalButton(enabled = st.uploaded, onClick = {
                testMsg = "已请求，几秒内应弹出“推送测试”…"
                scope.launch {
                    testMsg = when (val r = ctx.appGraph.phonebox.value?.client?.testPush()) {
                        is AdminResult.Ok -> if (r.value) "服务端已发出，请看通知栏和 连接诊断 里的“收到推送唤醒”" else "服务端发送失败（通道未配置或推送服务拒绝）"
                        is AdminResult.Failed -> if (r.message.contains("429") || r.message == "too_frequent") "30 秒内只能测一次" else "请求失败：${r.message.take(40)}"
                        null -> "phonebox 未登录"
                        else -> "服务端不支持（需要 0.3.0 服务端）"
                    }
                }
            }) { Text("发一条测试推送") }
            testMsg?.let { Muted(it) }
        }
        if (best == null && !st.enabled) {
            Muted("需要 UnifiedPush：手机上装 ntfy（或其他 distributor）并让它连你自建的 ntfy 服务器后，这里就能打开。")
        }
        Muted("只在实时连接断开、短信没送到时才用：推送只是唤醒信号，正文仍从你的 phonebox 拉取。经推送服务器的内容由服务端 PUSH_CONTENT 决定，默认不含正文。")
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
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

private fun fmt(ts: Long): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(ts))
