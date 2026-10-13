package top.xingzhenxi.phonebox.net

import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

/** 自签证书的 HTTPS MockWebServer；客户端靠指纹信任它 */
class TlsServer : AutoCloseable {
    val cert: HeldCertificate = HeldCertificate.Builder().commonName("phonebox.test").addSubjectAlternativeName("localhost").build()
    val fingerprint: String = PinnedTls.sha256Hex(cert.certificate)
    val server = MockWebServer().apply {
        useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
        start()
    }
    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    override fun close() = server.close()
}

class MemoryTokens(var value: Credential? = null) : TokenStore {
    override suspend fun load() = value
    override suspend fun save(credential: Credential?) { value = credential }
}
