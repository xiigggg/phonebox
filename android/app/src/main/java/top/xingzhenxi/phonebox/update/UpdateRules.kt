package top.xingzhenxi.phonebox.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** nas/update/README.md 里的 version.json */
@Serializable
data class UpdateManifest(
    val packageName: String,
    val versionCode: Long,
    val versionName: String = "",
    val apk: String,
    val sha256: String,
    val signerSha256: String,
    val notes: String = "",
    val published: Long = 0,
)

/** 下载下来的 APK 实际读到的信息，由 PackageManager 解析得出 */
data class ApkFacts(
    val sha256: String,
    val packageName: String?,
    val versionCode: Long?,
    /** APK 的签名证书 SHA-256；多签名者或读不出时按原样给出，交给规则拒绝 */
    val signers: List<String>,
)

/** 纯规则，不碰 Android API，便于单元测试。返回 null 表示通过，否则是拒绝原因 */
object UpdateRules {
    private val json = Json { ignoreUnknownKeys = true }
    private val HEX64 = Regex("[0-9a-f]{64}")
    // 只能是同目录下的文件名，挡住 ../ 和绝对地址
    private val APK_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}\\.apk")

    fun parse(text: String): UpdateManifest = json.decodeFromString(UpdateManifest.serializer(), text).also {
        require(HEX64.matches(it.sha256)) { "sha256 格式不对" }
        require(HEX64.matches(it.signerSha256)) { "signerSha256 格式不对" }
        require(APK_NAME.matches(it.apk)) { "apk 文件名不合法" }
    }

    /** 下载前：包名一致且版本更高才值得下 */
    fun checkManifest(m: UpdateManifest, selfPackage: String, currentVersion: Long): String? = when {
        m.packageName != selfPackage -> "包名不符"
        m.versionCode <= currentVersion -> "版本不高于当前（${m.versionCode} ≤ $currentVersion）"
        else -> null
    }

    /** 下载后：sha256、APK 自身的包名版本、签名证书（同时等于清单值和当前已安装 App 的证书） */
    fun checkApk(m: UpdateManifest, apk: ApkFacts, installedSigners: List<String>, selfPackage: String, currentVersion: Long): String? = when {
        apk.sha256 != m.sha256 -> "sha256 不符"
        apk.packageName != selfPackage -> "APK 包名不符"
        apk.versionCode == null || apk.versionCode <= currentVersion -> "APK 版本不高于当前"
        apk.versionCode != m.versionCode -> "APK 版本与清单不符"
        apk.signers.size != 1 -> "APK 签名者数量异常（${apk.signers.size}）"
        apk.signers[0] != m.signerSha256 -> "签名证书与清单不符"
        // 以已安装 App 的证书为准：只信清单的话，改了清单就能换签名
        installedSigners.size != 1 || apk.signers[0] != installedSigners[0] -> "签名证书与当前 App 不符"
        else -> null
    }

    fun sha256Hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(f: File): String = f.inputStream().use(::sha256Hex)

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
