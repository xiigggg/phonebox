package top.xingzhenxi.phonebox.telecom

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.xingzhenxi.phonebox.AppGraph
import top.xingzhenxi.phonebox.diag.DiagType
import top.xingzhenxi.phonebox.net.CallItem
import top.xingzhenxi.phonebox.sip.AudioRoute
import top.xingzhenxi.phonebox.sip.CallInfo
import top.xingzhenxi.phonebox.sip.CallState

data class AudioUi(val available: List<AudioRoute>, val current: AudioRoute?)

/**
 * 把 SipEngine 的通话和系统打通：core-telecom 自管通话、来电/通话中通知、前台服务类型切换。
 *
 * 音频路由以 Telecom 为准（与 linphone-android 官方 App 一致）：UI 只调 requestEndpointChange，
 * 再由 currentCallEndpoint 的回调把结果下发给 linphone 的 AudioDevice，linphone 自己不主动切路由，
 * 避免两边各切一次互相覆盖。Telecom 不可用时才直接调 SipEngine.setAudioRoute。
 */
class CallCoordinator(context: Context, private val graph: AppGraph) {
    private val app = context.applicationContext
    private val sip get() = graph.sipEngine
    private val diag get() = graph.diagLog
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val callsManager: CallsManager? = try {
        CallsManager(app).also { it.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE) }
    } catch (e: Exception) {
        graph.diagLog.log(DiagType.Call, "Telecom 注册失败，退化为仅通知模式：${e.javaClass.simpleName}")
        null
    }

    private val controls = HashMap<String, CallControlScope>()
    private val tracked = HashSet<String>()
    private val localEnd = HashSet<String>()
    private var endpoints: List<CallEndpointCompat> = emptyList()
    private var started = false
    private var incomingShown: String? = null
    private var ongoingKey: Pair<String, CallInfo>? = null
    private var statsJob: Job? = null

    private val _audio = MutableStateFlow(DEFAULT_AUDIO)
    val audio: StateFlow<AudioUi> = _audio.asStateFlow()

    fun start() {
        if (started) return
        started = true
        CallNotifications.createChannels(app)
        scope.launch { sip.calls.collect(::onCalls) }
    }

    fun isLive(id: String): Boolean = sip.calls.value.any { it.id == id && it.state !in ENDED }

    fun caller(call: CallInfo): CallerLabel = CallerLabel.parse(call.remoteNumber, call.remoteDisplay, history())

    private fun history(): List<CallItem> = graph.phonebox.value?.feedSync?.state?.value?.feed?.calls.orEmpty()

    // ---- 用户动作 ----

    fun answer(id: String) {
        stopRinging()
        sip.accept(id)
    }

    fun decline(id: String) {
        stopRinging()
        localEnd += id
        sip.decline(id)
    }

    fun hangup(id: String) {
        stopRinging()
        localEnd += id
        sip.hangup(id)
    }

    /** 在后台线程调用：SipEngine.dial 会同步等 Core 线程 */
    suspend fun dial(number: String): String? = withContext(Dispatchers.Default) { sip.dial(number) }

    fun requestRoute(route: AudioRoute) {
        val control = controls.values.firstOrNull()
        val ep = endpoints.firstOrNull { routeOf(it.type) == route }
        scope.launch {
            if (control != null && ep != null) {
                val r = runCatching { control.requestEndpointChange(ep) }.getOrNull()
                if (r is CallControlResult.Success) return@launch
                diag.log(DiagType.Call, "Telecom 切换路由失败，直接切 linphone")
            }
            val ok = withContext(Dispatchers.Default) { sip.setAudioRoute(route) }
            if (ok) _audio.value = _audio.value.copy(current = route)
        }
    }

    // ---- 状态同步 ----

    private fun onCalls(calls: List<CallInfo>) {
        val live = calls.filter { it.state !in ENDED }
        live.filter { it.id !in tracked }.forEach { c ->
            tracked += c.id
            diag.log(DiagType.Call, if (c.incoming) "来电" else "去电")
            addToTelecom(c)
        }

        val incoming = live.firstOrNull { it.state == CallState.Incoming }
        if (incoming == null) stopRinging()
        else if (incomingShown != incoming.id) {
            incomingShown = incoming.id
            val nm = app.getSystemService(android.app.NotificationManager::class.java)
            if (!nm.canUseFullScreenIntent()) diag.log(DiagType.Call, "无全屏通知权限，来电以横幅显示")
            nm.notify(CallNotifications.INCOMING_ID, CallNotifications.incoming(app, incoming, caller(incoming)))
        }

        val active = live.firstOrNull { it.state != CallState.Incoming }
        if (active != null) {
            // 只在影响通知内容的字段变化时重发，避免每次静音切换都重新 startForeground
            val key = active.id to active.copy(muted = false)
            if (ongoingKey != key) {
                ongoingKey = key
                setServiceCall(CallNotifications.ongoing(app, active, caller(active)))
            }
            if (active.state == CallState.Connected && statsJob == null) startStats(active.id)
        } else if (ongoingKey != null) {
            ongoingKey = null
            setServiceCall(null)
        }

        if (live.isEmpty()) {
            statsJob?.cancel()
            statsJob = null
            _audio.value = DEFAULT_AUDIO
            // 已结束的通话不会再出现，只保留存活的 id，防止集合无限长
            tracked.retainAll(calls.map { it.id }.toSet())
            localEnd.retainAll(calls.map { it.id }.toSet())
        }
    }

    private fun stopRinging() {
        if (incomingShown == null) return
        incomingShown = null
        app.getSystemService(android.app.NotificationManager::class.java).cancel(CallNotifications.INCOMING_ID)
    }

    private fun setServiceCall(notification: android.app.Notification?) {
        val ka = graph.keepAlive
        if (ka == null) {
            diag.log(DiagType.Call, "常驻服务未运行，无法提升为通话前台服务")
            return
        }
        ka.setCallNotification(notification)
    }

    /** 每 5 秒在 logcat 打一次收发包统计，便于确认媒体通了；不含号码 */
    private fun startStats(id: String) {
        statsJob = scope.launch {
            while (isActive) {
                delay(5_000)
                val s = withContext(Dispatchers.Default) { sip.audioStats(id) } ?: break
                Log.i(TAG, "audioStats sent=${s.packetsSent} recv=${s.packetsReceived} lost=${s.packetsLost} codec=${s.codec}")
            }
        }
    }

    // ---- core-telecom ----

    private fun addToTelecom(call: CallInfo) {
        val cm = callsManager ?: return
        val id = call.id
        val caller = caller(call)
        val attrs = CallAttributesCompat(
            displayName = listOfNotNull(caller.number.ifEmpty { "未知号码" }, caller.label).joinToString(" "),
            address = Uri.fromParts("tel", call.remoteNumber, null),
            direction = if (call.incoming) CallAttributesCompat.DIRECTION_INCOMING else CallAttributesCompat.DIRECTION_OUTGOING,
            callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE,
        )
        scope.launch {
            try {
                cm.addCall(
                    attrs,
                    onAnswer = { stopRinging(); sip.accept(id) },
                    onDisconnect = { if (isLive(id)) { localEnd += id; sip.hangup(id) } },
                    onSetActive = { sip.setHold(id, false) },
                    onSetInactive = { sip.setHold(id, true) },
                ) {
                    controls[id] = this
                    launch { availableEndpoints.collect { endpoints = it; publishAudio(null) } }
                    launch { currentCallEndpoint.collect { publishAudio(it); applyEndpoint(it) } }
                    launch { followSip(this@addCall, call) }
                }
            } catch (e: CancellationException) {
                // 通话结束时我们主动取消了 CallControlScope，属于正常收尾
                controls.remove(id)
            } catch (e: Exception) {
                controls.remove(id)
                diag.log(DiagType.Call, "Telecom addCall 失败，退化为仅通知模式：${e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun followSip(control: CallControlScope, first: CallInfo) {
        val id = first.id
        var connected = false
        sip.calls.map { l -> l.firstOrNull { it.id == id } }.distinctUntilChangedBy { it?.state }.collect { c ->
            when (c?.state) {
                CallState.Connected -> {
                    val r = if (first.incoming && !connected) control.answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL) else control.setActive()
                    if (r is CallControlResult.Error) diag.log(DiagType.Call, "Telecom 置为活动失败：${r.errorCode}")
                    connected = true
                    // 媒体流此时才建立，按 Telecom 当前端点再下发一次
                    endpoints.firstOrNull { routeOf(it.type) == _audio.value.current }?.let { applyEndpoint(it) }
                }
                CallState.Paused -> control.setInactive()
                CallState.Ended, CallState.Error, null -> {
                    val cause = when {
                        id in localEnd -> if (connected || !first.incoming) DisconnectCause.LOCAL else DisconnectCause.REJECTED
                        first.incoming && !connected -> DisconnectCause.MISSED
                        else -> DisconnectCause.REMOTE
                    }
                    runCatching { control.disconnect(DisconnectCause(cause)) }
                    controls.remove(id)
                    control.cancel()
                }
                else -> Unit
            }
        }
    }

    private fun publishAudio(current: CallEndpointCompat?) {
        val avail = endpoints.mapNotNull { routeOf(it.type) }.distinct()
        _audio.value = AudioUi(
            available = avail.ifEmpty { DEFAULT_AUDIO.available },
            current = current?.let { routeOf(it.type) } ?: _audio.value.current,
        )
    }

    private suspend fun applyEndpoint(ep: CallEndpointCompat) {
        val route = routeOf(ep.type) ?: return
        withContext(Dispatchers.Default) { sip.setAudioRoute(route) }
    }

    private fun routeOf(type: Int): AudioRoute? = when (type) {
        CallEndpointCompat.TYPE_EARPIECE -> AudioRoute.Earpiece
        CallEndpointCompat.TYPE_SPEAKER -> AudioRoute.Speaker
        CallEndpointCompat.TYPE_BLUETOOTH -> AudioRoute.Bluetooth
        CallEndpointCompat.TYPE_WIRED_HEADSET -> AudioRoute.Wired
        else -> null
    }

    companion object {
        private const val TAG = "PB.Call"
        private val ENDED = setOf(CallState.Ended, CallState.Error)
        private val DEFAULT_AUDIO = AudioUi(listOf(AudioRoute.Earpiece, AudioRoute.Speaker), AudioRoute.Earpiece)

        fun inCallIntent(context: Context, id: String): Intent =
            Intent(context, InCallActivity::class.java).putExtra(CallActions.EXTRA_CALL_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
