package top.xingzhenxi.phonebox

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import top.xingzhenxi.phonebox.config.ConfigRepository
import top.xingzhenxi.phonebox.contacts.ContactsSync
import top.xingzhenxi.phonebox.update.UpdateManager
import top.xingzhenxi.phonebox.update.UpdateWorker
import top.xingzhenxi.phonebox.config.PhoneboxConfig
import top.xingzhenxi.phonebox.data.FeedCache
import top.xingzhenxi.phonebox.data.FeedSync
import top.xingzhenxi.phonebox.data.NewItem
import top.xingzhenxi.phonebox.notify.ReadStore
import top.xingzhenxi.phonebox.notify.SmsNotifier
import top.xingzhenxi.phonebox.diag.DiagLog
import top.xingzhenxi.phonebox.diag.DiagType
import top.xingzhenxi.phonebox.diag.UptimeStore
import top.xingzhenxi.phonebox.net.ConfigTokenStore
import top.xingzhenxi.phonebox.net.PhoneboxClient
import top.xingzhenxi.phonebox.service.HeartbeatStore
import top.xingzhenxi.phonebox.shizuku.GuardianController
import top.xingzhenxi.phonebox.service.KeepAliveControl
import top.xingzhenxi.phonebox.sip.SipAccountConfig
import top.xingzhenxi.phonebox.sip.SipEngine
import top.xingzhenxi.phonebox.sip.SipTransport
import top.xingzhenxi.phonebox.telecom.CallCoordinator
import java.io.File

/** 进程级单例的手工依赖注入。UI、服务、通知都从这里取，保证全进程只有一个 SipEngine/FeedSync */
class AppGraph(context: Context, val configRepository: ConfigRepository) {
    private val app = context.applicationContext

    /** 跟进程同寿命；不随 Activity/Service 销毁取消 */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val diagLog = DiagLog(File(app.filesDir, "diag"))
    val sipEngine by lazy { SipEngine(app) }
    val callCoordinator by lazy { CallCoordinator(app, this) }
    val feedCache = FeedCache.inDir(app.filesDir)
    val heartbeat = HeartbeatStore(app)
    val uptime = UptimeStore(app)
    val latency = top.xingzhenxi.phonebox.diag.LatencyStore(app)
    val readStore by lazy { ReadStore(app) }
    val callLogPrefs by lazy { top.xingzhenxi.phonebox.data.CallLogPrefs(app) }
    val smsNotifier by lazy { SmsNotifier(app).also { it.createChannels() } }
    val updateManager by lazy { UpdateManager(app, configRepository, diagLog) }
    val contactsSync by lazy { ContactsSync(app, scope, diagLog) { phonebox.value?.client } }

    private val _phonebox = MutableStateFlow<PhoneboxGraph?>(null)
    /** phonebox 地址/指纹未配置或无效时为 null */
    val phonebox: StateFlow<PhoneboxGraph?> = _phonebox.asStateFlow()

    /** 服务在跑时由它注册进来，供闹钟广播、诊断页“立即重连”直接调用，免得再走 startForegroundService */
    @Volatile var keepAlive: KeepAliveControl? = null

    data class PhoneboxGraph(val client: PhoneboxClient, val feedSync: FeedSync)

    init {
        // 地址或指纹变了就整套重建：旧客户端的证书固定和 Origin 都已不适用
        scope.launch {
            configRepository.config.map { it.phonebox.baseUrl to it.phonebox.certFingerprint }
                .distinctUntilChanged()
                .collect { (url, fp) -> rebuildPhonebox(PhoneboxConfig(url, fp)) }
        }
        observeSmsNotifications()
        // Guardian 开关打开过的话，Shizuku binder 一到就重新绑定守护进程
        GuardianController.get(app).init()
        UpdateWorker.schedule(app)
        contactsSync.init()
    }

    /** 新短信发通知、会话失效发“需要重新登录”；放在进程级而非服务里，谁启动了 FeedSync 都能生效 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeSmsNotifications() {
        scope.launch {
            phonebox.flatMapLatest { pb -> pb?.feedSync?.let { fs -> fs.newItems.map { it to fs } } ?: emptyFlow() }.collect { (item, fs) ->
                if (item is NewItem.Sms) {
                    val blocked = top.xingzhenxi.phonebox.data.SmsCategories.blockedNumbers(fs.state.value.feed.calls)
                    smsNotifier.post(item.item, readStore.spamMode, blocked)
                    latency.record(item.item.ts, System.currentTimeMillis(), item.via)
                }
            }
        }
        scope.launch {
            phonebox.flatMapLatest { it?.feedSync?.state?.map { s -> s.sessionExpired } ?: emptyFlow() }
                .distinctUntilChanged()
                .collect { expired -> if (expired) smsNotifier.postSessionExpired() else smsNotifier.clearSessionExpired() }
        }
    }

    private fun rebuildPhonebox(cfg: PhoneboxConfig) {
        _phonebox.value?.feedSync?.stop()
        _phonebox.value = if (cfg.baseUrl.isBlank()) null else runCatching {
            val client = PhoneboxClient(cfg.baseUrl, cfg.certFingerprint, ConfigTokenStore(configRepository))
            PhoneboxGraph(client, FeedSync(client, feedCache))
        }.onFailure { diagLog.log(DiagType.Feed, "phonebox 地址无效：${it.javaClass.simpleName}") }.getOrNull()
    }

    /** 从配置现组装 SIP 账号；口令现解密，不缓存。未配置完整时返回 null */
    suspend fun sipAccount(): SipAccountConfig? {
        val c = configRepository.config.first()
        val s = c.sip
        if (!c.wizardCompleted || s.host.isBlank() || s.username.isBlank() || s.port <= 0) return null
        val pass = configRepository.sipPassword() ?: return null
        return SipAccountConfig(
            host = s.host,
            port = s.port,
            transport = SipTransport.valueOf(s.transport.name),
            username = s.username,
            password = pass,
            certSha256 = s.tlsFingerprint.ifBlank { null },
            srtp = s.srtp,
        )
    }
}
