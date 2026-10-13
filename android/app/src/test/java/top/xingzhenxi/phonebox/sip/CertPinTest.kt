package top.xingzhenxi.phonebox.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CertPinTest {
    private val der = "hello".toByteArray()
    // SHA-256("hello")
    private val hex = "2CF24DBA5FB0A30E26E83B2AC5B9E29E1B161E5C1FA7425E73043362938B9824"

    @Test fun normalizeAcceptsColonAndLowercase() {
        val colon = hex.chunked(2).joinToString(":").lowercase()
        assertEquals(hex, CertPin.normalize(colon))
        assertEquals(hex, CertPin.normalize(" $hex "))
    }

    @Test fun normalizeRejectsBadInput() {
        assertNull(CertPin.normalize("ABC"))
        assertNull(CertPin.normalize(hex.dropLast(1) + "G"))
        assertNull(CertPin.normalize(""))
    }

    @Test fun matchesComputedDigest() {
        assertEquals(hex, CertPin.sha256Hex(der))
        assertTrue(CertPin.matches(hex.chunked(2).joinToString(":"), der))
        assertFalse(CertPin.matches(hex.replace('2', '3'), der))
        assertFalse(CertPin.matches("garbage", der))
    }

    @Test fun pemHasMarkers() {
        val pem = CertPin.toPem(der)
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----\n"))
        assertTrue(pem.trimEnd().endsWith("-----END CERTIFICATE-----"))
    }
}
