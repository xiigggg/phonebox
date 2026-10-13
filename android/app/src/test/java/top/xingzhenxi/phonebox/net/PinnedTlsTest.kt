package top.xingzhenxi.phonebox.net

import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import javax.net.ssl.SSLException

class PinnedTlsTest {
    private val tls = TlsServer()

    @After fun tearDown() = tls.close()

    private fun get(fp: String) = PinnedTls.configure(OkHttpClient.Builder(), fp).build()
        .newCall(Request.Builder().url(tls.server.url("/x")).build()).execute()

    @Test fun matchingFingerprintConnects() {
        tls.server.enqueue(MockResponse.Builder().body("ok").build())
        get(tls.fingerprint).use { assertEquals("ok", it.body.string()) }
    }

    @Test fun wrongFingerprintFails() {
        assertTlsRejected { get("00".repeat(32)).close() }
    }

    @Test fun noFingerprintUsesSystemTrustAndRejectsSelfSigned() {
        assertTlsRejected { get("").close() }
    }

    /** OkHttp 握手失败后会换路由（如 IPv6）重试，最终异常可能是 ConnectException，SSL 错误挂在 suppressed 里 */
    private fun assertTlsRejected(block: () -> Unit) {
        val e = assertThrows(IOException::class.java) { block() }
        val all = generateSequence<Throwable>(e) { it.cause } + e.suppressed.asSequence()
        assertTrue(all.any { it is SSLException })
    }

    @Test fun clientRejectsPlainHttp() {
        assertThrows(IllegalArgumentException::class.java) {
            PhoneboxClient("http://phonebox.test", "", MemoryTokens())
        }
    }
}
