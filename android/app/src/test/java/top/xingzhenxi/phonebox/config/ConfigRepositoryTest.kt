package top.xingzhenxi.phonebox.config

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

class ConfigRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AesGcmCredentialCipher { key }
    private lateinit var file: File

    private fun repo(): ConfigRepository {
        file = File(tmp.root, "settings.preferences_pb")
        return ConfigRepository(PreferenceDataStoreFactory.create(scope = scope) { file }, cipher)
    }

    @After fun tearDown() = scope.cancel()

    private val sip = SipConfig("sip.example.com", 5061, SipTransport.TCP, "201", "AB".repeat(32), srtp = true)
    private val pb = PhoneboxConfig("https://phonebox.example.com/", "")

    @Test fun roundTripWithoutPlaintextOnDisk() = runBlocking {
        val r = repo()
        assertFalse(r.config.first().wizardCompleted)
        r.save(sip, "s3cret-test-pass", pb, wizardCompleted = true)
        r.saveSessionToken("token-for-test")

        val c = r.config.first()
        assertTrue(c.wizardCompleted)
        assertEquals("sip.example.com", c.sip.host)
        assertEquals(SipTransport.TCP, c.sip.transport)
        assertEquals("ab".repeat(32), c.sip.tlsFingerprint)
        assertTrue(c.sip.hasPassword)
        assertEquals("https://phonebox.example.com", c.phonebox.baseUrl)
        assertEquals("s3cret-test-pass", r.sipPassword())
        assertEquals("token-for-test", r.sessionToken())

        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains("s3cret-test-pass"))
        assertFalse(raw.contains("token-for-test"))
    }

    @Test fun nullPasswordKeepsExisting() = runBlocking {
        val r = repo()
        r.save(sip, "first-pass", pb, true)
        r.save(sip.copy(port = 5062), null, pb, true)
        assertEquals("first-pass", r.sipPassword())
        assertEquals(5062, r.config.first().sip.port)
    }

    @Test fun changingServerDropsToken() = runBlocking {
        val r = repo()
        r.save(sip, "p", pb, true)
        r.saveSessionToken("t")
        r.save(sip, null, pb.copy(baseUrl = "https://other.example.com"), true)
        assertNull(r.sessionToken())
    }

    @Test fun cipherUsesRandomIvAndDetectsTampering() {
        val a = cipher.encrypt("same")
        val b = cipher.encrypt("same")
        assertTrue(a != b)
        assertEquals("same", cipher.decrypt(a))
        val bytes = java.util.Base64.getDecoder().decode(a)
        bytes[bytes.size - 1] = (bytes.last().toInt() xor 1).toByte()
        assertNull(cipher.decrypt(java.util.Base64.getEncoder().encodeToString(bytes)))
        assertNull(cipher.decrypt("not base64!"))
    }
}
