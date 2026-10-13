package top.xingzhenxi.phonebox.sip

// sip 包对外的类型：不含任何 linphone 类型，供 telecom/UI/服务层使用

enum class SipTransport { UDP, TCP, TLS }

data class SipAccountConfig(
    val host: String,
    val port: Int,
    val transport: SipTransport,
    val username: String,
    val password: String,
    /** 服务器证书 SHA-256 指纹（冒号分隔或纯 hex 均可）；为空时 TLS 走系统 CA 校验 */
    val certSha256: String? = null,
    val srtp: Boolean = false,
) {
    // 防止口令随 data class 默认 toString 进日志
    override fun toString(): String =
        "SipAccountConfig(host=$host, port=$port, transport=$transport, username=$username, pinned=${certSha256 != null}, srtp=$srtp)"
}

sealed interface RegState {
    data object None : RegState
    data object Progress : RegState
    data object Ok : RegState
    data class Failed(val reason: String) : RegState
    data object Cleared : RegState
}

enum class CallState { Incoming, OutgoingInit, OutgoingRinging, Connected, Paused, Ended, Error }

data class CallInfo(
    val id: String,
    val remoteNumber: String,
    val remoteDisplay: String?,
    val incoming: Boolean,
    val state: CallState,
    val muted: Boolean,
    val onHold: Boolean,
    val startedAtMillis: Long?,
)

enum class AudioRoute { Earpiece, Speaker, Bluetooth, Wired }

data class AudioStats(
    val packetsSent: Int,
    val packetsReceived: Int,
    val packetsLost: Int,
    /** kbit/s */
    val uploadKbps: Float,
    val downloadKbps: Float,
    val codec: String?,
)
