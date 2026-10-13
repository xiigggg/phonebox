package top.xingzhenxi.phonebox.ui.wizard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.xingzhenxi.phonebox.config.ConfigRepository
import top.xingzhenxi.phonebox.config.PhoneboxConfig
import top.xingzhenxi.phonebox.config.SipConfig
import top.xingzhenxi.phonebox.config.SipTransport
import top.xingzhenxi.phonebox.config.Validation
import top.xingzhenxi.phonebox.net.ConfigTokenStore
import top.xingzhenxi.phonebox.net.Credential
import top.xingzhenxi.phonebox.net.PhoneboxClient
import top.xingzhenxi.phonebox.net.TokenStore
import top.xingzhenxi.phonebox.ui.login.LoginText

enum class WizardStep { Welcome, Sip, Phonebox, Permissions, Done }

/** 表单状态放在 ViewModel 里，旋转、折叠/展开不丢；口令和 PIN 只在内存 */
class WizardViewModel(private val repo: ConfigRepository) : ViewModel() {
    var step by mutableStateOf(WizardStep.Welcome); private set

    var host by mutableStateOf("")
    var port by mutableStateOf("")
    var transport by mutableStateOf(SipTransport.TLS)
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var sipFingerprint by mutableStateOf("")
    var srtp by mutableStateOf(false)
    var baseUrl by mutableStateOf("")
    var pbFingerprint by mutableStateOf("")
    var pin by mutableStateOf("")

    /** 编辑已有配置时口令框可留空表示不改 */
    var hasSavedPassword by mutableStateOf(false); private set
    var errors by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var saving by mutableStateOf(false); private set

    init {
        viewModelScope.launch {
            val c = repo.config.first()
            if (c.sip.host.isNotEmpty()) {
                host = c.sip.host
                port = c.sip.port.takeIf { it > 0 }?.toString().orEmpty()
                transport = c.sip.transport
                username = c.sip.username
                sipFingerprint = c.sip.tlsFingerprint
                srtp = c.sip.srtp
            }
            hasSavedPassword = c.sip.hasPassword
            baseUrl = c.phonebox.baseUrl
            pbFingerprint = c.phonebox.certFingerprint
        }
    }

    /** 向导里登录拿到的凭据先放内存，等“开始使用”保存配置后再写入（换服务器时 save 会清旧令牌） */
    private var pendingCredential: Credential? = null
    var loggingIn by mutableStateOf(false); private set

    fun next() {
        val e = validate(step)
        errors = e
        if (e.isNotEmpty() || loggingIn) return
        if (step == WizardStep.Phonebox && pin.isNotEmpty()) {
            loginThenNext()
            return
        }
        advance()
    }

    private fun advance() {
        step = WizardStep.entries[(step.ordinal + 1).coerceAtMost(WizardStep.entries.lastIndex)]
    }

    private fun loginThenNext() {
        loggingIn = true
        viewModelScope.launch {
            try {
                val mem = object : TokenStore {
                    override suspend fun load() = pendingCredential
                    override suspend fun save(credential: Credential?) { pendingCredential = credential }
                }
                val client = PhoneboxClient(Validation.normalizeUrl(baseUrl), Validation.normalizeFingerprint(pbFingerprint), mem)
                val r = client.login(pin)
                val msg = LoginText.describe(r)
                if (msg != null) {
                    errors = errors + (F_PIN to msg)
                } else {
                    pin = ""
                    advance()
                }
            } finally {
                loggingIn = false
            }
        }
    }

    fun back() {
        errors = emptyMap()
        if (step.ordinal > 0) step = WizardStep.entries[step.ordinal - 1]
    }

    fun clearError(field: String) {
        if (field in errors) errors = errors - field
    }

    internal fun validate(s: WizardStep): Map<String, String> = buildMap {
        when (s) {
            WizardStep.Sip -> {
                Validation.host(host)?.let { put(F_HOST, it) }
                Validation.port(port)?.let { put(F_PORT, it) }
                Validation.extension(username)?.let { put(F_USER, it) }
                if (password.isEmpty() && !hasSavedPassword) put(F_PASS, "请填写口令")
                Validation.fingerprint(sipFingerprint)?.let { put(F_SIP_FP, it) }
            }
            WizardStep.Phonebox -> {
                Validation.httpsUrl(baseUrl)?.let { put(F_URL, it) }
                Validation.fingerprint(pbFingerprint)?.let { put(F_PB_FP, it) }
            }
            else -> Unit
        }
    }

    fun finish(onDone: () -> Unit) {
        if (saving) return
        saving = true
        viewModelScope.launch {
            try {
                repo.save(
                    sip = SipConfig(host.trim(), port.trim().toInt(), transport, username.trim(), sipFingerprint, srtp),
                    sipPassword = password.ifEmpty { null },
                    phonebox = PhoneboxConfig(baseUrl, pbFingerprint),
                    wizardCompleted = true,
                )
                pendingCredential?.let { ConfigTokenStore(repo).save(it) }
                pendingCredential = null
                password = ""
                pin = ""
                onDone()
            } finally {
                saving = false
            }
        }
    }

    companion object {
        const val F_HOST = "host"
        const val F_PORT = "port"
        const val F_USER = "user"
        const val F_PASS = "pass"
        const val F_SIP_FP = "sipFp"
        const val F_URL = "url"
        const val F_PB_FP = "pbFp"
        const val F_PIN = "pin"
    }
}
