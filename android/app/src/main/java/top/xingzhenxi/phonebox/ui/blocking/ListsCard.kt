package top.xingzhenxi.phonebox.ui.blocking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.xingzhenxi.phonebox.net.AdminResult
import top.xingzhenxi.phonebox.net.BlockList
import top.xingzhenxi.phonebox.net.BlockLists
import top.xingzhenxi.phonebox.net.ListRules
import top.xingzhenxi.phonebox.net.PhoneboxClient

private sealed interface Load {
    data object Loading : Load
    data object Unsupported : Load
    data class Error(val msg: String) : Load
    data class Ready(val lists: BlockLists, val hintOnly: Boolean) : Load
}

private fun <T> AdminResult<T>.errorText(): String = when (this) {
    is AdminResult.Ok -> ""
    AdminResult.Unsupported -> "NAS 补丁未安装"
    AdminResult.SessionExpired -> "登录已失效，请到设置里重新登录"
    is AdminResult.Rejected -> if (reason == "invalid_number") "号码格式不对" else "被拒绝：$reason"
    is AdminResult.Failed -> "失败：$message"
}

/** 设置页的黑白名单与“仅提示不拦截”；服务端没有这些接口（现网旧版）时只显示提示 */
@Composable
fun ListsCard(client: PhoneboxClient?) {
    val scope = rememberCoroutineScope()
    var load by remember { mutableStateOf<Load>(Load.Loading) }
    var which by rememberSaveable { mutableStateOf(BlockList.Black) }
    var input by rememberSaveable { mutableStateOf("") }
    var opError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun reload() {
        val c = client ?: run { load = Load.Error("还没有配置 phonebox"); return }
        val l = c.lists()
        load = when (l) {
            is AdminResult.Ok -> when (val h = c.hintOnly()) {
                is AdminResult.Ok -> Load.Ready(l.value, h.value)
                AdminResult.Unsupported -> Load.Unsupported
                else -> Load.Error(h.errorText())
            }
            AdminResult.Unsupported -> Load.Unsupported
            else -> Load.Error(l.errorText())
        }
    }
    LaunchedEffect(client) { reload() }

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("名单与拦截方式", style = MaterialTheme.typography.titleSmall)
            when (val s = load) {
                Load.Loading -> Text("加载中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Load.Unsupported -> Text("NAS 补丁未安装：黑白名单和“仅提示不拦截”需要先在 NAS 上打补丁", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                is Load.Error -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { load = Load.Loading; reload() } }) { Text("重试") }
                }
                is Load.Ready -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("仅提示不拦截", style = MaterialTheme.typography.bodyLarge)
                            Text("骚扰来电照常接进来，只在来电显示里标出", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = s.hintOnly, enabled = !busy && client != null, onCheckedChange = { v ->
                            scope.launch {
                                busy = true
                                when (val r = client!!.setHintOnly(v)) {
                                    is AdminResult.Ok -> { load = s.copy(hintOnly = r.value); opError = null }
                                    else -> opError = r.errorText()
                                }
                                busy = false
                            }
                        })
                    }
                    if (!s.lists.fresh) Text("名单可能不是最新（NAS 暂时读不到 Asterisk）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        BlockList.entries.forEachIndexed { i, l ->
                            val n = if (l == BlockList.Black) s.lists.black.size else s.lists.white.size
                            SegmentedButton(
                                selected = which == l,
                                onClick = { which = l },
                                shape = SegmentedButtonDefaults.itemShape(i, BlockList.entries.size),
                            ) { Text((if (l == BlockList.Black) "黑名单" else "白名单") + " $n") }
                        }
                    }
                    val numbers = if (which == BlockList.Black) s.lists.black else s.lists.white
                    if (numbers.isEmpty()) Text("（空）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    numbers.forEach { n ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(n, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            TextButton(enabled = !busy, onClick = {
                                scope.launch {
                                    busy = true
                                    val r = client!!.editList(which, n, add = false)
                                    opError = if (r is AdminResult.Ok) null else r.errorText()
                                    reload()
                                    busy = false
                                }
                            }) { Text("移除") }
                        }
                    }
                    val trimmed = input.trim()
                    val valid = ListRules.valid(trimmed)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it.filter { ch -> ch.isDigit() || ch == '+' }.take(21) },
                            label = { Text("号码") },
                            singleLine = true,
                            isError = trimmed.isNotEmpty() && !valid,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(enabled = valid && !busy, onClick = {
                            scope.launch {
                                busy = true
                                val r = client!!.editList(which, trimmed, add = true)
                                if (r is AdminResult.Ok) { input = ""; opError = null } else opError = r.errorText()
                                reload()
                                busy = false
                            }
                        }) { Text("加入") }
                    }
                }
            }
            opError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}
