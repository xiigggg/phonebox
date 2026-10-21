package top.xingzhenxi.phonebox.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootTest {
    @Test fun quoteNeutralizesShellMetacharacters() {
        assertEquals("'top.example.app'", RootShell.quote("top.example.app"))
        assertEquals("'a'\\''b'", RootShell.quote("a'b"))
        assertEquals("'\$(reboot);`id`'", RootShell.quote("\$(reboot);`id`"))
    }

    @Test fun provider() {
        assertEquals("KernelSU", RootShell.providerOf("32680:KernelSU"))
        assertEquals("Magisk", RootShell.providerOf("27.0:MAGISKSU"))
        assertEquals("未知", RootShell.providerOf(""))
    }

    @Test fun cgroupFreezePath() {
        assertEquals("/sys/fs/cgroup/apps/uid_10490/pid_123/cgroup.freeze", CgroupPath.freezeFile("0::/apps/uid_10490/pid_123"))
        assertNull(CgroupPath.freezeFile("0::/apps/../../etc"))
        assertNull(CgroupPath.freezeFile("0::relative"))
    }

    @Test fun procState() {
        assertEquals('S', CgroupPath.stateOf("123 (top.example) S 1 2 3"))
        assertEquals('T', CgroupPath.stateOf("123 (we) ird) name) T 1 2"))
        assertNull(CgroupPath.stateOf("garbage"))
    }

    @Test fun appDataContextOnly() {
        assertTrue(CgroupPath.isAppDataContext("u:object_r:app_data_file:s0:c234,c257,c512,c768"))
        assertTrue(!CgroupPath.isAppDataContext("u:object_r:system_file:s0"))
        assertTrue(!CgroupPath.isAppDataContext("u:object_r:app_data_file:s0 /system"))
    }

    @Test fun stateRoundTrip() {
        val s = GuardianState(pid = 9, running = true, lastCheckAt = 5, lastLaunchAt = 6, lastReason = "Root：进程不在", launches = 2, thaws = 1)
        assertEquals(s, GuardianState.decode(s.encode()))
        assertEquals(GuardianState(running = false), GuardianState.decode("junk"))
    }

    @Test fun launchCommandOnlyAcceptsInstalledApkPath() {
        val ok = RootGuardianLaunch.command("/data/app/~~abc==/top.example-XYZ==/base.apk")!!
        assertTrue(ok.startsWith("CLASSPATH=/data/app/~~abc==/top.example-XYZ==/base.apk setsid app_process /system/bin "))
        assertNull(RootGuardianLaunch.command("/data/app/x/base.apk;reboot"))
        assertNull(RootGuardianLaunch.command("/sdcard/evil/base.apk"))
        assertNull(RootGuardianLaunch.command("/data/app/../local/tmp/base.apk"))
        assertNull(RootGuardianLaunch.command("/data/app/a b/base.apk"))
    }
}
