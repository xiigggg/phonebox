package top.xingzhenxi.phonebox.telecom

import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import top.xingzhenxi.phonebox.appGraph
import top.xingzhenxi.phonebox.sip.AudioRoute
import top.xingzhenxi.phonebox.sip.CallState
import top.xingzhenxi.phonebox.ui.theme.PhoneboxTheme

/** 通话中界面；也是通知“接听”按钮的落点（ACTION_ANSWER） */
class InCallActivity : ComponentActivity() {
    private var callId by mutableStateOf<String?>(null)
    private var proximity: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        enableEdgeToEdge()
        handle(intent, savedInstanceState == null)
        val graph = appGraph
        val coordinator = graph.callCoordinator
        setContent {
            PhoneboxTheme {
                // Surface 提供 onSurface 作为默认文字色，否则深色主题下文字是黑的
                Surface(color = MaterialTheme.colorScheme.background) {
                val calls by graph.sipEngine.calls.collectAsStateWithLifecycle()
                val audio by coordinator.audio.collectAsStateWithLifecycle()
                val id = callId ?: calls.firstOrNull { it.state !in ENDED }?.id
                val call = calls.firstOrNull { it.id == id }
                val ended = call == null || call.state in ENDED
                LaunchedEffect(ended) {
                    if (ended) {
                        // 留一秒显示“通话结束”再关
                        delay(1000)
                        finish()
                    }
                }
                val earpiece = !ended && audio.current == AudioRoute.Earpiece && call?.state != CallState.Incoming
                LaunchedEffect(earpiece) { setProximity(earpiece) }
                InCallScreen(
                    call = call,
                    caller = call?.let(coordinator::caller) ?: CallerLabel("", null, top.xingzhenxi.phonebox.ui.theme.RiskLevel.Unknown),
                    audio = audio,
                    onMute = { m -> id?.let { graph.sipEngine.setMuted(it, m) } },
                    onHold = { h -> id?.let { graph.sipEngine.setHold(it, h) } },
                    onRoute = coordinator::requestRoute,
                    onDtmf = { c -> id?.let { graph.sipEngine.sendDtmf(it, c) } },
                    onHangup = { id?.let(coordinator::hangup) },
                )
            }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent, true)
    }

    private fun handle(intent: Intent, fresh: Boolean) {
        val id = intent.getStringExtra(CallActions.EXTRA_CALL_ID)
        if (id != null) callId = id
        // 只在首次投递时执行接听，旋转重建不重复接听；id 必须对应仍在振铃的通话
        if (fresh && intent.action == CallActions.ACTION_ANSWER && id != null) {
            val c = appGraph.sipEngine.calls.value.firstOrNull { it.id == id }
            if (c?.state == CallState.Incoming) appGraph.callCoordinator.answer(id)
        }
    }

    /** 听筒模式下贴脸灭屏，防止脸颊误触 */
    private fun setProximity(on: Boolean) {
        if (on) {
            if (proximity?.isHeld == true) return
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
            proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "phonebox:incall").apply { acquire(4 * 60 * 60_000L) }
        } else {
            proximity?.takeIf { it.isHeld }?.release()
            proximity = null
        }
    }

    override fun onDestroy() {
        setProximity(false)
        super.onDestroy()
    }

    private companion object {
        val ENDED = setOf(CallState.Ended, CallState.Error)
    }
}
