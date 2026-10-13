package top.xingzhenxi.phonebox.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DialPolicyTest {
    private val none = EmergencyChecker { false }
    private val list = EmergencyChecker { DialPolicy.isFallbackEmergency(it) }

    @Test fun keepsOnlyDialableChars() {
        assertEquals("+8613800000000", DialPolicy.sanitize("+86 138-0000 0000"))
        assertEquals("*21#", DialPolicy.sanitize("*21#"))
        assertEquals("123", DialPolicy.sanitize("1a2;b3"))
        assertEquals("12", DialPolicy.sanitize("1+2"))
        assertEquals("", DialPolicy.sanitize("abc"))
    }

    @Test fun nonAsciiDigitsDropped() {
        assertEquals("1", DialPolicy.sanitize("1٢"))
    }

    @Test fun emptyReturnsNull() {
        assertNull(DialPolicy.prepare(" - ", none))
    }

    @Test fun emergencyRejected() {
        assertNull(DialPolicy.prepare("110", list))
        assertNull(DialPolicy.prepare("1-1-2", list))
        assertEquals("600", DialPolicy.prepare("600", list))
    }
}
