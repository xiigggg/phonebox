package top.xingzhenxi.phonebox.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import top.xingzhenxi.phonebox.BuildConfig
import top.xingzhenxi.phonebox.appGraph

/** 设置页“应用更新”卡片 */
@Composable
fun UpdateCard() {
    val mgr = LocalContext.current.appGraph.updateManager
    val state by mgr.state.collectAsStateWithLifecycle()
    val custom by mgr.customUrl.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var urlField by remember { mutableStateOf("") }
    var defaultUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(custom) { defaultUrl = mgr.manifestUrl() }

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("应用更新", style = MaterialTheme.typography.titleMedium)
            Text("当前版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val busy = state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing
            when (val s = state) {
                UpdateState.Idle -> Unit
                UpdateState.Checking -> Hint("正在检查…")
                is UpdateState.UpToDate -> Hint("已是最新版本")
                is UpdateState.Available -> {
                    Hint("发现新版本 ${s.manifest.versionName}（${s.manifest.versionCode}）")
                    if (s.manifest.notes.isNotBlank()) Hint(s.manifest.notes)
                }
                is UpdateState.Downloading -> Hint("下载中 ${s.percent}%")
                is UpdateState.Installing -> Hint(if (s.silent) "校验通过，正在经 Shizuku 安装…" else "校验通过，请在系统界面确认安装")
                is UpdateState.Error -> Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(enabled = !busy, onClick = { scope.launch { mgr.check() } }) { Text("检查更新") }
                val avail = state as? UpdateState.Available
                if (avail != null) {
                    FilledTonalButton(onClick = { scope.launch { mgr.downloadAndInstall(avail.manifest) } }) { Text("下载并安装") }
                }
            }
            if (editing) {
                OutlinedTextField(
                    value = urlField,
                    onValueChange = { urlField = it.take(300) },
                    label = { Text("清单地址（留空用默认）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row {
                    TextButton(onClick = { mgr.setCustomUrl(urlField); editing = false }) { Text("保存") }
                    TextButton(onClick = { editing = false }) { Text("取消") }
                }
            } else {
                Row {
                    Text(defaultUrl ?: "未配置 phonebox 地址", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(top = 12.dp))
                    TextButton(onClick = { urlField = custom; editing = true }) { Text("修改") }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
