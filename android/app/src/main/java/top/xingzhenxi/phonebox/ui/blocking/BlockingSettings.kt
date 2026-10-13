package top.xingzhenxi.phonebox.ui.blocking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.xingzhenxi.phonebox.appGraph
import top.xingzhenxi.phonebox.notify.SpamNotifyMode
import top.xingzhenxi.phonebox.ui.messages.SpamInAllSwitch

/** 设置页"拦截设置"区：原拦截页的开关和名单都集中在这里，被拦截的记录分别进通话页、信息页的"骚扰拦截"分类 */
@Composable
fun BlockingSettings() {
    val graph = LocalContext.current.appGraph
    val pb by graph.phonebox.collectAsStateWithLifecycle()
    val spamMode by graph.readStore.spamModeFlow.collectAsStateWithLifecycle()
    val showBlockedCalls by graph.callLogPrefs.showBlockedInAll.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("拦截设置", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 4.dp))
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SwitchRow("在全部中显示被拦截来电", "关闭后被拦截的来电只在通话页的“骚扰拦截”里看", showBlockedCalls) {
                    graph.callLogPrefs.setShowBlockedInAll(it)
                }
                SpamInAllSwitch()
                SwitchRow("骚扰短信静默通知", "关闭后完全不通知，只在信息页的“骚扰拦截”里查看", spamMode == SpamNotifyMode.Silent) {
                    graph.readStore.spamMode = if (it) SpamNotifyMode.Silent else SpamNotifyMode.Off
                }
            }
        }
        ListsCard(pb?.client)
    }
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
