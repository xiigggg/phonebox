package top.xingzhenxi.phonebox.ui.messages

import org.junit.Assert.assertEquals
import org.junit.Test

class AvatarTest {
    @Test fun stableIndexAndText() {
        val i = MessageFormat.avatarIndex("sig:某某银行", 6)
        repeat(3) { assertEquals(i, MessageFormat.avatarIndex("sig:某某银行", 6)) }
        assertEquals("某", MessageFormat.avatarText("某某银行", "95555"))
        assertEquals("01", MessageFormat.avatarText("", "13800000001"))
    }
}
