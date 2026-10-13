package top.xingzhenxi.phonebox.roles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeVerifierTest {
    private val key = "test-wake-key"
    private val now = 1_791_649_038L

    private fun msg(ts: Long, k: String = key) = "PBWAKE:$ts:${WakeVerifier.mac(k, ts)}"

    @Test fun macMatchesPythonReference() {
        // python3 -c 'import hmac,hashlib;print(hmac.new(b"test-wake-key",b"PBWAKE:1791649038",hashlib.sha256).hexdigest()[:16])'
        assertEquals(REF, WakeVerifier.mac(key, now))
    }

    @Test fun accepted() {
        assertEquals(WakeResult.Accepted(now), WakeVerifier.verify(msg(now), key, now + 30, 0))
    }

    @Test fun upperCaseMacAccepted() {
        val s = "PBWAKE:$now:${WakeVerifier.mac(key, now).uppercase()}"
        assertEquals(WakeResult.Accepted(now), WakeVerifier.verify(s, key, now, 0))
    }

    @Test fun wrongMac() {
        assertTrue(WakeVerifier.verify(msg(now, "other"), key, now, 0) is WakeResult.Rejected)
    }

    @Test fun expired() {
        assertTrue(WakeVerifier.verify(msg(now), key, now + 601, 0) is WakeResult.Rejected)
        assertTrue(WakeVerifier.verify(msg(now), key, now - 601, 0) is WakeResult.Rejected)
    }

    @Test fun replay() {
        assertTrue(WakeVerifier.verify(msg(now), key, now, now) is WakeResult.Rejected)
        assertTrue(WakeVerifier.verify(msg(now - 5), key, now, now) is WakeResult.Rejected)
    }

    @Test fun noKey() {
        assertTrue(WakeVerifier.verify(msg(now), null, now, 0) is WakeResult.Rejected)
        assertTrue(WakeVerifier.verify(msg(now), "", now, 0) is WakeResult.Rejected)
    }

    @Test fun notWake() {
        assertEquals(WakeResult.NotWake, WakeVerifier.verify("你好", key, now, 0))
        assertEquals(WakeResult.NotWake, WakeVerifier.verify("PBWAKE:abc:123", key, now, 0))
    }

    private companion object { const val REF = "06385e6a0117687f" }
}
