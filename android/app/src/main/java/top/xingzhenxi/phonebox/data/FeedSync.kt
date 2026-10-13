package top.xingzhenxi.phonebox.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.xingzhenxi.phonebox.net.CallItem
import top.xingzhenxi.phonebox.net.Feed
import top.xingzhenxi.phonebox.net.FeedSource
import top.xingzhenxi.phonebox.net.SessionExpiredException
import top.xingzhenxi.phonebox.net.SmsItem
import top.xingzhenxi.phonebox.net.StreamEvent
import java.io.IOException
import java.security.MessageDigest
import kotlin.random.Random

enum class SyncMode { Idle, Streaming, Polling }

data class FeedSnapshot(
    val feed: Feed = Feed(),
    /** 最近一次从服务器成功拿到数据的时间（毫秒）；0 表示还只有缓存或什么都没有 */
    val updatedAt: Long = 0,
    val fromCache: Boolean = false,
    val mode: SyncMode = SyncMode.Idle,
    /** 服务器没有 /api/events，只能轮询 */
    val sseUnsupported: Boolean = false,
    val sessionExpired: Boolean = false,
    val lastError: String? = null,
)

sealed interface NewItem {
    data class Sms(val item: SmsItem) : NewItem
    data class Call(val item: CallItem) : NewItem
}

/**
 * SSE 优先，拿不到就每 3 分钟轮询 /api/feed。
 * 每次（重）连上事件流前先全量拉一次，补上断线期间漏掉的条目；去重靠 ts + 内容哈希。
 */
class FeedSync(
    private val source: FeedSource,
    private val cache: FeedCache?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pollIntervalMs: Long = 3 * 60_000L,
    private val maxBackoffMs: Long = 5 * 60_000L,
) {
    private val _state = MutableStateFlow(FeedSnapshot())
    val state: StateFlow<FeedSnapshot> = _state.asStateFlow()

    private val _newItems = MutableSharedFlow<NewItem>(extraBufferCapacity = 64)
    val newItems: SharedFlow<NewItem> = _newItems.asSharedFlow()

    private val mutex = Mutex()
    private val seen = HashSet<String>()
    /** 还没有任何基线（无缓存、未拉取过）时，第一批数据只建基线，不当“新”消息 */
    private var hasBaseline = false
    private var lastEventId: String? = null
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        // 重新登录后会再次 start：清掉上一轮留下的“会话失效”
        _state.value = _state.value.copy(sessionExpired = false)
        job = scope.launch {
            withContext(io) { cache?.read() }?.let { cached ->
                mutex.withLock {
                    markSeen(cached)
                    hasBaseline = true
                    _state.value = _state.value.copy(feed = cached, fromCache = true)
                }
            }
            runLoop()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(mode = SyncMode.Idle)
    }

    /** 全量拉一次；会话失效返回 false */
    suspend fun pollNow(): Boolean = try {
        val feed = source.fetchFeed()
        applyFull(feed)
        true
    } catch (e: SessionExpiredException) {
        _state.value = _state.value.copy(sessionExpired = true, lastError = "登录已失效")
        false
    } catch (e: IOException) {
        _state.value = _state.value.copy(lastError = e.message ?: e.javaClass.simpleName)
        true
    } catch (e: kotlinx.serialization.SerializationException) {
        _state.value = _state.value.copy(lastError = "数据格式不对")
        true
    }

    private suspend fun runLoop() {
        var attempt = 0
        var sseSupported = true
        var resync = true
        while (currentCoroutineActive()) {
            if (resync && !pollNow()) return
            if (!sseSupported) {
                _state.value = _state.value.copy(mode = SyncMode.Polling)
                delay(pollIntervalMs)
                resync = true
                continue
            }
            when (val r = runStream()) {
                StreamOutcome.Unsupported -> {
                    sseSupported = false
                    resync = false
                    _state.value = _state.value.copy(sseUnsupported = true)
                }
                StreamOutcome.SessionExpired -> {
                    _state.value = _state.value.copy(sessionExpired = true, mode = SyncMode.Idle, lastError = "登录已失效")
                    return
                }
                is StreamOutcome.Ended -> {
                    if (r.opened) attempt = 0
                    _state.value = _state.value.copy(mode = SyncMode.Polling)
                    delay(backoffMs(attempt++))
                    resync = true
                }
            }
        }
    }

    private suspend fun currentCoroutineActive() = kotlin.coroutines.coroutineContext.isActive

    /** 1s、2s、4s…封顶 5 分钟，再乘 0.5–1.0 的随机系数，避免多端同时重连 */
    internal fun backoffMs(attempt: Int): Long {
        val exp = (1000L shl attempt.coerceIn(0, 20)).coerceAtMost(maxBackoffMs)
        return (exp * (0.5 + random.nextDouble() * 0.5)).toLong()
    }

    private sealed interface StreamOutcome {
        data object Unsupported : StreamOutcome
        data object SessionExpired : StreamOutcome
        data class Ended(val opened: Boolean) : StreamOutcome
    }

    private suspend fun runStream(): StreamOutcome {
        val events = Channel<StreamEvent>(Channel.UNLIMITED)
        val stream = source.openEvents(lastEventId) { events.trySend(it) }
        var opened = false
        try {
            for (ev in events) {
                when (ev) {
                    StreamEvent.Opened -> {
                        opened = true
                        _state.value = _state.value.copy(mode = SyncMode.Streaming, lastError = null)
                    }
                    is StreamEvent.Sms -> { ev.id?.let { lastEventId = it }; applyOne(sms = ev.item) }
                    is StreamEvent.Call -> { ev.id?.let { lastEventId = it }; applyOne(call = ev.item) }
                    StreamEvent.Ping -> Unit
                    StreamEvent.Unsupported -> return StreamOutcome.Unsupported
                    StreamEvent.SessionExpired -> return StreamOutcome.SessionExpired
                    is StreamEvent.Failed -> {
                        _state.value = _state.value.copy(lastError = ev.error?.message ?: "事件流断开")
                        return StreamOutcome.Ended(opened)
                    }
                    StreamEvent.Closed -> return StreamOutcome.Ended(opened)
                }
            }
            return StreamOutcome.Ended(opened)
        } finally {
            stream.cancel()
            events.close()
        }
    }

    private suspend fun applyFull(feed: Feed) {
        val fresh = mutex.withLock {
            val prevMax = maxTs(_state.value.feed)
            val fresh = if (hasBaseline) newSince(feed, prevMax) else emptyList()
            markSeen(feed)
            hasBaseline = true
            _state.value = _state.value.copy(feed = feed, updatedAt = clock(), fromCache = false, lastError = null)
            fresh
        }
        persist(feed)
        fresh.forEach { _newItems.emit(it) }
    }

    private suspend fun applyOne(sms: SmsItem? = null, call: CallItem? = null) {
        val (feed, isNew) = mutex.withLock {
            val key = sms?.let(::smsKey) ?: call?.let(::callKey) ?: return
            if (!seen.add(key)) return
            val cur = _state.value.feed
            val next = cur.copy(
                sms = if (sms != null) listOf(sms) + cur.sms else cur.sms,
                calls = if (call != null) listOf(call) + cur.calls else cur.calls,
            )
            _state.value = _state.value.copy(feed = next, updatedAt = clock(), fromCache = false)
            next to hasBaseline
        }
        persist(feed)
        if (isNew) _newItems.emit(sms?.let { NewItem.Sms(it) } ?: NewItem.Call(call!!))
    }

    /** 只把比上次已知最新还新（或同一秒）的未见条目当新消息，避免长时间离线后一次弹出几百条旧记录 */
    private fun newSince(feed: Feed, prevMax: Long): List<NewItem> =
        feed.sms.filter { it.ts >= prevMax && smsKey(it) !in seen }.map { NewItem.Sms(it) } +
            feed.calls.filter { it.ts >= prevMax && callKey(it) !in seen }.map { NewItem.Call(it) }

    private fun maxTs(f: Feed): Long = maxOf(f.sms.maxOfOrNull { it.ts } ?: 0, f.calls.maxOfOrNull { it.ts } ?: 0)

    private fun markSeen(f: Feed) {
        f.sms.forEach { seen.add(smsKey(it)) }
        f.calls.forEach { seen.add(callKey(it)) }
    }

    private suspend fun persist(feed: Feed) {
        val c = cache ?: return
        runCatching { withContext(io) { c.write(feed) } }
    }

    companion object {
        fun smsKey(s: SmsItem) = "s:${s.ts}:${hash(s.from + "\u0000" + s.body)}"
        fun callKey(c: CallItem) = "c:${c.ts}:${hash(c.number + "\u0000" + c.dir + "\u0000" + c.disposition)}"

        private fun hash(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }
}
