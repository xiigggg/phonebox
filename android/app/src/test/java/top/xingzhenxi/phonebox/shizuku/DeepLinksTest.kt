package top.xingzhenxi.phonebox.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepLinksTest {
    private val a = DeepLink.Component("com.oplus.safecenter", "A")
    private val b = DeepLink.Component("com.coloros.safecenter", "B")

    @Test fun keepsExistingInOrderThenFallback() {
        assertEquals(listOf(b, DeepLink.AppDetails), DeepLinks.resolve(listOf(a, b)) { it == b })
        assertEquals(listOf(a, b, DeepLink.AppDetails), DeepLinks.resolve(listOf(a, b)) { true })
    }

    @Test fun nothingResolvesFallsBackToAppDetails() {
        assertEquals(listOf(DeepLink.AppDetails), DeepLinks.resolve(listOf(a, b)) { false })
    }

    @Test fun selfReportedItemHasNoLink() {
        assertTrue(DeepLinks.resolve(ColorOsSwitch.LockInRecents.links) { true }.isEmpty())
        ColorOsSwitch.entries.filter { it != ColorOsSwitch.LockInRecents }.forEach { assertTrue(it.links.isNotEmpty()) }
    }
}
