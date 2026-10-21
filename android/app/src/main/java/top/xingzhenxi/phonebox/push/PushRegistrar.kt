package top.xingzhenxi.phonebox.push

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.xingzhenxi.phonebox.appGraph
import top.xingzhenxi.phonebox.diag.DiagType
import top.xingzhenxi.phonebox.net.AdminResult

data class PushState(
    val enabled: Boolean = false,
    val provider: String? = null,
    val hasToken: Boolean = false,
    val uploaded: Boolean = false,
    val lastWakeAt: Long = 0,
    val lastError: String? = null,
)

/**
 * 推送开关与 token 的本地记录，并负责把 token 报给 phonebox（失败就在下次启动 / 重新登录时再报）。
 * token 只存本应用私有 prefs，不进日志；它不是机密（只能用来给本机发推送），但也没必要外露。
 */
class PushRegistrar private constructor(private val app: Context) {
    private val prefs = app.getSharedPreferences("push", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<PushState> = _state.asStateFlow()

    fun setEnabled(on: Boolean) {
        val old = prefs.getString(KEY_PROVIDER, null)
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        if (!on) {
            PushChannels.byId(old)?.let { runCatching { it.unregister(app) } }
            prefs.edit().remove(KEY_TOKEN).remove(KEY_PUB).remove(KEY_AUTH).putBoolean(KEY_UPLOADED, false).apply()
            app.appGraph.scope.launch { app.appGraph.phonebox.value?.client?.unregisterPush() }
            log("关闭推送唤醒")
        }
        refresh()
    }

    fun setProvider(id: String) {
        prefs.edit().putString(KEY_PROVIDER, id).apply()
        refresh()
    }

    /** 通道回调：拿到新 token（或 endpoint） */
    fun onToken(provider: String, token: String, pub: String? = null, auth: String? = null) {
        if (!prefs.getBoolean(KEY_ENABLED, false)) return
        prefs.edit().putString(KEY_PROVIDER, provider).putString(KEY_TOKEN, token)
            .putString(KEY_PUB, pub).putString(KEY_AUTH, auth).putBoolean(KEY_UPLOADED, false).apply()
        log("$provider 注册成功，正在上报")
        refresh()
        upload()
    }

    fun onUnregistered(provider: String) {
        if (prefs.getString(KEY_PROVIDER, null) != provider) return
        prefs.edit().remove(KEY_TOKEN).putBoolean(KEY_UPLOADED, false).apply()
        log("$provider 注册失效")
        refresh()
    }

    fun onError(provider: String, reason: String) {
        prefs.edit().putString(KEY_ERROR, "$provider：$reason").apply()
        log("$provider 注册失败：$reason")
        refresh()
    }

    fun recordWake(now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_WAKE, now).apply()
        refresh()
    }

    /** 有 token 没报上去就报；App 启动、重新登录后各调一次 */
    fun upload() {
        val provider = prefs.getString(KEY_PROVIDER, null) ?: return
        val token = prefs.getString(KEY_TOKEN, null) ?: return
        if (!prefs.getBoolean(KEY_ENABLED, false) || prefs.getBoolean(KEY_UPLOADED, false)) return
        val graph = app.appGraph
        graph.scope.launch {
            val client = graph.phonebox.value?.client ?: return@launch
            when (val r = client.registerPush(provider, token, prefs.getString(KEY_PUB, null), prefs.getString(KEY_AUTH, null))) {
                is AdminResult.Ok -> {
                    prefs.edit().putBoolean(KEY_UPLOADED, true).remove(KEY_ERROR).apply()
                    log(if (r.value) "已上报 phonebox" else "已上报，但服务端没配置 $provider 通道")
                }
                AdminResult.Unsupported -> setError("服务端版本太旧，没有推送接口")
                AdminResult.SessionExpired -> setError("phonebox 登录已失效")
                is AdminResult.Rejected -> setError("服务端拒绝：${r.reason}")
                is AdminResult.Failed -> setError("上报失败：${r.message.take(60)}")
            }
            refresh()
        }
    }

    private fun setError(msg: String) {
        prefs.edit().putString(KEY_ERROR, msg).apply()
        log(msg)
    }

    fun refresh() {
        _state.value = read()
    }

    private fun read() = PushState(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        provider = prefs.getString(KEY_PROVIDER, null),
        hasToken = prefs.getString(KEY_TOKEN, null) != null,
        uploaded = prefs.getBoolean(KEY_UPLOADED, false),
        lastWakeAt = prefs.getLong(KEY_WAKE, 0),
        lastError = prefs.getString(KEY_ERROR, null),
    )

    private fun log(msg: String) = app.appGraph.diagLog.log(DiagType.Push, msg)

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_TOKEN = "token"
        private const val KEY_PUB = "pub"
        private const val KEY_AUTH = "auth"
        private const val KEY_UPLOADED = "uploaded"
        private const val KEY_WAKE = "last_wake"
        private const val KEY_ERROR = "last_error"

        @Volatile private var instance: PushRegistrar? = null
        fun get(context: Context): PushRegistrar = instance ?: synchronized(this) {
            instance ?: PushRegistrar(context.applicationContext).also { instance = it }
        }
    }
}
