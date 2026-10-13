package top.xingzhenxi.phonebox.shizuku

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ShizukuFailureTest {
    private val snap = ShizukuSnapshot(version = 13, uid = 2000, ping = false, granted = true)

    @Test fun fieldsCoverAllStateAndException() {
        val f = ShizukuFailure.fields(FailStep.Bind, IllegalStateException("binder haven't been received"), snap).toMap()
        assertEquals("绑定 UserService", f["步骤"])
        assertEquals("IllegalStateException: binder haven't been received", f["异常"])
        assertEquals("13", f["Shizuku 版本"])
        assertEquals("2000（adb/shell）", f["服务 uid"])
        assertEquals("false", f["pingBinder"])
        assertEquals("true", f["已授权"])
    }

    @Test fun unknownFieldsAndRoot() {
        val f = ShizukuFailure.fields(FailStep.Exec, null, ShizukuSnapshot(null, 0, true, null), "RoleSms").toMap()
        assertEquals("执行模板 RoleSms", f["步骤"])
        assertEquals(null, f["异常"])
        assertEquals("未知", f["Shizuku 版本"])
        assertEquals("0（root）", f["服务 uid"])
        assertEquals("未知", f["已授权"])
    }

    @Test fun logLineHasAttemptAndKeys() {
        val line = ShizukuFailure.logLine(FailStep.Timeout, RuntimeException("x"), snap, attempt = 2)
        assertTrue(line, line.startsWith("第2次; 步骤=绑定超时; 异常=RuntimeException: x; Shizuku 版本=13; 服务 uid=2000（adb/shell）"))
    }

    @Test fun timeoutIsClassified() = runTest {
        val e = runCatching { withTimeout(1) { kotlinx.coroutines.delay(10) } }.exceptionOrNull()!!
        assertEquals(FailStep.Timeout, ShizukuFailure.stepOf(e))
        assertEquals(FailStep.Bind, ShizukuFailure.stepOf(IllegalStateException()))
    }

    @Test fun execLineTruncatesOutput() {
        val line = ShizukuFailure.execLine("RoleSms", 255, "e".repeat(300))
        assertEquals("RoleSms 退出码=255 输出=" + "e".repeat(200), line)
    }

    @Test fun retrySucceedsSecondTime() = runTest {
        val failures = mutableListOf<Int>()
        val r = ShizukuFailure.retryOnce(1_500, { n, _ -> failures += n }) { n ->
            if (n == 1) throw IllegalStateException("not ready") else "ok$n"
        }
        assertEquals("ok2", r)
        assertEquals(listOf(1), failures)
        assertEquals(1_500, currentTime)
    }

    @Test fun retryBothFail() = runTest {
        val failures = mutableListOf<Int>()
        var calls = 0
        try {
            ShizukuFailure.retryOnce<String>(1_500, { n, _ -> failures += n }) { n -> calls++; throw IllegalStateException("fail$n") }
            fail()
        } catch (e: IllegalStateException) {
            assertEquals("fail2", e.message)
            assertEquals("fail1", e.suppressed.single().message)
        }
        assertEquals(2, calls)
        assertEquals(listOf(1, 2), failures)
    }

    @Test fun noRetryOnSuccess() = runTest {
        var calls = 0
        assertEquals(1, ShizukuFailure.retryOnce(1_500, { _, _ -> fail() }) { calls++; 1 })
        assertEquals(1, calls)
        assertEquals(0, currentTime)
    }
}
