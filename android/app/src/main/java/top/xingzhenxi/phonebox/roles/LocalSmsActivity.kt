package top.xingzhenxi.phonebox.roles

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.LaunchedEffect
import top.xingzhenxi.phonebox.ui.theme.PhoneboxTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LocalSms(val id: Long, val address: String, val body: String, val date: Long, val incoming: Boolean)

object LocalSmsStore {
    fun canRead(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** 读系统短信库（收件+发件），最新在前；没有 READ_SMS（不是默认短信应用）时返回空 */
    fun load(context: Context, limit: Int = 200): List<LocalSms> {
        if (!canRead(context)) return emptyList()
        val out = ArrayList<LocalSms>()
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                null, null, "${Telephony.Sms.DATE} DESC",
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    out += LocalSms(
                        c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getLong(3),
                        c.getInt(4) == Telephony.Sms.MESSAGE_TYPE_INBOX,
                    )
                }
            }
        }
        return out
    }
}

/** “本机卡”分组：系统短信库里的短信，只读展示 */
@Composable
fun LocalSimSection(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf<List<LocalSms>?>(null) }
    var version by remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { version++ }
        }
        ctx.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, obs)
        onDispose { ctx.contentResolver.unregisterContentObserver(obs) }
    }
    LaunchedEffect(version) { items = withContext(Dispatchers.IO) { LocalSmsStore.load(ctx) } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("本机卡", style = MaterialTheme.typography.titleMedium)
        val list = items
        when {
            !LocalSmsStore.canRead(ctx) -> Text("需要设为默认短信应用后才能读取本机卡短信", color = MaterialTheme.colorScheme.onSurfaceVariant)
            list == null -> Text("读取中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            list.isEmpty() -> Text("没有本机卡短信", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> LazyColumn(Modifier.fillMaxWidth()) {
                items(list, key = { it.id }) { s ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Text((if (s.incoming) "" else "发往 ") + s.address, style = MaterialTheme.typography.titleSmall)
                        Text(FMT.format(Date(s.date)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(s.body, style = MaterialTheme.typography.bodyMedium)
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

private val FMT = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

/** 本机卡短信的独立入口（通知点开、设置页按钮） */
class LocalSmsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhoneboxTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    LocalSimSection(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp))
                }
            }
        }
    }
}
