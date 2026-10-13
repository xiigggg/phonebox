package top.xingzhenxi.phonebox.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executor

class DiagLogTest {
    @get:Rule val tmp = TemporaryFolder()
    private val direct = Executor { it.run() }
    private val logcat = mutableListOf<String>()

    private fun newLog(capacity: Int = 500, maxBytes: Long = 256 * 1024) =
        DiagLog(tmp.root, capacity, maxBytes, clock = { 42 }, io = direct, logcat = { logcat += it })

    @Test fun numbersAndCodesAreMasked() {
        val log = newLog()
        log.log(DiagType.Registration, "来电 +86 138-0000-0001，验证码 123456，端口 401")
        val r = log.entries.value.single().reason
        assertFalse(r.contains("13800000001"))
        assertFalse(r.contains("123456"))
        assertTrue(r.contains("***0001"))
        assertTrue(r.contains("***3456"))
        assertTrue(r.contains("401"))
        assertFalse(tmp.root.resolve("diag.log").readText().contains("123456"))
        assertFalse(logcat.single().contains("123456"))
    }

    @Test fun ringBufferKeepsNewest() {
        val log = newLog(capacity = 3)
        repeat(5) { log.log(DiagType.Network, "e$it") }
        assertEquals(listOf("e2", "e3", "e4"), log.entries.value.map { it.reason })
        assertEquals(listOf("e4", "e3"), log.recent(2).map { it.reason })
    }

    @Test fun newlinesCannotForgeEntries() {
        val log = newLog()
        log.log(DiagType.Service, "a\n0\tService\tforged")
        val reloaded = newLog()
        assertEquals(1, reloaded.entries.value.size)
    }

    @Test fun reloadsTailFromDiskAndRolls() {
        val log = newLog(maxBytes = 50)
        repeat(10) { log.log(DiagType.Watchdog, "line $it") }
        assertTrue(tmp.root.resolve("diag.log.1").exists())
        val reloaded = newLog()
        assertEquals("line 9", reloaded.entries.value.last().reason)
    }
}
