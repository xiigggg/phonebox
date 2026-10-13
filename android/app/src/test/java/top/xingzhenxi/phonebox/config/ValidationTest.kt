package top.xingzhenxi.phonebox.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ValidationTest {
    @Test fun hostAcceptsNamesAndIps() {
        listOf("sip.example.com", "localhost", "192.168.1.10", "255.255.255.255", "a-b.example", "::1", "[fe80::1]")
            .forEach { assertNull(it, Validation.host(it)) }
    }

    @Test fun hostRejectsGarbage() {
        listOf("", "  ", "256.1.1.1", "1.2.3", "-bad.example", "has space.com", "a_b.com", "x".repeat(64) + ".com")
            .forEach { assertNotNull(it, Validation.host(it)) }
    }

    @Test fun portRange() {
        assertNull(Validation.port("1"))
        assertNull(Validation.port("65535"))
        assertNotNull(Validation.port("0"))
        assertNotNull(Validation.port("65536"))
        assertNotNull(Validation.port("abc"))
        assertNotNull(Validation.port(""))
    }

    @Test fun extensionDigitsOnly() {
        assertNull(Validation.extension("201"))
        assertNotNull(Validation.extension(""))
        assertNotNull(Validation.extension("20a"))
        assertNotNull(Validation.extension("+201"))
    }

    @Test fun httpsUrl() {
        assertNull(Validation.httpsUrl("https://phonebox.example.com"))
        assertNull(Validation.httpsUrl("https://192.168.1.10:8443/"))
        assertNotNull(Validation.httpsUrl("http://phonebox.example.com"))
        assertNotNull(Validation.httpsUrl("phonebox.example.com"))
        assertNotNull(Validation.httpsUrl("https://user:pw@example.com"))
        assertNotNull(Validation.httpsUrl("https://"))
        assertNotNull(Validation.httpsUrl(""))
    }

    @Test fun fingerprintFormats() {
        val hex = "ab".repeat(32)
        assertNull(Validation.fingerprint(""))
        assertNull(Validation.fingerprint(hex))
        assertNull(Validation.fingerprint(hex.uppercase()))
        assertNull(Validation.fingerprint(hex.chunked(2).joinToString(":")))
        assertNotNull(Validation.fingerprint(hex.dropLast(2)))
        assertNotNull(Validation.fingerprint("zz".repeat(32)))
        assertNotNull(Validation.fingerprint("abab:" + "ab".repeat(30)))
    }

    @Test fun normalize() {
        assertEquals("ab".repeat(32), Validation.normalizeFingerprint("AB".repeat(32).chunked(2).joinToString(":")))
        assertEquals("https://x.example", Validation.normalizeUrl(" https://x.example/ "))
    }
}
