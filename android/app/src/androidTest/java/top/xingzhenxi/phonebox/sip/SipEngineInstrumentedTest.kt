package top.xingzhenxi.phonebox.sip

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.sin

/**
 * 对一台测试用 Asterisk（例如本机按 server/ 部署、TEST_ECHO=yes 的实例）的端到端测试。账号通过 instrumentation 参数传入，不进代码：
 *   -e sipHost <宿主机地址，模拟器用其宿主别名> -e sipPort 25060 -e sipTlsPort 25061 -e sipUser 201 -e sipPass ... -e sipPin AA:BB:...
 * 来电用例需要宿主在测试开始后让 Asterisk 呼叫该分机（如 asterisk -rx "channel originate ..."），并传 -e expectIncoming 1。
 */
@RunWith(AndroidJUnit4::class)
class SipEngineInstrumentedTest {
    private val args = InstrumentationRegistry.getArguments()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var engine: SipEngine

    private fun arg(k: String): String? = args.getString(k)?.takeIf { it.isNotBlank() }

    private fun cfg(transport: SipTransport, pin: String? = null) = SipAccountConfig(
        host = arg("sipHost") ?: "192.168.1.10",
        port = if (transport == SipTransport.TLS) (arg("sipTlsPort") ?: "25061").toInt() else (arg("sipPort") ?: "25060").toInt(),
        transport = transport,
        username = arg("sipUser")!!,
        password = arg("sipPass")!!,
        certSha256 = pin,
    )

    @Before fun setUp() {
        assumeTrue("未传入测试账号，跳过", arg("sipUser") != null && arg("sipPass") != null)
        engine = SipEngine(ctx) { false }
        engine.setFileAudio(true, makeToneWav().absolutePath)
        engine.start()
    }

    @After fun tearDown() {
        if (::engine.isInitialized) engine.stop()
    }

    private fun awaitReg(ms: Long = 20_000): RegState = runBlocking {
        withTimeout(ms) { engine.registration.first { it is RegState.Ok || it is RegState.Failed } }
    }

    private fun registerOk(t: SipTransport, pin: String? = null) {
        engine.applyAccount(cfg(t, pin))
        val r = awaitReg()
        Log.i(TAG, "$t 注册结果：$r")
        assertEquals(RegState.Ok, r)
    }

    @Test fun registerUdp() = registerOk(SipTransport.UDP)

    @Test fun registerTcp() = registerOk(SipTransport.TCP)

    @Test fun registerTlsPinned() = registerOk(SipTransport.TLS, arg("sipPin")!!)

    @Test fun registerTlsWrongPinRejected() {
        val wrong = (1..32).joinToString(":") { "AB" }
        engine.applyAccount(cfg(SipTransport.TLS, wrong))
        val r = awaitReg()
        Log.i(TAG, "错误指纹结果：$r")
        assertEquals(RegState.Failed("证书指纹不匹配"), r)
    }

    @Test fun emergencyNumberNotDialed() {
        val e = SipEngine(ctx) { it == "110" }
        e.start()
        try {
            assertEquals(null, e.dial("110"))
        } finally { e.stop() }
    }

    @Test fun echoCallHasRtpBothWays() {
        registerOk(SipTransport.UDP)
        val id = engine.dial("600")
        assertNotNull(id)
        runBlocking { withTimeout(15_000) { engine.calls.first { l -> l.any { it.id == id && it.state == CallState.Connected } } } }
        Thread.sleep(5_000)
        val s = engine.audioStats(id!!)
        Log.i(TAG, "回声通话统计：$s")
        assertNotNull(s)
        assertTrue("发送包数 ${s!!.packetsSent}", s.packetsSent > 0)
        assertTrue("接收包数 ${s.packetsReceived}", s.packetsReceived > 0)
        engine.hangup(id)
        awaitNoCalls()
    }

    @Test fun incomingCallAcceptAndHangup() {
        assumeTrue("未要求来电用例", arg("expectIncoming") == "1")
        registerOk(SipTransport.UDP)
        Log.i(TAG, "READY_FOR_INCOMING")
        val call = runBlocking {
            withTimeout(90_000) { engine.calls.first { l -> l.any { it.state == CallState.Incoming } } }
        }.first { it.state == CallState.Incoming }
        Log.i(TAG, "来电：number=${call.remoteNumber} display=${call.remoteDisplay} incoming=${call.incoming}")
        assertTrue(call.incoming)
        assertEquals(arg("expectNumber") ?: "13800000000", call.remoteNumber)
        engine.accept(call.id)
        runBlocking { withTimeout(10_000) { engine.calls.first { l -> l.any { it.id == call.id && it.state == CallState.Connected } } } }
        Log.i(TAG, "来电已接通")
        Thread.sleep(2_000)
        engine.hangup(call.id)
        runBlocking { withTimeout(10_000) { engine.calls.first { l -> l.any { it.id == call.id && it.state == CallState.Ended } } } }
        Log.i(TAG, "来电已结束")
        awaitNoCalls()
        Log.i(TAG, "通话列表已清空")
    }

    private fun awaitNoCalls() {
        try {
            runBlocking { withTimeout(10_000) { engine.calls.first { it.isEmpty() } } }
        } catch (e: Exception) {
            throw AssertionError("通话列表未清空：${engine.calls.value}", e)
        }
    }

    /** 模拟器没有声卡，用 440Hz 正弦 wav 当麦克风输入 */
    private fun makeToneWav(): File {
        val f = File(ctx.cacheDir, "tone8k.wav")
        if (f.exists()) return f
        val rate = 8000
        val n = rate * 30
        RandomAccessFile(f, "rw").use { raf ->
            fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
            fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
            raf.write("RIFF".toByteArray()); raf.write(le32(36 + n * 2)); raf.write("WAVEfmt ".toByteArray())
            raf.write(le32(16)); raf.write(le16(1)); raf.write(le16(1)); raf.write(le32(rate)); raf.write(le32(rate * 2))
            raf.write(le16(2)); raf.write(le16(16)); raf.write("data".toByteArray()); raf.write(le32(n * 2))
            val buf = ByteArray(n * 2)
            for (i in 0 until n) {
                val v = (sin(2 * Math.PI * 440 * i / rate) * 8000).toInt()
                buf[2 * i] = v.toByte(); buf[2 * i + 1] = (v shr 8).toByte()
            }
            raf.write(buf)
        }
        return f
    }

    companion object { const val TAG = "PB.SipTest" }
}
