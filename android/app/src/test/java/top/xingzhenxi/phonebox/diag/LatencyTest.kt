package top.xingzhenxi.phonebox.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatencyTest {
    @Test fun arrivalBounds() {
        assertEquals(1_500L, Latency.arrival(1_000, 1_001_500, ArrivalVia.Stream)!!.delayMs)
        // 手机时钟略快：小幅负值按 0
        assertEquals(0L, Latency.arrival(1_000, 999_000, ArrivalVia.Stream)!!.delayMs)
        assertNull(Latency.arrival(1_000, 990_000, ArrivalVia.Stream))
        assertNull(Latency.arrival(1_000, 1_000_000 + 25 * 3600_000L, ArrivalVia.Poll))
        assertNull(Latency.arrival(0, 5_000, ArrivalVia.Poll))
    }

    @Test fun summary() {
        val list = (1..10).map { Arrival(it.toLong(), it * 1000L, if (it > 8) ArrivalVia.Poll else ArrivalVia.Stream) }
        val s = Latency.summarize(list)!!
        assertEquals(10, s.count)
        assertEquals(5_000L, s.p50Ms)
        assertEquals(9_000L, s.p90Ms)
        assertEquals(10_000L, s.maxMs)
        assertEquals(mapOf(ArrivalVia.Stream to 8, ArrivalVia.Poll to 2), s.byVia)
        assertNull(Latency.summarize(emptyList()))
    }
}
