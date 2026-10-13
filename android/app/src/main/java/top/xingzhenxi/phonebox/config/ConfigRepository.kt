package top.xingzhenxi.phonebox.config

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 设置读写。敏感项只以密文写入 DataStore */
class ConfigRepository(
    private val store: DataStore<Preferences>,
    private val cipher: CredentialCipher,
) {
    val config: Flow<AppConfig> = store.data.map(::toConfig)

    /** [sipPassword] 为 null 表示保持原口令不变（编辑设置时口令框留空） */
    suspend fun save(sip: SipConfig, sipPassword: String?, phonebox: PhoneboxConfig, wizardCompleted: Boolean) {
        val encrypted = sipPassword?.let(cipher::encrypt)
        store.edit { p ->
            p[K.sipHost] = sip.host.trim()
            p[K.sipPort] = sip.port
            p[K.sipTransport] = sip.transport.name
            p[K.sipUser] = sip.username.trim()
            p[K.sipFp] = Validation.normalizeFingerprint(sip.tlsFingerprint)
            p[K.sipSrtp] = sip.srtp
            if (encrypted != null) p[K.sipPassEnc] = encrypted
            val newUrl = Validation.normalizeUrl(phonebox.baseUrl)
            // 换了服务器，旧令牌对新服务器无意义，也不该发给它
            if (p[K.pbUrl] != null && p[K.pbUrl] != newUrl) p.remove(K.pbTokenEnc)
            p[K.pbUrl] = newUrl
            p[K.pbFp] = Validation.normalizeFingerprint(phonebox.certFingerprint)
            p[K.wizardDone] = wizardCompleted
        }
    }

    suspend fun setWizardCompleted(done: Boolean) {
        store.edit { it[K.wizardDone] = done }
    }

    suspend fun sipPassword(): String? = store.data.first()[K.sipPassEnc]?.let(cipher::decrypt)

    // TODO(P4)：登录成功后保存服务端下发的会话/设备令牌
    suspend fun saveSessionToken(token: String?) {
        val encrypted = token?.let(cipher::encrypt)
        store.edit { if (encrypted == null) it.remove(K.pbTokenEnc) else it[K.pbTokenEnc] = encrypted }
    }

    suspend fun sessionToken(): String? = store.data.first()[K.pbTokenEnc]?.let(cipher::decrypt)

    private fun toConfig(p: Preferences) = AppConfig(
        sip = SipConfig(
            host = p[K.sipHost].orEmpty(),
            port = p[K.sipPort] ?: 0,
            transport = p[K.sipTransport]?.let { runCatching { SipTransport.valueOf(it) }.getOrNull() }
                ?: SipTransport.TLS,
            username = p[K.sipUser].orEmpty(),
            tlsFingerprint = p[K.sipFp].orEmpty(),
            srtp = p[K.sipSrtp] ?: false,
            hasPassword = p[K.sipPassEnc] != null,
        ),
        phonebox = PhoneboxConfig(
            baseUrl = p[K.pbUrl].orEmpty(),
            certFingerprint = p[K.pbFp].orEmpty(),
            hasSession = p[K.pbTokenEnc] != null,
        ),
        wizardCompleted = p[K.wizardDone] ?: false,
    )

    private object K {
        val sipHost = stringPreferencesKey("sip_host")
        val sipPort = intPreferencesKey("sip_port")
        val sipTransport = stringPreferencesKey("sip_transport")
        val sipUser = stringPreferencesKey("sip_username")
        val sipPassEnc = stringPreferencesKey("sip_password_enc")
        val sipFp = stringPreferencesKey("sip_tls_fingerprint")
        val sipSrtp = booleanPreferencesKey("sip_srtp")
        val pbUrl = stringPreferencesKey("phonebox_base_url")
        val pbFp = stringPreferencesKey("phonebox_cert_fingerprint")
        val pbTokenEnc = stringPreferencesKey("phonebox_token_enc")
        val wizardDone = booleanPreferencesKey("wizard_completed")
    }
}
