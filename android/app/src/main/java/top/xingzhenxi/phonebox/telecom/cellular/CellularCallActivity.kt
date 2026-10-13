package top.xingzhenxi.phonebox.telecom.cellular

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telecom.Call
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import top.xingzhenxi.phonebox.ui.theme.PhoneboxTheme

/** 蜂窝通话的最小界面：号码、接听、拒接、挂断、静音、扬声器 */
class CellularCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent {
            PhoneboxTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val ui by CellularInCallService.ui.collectAsStateWithLifecycle()
                    val gone = ui == null || ui?.ended == true
                    LaunchedEffect(gone) {
                        if (gone) { delay(800); finish() }
                    }
                    val c = ui
                    Column(
                        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Spacer(Modifier.height(48.dp))
                        Text("本机卡", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(c?.number?.ifEmpty { "未知号码" } ?: "", style = MaterialTheme.typography.headlineLarge)
                        Text(stateText(c), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        if (c != null && !c.ended) {
                            if (c.ringing) {
                                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                    Button(onClick = CellularInCallService::reject, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD93025))) { Text("拒接") }
                                    Button(onClick = CellularInCallService::answer, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E8E3E))) { Text("接听") }
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    FilterChip(c.muted, { CellularInCallService.setMuted(!c.muted) }, label = { Text("静音") })
                                    FilterChip(c.speaker, { CellularInCallService.setSpeaker(!c.speaker) }, label = { Text("扬声器") })
                                }
                                FilledTonalButton(onClick = CellularInCallService::hangup, colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFFD93025), contentColor = Color.White)) { Text("挂断") }
                            }
                        }
                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_ANSWER, false) && CellularInCallService.ui.value?.ringing == true) CellularInCallService.answer()
    }

    private fun stateText(c: CellularCallUi?) = when (c?.state) {
        null, Call.STATE_DISCONNECTED -> "通话结束"
        Call.STATE_RINGING -> "来电"
        Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_NEW -> "正在呼叫…"
        Call.STATE_ACTIVE -> "通话中"
        Call.STATE_HOLDING -> "保持中"
        else -> ""
    }

    companion object {
        const val EXTRA_ANSWER = "answer"
        fun intent(context: Context): Intent = Intent(context, CellularCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
