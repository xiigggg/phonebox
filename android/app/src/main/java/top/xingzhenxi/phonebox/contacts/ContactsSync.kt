package top.xingzhenxi.phonebox.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.xingzhenxi.phonebox.appGraph
import top.xingzhenxi.phonebox.diag.DiagLog
import top.xingzhenxi.phonebox.diag.DiagType
import top.xingzhenxi.phonebox.net.AdminResult
import top.xingzhenxi.phonebox.net.PhoneboxClient
import java.util.concurrent.TimeUnit

/**
 * 通讯录号码哈希上传：开关打开时传一次，之后每天最多一次，或通讯录变化（去抖）后重传。
 * 只上传 sha256，号码本身不出手机。
 */
class ContactsSync(
    context: Context,
    private val scope: CoroutineScope,
    private val diag: DiagLog,
    private val client: () -> PhoneboxClient?,
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("contacts_sync", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var debounce: Job? = null
    private var observer: ContentObserver? = null

    private val _enabled = MutableStateFlow(prefs.getBoolean(K_ON, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _status = MutableStateFlow(statusText())
    val status: StateFlow<String> = _status.asStateFlow()

    fun init() {
        if (_enabled.value) {
            registerObserver()
            ContactsWorker.schedule(app)
        }
    }

    /** 调用前 UI 已申请好 READ_CONTACTS */
    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(K_ON, on).apply()
        _enabled.value = on
        if (on) {
            registerObserver()
            ContactsWorker.schedule(app)
            scope.launch { upload(force = true) }
        } else {
            observer?.let { app.contentResolver.unregisterContentObserver(it) }
            observer = null
            WorkManager.getInstance(app).cancelUniqueWork(ContactsWorker.NAME)
        }
    }

    /** [force] 为 false 时距上次成功不足一天就跳过 */
    suspend fun upload(force: Boolean): Boolean = mutex.withLock {
        if (!_enabled.value) return false
        if (!force && System.currentTimeMillis() - prefs.getLong(K_LAST, 0) < DAY_MS) return true
        if (app.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            diag.log(DiagType.Contacts, "没有通讯录权限，跳过上传")
            return false
        }
        val c = client() ?: return false
        val hashes = try {
            ContactHash.hashAll(readNumbers())
        } catch (e: Exception) {
            diag.log(DiagType.Contacts, "读取通讯录失败：${e.javaClass.simpleName}")
            return false
        }
        return when (val r = c.uploadContacts(hashes)) {
            is AdminResult.Ok -> {
                prefs.edit().putLong(K_LAST, System.currentTimeMillis()).putInt(K_COUNT, r.value).apply()
                _status.value = statusText()
                diag.log(DiagType.Contacts, "通讯录哈希已上传 ${r.value} 条")
                true
            }
            AdminResult.Unsupported -> { _status.value = "NAS 补丁未安装"; diag.log(DiagType.Contacts, "服务端不支持 /api/contacts"); false }
            AdminResult.SessionExpired -> { _status.value = "需要重新登录 phonebox"; false }
            is AdminResult.Rejected -> { diag.log(DiagType.Contacts, "上传被拒：${r.reason}"); false }
            is AdminResult.Failed -> { diag.log(DiagType.Contacts, "上传失败：${r.message}"); false }
        }
    }

    private fun readNumbers(): List<String> {
        val out = ArrayList<String>()
        app.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null,
        )?.use { cur -> while (cur.moveToNext()) cur.getString(0)?.let(out::add) }
        return out
    }

    private fun registerObserver() {
        if (observer != null) return
        if (app.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                // 同步账号时会连发一串变更，攒一会儿再传
                debounce?.cancel()
                debounce = scope.launch { delay(DEBOUNCE_MS); upload(force = true) }
            }
        }
        app.contentResolver.registerContentObserver(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, true, o)
        observer = o
    }

    private fun statusText(): String {
        val last = prefs.getLong(K_LAST, 0)
        if (last == 0L) return "尚未上传"
        val t = java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(last))
        return "上次上传 $t，共 ${prefs.getInt(K_COUNT, 0)} 条"
    }

    private companion object {
        const val K_ON = "enabled"
        const val K_LAST = "last_upload"
        const val K_COUNT = "count"
        const val DAY_MS = 24 * 3600_000L
        const val DEBOUNCE_MS = 30_000L
    }
}

class ContactsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        applicationContext.appGraph.contactsSync.upload(force = false)
        return Result.success()
    }

    companion object {
        const val NAME = "contacts-upload"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<ContactsWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
