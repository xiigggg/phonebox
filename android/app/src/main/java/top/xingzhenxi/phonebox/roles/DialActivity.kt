package top.xingzhenxi.phonebox.roles

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import top.xingzhenxi.phonebox.MainActivity
import top.xingzhenxi.phonebox.ui.theme.PhoneboxTheme

/**
 * DIALER 角色要求的拨号入口：DIAL（带或不带 tel:）显示预填号码的简易拨号界面；
 * CALL tel: 不显示界面，直接按“紧急交系统、其余走 SIP”处理。
 */
class DialActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val number = intent.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart.orEmpty()
        // 只认经 CALL_PHONE 保护的别名进来的 CALL；显式点名 DialActivity 的 CALL 当 DIAL 处理，防止绕过权限直接拨出
        if (intent.action == Intent.ACTION_CALL && intent.component?.className == CALL_ALIAS) {
            if (savedInstanceState == null) SipDialer.handleCall(this, number)
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            PhoneboxTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    var input by rememberSaveable { mutableStateOf(number) }
                    Column(
                        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("拨号", style = MaterialTheme.typography.headlineSmall)
                        OutlinedTextField(
                            input, { input = it }, label = { Text("号码") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(enabled = input.isNotBlank(), onClick = {
                            SipDialer.handleCall(this@DialActivity, input)
                            finish()
                        }) { Text("拨打") }
                        Text("普通号码经 NAS 线路拨出；紧急号码一律走本机网络。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = {
                            startActivity(Intent(this@DialActivity, MainActivity::class.java))
                            finish()
                        }) { Text("打开完整拨号盘") }
                    }
                }
            }
        }
    }

    private companion object {
        const val CALL_ALIAS = "top.xingzhenxi.phonebox.roles.CallActivity"
    }
}
