package top.xingzhenxi.phonebox.ui.messages

import org.junit.Assert.assertEquals
import org.junit.Test

class TicketTimeTest {
    @Test fun dropsSecondsOnly() {
        assertEquals("08:00", hourMinute("08:00:00"))
        assertEquals("8:05", hourMinute(" 8:05:30 "))
        assertEquals("08:00", hourMinute("08:00"))
        assertEquals("--", hourMinute("--"))
    }
}
