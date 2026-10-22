package top.xingzhenxi.phonebox.data

import top.xingzhenxi.phonebox.diag.ArrivalVia
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.xingzhenxi.phonebox.net.EventStream
import top.xingzhenxi.phonebox.net.Feed
import top.xingzhenxi.phonebox.net.FeedSource
import top.xingzhenxi.phonebox.net.SessionExpiredException
import top.xingzhenxi.phonebox.net.SmsItem
import top.xingzhenxi.phonebox.net.StreamEvent
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class FeedSyncTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeSource : FeedSource {
        var feed = Feed()
        var fetches = 0
        var expired = false
        var sse: ((String?, (StreamEvent) -> Unit) -> Unit) = { _, on -> on(StreamEvent.Unsupported) }
        val lastIds = mutableListOf<String?>()
        lateinit var emit: (StreamEvent) -> Unit

        override suspend fun fetchFeed(): Feed {
            fetches++
            if (expired) throw SessionExpiredException()
            return feed
        }

        override suspend fun openEvents(lastEventId: String?, onEvent: (StreamEvent) -> Unit): EventStream {
            lastIds += lastEventId
            emit = onEvent
            sse(lastEventId, onEvent)
            return EventStream { }
        }
    }

    private fun sms(ts: Long, body: String) = SmsItem(ts = ts, from = "13800000001", body = body)

    private fun TestScope.sync(src: FeedSource, cache: FeedCache? = null) =
        FeedSync(src, cache, clock = { testScheduler.currentTime }, random = Random(1),
            io = StandardTestDispatcher(testScheduler))

    @Test fun firstLoadEmitsNothingThenPollingFindsNew() = runTest {
        val src = FakeSource().apply { feed = Feed(sms = listOf(sms(100, "a"))) }
        val s = sync(src)
        val got = mutableListOf<NewItem>()
        val collector = launch { s.newItems.collect { got += it } }
        s.start(backgroundScope)
        runCurrent()
        assertEquals(1, s.state.value.feed.sms.size)
        assertTrue(s.state.value.sseUnsupported)
        assertTrue(got.isEmpty())

        src.feed = Feed(sms = listOf(sms(200, "b"), sms(100, "a")))
        advanceTimeBy(3 * 60_000L + 1)
        runCurrent()
        assertEquals(listOf<NewItem>(NewItem.Sms(sms(200, "b"), ArrivalVia.Poll)), got)
        assertEquals(SyncMode.Polling, s.state.value.mode)

        // 同样的数据再拉一次不重复发
        advanceTimeBy(3 * 60_000L + 1)
        runCurrent()
        assertEquals(1, got.size)
        assertEquals(3, src.fetches)
        collector.cancel()
    }

    @Test fun stalledDetectionAndLog() = runTest {
        val logs = mutableListOf<String>()
        val src = FakeSource().apply {
            feed = Feed(sms = listOf(sms(100, "a")))
            sse = { _, on -> on(StreamEvent.Opened) }
        }
        val s = FeedSync(src, null, clock = { testScheduler.currentTime }, random = Random(1), io = StandardTestDispatcher(testScheduler), log = { logs += it })
        assertTrue(s.isStalled())   // 还没启动
        s.start(backgroundScope)
        runCurrent()
        assertEquals(SyncMode.Streaming, s.state.value.mode)
        assertTrue(!s.isStalled())
        assertTrue(logs.contains("事件流已连接"))
        src.emit(StreamEvent.Failed(null))
        runCurrent()
        assertTrue(!s.isStalled())   // 在退避重连，不算卡住
        assertTrue(logs.any { it.startsWith("事件流断开") })
        s.stop()
        assertTrue(s.isStalled())
    }

    @Test fun sseItemsAreDedupedAgainstPoll() = runTest {
        val src = FakeSource().apply {
            feed = Feed(sms = listOf(sms(100, "a")))
            sse = { _, on -> on(StreamEvent.Opened) }
        }
        val s = sync(src)
        val got = mutableListOf<NewItem>()
        val collector = launch { s.newItems.collect { got += it } }
        s.start(backgroundScope)
        runCurrent()
        assertEquals(SyncMode.Streaming, s.state.value.mode)

        src.emit(StreamEvent.Sms("1-1", sms(200, "b")))
        src.emit(StreamEvent.Sms("1-1", sms(200, "b")))
        src.emit(StreamEvent.Sms("1-2", sms(100, "a")))
        runCurrent()
        assertEquals(listOf<NewItem>(NewItem.Sms(sms(200, "b"))), got)
        assertEquals(2, s.state.value.feed.sms.size)

        // 断线：退避后先全量补拉，再带 Last-Event-ID 重连；补拉到的旧条目不重复通知
        src.feed = Feed(sms = listOf(sms(200, "b"), sms(100, "a")))
        src.emit(StreamEvent.Failed(null))
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(null, "1-2"), src.lastIds)
        assertEquals(1, got.size)
        collector.cancel()
    }

    @Test fun cacheIsShownFirstAndWritten() = runTest {
        val cache = FeedCache(tmp.newFile("feed-cache.json"))
        cache.write(Feed(sms = listOf(sms(100, "cached"))))
        val src = FakeSource().apply { feed = Feed(sms = listOf(sms(300, "new"), sms(100, "cached"))) }
        val s = sync(src, cache)
        val got = mutableListOf<NewItem>()
        val collector = launch { s.newItems.collect { got += it } }
        s.start(backgroundScope)
        runCurrent()
        // 有缓存作为基线，离线期间到的新短信要通知
        assertEquals(listOf<NewItem>(NewItem.Sms(sms(300, "new"), ArrivalVia.Poll)), got)
        assertEquals(2, cache.read()!!.sms.size)
        collector.cancel()
    }

    @Test fun sessionExpiredStopsLoop() = runTest {
        val src = FakeSource().apply { expired = true }
        val s = sync(src)
        s.start(backgroundScope)
        runCurrent()
        advanceTimeBy(10 * 60_000L)
        assertTrue(s.state.value.sessionExpired)
        assertEquals(1, src.fetches)
    }

    @Test fun backoffIsCappedWithJitter() = runTest {
        val s = sync(FakeSource())
        repeat(30) { a ->
            val b = s.backoffMs(a)
            assertTrue(b <= 5 * 60_000L)
            assertTrue(b >= (minOf(1000L shl minOf(a, 20), 5 * 60_000L)) / 2)
        }
    }
}
