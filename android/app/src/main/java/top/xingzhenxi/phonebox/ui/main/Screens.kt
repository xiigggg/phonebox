package top.xingzhenxi.phonebox.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.xingzhenxi.phonebox.config.AppConfig
import top.xingzhenxi.phonebox.ui.dialer.DialerScreen
import top.xingzhenxi.phonebox.ui.login.ReloginDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
fun MessagesScreen() = top.xingzhenxi.phonebox.ui.messages.MessagesScreen()

@Composable
fun CallsScreen() = DialerScreen()

@Composable
fun SettingsScreen(config: AppConfig, onEditSettings: () -> Unit, onOpenDiag: () -> Unit, onOpenHealth: () -> Unit) {
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("SIP 账号", style = MaterialTheme.typography.titleMedium)
                Text("${config.sip.host}:${config.sip.port} · ${config.sip.transport} · 分机 ${config.sip.username}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                Text("phonebox", style = MaterialTheme.typography.titleMedium)
                Text(config.phonebox.baseUrl, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        top.xingzhenxi.phonebox.ui.blocking.BlockingSettings()
        top.xingzhenxi.phonebox.roles.RolesCard()
        top.xingzhenxi.phonebox.update.UpdateCard()
        top.xingzhenxi.phonebox.contacts.ContactsCard()
        var relogin by remember { mutableStateOf(false) }
        if (relogin) ReloginDialog(onDismiss = { relogin = false })
        FilledTonalButton(onClick = { relogin = true }) { Text("重新登录 phonebox") }
        FilledTonalButton(onClick = onEditSettings) { Text("编辑连接设置（重新打开向导）") }
        FilledTonalButton(onClick = onOpenDiag) { Text("连接诊断") }
        FilledTonalButton(onClick = onOpenHealth) { Text("后台保活体检（Root / Shizuku）") }
    }
}
