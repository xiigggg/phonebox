package top.xingzhenxi.phonebox.telecom

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import top.xingzhenxi.phonebox.sip.AudioRoute
import top.xingzhenxi.phonebox.sip.CallInfo
import top.xingzhenxi.phonebox.sip.CallState
import top.xingzhenxi.phonebox.ui.main.AppIcons
import top.xingzhenxi.phonebox.ui.theme.RiskTag

// 接听/挂断用固定的绿/红，深浅主题下都要一眼认出
private val AnswerGreen = Color(0xFF3E8E5A)
private val HangupRed = Color(0xFFC8462F)

@Composable
fun CallerHeader(caller: CallerLabel, status: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(caller.number.ifEmpty { "未知号码" }, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        caller.label?.let { RiskTag(it, caller.risk) }
        val extra = listOf(caller.location, caller.numberType).filter { it.isNotBlank() }.joinToString(" · ")
        if (extra.isNotEmpty()) {
            Text(extra, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
        Text(status, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
    }
}

/** 长按触发，防止口袋里误触；单击只给出提示 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HoldButton(label: String, color: Color, rotated: Boolean, onHint: () -> Unit, onConfirm: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(color)
                .semantics { contentDescription = label }
                .combinedClickable(onClick = onHint, onLongClick = onConfirm),
        ) {
            Icon(AppIcons.Phone, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp).rotate(if (rotated) 135f else 0f))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
fun IncomingScreen(caller: CallerLabel, onAnswer: () -> Unit, onDecline: () -> Unit) {
    var hint by remember { mutableStateOf("长按按钮接听或拒接") }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.6f))
        Text("来电", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        CallerHeader(caller, "电话信箱 · SIP")
        Spacer(Modifier.weight(1f))
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth().widthIn(max = 420.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            HoldButton("拒接", HangupRed, rotated = true, onHint = { hint = "请长按“拒接”" }, onConfirm = onDecline)
            HoldButton("接听", AnswerGreen, rotated = false, onHint = { hint = "请长按“接听”" }, onConfirm = onAnswer)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ToggleButton(label: String, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(bg)
            .combinedClickableCompat(onClick),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) = combinedClickable(onClick = onClick)

private fun routeLabel(r: AudioRoute) = when (r) {
    AudioRoute.Earpiece -> "听筒"
    AudioRoute.Speaker -> "扬声器"
    AudioRoute.Bluetooth -> "蓝牙"
    AudioRoute.Wired -> "耳机"
}

fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Composable
fun InCallScreen(
    call: CallInfo?,
    caller: CallerLabel,
    audio: AudioUi,
    onMute: (Boolean) -> Unit,
    onHold: (Boolean) -> Unit,
    onRoute: (AudioRoute) -> Unit,
    onDtmf: (Char) -> Unit,
    onHangup: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    var showPad by remember { mutableStateOf(false) }
    var dtmfShown by remember { mutableStateOf("") }
    val ended = call == null || call.state == CallState.Ended || call.state == CallState.Error
    val status = when {
        ended -> "通话结束"
        call!!.onHold -> "已保持"
        call.state == CallState.OutgoingInit -> "正在呼叫…"
        call.state == CallState.OutgoingRinging -> "对方振铃中…"
        call.state == CallState.Incoming -> "正在接通…"
        call.startedAtMillis != null -> formatDuration(((now - call.startedAtMillis) / 1000).coerceAtLeast(0))
        else -> "正在接通…"
    }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.4f))
        CallerHeader(caller, status)
        if (dtmfShown.isNotEmpty()) Text(dtmfShown, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.weight(1f))
        if (showPad) {
            DtmfPad { c -> dtmfShown = (dtmfShown + c).takeLast(16); onDtmf(c) }
        } else if (call != null && !ended) {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    ToggleButton(if (call.muted) "已静音" else "静音", call.muted) { onMute(!call.muted) }
                    ToggleButton("键盘", false) { showPad = true }
                    ToggleButton(if (call.onHold) "恢复" else "保持", call.onHold) { onHold(!call.onHold) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    audio.available.forEach { r -> ToggleButton(routeLabel(r), audio.current == r) { onRoute(r) } }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showPad) ToggleButton("收起", false) { showPad = false }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(80.dp).clip(CircleShape).background(HangupRed)
                    .semantics { contentDescription = "挂断" }
                    .combinedClickableCompat { if (!ended) onHangup() },
            ) {
                Icon(AppIcons.Phone, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp).rotate(135f))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DtmfPad(onKey: (Char) -> Unit) {
    val rows = listOf("123", "456", "789", "*0#")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                r.forEach { c ->
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(68.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .combinedClickableCompat { onKey(c) },
                    ) { Text(c.toString(), style = MaterialTheme.typography.headlineSmall) }
                }
            }
        }
    }
}
