package top.xingzhenxi.phonebox.roles

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.xingzhenxi.phonebox.config.AesGcmCredentialCipher

/**
 * 默认应用相关的少量设置。用 SharedPreferences 而不是 DataStore：
 * 短信广播接收器要同步读写（防重放的时间戳必须在 onReceive 内落盘）。
 * wake_key 只存 Keystore 加密后的密文。
 */
class RoleSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("roles", Context.MODE_PRIVATE)
    private val cipher by lazy { AesGcmCredentialCipher.keystore() }

    private val _redirectEnabled = MutableStateFlow(prefs.getBoolean(K_REDIRECT, true))
    val redirectEnabled: StateFlow<Boolean> = _redirectEnabled.asStateFlow()

    private val _hasWakeKey = MutableStateFlow(prefs.contains(K_WAKE_KEY))
    val hasWakeKey: StateFlow<Boolean> = _hasWakeKey.asStateFlow()

    fun setRedirectEnabled(on: Boolean) {
        prefs.edit().putBoolean(K_REDIRECT, on).apply()
        _redirectEnabled.value = on
    }

    /** 空串表示清除 */
    fun setWakeKey(key: String) {
        val k = key.trim()
        if (k.isEmpty()) prefs.edit().remove(K_WAKE_KEY).apply()
        else prefs.edit().putString(K_WAKE_KEY, cipher.encrypt(k)).apply()
        _hasWakeKey.value = k.isNotEmpty()
    }

    fun wakeKey(): String? = prefs.getString(K_WAKE_KEY, null)?.let(cipher::decrypt)

    fun lastWakeTs(): Long = prefs.getLong(K_LAST_WAKE, 0)

    fun setLastWakeTs(ts: Long) {
        // commit 而非 apply：接收器返回后进程可能立刻被杀，重放保护不能丢
        prefs.edit().putLong(K_LAST_WAKE, ts).commit()
    }

    private companion object {
        const val K_REDIRECT = "redirect_enabled"
        const val K_WAKE_KEY = "wake_key_enc"
        const val K_LAST_WAKE = "wake_last_ts"
    }
}

private object RoleSettingsHolder {
    @Volatile var instance: RoleSettings? = null
}

/** 进程内单例；不放进 AppGraph，免得和其他任务同时改它 */
val Context.roleSettings: RoleSettings
    get() = RoleSettingsHolder.instance ?: synchronized(RoleSettingsHolder) {
        RoleSettingsHolder.instance ?: RoleSettings(this).also { RoleSettingsHolder.instance = it }
    }
