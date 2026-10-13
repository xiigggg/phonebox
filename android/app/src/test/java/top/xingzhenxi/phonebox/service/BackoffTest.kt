package top.xingzhenxi.phonebox.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BackoffTest {
    private class Fixed(private val v: Double) : Random() {
        override fun nextBits(bitCount: Int) = 0
        override fun nextDouble() = v
    }

    @Test fun doublesFromTwoSecondsAndCapsAtFiveMinutes() {
        val b = Backoff(random = Fixed(1.0))
        val seq = List(10) { b.next() }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 64_000L, 128_000L, 256_000L, 300_000L, 300_000L), seq)
    }

    @Test fun jitterStaysWithinHalfToFull() {
        val b = Backoff()
        repeat(30) {
            val exp = minOf(2_000L shl minOf(b.attempt, 20), 300_000L)
            val d = b.next()
            assertTrue(d in exp / 2..exp)
        }
    }

    @Test fun resetStartsOver() {
        val b = Backoff(random = Fixed(1.0))
        repeat(5) { b.next() }
        b.reset()
        assertEquals(0, b.attempt)
        assertEquals(2_000L, b.next())
    }

    @Test fun manyAttemptsDoNotOverflow() {
        val b = Backoff(random = Fixed(1.0))
        repeat(200) { assertTrue(b.next() in 1..300_000L) }
    }
}
