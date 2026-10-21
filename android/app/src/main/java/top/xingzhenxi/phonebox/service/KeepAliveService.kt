package top.xingzhenxi.phonebox.service

import android.app.AlarmManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import top.xingzhenxi.phonebox.AppGraph
import top.xingzhenxi.phonebox.MainActivity
import top.xingzhenxi.phonebox.appGraph
import top.xingzhenxi.phonebox.data.FeedSync
import top.xingzhenxi.phonebox.diag.DiagType
import top.xingzhenxi.phonebox.sip.RegState

/** 服务对进程内其他组件（闹钟广播、看门狗、诊断页）暴露的入口 */
interface KeepAliveControl {
    fun heartbeat(reason: String)
    fun reconnectNow(reason: String)
    /** 有通话时传入 CallStyle 通知，服务改以 phoneCall|microphone 类型前台运行；null 表示通话结束、复原 */
    fun setCallNotification(notification: Notification?)
}

/**
 * 常驻前台服务：持有 SIP 注册和 phonebox 事件流。
 * 类型用 specialUse：dataSync 在 Android 15 起每天限 6 小时，phoneCall 不能从开机广播启动。
 */
class KeepAliveService : Service(), KeepAliveControl {
    private lateinit var graph: AppGraph
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private val backoff = Backoff()
    private var retryPending: Runnable? = null
    private var configured = true
    private var regState: RegState = RegState.None
    private var currentNetwork: Network? = null
    private var feed: FeedSync? = null
    private var started = false
    /** onCreate 已经在连了，紧接着的那次 onStartCommand 不必再重连一遍 */
    private var justCreated = false
    private var inCall = false

    private val sip get() = graph.sipEngine
    private val diag get() = graph.diagLog

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        graph = appGraph
        createChannel(this)
        try {
            startForeground(NOTIFICATION_ID, buildNotification("正在连接…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: ForegroundServiceStartNotAllowedException) {
            // 粘性重启等场景可能被系统拒绝；不硬撑，交给看门狗下次再试
            diag.log(DiagType.FgsDenied, "onCreate startForeground 被拒")
            stopSelf()
            return
        }
        started = true
        justCreated = true
        // 上次进程是“在线”状态被杀的：在线时长只能算到最后一次心跳，之后按离线计
        if (graph.uptime.isOnline()) {
            graph.uptime.record(false, "进程重启", maxOf(graph.heartbeat.last(), graph.uptime.lastTransitionTs()))
        }
        diag.log(DiagType.Service, "服务启动")
        graph.keepAlive = this
        graph.heartbeat.beat()

        sip.start()
        sip.setHostedByForegroundService(true)
        graph.callCoordinator.start()
        observeConfig()
        observeRegistration()
        observeFeed()
        registerNetwork()
        scheduleHeartbeatAlarm()
        WatchdogWorker.schedule(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) return START_NOT_STICKY
        val first = justCreated
        justCreated = false
        when (intent?.action) {
            null -> diag.log(DiagType.Service, "系统粘性重启")
            ACTION_RECONFIGURE -> applyAccountNow("配置变更")
            ACTION_START -> if (!first) reconnectNow(intent.getStringExtra(EXTRA_REASON) ?: "外部唤醒")
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 部分 ROM 划掉任务会连同前台服务一起杀；先订好 1 秒后的闹钟把自己拉回来
        diag.log(DiagType.Service, "任务被移除，1 秒后自检")
        val am = getSystemService(AlarmManager::class.java)
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1_000, KeepAliveReceiver.pending(this, KeepAliveReceiver.ACTION_RESTART))
    }

    override fun onDestroy() {
        if (started) {
            // 先置位：sip.stop() 会触发注册状态回调，不能再借 updateNotification 贴出一条没有服务撑着的“正在重连”
            started = false
            diag.log(DiagType.Service, "服务停止")
            graph.uptime.record(false, "服务停止")
            graph.keepAlive = null
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback) }
            cancelRetry()
            feed?.stop()
            sip.setHostedByForegroundService(false)
            sip.stop()
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
        scope.cancel()
        super.onDestroy()
    }

    // ---- SIP ----

    private fun observeConfig() = scope.launch {
        graph.configRepository.config.map { it.sip to it.wizardCompleted }.distinctUntilChanged().collect {
            applyAccountNow("读取配置")
        }
    }

    private fun applyAccountNow(reason: String) {
        cancelRetry()
        scope.launch {
            val account = graph.sipAccount()
            configured = account != null
            if (account == null) {
                sip.clearAccount()
                diag.log(DiagType.Registration, "未配置 SIP 账号（$reason）")
            } else {
                sip.applyAccount(account)
            }
            updateNotification()
        }
    }

    private fun observeRegistration() = scope.launch {
        sip.registration.collect { st ->
            regState = st
            when (st) {
                RegState.Ok -> {
                    backoff.reset()
                    cancelRetry()
                    graph.uptime.record(true, "注册成功")
                    diag.log(DiagType.Registration, "注册成功")
                }
                is RegState.Failed -> {
                    graph.uptime.record(false, st.reason)
                    diag.log(DiagType.Registration, "注册失败：${st.reason}")
                    scheduleRetry()
                }
                RegState.Cleared -> diag.log(DiagType.Registration, "注册已清除")
                // Progress 在每次刷新都会出现，记下来只会刷屏
                RegState.Progress, RegState.None -> Unit
            }
            graph.heartbeat.beat()
            updateNotification()
        }
    }

    private fun scheduleRetry() {
        if (retryPending != null || !configured) return
        val delay = backoff.next()
        diag.log(DiagType.Backoff, "第 ${backoff.attempt} 次重试，${delay / 1000} 秒后")
        val r = Runnable { retryPending = null; applyAccountNow("退避重试") }
        retryPending = r
        main.postDelayed(r, delay)
    }

    private fun cancelRetry() {
        retryPending?.let(main::removeCallbacks)
        retryPending = null
    }

    override fun reconnectNow(reason: String) {
        diag.log(DiagType.Service, "立即重连：$reason")
        graph.heartbeat.beat()
        backoff.reset()
        if (regState is RegState.Ok) sip.refreshRegistration() else applyAccountNow(reason)
        restartFeed()
    }

    // ---- 事件流 ----

    private fun observeFeed() = scope.launch {
        combine(graph.phonebox, graph.configRepository.config.map { it.phonebox.hasSession }.distinctUntilChanged()) { pb, logged ->
            pb?.feedSync?.takeIf { logged }
        }.collect { next ->
            if (next === feed) return@collect
            feed?.stop()
            feed = next
            if (next == null) diag.log(DiagType.Feed, "phonebox 未登录或未配置，跳过事件流")
            else next.start(graph.scope)
        }
    }

    private fun restartFeed() {
        val f = feed ?: return
        f.stop()
        f.start(graph.scope)
    }

    // ---- 网络 ----

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            main.post {
                val prev = currentNetwork
                currentNetwork = network
                sip.onNetworkReachable(true)
                if (prev == network) return@post
                // 首次注册回调时 prev 为 null 且账号刚在 apply，不必再刷新
                if (prev == null && regState !is RegState.Failed && !lostSinceStart) return@post
                diag.log(DiagType.Network, if (prev == null) "网络恢复" else "网络切换")
                lostSinceStart = false
                backoff.reset()
                if (regState is RegState.Failed || retryPending != null) applyAccountNow("网络恢复") else sip.refreshRegistration()
                restartFeed()
            }
        }

        override fun onLost(network: Network) {
            main.post {
                if (network != currentNetwork) return@post
                currentNetwork = null
                lostSinceStart = true
                diag.log(DiagType.Network, "网络断开")
                graph.uptime.record(false, "网络断开")
                sip.onNetworkReachable(false)
                updateNotification()
            }
        }
    }
    private var lostSinceStart = false

    private fun registerNetwork() {
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netCallback, main)
    }

    // ---- 心跳对齐 ----

    /**
     * 约 9 分钟一次的闹钟里同时刷新 SIP 注册和拉一次 feed：两件事挤在同一次唤醒，
     * 比各自定时省电。setAndAllowWhileIdle 在 Doze 下最密约 9 分钟放行一次，所以取 9 分钟；
     * 不用精确闹钟，免得申请 SCHEDULE_EXACT_ALARM。
     */
    private fun scheduleHeartbeatAlarm() {
        getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + HEARTBEAT_INTERVAL_MS,
            KeepAliveReceiver.pending(this, KeepAliveReceiver.ACTION_HEARTBEAT),
        )
    }

    override fun heartbeat(reason: String) {
        // 闹钟广播返回后 CPU 可能立刻睡回去，持 ≤10 秒的锁让 REGISTER 和 feed 请求发出去
        val wl = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonebox:heartbeat")
            .apply { acquire(10_000) }
        graph.heartbeat.beat()
        when {
            !configured -> Unit
            regState is RegState.Ok -> sip.refreshRegistration()
            // 没注册上又没有排着的重试：说明重试链断了（账号已清掉时 refreshRegistration 什么也不做），重新来一遍
            retryPending == null -> {
                diag.log(DiagType.Registration, "心跳自检：未注册且无待重试，重新注册")
                backoff.reset()
                applyAccountNow("心跳自检")
            }
        }
        scheduleHeartbeatAlarm()
        val f = feed
        graph.scope.launch {
            try {
                if (f != null) withTimeoutOrNull(8_000) { f.pollNow() }
            } finally {
                if (wl.isHeld) wl.release()
            }
        }
    }

    // ---- 通话 ----

    /**
     * 同一个通知 id 上切换内容和前台类型。microphone 类型受 while-in-use 限制：
     * 接听和拨出都经由前台 Activity 触发，所以通常能成功；万一从后台被拒（如 Telecom 外部接听），
     * 退到 phoneCall 保住通话通知并记诊断日志，此时后台录音可能被系统静音。
     */
    override fun setCallNotification(notification: Notification?) {
        if (!started) return
        inCall = notification != null
        if (notification == null) {
            startForegroundCompat(buildNotification(statusText()), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            diag.log(DiagType.Call, "通话结束，前台类型复原")
            return
        }
        val full = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (startForegroundCompat(notification, full)) return
        diag.log(DiagType.FgsDenied, "通话前台类型（含麦克风）被拒，退为 phoneCall")
        startForegroundCompat(notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
    }

    private fun startForegroundCompat(n: Notification, types: Int): Boolean = try {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, types)
        true
    } catch (e: Exception) {
        // SecurityException（缺权限/while-in-use）或 ForegroundServiceStartNotAllowedException
        Log.w("PB.Call", "startForeground 失败：${e.javaClass.simpleName}")
        false
    }

    // ---- 通知 ----

    private fun statusText() = when {
        !configured -> "未配置"
        currentNetwork == null && regState !is RegState.Ok -> "正在重连（无网络）"
        regState is RegState.Ok -> "已连接"
        else -> "正在重连"
    }

    private fun updateNotification() {
        if (!started || inCall) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(statusText()))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("电话信箱")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "keepalive"
        const val NOTIFICATION_ID = 1001
        const val HEARTBEAT_INTERVAL_MS = 9 * 60_000L
        const val ACTION_START = "top.xingzhenxi.phonebox.action.KEEPALIVE_START"
        const val ACTION_RECONFIGURE = "top.xingzhenxi.phonebox.action.KEEPALIVE_RECONFIGURE"
        private const val EXTRA_REASON = "reason"

        private fun createChannel(context: Context) {
            val ch = NotificationChannel(CHANNEL_ID, "常驻连接", NotificationManager.IMPORTANCE_LOW).apply {
                description = "保持 SIP 注册以接收来电"
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        /** 拉起或唤醒服务；后台受限时系统会拒绝，记诊断日志后交给看门狗/下次时机 */
        fun ensureRunning(context: Context, reason: String, reconfigure: Boolean = false): Boolean {
            val intent = Intent(context, KeepAliveService::class.java)
                .setAction(if (reconfigure) ACTION_RECONFIGURE else ACTION_START)
                .putExtra(EXTRA_REASON, reason)
            return try {
                context.startForegroundService(intent)
                true
            } catch (e: ForegroundServiceStartNotAllowedException) {
                context.appGraph.diagLog.log(DiagType.FgsDenied, "前台服务启动被拒（$reason）")
                false
            } catch (e: IllegalStateException) {
                context.appGraph.diagLog.log(DiagType.FgsDenied, "服务启动失败（$reason）：${e.javaClass.simpleName}")
                false
            }
        }
    }
}
