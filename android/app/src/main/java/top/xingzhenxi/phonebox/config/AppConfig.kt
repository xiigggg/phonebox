package top.xingzhenxi.phonebox.config

enum class SipTransport { UDP, TCP, TLS }

/**
 * 非敏感配置的快照。口令和会话令牌不放在这里，需要时经 [ConfigRepository] 现解密，
 * 避免随 toString、状态保存或日志意外泄露。
 */
data class SipConfig(
    val host: String = "",
    val port: Int = 0,
    val transport: SipTransport = SipTransport.TLS,
    val username: String = "",
    /** 小写 64 位 hex，空串表示不固定证书 */
    val tlsFingerprint: String = "",
    val srtp: Boolean = false,
    val hasPassword: Boolean = false,
)

data class PhoneboxConfig(
    val baseUrl: String = "",
    val certFingerprint: String = "",
    val hasSession: Boolean = false,
)

data class AppConfig(
    val sip: SipConfig = SipConfig(),
    val phonebox: PhoneboxConfig = PhoneboxConfig(),
    val wizardCompleted: Boolean = false,
)
