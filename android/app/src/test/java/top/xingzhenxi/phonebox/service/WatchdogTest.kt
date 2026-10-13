package top.xingzhenxi.phonebox.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchdogTest {
    private val now = 1_000_000_000L

    @Test fun freshBeatIsNotStale() = assertFalse(Watchdog.isStale(now, now - 9 * 60_000L))
    @Test fun oldBeatIsStale() = assertTrue(Watchdog.isStale(now, now - 21 * 60_000L))
    @Test fun boundaryIsNotStale() = assertFalse(Watchdog.isStale(now, now - Watchdog.STALE_AFTER_MS))
    @Test fun neverBeatIsStale() = assertTrue(Watchdog.isStale(now, 0))
    @Test fun futureBeatIsStale() = assertTrue(Watchdog.isStale(now, now + 60_000L))
}
