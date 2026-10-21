package top.xingzhenxi.phonebox.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianPolicyTest {
    private val pkg = "top.xingzhenxi.phonebox"

    @Test fun detectsRunningService() {
        val out = """
            ACTIVITY MANAGER SERVICES (dumpsys activity services)
              User 0 active services:
              * ServiceRecord{8a1b2c u0 top.xingzhenxi.phonebox/.service.KeepAliveService c:top.xingzhenxi.phonebox}
                intent={act=top.xingzhenxi.phonebox.action.KEEPALIVE_START}
        """.trimIndent()
        assertTrue(GuardianPolicy.isServiceRunning(out, pkg))
    }

    @Test fun nothingMeansNotRunning() {
        assertFalse(GuardianPolicy.isServiceRunning("ACTIVITY MANAGER SERVICES (dumpsys activity services)\n  (nothing)", pkg))
        assertFalse(GuardianPolicy.isServiceRunning("* ServiceRecord{1 u0 com.other/.service.KeepAliveService}", pkg))
    }

    @Test fun truncatedDumpIsNotEvidence() {
        assertFalse(GuardianPolicy.isCompleteDump(""))
        assertFalse(GuardianPolicy.isCompleteDump("  User 0 active services:"))
        assertTrue(GuardianPolicy.isCompleteDump("ACTIVITY MANAGER SERVICES (dumpsys activity services)\n  (nothing)"))
    }

    @Test fun launchArgv() {
        assertEquals(
            listOf("am", "start-foreground-service", "-n", "$pkg/.shizuku.GuardianTargetService", "--es", "reason", "x"),
            GuardianPolicy.launchArgv(pkg, "x"),
        )
    }
}
