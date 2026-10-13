package top.xingzhenxi.phonebox.sip

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.linphone.core.Account
import org.linphone.core.AudioDevice
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.LogLevel
import org.linphone.core.LoggingServiceListener
import org.linphone.core.MediaEncryption
import org.linphone.core.Reason
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType
import org.linphone.core.tools.AndroidPlatformHelper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * liblinphone 封装。linphone 要求所有调用在同一线程：Core 在专用 HandlerThread 上创建，
 * 自动 iterate 也挂在这个 Looper 上；对外方法一律转投到该线程执行。
 */
class SipEngine(
    context: Context,
    private val emergency: EmergencyChecker = TelephonyEmergencyChecker(context),
) {
    private val appContext = context.applicationContext
    private val _registration = MutableStateFlow<RegState>(RegState.None)
    val registration: StateFlow<RegState> = _registration.asStateFlow()
    private val _calls = MutableStateFlow<List<CallInfo>>(emptyList())
    val calls: StateFlow<List<CallInfo>> = _calls.asStateFlow()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var core: Core? = null
    // TLS 预握手是阻塞网络操作，不能占用 Core 线程
    private val pinExecutor = Executors.newSingleThreadExecutor { Thread(it, "PB.SipPin") }
    @Volatile private var accountGeneration = 0
    private var currentCfg: SipAccountConfig? = null
    private val startedAt = HashMap<String, Long>()
    // 已结束但尚未 Released 的通话，保留一帧 Ended/Error 状态给上层
    private val ended = LinkedHashMap<String, CallInfo>()
    private var fileAudio: Pair<Boolean, String?> = false to null

    fun start() {
        if (thread != null) return
        val t = HandlerThread("PB.SipCore").also { it.start() }
        thread = t
        handler = Handler(t.looper)
        runSync { createCore() }
    }

    fun stop() {
        val h = handler ?: return
        runSync {
            core?.let { c ->
                c.removeListener(listener)
                c.terminateAllCalls()
                c.stop()
            }
            core = null
        }
        h.looper.quitSafely()
        thread = null
        handler = null
        _registration.value = RegState.None
        _calls.value = emptyList()
    }

    fun applyAccount(cfg: SipAccountConfig) {
        val gen = ++accountGeneration
        post { clearAccount(); currentCfg = cfg; _registration.value = RegState.Progress }
        val pin = cfg.certSha256?.takeIf { it.isNotBlank() }
        if (cfg.transport == SipTransport.TLS && pin != null) {
            if (CertPin.normalize(pin) == null) {
                post { _registration.value = RegState.Failed("证书指纹格式错误") }
                return
            }
            pinExecutor.execute {
                val result = runCatching { CertPin.fetchPinnedPem(cfg.host, cfg.port, pin) }
                post {
                    if (gen != accountGeneration) return@post
                    result.fold(
                        onSuccess = { pem -> addAccount(cfg, pem) },
                        onFailure = { e ->
                            val reason = if (e is CertPin.PinMismatchException) "证书指纹不匹配"
                            else "TLS 连接失败：${e.javaClass.simpleName}"
                            Log.w(TAG, "TLS 预握手失败：$reason")
                            _registration.value = RegState.Failed(reason)
                        },
                    )
                }
            }
        } else {
            post { if (gen == accountGeneration) addAccount(cfg, null) }
        }
    }

    fun clearAccount() = post {
        val c = core ?: return@post
        c.clearAccounts()
        c.clearAllAuthInfo()
        currentCfg = null
        _registration.value = RegState.Cleared
    }

    fun refreshRegistration() = post { core?.defaultAccount?.refreshRegister() }

    fun onNetworkReachable(reachable: Boolean) = post { core?.isNetworkReachable = reachable }

    /**
     * 告诉 linphone“已有前台服务托管 Core”。否则它在第一通电话时会自己去启动 CoreService
     * （我们没有声明，也不需要），日志里报 POST_NOTIFICATIONS / 启动失败。
     */
    fun setHostedByForegroundService(hosted: Boolean) = post {
        if (!AndroidPlatformHelper.isReady()) return@post
        AndroidPlatformHelper.instance().setServiceRunning(hosted)
        AndroidPlatformHelper.instance().setServiceRunningAsForeground(hosted)
    }

    /** 返回 call id；空号、紧急号码、未注册账号时返回 null */
    fun dial(number: String): String? {
        val n = DialPolicy.prepare(number, emergency)
        if (n == null) {
            Log.w(TAG, "拒绝经 SIP 拨出：空号或紧急号码")
            return null
        }
        return runSync {
            val c = core ?: return@runSync null
            val cfg = currentCfg ?: return@runSync null
            val acc = c.defaultAccount ?: return@runSync null
            val addr = c.interpretUrl("sip:$n@${cfg.host}", false) ?: return@runSync null
            val params = c.createCallParams(null) ?: return@runSync null
            params.account = acc
            params.isVideoEnabled = false
            c.inviteAddressWithParams(addr, params)?.let { idOf(it) }
        }
    }

    fun accept(id: String) = withCall(id) { call ->
        val params = core?.createCallParams(call)
        params?.isVideoEnabled = false
        if (params != null) call.acceptWithParams(params) else call.accept()
    }

    fun decline(id: String) = withCall(id) { it.decline(Reason.Declined) }
    fun hangup(id: String) = withCall(id) { it.terminate() }
    fun setMuted(id: String, muted: Boolean) = withCall(id) { it.microphoneMuted = muted; publishCalls() }
    fun setHold(id: String, hold: Boolean) = withCall(id) { if (hold) it.pause() else it.resume() }
    fun sendDtmf(id: String, digit: Char) = withCall(id) {
        if (digit in "0123456789*#ABCD") it.sendDtmf(digit)
    }

    /** 找不到对应设备时返回 false（如未连接蓝牙） */
    fun setAudioRoute(route: AudioRoute): Boolean = runSync {
        val c = core ?: return@runSync false
        val types = when (route) {
            AudioRoute.Earpiece -> listOf(AudioDevice.Type.Earpiece)
            AudioRoute.Speaker -> listOf(AudioDevice.Type.Speaker)
            AudioRoute.Bluetooth -> listOf(AudioDevice.Type.Bluetooth, AudioDevice.Type.HearingAid)
            AudioRoute.Wired -> listOf(AudioDevice.Type.Headset, AudioDevice.Type.Headphones, AudioDevice.Type.GenericUsb)
        }
        val devices = c.audioDevices
        val out = devices.firstOrNull { it.type in types && it.hasCapability(AudioDevice.Capabilities.CapabilityPlay) }
            ?: return@runSync false
        // 蓝牙/耳机同时切麦克风，扬声器/听筒用内置麦克风
        val input = devices.firstOrNull { it.type in types && it.hasCapability(AudioDevice.Capabilities.CapabilityRecord) }
            ?: devices.firstOrNull { it.type == AudioDevice.Type.Microphone }
        c.calls.forEach { call ->
            call.outputAudioDevice = out
            input?.let { call.inputAudioDevice = it }
        }
        c.defaultOutputAudioDevice = out
        input?.let { c.defaultInputAudioDevice = it }
        true
    } ?: false

    fun audioStats(id: String): AudioStats? = runSync {
        val call = findCall(id) ?: return@runSync null
        val s = call.audioStats ?: return@runSync null
        AudioStats(
            packetsSent = s.rtpPacketSent,
            packetsReceived = s.rtpPacketRecv,
            packetsLost = s.rtpCumPacketLoss,
            uploadKbps = s.uploadBandwidth,
            downloadKbps = s.downloadBandwidth,
            codec = call.currentParams.usedAudioPayloadType?.mimeType,
        )
    }

    /**
     * 用文件代替声卡（模拟器无音频设备时的测试/诊断用途）。playFile 为 wav 路径，
     * 为 null 时发静音。
     */
    fun setFileAudio(enabled: Boolean, playFile: String?) {
        fileAudio = enabled to playFile
        post { applyFileAudio() }
    }

    // ---- 以下都在 Core 线程上执行 ----

    private fun createCore() {
        val factory = Factory.instance()
        factory.setDebugMode(false, TAG)
        factory.loggingService.setLogLevel(LogLevel.Warning)
        factory.loggingService.addListener(logListener)
        // 不给配置文件路径：账号和口令不落盘到 linphonerc，每次由上层从加密存储重新 applyAccount
        val c = factory.createCore(null, null, appContext)
        c.isAutoIterateEnabled = true
        c.isVideoCaptureEnabled = false
        c.isVideoDisplayEnabled = false
        c.videoActivationPolicy = c.videoActivationPolicy.apply {
            automaticallyAccept = false
            automaticallyInitiate = false
        }
        c.isIpv6Enabled = false
        c.isLimeX3DhEnabled = false
        c.isPushNotificationEnabled = false
        c.isFriendListSubscriptionEnabled = false
        c.isConferenceServerEnabled = false
        c.isRecordAwareEnabled = false
        c.isKeepAliveEnabled = true
        c.maxCalls = 2
        c.setUserAgent("Phonebox-Android", null)
        // 输入输出路由由我们和 core-telecom 控制，不让 linphone 自己播铃声
        c.isNativeRingingEnabled = false
        c.ring = null
        c.setUseRfc2833ForDtmf(true)
        c.setUseInfoForDtmf(false)
        c.transports = c.transports.apply { udpPort = -1; tcpPort = -1; tlsPort = -1 }
        c.audioPayloadTypes.forEach { pt ->
            pt.enable(pt.mimeType.lowercase() in setOf("opus", "pcmu", "pcma", "telephone-event"))
        }
        c.addListener(listener)
        applyFileAudio(c)
        c.start()
        core = c
    }

    private fun applyFileAudio(c: Core? = core) {
        c ?: return
        val (on, file) = fileAudio
        c.useFiles = on
        c.playFile = file
    }

    private fun addAccount(cfg: SipAccountConfig, pinnedPem: String?) {
        val c = core ?: return
        c.clearAccounts()
        c.clearAllAuthInfo()
        currentCfg = cfg
        if (cfg.transport == SipTransport.TLS) {
            if (pinnedPem != null) {
                c.setRootCaData(pinnedPem)
                c.verifyServerCertificates(true)
                // 指纹固定已精确锁定这张证书，CN/SAN 不再额外要求（NAS 证书未必覆盖所用 IP/域名）
                c.verifyServerCn(false)
            } else {
                c.setRootCaData(null)
                c.verifyServerCertificates(true)
                c.verifyServerCn(true)
            }
        }
        c.mediaEncryption = if (cfg.srtp) MediaEncryption.SRTP else MediaEncryption.None
        c.isMediaEncryptionMandatory = false

        val factory = Factory.instance()
        val transport = when (cfg.transport) {
            SipTransport.UDP -> TransportType.Udp
            SipTransport.TCP -> TransportType.Tcp
            SipTransport.TLS -> TransportType.Tls
        }
        val server = factory.createAddress("sip:${cfg.host}:${cfg.port}")
        val identity = factory.createAddress("sip:${cfg.username}@${cfg.host}")
        if (server == null || identity == null) {
            _registration.value = RegState.Failed("服务器地址无效")
            return
        }
        server.transport = transport
        val params = c.createAccountParams().apply {
            identityAddress = identity
            serverAddress = server
            // 显式路由经服务器，避免按号码域名去解析
            setRoutesAddresses(arrayOf(server))
            expires = 600
            isRegisterEnabled = true
            pushNotificationAllowed = false
            remotePushNotificationAllowed = false
        }
        val auth = factory.createAuthInfo(cfg.username, null, cfg.password, null, null, cfg.host)
        c.addAuthInfo(auth)
        val account = c.createAccount(params)
        c.addAccount(account)
        c.defaultAccount = account
    }

    private val listener = object : CoreListenerStub() {
        override fun onAccountRegistrationStateChanged(core: Core, account: Account, state: RegistrationState?, message: String) {
            if (account != core.defaultAccount) return
            _registration.value = when (state) {
                RegistrationState.Progress, RegistrationState.Refreshing -> RegState.Progress
                RegistrationState.Ok -> RegState.Ok
                RegistrationState.Failed -> RegState.Failed(
                    account.errorInfo?.phrase?.takeIf { it.isNotBlank() } ?: message.ifBlank { "注册失败" },
                )
                RegistrationState.Cleared -> RegState.Cleared
                else -> RegState.None
            }
        }

        override fun onCallStateChanged(core: Core, call: Call, state: Call.State?, message: String) {
            val id = idOf(call)
            when (state) {
                Call.State.Connected, Call.State.StreamsRunning -> startedAt.putIfAbsent(id, System.currentTimeMillis())
                Call.State.End, Call.State.Error -> {
                    ended[id] = toInfo(call, id)
                    // Released 可能迟迟不来（native 对象仍被引用），终态只保留片刻给上层展示
                    handler?.postDelayed({ ended.remove(id); startedAt.remove(id); publishCalls() }, ENDED_LINGER_MS)
                }
                Call.State.Released -> { ended.remove(id); startedAt.remove(id) }
                else -> Unit
            }
            publishCalls()
        }
    }

    private val logListener = LoggingServiceListener { _, _, level, message ->
        when (level) {
            LogLevel.Error, LogLevel.Fatal -> Log.e(TAG, message)
            LogLevel.Warning -> Log.w(TAG, message)
            else -> Unit
        }
    }

    private fun publishCalls() {
        val c = core
        val live = c?.calls.orEmpty()
            .filter { it.state !in TERMINAL }
            .map { toInfo(it, idOf(it)) }
            .associateBy { it.id }
        _calls.value = (ended + live.filterKeys { it !in ended }).values.toList()
    }

    private fun toInfo(call: Call, id: String): CallInfo {
        val remote = call.remoteAddress
        val state = mapState(call.state)
        return CallInfo(
            id = id,
            remoteNumber = remote.username.orEmpty(),
            remoteDisplay = remote.displayName?.takeIf { it.isNotBlank() },
            incoming = call.dir == Call.Dir.Incoming,
            state = state,
            muted = call.microphoneMuted,
            onHold = call.state == Call.State.Paused || call.state == Call.State.Pausing,
            startedAtMillis = startedAt[id],
        )
    }

    private fun mapState(s: Call.State): CallState = when (s) {
        Call.State.IncomingReceived, Call.State.IncomingEarlyMedia, Call.State.PushIncomingReceived -> CallState.Incoming
        Call.State.OutgoingInit, Call.State.OutgoingProgress -> CallState.OutgoingInit
        Call.State.OutgoingRinging, Call.State.OutgoingEarlyMedia -> CallState.OutgoingRinging
        Call.State.Pausing, Call.State.Paused, Call.State.PausedByRemote -> CallState.Paused
        Call.State.End, Call.State.Released -> CallState.Ended
        Call.State.Error -> CallState.Error
        else -> CallState.Connected
    }

    // 去电刚发起时 SIP Call-ID 可能还没生成，按它取 id 会中途变化，导致上层把同一通电话当成两通、
    // 挂断时也找不到；所以首次见到就生成一个本地 id 挂在 userData 上，之后一直用它
    private fun idOf(call: Call): String =
        call.userData as? String ?: java.util.UUID.randomUUID().toString().also { call.userData = it }

    private fun findCall(id: String): Call? = core?.calls?.firstOrNull { idOf(it) == id }

    private fun withCall(id: String, block: (Call) -> Unit) = post {
        findCall(id)?.let(block) ?: Log.w(TAG, "找不到通话 $id")
    }

    private fun post(block: () -> Unit) {
        val h = handler ?: return
        if (Looper.myLooper() == h.looper) block() else h.post(block)
    }

    /** 在 Core 线程同步执行并取回结果；超时返回 null，避免调用方（含主线程）无限阻塞 */
    private fun <T> runSync(block: () -> T): T? {
        val h = handler ?: return null
        if (Looper.myLooper() == h.looper) return block()
        var result: T? = null
        val latch = CountDownLatch(1)
        h.post { try { result = block() } finally { latch.countDown() } }
        return if (latch.await(5, TimeUnit.SECONDS)) result else null
    }

    companion object {
        const val TAG = "PB.Sip"
        private const val ENDED_LINGER_MS = 1500L
        private val TERMINAL = setOf(Call.State.End, Call.State.Error, Call.State.Released)
    }
}
