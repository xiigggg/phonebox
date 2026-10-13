package top.xingzhenxi.phonebox.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRulesTest {
    private val pkg = "top.xingzhenxi.phonebox"
    private val sha = "a".repeat(64)
    private val signer = "b".repeat(64)
    private val manifestJson = """
        {"packageName":"$pkg","versionCode":2,"versionName":"0.2.0","apk":"phonebox-2.apk",
         "sha256":"$sha","signerSha256":"$signer","notes":"修复","published":1791649186,"extra":1}
    """.trimIndent()
    private val m = UpdateRules.parse(manifestJson)
    private val good = ApkFacts(sha, pkg, 2, listOf(signer))

    @Test fun parsesManifest() {
        assertEquals(2L, m.versionCode)
        assertEquals("phonebox-2.apk", m.apk)
        assertEquals("修复", m.notes)
    }

    @Test fun rejectsBadManifestFields() {
        listOf(
            manifestJson.replace("phonebox-2.apk", "../x.apk"),
            manifestJson.replace("phonebox-2.apk", "https://evil/x.apk"),
            manifestJson.replace(sha, "A".repeat(64)),
            manifestJson.replace(signer, "bb:cc"),
        ).forEach { j -> assertTrue(runCatching { UpdateRules.parse(j) }.isFailure) }
    }

    @Test fun manifestNeedsSamePackageAndHigherVersion() {
        assertNull(UpdateRules.checkManifest(m, pkg, 1))
        assertNotNull(UpdateRules.checkManifest(m, pkg, 2))
        assertNotNull(UpdateRules.checkManifest(m, pkg, 3))
        assertNotNull(UpdateRules.checkManifest(m, "other.pkg", 1))
    }

    @Test fun acceptsWhenEverythingMatches() {
        assertNull(UpdateRules.checkApk(m, good, listOf(signer), pkg, 1))
    }

    @Test fun rejectsShaMismatch() {
        assertEquals("sha256 不符", UpdateRules.checkApk(m, good.copy(sha256 = "c".repeat(64)), listOf(signer), pkg, 1))
    }

    @Test fun rejectsSignerMismatch() {
        val other = "d".repeat(64)
        // APK 签名与清单不符
        assertEquals("签名证书与清单不符", UpdateRules.checkApk(m, good.copy(signers = listOf(other)), listOf(signer), pkg, 1))
        // 清单被改成攻击者的证书：与已安装 App 不符照样拒
        val forged = m.copy(signerSha256 = other)
        assertEquals("签名证书与当前 App 不符", UpdateRules.checkApk(forged, good.copy(signers = listOf(other)), listOf(signer), pkg, 1))
        assertNotNull(UpdateRules.checkApk(m, good.copy(signers = emptyList()), listOf(signer), pkg, 1))
        assertNotNull(UpdateRules.checkApk(m, good.copy(signers = listOf(signer, other)), listOf(signer), pkg, 1))
    }

    @Test fun rejectsNonUpgrade() {
        assertNotNull(UpdateRules.checkApk(m, good, listOf(signer), pkg, 2))
        assertNotNull(UpdateRules.checkApk(m, good.copy(versionCode = 1), listOf(signer), pkg, 1))
        assertNotNull(UpdateRules.checkApk(m, good.copy(versionCode = 3), listOf(signer), pkg, 1))
        assertNotNull(UpdateRules.checkApk(m, good.copy(packageName = "x.y"), listOf(signer), pkg, 1))
    }

    @Test fun sha256Hex() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", UpdateRules.sha256Hex(ByteArray(0)))
    }
}
