package top.xingzhenxi.phonebox.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.xingzhenxi.phonebox.appGraph
import top.xingzhenxi.phonebox.net.LoginResult
import top.xingzhenxi.phonebox.service.KeepAliveService

object LoginText {
    /** 成功返回 null，否则给用户看的错误说明；不含 PIN */
    fun describe(r: LoginResult): String? = when (r) {
        is LoginResult.Success -> null
        LoginResult.WrongPin -> "PIN 不对"
        is LoginResult.Locked -> {
            val min = (r.retryAfterSeconds + 59) / 60
            if (min > 0) "尝试次数过多，已被锁定，约 $min 分钟后再试" else "尝试次数过多，已被锁定，请稍后再试"
        }
        LoginResult.CertMismatch -> "证书指纹不匹配：服务器证书与填写的指纹不同"
        LoginResult.Unreachable -> "连不上服务器：检查地址和网络"
        is LoginResult.Failed -> "登录失败：${r.message}"
    }
}

/** 设置页/信息页的“重新登录”：PIN 只在这个对话框的内存状态里，关掉即丢 */
@Composable
fun ReloginDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("重新登录 phonebox") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(12); error = null },
                    label = { Text("PIN") },
                    singleLine = true,
                    isError = error != null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && pin.isNotEmpty(), onClick = {
                busy = true
                scope.launch {
                    val client = ctx.appGraph.phonebox.value?.client
                    val r = client?.login(pin) ?: LoginResult.Failed("phonebox 地址未配置")
                    busy = false
                    error = LoginText.describe(r)
                    if (error == null) {
                        pin = ""
                        KeepAliveService.ensureRunning(ctx, "重新登录")
                        onDismiss()
                    }
                }
            }) { Text(if (busy) "登录中…" else "登录") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}
