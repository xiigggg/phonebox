package top.xingzhenxi.phonebox.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushChannelsTest {
    @Test fun pickFirstAvailable() {
        val order = listOf("a", "unifiedpush")
        assertEquals("a", PushChannels.pick(order) { true })
        assertEquals("unifiedpush", PushChannels.pick(order) { it == "unifiedpush" })
        assertNull(PushChannels.pick(order) { false })
    }

    @Test fun onlyUnifiedPush() {
        assertEquals(listOf("unifiedpush"), PushChannels.all().map { it.id })
    }
}
