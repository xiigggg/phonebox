package top.xingzhenxi.phonebox.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellCommandTest {
    private val self = "top.xingzhenxi.phonebox"
    private val uid = 10_123

    @Test fun producesFixedArgv() {
        assertEquals(listOf("cmd", "deviceidle", "whitelist", "+$self"), ShellCommand.build(ShellTemplate.DeviceIdleWhitelist, self, uid, self))
        assertEquals(listOf("cmd", "netpolicy", "add", "restrict-background-whitelist", "10123"), ShellCommand.build(ShellTemplate.RestrictBackgroundWhitelist, self, uid, self))
        assertEquals(listOf("cmd", "appops", "set", self, "RUN_ANY_IN_BACKGROUND", "allow"), ShellCommand.build(ShellTemplate.RunAnyInBackground, self, uid, self))
        assertEquals(listOf("cmd", "role", "add-role-holder", "--user", "0", "android.app.role.SMS", self), ShellCommand.build(ShellTemplate.RoleSms, self, uid, self))
        assertEquals(listOf("pm", "grant", self, "android.permission.READ_LOGS"), ShellCommand.build(ShellTemplate.GrantReadLogs, self, uid, self))
    }

    @Test fun everyTemplateHasNoShellMetachars() {
        ShellTemplate.entries.forEach { t ->
            ShellCommand.build(t, self, uid, self)!!.forEach { a -> assert(a.none { it in " ;|&$`<>\n" }) { "$t: $a" } }
        }
    }

    @Test fun rejectsOtherPackage() {
        assertNull(ShellCommand.build(ShellTemplate.DeviceIdleWhitelist, "com.evil", uid, self))
    }

    @Test fun rejectsSpaceAndSemicolon() {
        assertNull(ShellCommand.build(ShellTemplate.DeviceIdleWhitelist, "$self; reboot", uid, "$self; reboot"))
        assertNull(ShellCommand.build(ShellTemplate.DeviceIdleWhitelist, "top.x ingzhenxi.phonebox", uid, "top.x ingzhenxi.phonebox"))
    }

    @Test fun rejectsNonAppUid() {
        assertNull(ShellCommand.build(ShellTemplate.RestrictBackgroundWhitelist, self, 2000, self))
        assertNull(ShellCommand.build(ShellTemplate.RestrictBackgroundWhitelist, self, 0, self))
        assertEquals("1010123", ShellCommand.build(ShellTemplate.RestrictBackgroundWhitelist, self, 1_010_123, self)!!.last())
    }

    @Test fun execResultParse() {
        assertEquals(ExecResult(0, "Success"), ExecResult.parse("0\tSuccess"))
        assertEquals(-1, ExecResult.parse(null).code)
        assertEquals(ExecResult(255, "a\tb"), ExecResult.parse("255\ta\tb"))
    }

    @Test fun installUpdateTakesOnlySizeAndReadsStdin() {
        assertEquals(listOf("pm", "install", "-r", "-S", "12345"), ShellCommand.installUpdate(12345))
        assertNull(ShellCommand.installUpdate(0))
        assertNull(ShellCommand.installUpdate(-1))
        assertNull(ShellCommand.installUpdate(ShellCommand.MAX_APK_BYTES + 1))
    }

    @Test fun uidMustBeTheBinderCaller() {
        assertTrue(ShellCommand.isCaller(10_123, 10_123))
        assertTrue(ShellCommand.isCaller(1_010_123, 1_010_123))
        // 别的应用的 uid、shell/system uid 都不行
        assertFalse(ShellCommand.isCaller(10_124, 10_123))
        assertFalse(ShellCommand.isCaller(2000, 2000))
        assertFalse(ShellCommand.isCaller(1000, 1000))
        assertFalse(ShellCommand.isCaller(-1, -1))
    }
}
