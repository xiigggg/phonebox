package top.xingzhenxi.phonebox.net

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * 证书指纹固定。NAS 用的多是自签或内网证书，系统 CA 校验不过，
 * 所以配置了指纹时只认叶子证书的 SHA-256，别的一概不信。
 */
object PinnedTls {
    /** [fingerprint] 为空表示走系统默认校验；非空须是 [top.xingzhenxi.phonebox.config.Validation.normalizeFingerprint] 之后的形式 */
    fun configure(builder: OkHttpClient.Builder, fingerprint: String): OkHttpClient.Builder {
        if (fingerprint.isEmpty()) return builder
        val tm = FingerprintTrustManager(fingerprint)
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        return builder
            .sslSocketFactory(ctx.socketFactory, tm)
            // 指纹已精确锁定到一张证书，比“CA 签发 + 主机名匹配”更强；
            // 自签证书常常没有匹配的 SAN（例如用 IP 访问），再校验主机名只会误杀
            .hostnameVerifier { _, _ -> true }
    }

    fun sha256Hex(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
}

class FingerprintTrustManager(private val expected: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("empty chain")
        if (PinnedTls.sha256Hex(leaf) != expected) {
            throw CertificateException("certificate fingerprint mismatch")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("client certificates not supported")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
