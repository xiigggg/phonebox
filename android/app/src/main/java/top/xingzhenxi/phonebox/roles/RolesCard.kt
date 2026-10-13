package top.xingzhenxi.phonebox.roles

import android.app.role.RoleManager
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect

private data class RoleRow(val role: String, val title: String, val hint: String)

private val ROLES = listOf(
    RoleRow(RoleManager.ROLE_DIALER, "默认电话应用", "接管本机卡来电/去电界面"),
    RoleRow(RoleManager.ROLE_SMS, "默认短信应用", "收本机卡短信、短信唤醒"),
    RoleRow(RoleManager.ROLE_CALL_REDIRECTION, "呼叫重定向", "系统拨号改走 NAS 线路"),
)

/** 设置页“默认应用”卡片：三个角色的持有状态、请求按钮、重定向开关和唤醒密钥 */
@Composable
fun RolesCard() {
    val ctx = LocalContext.current
    val rm = remember { ctx.getSystemService(RoleManager::class.java) }
    var tick by remember { mutableIntStateOf(0) }
    // 从系统授权页回来时重新读一遍持有状态
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    val settings = ctx.roleSettings
    val redirect by settings.redirectEnabled.collectAsState()
    val hasKey by settings.hasWakeKey.collectAsState()

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("默认应用", style = MaterialTheme.typography.titleMedium)
            ROLES.forEach { r ->
                val available = tick >= 0 && rm.isRoleAvailable(r.role)
                val held = available && rm.isRoleHeld(r.role)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            when { !available -> "本机不支持"; held -> "已设置 · ${r.hint}"; else -> "未设置 · ${r.hint}" },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (available && !held) {
                        FilledTonalButton(onClick = { launcher.launch(rm.createRequestRoleIntent(r.role)) }) { Text("设置") }
                    }
                }
            }
            Text(
                "部分系统（含 ColorOS）的“默认应用”设置里没有“呼叫重定向”入口，只能在这里请求；被拒后系统可能不再弹窗，后续版本会用 Shizuku 补上。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("系统拨号走 NAS 线路", style = MaterialTheme.typography.bodyLarge)
                    Text("紧急号码始终走本机网络", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(redirect, settings::setRedirectEnabled)
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Text("短信唤醒密钥", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (hasKey) "已设置（加密保存）。填新值覆盖，留空保存为清除。" else "未设置：唤醒短信会按普通短信处理。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            var key by remember { mutableStateOf("") }
            OutlinedTextField(
                key, { key = it }, label = { Text("wake_key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row {
                TextButton(onClick = { settings.setWakeKey(key); key = "" }) { Text("保存密钥") }
                TextButton(onClick = { ctx.startActivity(Intent(ctx, LocalSmsActivity::class.java)) }) { Text("查看本机卡短信") }
            }
        }
    }
}
