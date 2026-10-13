package top.xingzhenxi.phonebox.telecom

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.xingzhenxi.phonebox.appGraph
import top.xingzhenxi.phonebox.sip.CallState
import top.xingzhenxi.phonebox.ui.theme.PhoneboxTheme

/** 锁屏全屏来电界面；通话不再处于振铃（接听、拒接、对方取消）就自行关闭 */
class IncomingCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge()
        val graph = appGraph
        val id = intent.getStringExtra(CallActions.EXTRA_CALL_ID)
        if (id == null) { finish(); return }
        setContent {
            PhoneboxTheme {
                // Surface 提供 onSurface 作为默认文字色，否则深色主题下文字是黑的
                Surface(color = MaterialTheme.colorScheme.background) {
                val calls by graph.sipEngine.calls.collectAsStateWithLifecycle()
                val call = calls.firstOrNull { it.id == id }
                LaunchedEffect(call?.state) { if (call?.state != CallState.Incoming) finish() }
                if (call != null) {
                    IncomingScreen(
                        caller = graph.callCoordinator.caller(call),
                        onAnswer = {
                            startActivity(CallCoordinator.inCallIntent(this, id).setAction(CallActions.ACTION_ANSWER))
                            finish()
                        },
                        onDecline = { graph.callCoordinator.decline(id); finish() },
                    )
                }
            }
            }
        }
    }
}
