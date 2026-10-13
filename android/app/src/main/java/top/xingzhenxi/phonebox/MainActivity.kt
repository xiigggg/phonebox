package top.xingzhenxi.phonebox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.xingzhenxi.phonebox.config.AppConfig
import top.xingzhenxi.phonebox.service.KeepAliveService
import top.xingzhenxi.phonebox.ui.main.MainScaffold
import top.xingzhenxi.phonebox.ui.theme.PhoneboxTheme
import top.xingzhenxi.phonebox.ui.wizard.WizardScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = (application as PhoneboxApp).configRepository
        // 打开 App 也是一次可靠的前台时机：此时启动前台服务不受后台限制
        if (savedInstanceState == null) KeepAliveService.ensureRunning(this, "打开应用")
        setContent {
            PhoneboxTheme {
                // 初值 null：在 DataStore 读出来之前不渲染，避免向导和主界面闪一下
                val config: AppConfig? by repo.config.collectAsStateWithLifecycle(initialValue = null)
                var editing by rememberSaveable { mutableStateOf(false) }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    val c = config ?: return@Box
                    if (!c.wizardCompleted || editing) {
                        WizardScreen(
                            onFinished = {
                                editing = false
                                KeepAliveService.ensureRunning(this@MainActivity, "向导完成", reconfigure = true)
                            },
                            onCancel = if (c.wizardCompleted) ({ editing = false }) else null,
                        )
                    } else {
                        MainScaffold(config = c, onEditSettings = { editing = true })
                    }
                }
            }
        }
    }
}
