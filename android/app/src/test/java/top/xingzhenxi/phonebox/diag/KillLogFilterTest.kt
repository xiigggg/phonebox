package top.xingzhenxi.phonebox.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KillLogFilterTest {
    private val pkg = "top.xingzhenxi.phonebox"

    @Test fun amKillSelf() {
        val e = KillLogFilter.parse("10-10 12:00:01.123  1000  1250 I am_kill : [0,4567,top.xingzhenxi.phonebox,900,remove task,12345]", pkg)
        assertEquals(KillLogEntry("10-10 12:00:01", "am_kill", "remove task"), e)
    }

    @Test fun otherAppsDropped() {
        assertNull(KillLogFilter.parse("10-10 12:00:01.123  1000  1250 I am_kill : [0,4567,com.tencent.mm,900,empty #17]", pkg))
        assertNull(KillLogFilter.parse("10-10 12:00:01.123  1000  1250 I am_proc_died: [0,4567,top.xingzhenxi.phoneboxer,900,19]", pkg))
        assertNull(KillLogFilter.parse("garbage", pkg))
    }

    @Test fun procDiedAndFreeze() {
        assertEquals("am_proc_died", KillLogFilter.parse("10-10 12:00:02.000  1000  1250 I am_proc_died: [0,4567,top.xingzhenxi.phonebox:guardian,900,19]", pkg)?.type)
        assertEquals(KillLogEntry("10-10 12:00:03", "am_freeze", "进程被冻结"),
            KillLogFilter.parse("10-10 12:00:03.000  1000  1250 I am_freeze: [4567,top.xingzhenxi.phonebox]", pkg))
    }

    @Test fun oppoLineSanitized() {
        val e = KillLogFilter.parse("10-10 12:00:04.000  1000  1250 W OplusHansManager: freeze top.xingzhenxi.phonebox uid 10123 with com.tencent.mm caller 13800138000", pkg)!!
        assertEquals("OPPO/OplusHansManager", e.type)
        assertFalse(e.reason.contains("com.tencent.mm"))
        assertFalse(e.reason.contains("13800138000"))
        assertTrue(e.reason.contains("本应用"))
    }

    @Test fun keepsNewestFiftyNewestFirst() {
        val lines = (0 until 80).map { "10-10 12:00:%02d.000  1000  1250 I am_kill : [0,1,$pkg,900,r$it]".format(it % 60) }
        val out = KillLogFilter.filter(lines.asSequence(), pkg)
        assertEquals(50, out.size)
        assertEquals("r79", out.first().reason)
        assertEquals("r30", out.last().reason)
    }
}
