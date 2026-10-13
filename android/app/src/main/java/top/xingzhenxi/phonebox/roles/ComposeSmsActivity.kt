package top.xingzhenxi.phonebox.roles

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.xingzhenxi.phonebox.appGraph
import top.xingzhenxi.phonebox.config.AppConfig
import top.xingzhenxi.phonebox.net.SendResult
import top.xingzhenxi.phonebox.ui.theme.PhoneboxTheme

/** 别的应用通过 SENDTO sms:/smsto:/mms:/mmsto: 发短信时的落点；一律经 NAS 发出 */
class ComposeSmsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialTo = intent.data?.let(SmsUris::recipient).orEmpty()
        val initialText = intent.getStringExtra("sms_body") ?: intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val graph = appGraph
        setContent {
            PhoneboxTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val config: AppConfig? by graph.configRepository.config.collectAsState(initial = null)
                    var to by rememberSaveable { mutableStateOf(initialTo) }
                    var text by rememberSaveable { mutableStateOf(initialText) }
                    var sending by rememberSaveable { mutableStateOf(false) }
                    val scope = rememberCoroutineScope()
                    val loggedIn = config?.phonebox?.hasSession == true
                    Column(
                        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("新短信（经 NAS 发送）", style = MaterialTheme.typography.headlineSmall)
                        if (config != null && !loggedIn) {
                            Text("还没有登录 phonebox，无法发送。请先在“设置”里登录。", color = MaterialTheme.colorScheme.error)
                        }
                        OutlinedTextField(to, { to = it }, label = { Text("收件人") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(text, { text = it }, label = { Text("内容") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                        Button(
                            enabled = loggedIn && !sending && to.isNotBlank() && text.isNotBlank(),
                            onClick = {
                                sending = true
                                scope.launch {
                                    val client = graph.phonebox.value?.client
                                    val r = client?.send(to.trim(), text.trim()) ?: SendResult.Failed("phonebox 未配置")
                                    sending = false
                                    val msg = when (r) {
                                        SendResult.Ok -> "已发送"
                                        SendResult.SessionExpired -> "登录已失效，请重新登录"
                                        is SendResult.Rejected -> if (r.reason == "invalid_number") "号码不支持" else "内容不合规"
                                        is SendResult.Failed -> "发送失败"
                                    }
                                    Toast.makeText(this@ComposeSmsActivity, msg, Toast.LENGTH_SHORT).show()
                                    if (r == SendResult.Ok) finish()
                                }
                            },
                        ) { Text(if (sending) "发送中…" else "发送") }
                    }
                }
            }
        }
    }
}
