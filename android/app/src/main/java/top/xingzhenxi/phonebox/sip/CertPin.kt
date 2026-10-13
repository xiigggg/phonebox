package top.xingzhenxi.phonebox.sip

import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** TLS 指纹固定：纯函数部分 + 一次预握手取证书 */
object CertPin {
    /** 规范化为大写、无分隔符的 64 位 hex；格式不对返回 null */
    fun normalize(fp: String): String? {
        val hex = fp.filterNot { it == ':' || it == ' ' || it == '-' }.uppercase()
        if (hex.length != 64 || hex.any { it !in '0'..'9' && it !in 'A'..'F' }) return null
        return hex
    }

    fun sha256Hex(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02X".format(it) }

    fun matches(expected: String, der: ByteArray): Boolean {
        val want = normalize(expected) ?: return false
        // 常量时间比较，避免把比较结果作为侧信道
        return MessageDigest.isEqual(want.toByteArray(), sha256Hex(der).toByteArray())
    }

    fun toPem(der: ByteArray): String {
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN CERTIFICATE-----\n$b64\n-----END CERTIFICATE-----\n"
    }

    class PinMismatchException : Exception("证书指纹不匹配")

    /**
     * 先用 Java TLS 握手一次，只认指纹匹配的叶子证书，返回其 PEM。
     * linphone 随后以这张证书为唯一根 CA 自己再握手，所以两次连接之间换证书也会被拒。
     */
    fun fetchPinnedPem(host: String, port: Int, expected: String, timeoutMs: Int = 5000): String {
        var leaf: X509Certificate? = null
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                throw CertificateException()

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val c = chain?.firstOrNull() ?: throw CertificateException("无证书")
                if (!matches(expected, c.encoded)) throw CertificateException("pin")
                leaf = c
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        try {
            (ctx.socketFactory.createSocket() as SSLSocket).use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                s.soTimeout = timeoutMs
                s.startHandshake()
            }
        } catch (e: Exception) {
            if (leaf == null && generateSequence<Throwable>(e) { it.cause }.any { it.message == "pin" }) {
                throw PinMismatchException()
            }
            if (leaf == null) throw e
        }
        return toPem(leaf!!.encoded)
    }
}
