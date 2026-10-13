package top.xingzhenxi.phonebox.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UptimeStatsTest {
    private val h = 3600_000L

    @Test fun emptyHasNoRatio() = assertNull(UptimeStats().onlineRatio(100 * h))

    @Test fun ratioOverObservedSpan() {
        val u = UptimeStats()
        u.record(0, true, "ok")
        u.record(3 * h, false, "断网")
        u.record(4 * h, true, "ok")
        assertEquals(0.75, u.onlineRatio(4 * h)!!, 1e-9)
        assertEquals(7.0 / 8, u.onlineRatio(8 * h)!!, 1e-9)
    }

    @Test fun windowIsLast24Hours() {
        val u = UptimeStats()
        u.record(0, false, "init")
        u.record(10 * h, true, "ok")
        // 窗口 [12h, 36h]：全在线
        assertEquals(1.0, u.onlineRatio(36 * h)!!, 1e-9)
        u.record(36 * h, false, "x")
        // 窗口 [24h, 48h]：前 12 小时在线
        assertEquals(0.5, u.onlineRatio(48 * h)!!, 1e-9)
    }

    @Test fun duplicateStatesIgnoredAndLastDropFound() {
        val u = UptimeStats()
        u.record(0, true, "ok")
        u.record(1, true, "ok again")
        u.record(2 * h, false, "408 超时")
        assertEquals(2, u.transitions.size)
        assertEquals(Drop(2 * h, "408 超时"), u.lastDrop())
    }

    @Test fun initialOfflineIsNotADrop() {
        val u = UptimeStats()
        u.record(0, false, "启动")
        assertNull(u.lastDrop())
    }

    @Test fun pruneKeepsStateAtWindowStart() {
        val u = UptimeStats()
        u.record(0, true, "ok")
        u.record(h, false, "a")
        u.record(2 * h, true, "b")
        u.record(30 * h, false, "c")
        // 窗口 [6h, 30h] 之前的只留最后一条（2h 在线）
        assertEquals(listOf(2 * h, 30 * h), u.transitions.map { it.ts })
        assertEquals(1.0, u.onlineRatio(30 * h)!!, 1e-9)
    }

    @Test fun encodeDecodeRoundTrip() {
        val u = UptimeStats()
        u.record(5, true, "ok")
        u.record(9, false, "断网|x")
        val back = UptimeStats.decode(u.encode())
        assertEquals(u.transitions, back.transitions)
    }

    @Test fun reasonsAreRedacted() {
        val u = UptimeStats()
        u.record(1, true, "ok")
        u.record(2, false, "来自 13800000001 的呼叫")
        assertEquals("来自 ***0001 的呼叫", u.lastDrop()!!.reason)
    }
}
