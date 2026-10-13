package top.xingzhenxi.phonebox.net

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class PhoneboxClientTest {
    private val tls = TlsServer()
    private val tokens = MemoryTokens()
    private val client = PhoneboxClient(tls.baseUrl, tls.fingerprint, tokens)
    private val fixture = javaClass.getResource("/feed-fixture.json")!!.readText()

    @After fun tearDown() = tls.close()

    private fun enqueue(code: Int, body: String = "", vararg headers: Pair<String, String>) {
        val b = MockResponse.Builder().code(code).body(body)
        headers.forEach { (k, v) -> b.addHeader(k, v) }
        tls.server.enqueue(b.build())
    }

    @Test fun loginFallsBackToCookieWhenTokenApiMissing() = runBlocking {
        enqueue(404)
        enqueue(303, "", "Location" to "/app", "Set-Cookie" to "pb_token=abc-123_x; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=2592000")
        val r = client.login("000000")
        assertEquals(LoginResult.Success(Credential.Cookie("abc-123_x")), r)
        assertEquals(Credential.Cookie("abc-123_x"), tokens.value)
        assertEquals("/api/token", tls.server.takeRequest().target)
        val login = tls.server.takeRequest()
        assertEquals("/login", login.target)
        assertEquals("code=000000", login.body!!.utf8())
    }

    @Test fun loginPrefersDeviceToken() = runBlocking {
        enqueue(200, """{"token":"dev-tok"}""")
        assertEquals(LoginResult.Success(Credential.Bearer("dev-tok")), client.login("000000"))
        assertEquals(1, tls.server.requestCount)
    }

    @Test fun loginWrongPinAndLocked() = runBlocking {
        enqueue(404); enqueue(403, "<html>密码不对</html>")
        assertEquals(LoginResult.WrongPin, client.login("111111"))
        enqueue(404); enqueue(429, "", "Retry-After" to "540")
        assertEquals(LoginResult.Locked(540), client.login("111111"))
        assertNull(tokens.value)
    }

    @Test fun feedParsesFixtureWithCookie() = runBlocking {
        tokens.value = Credential.Cookie("ck")
        enqueue(200, fixture, "Content-Type" to "application/json; charset=utf-8")
        val f = client.fetchFeed()
        assertEquals("pb_token=ck", tls.server.takeRequest().headers["Cookie"])
        assertEquals(6, f.sms.size)
        assertEquals(4, f.calls.size)
        val first = f.sms[0]
        assertEquals("123456", first.code)
        assertEquals("验证码", first.codeLabel)
        assertEquals("测试银行", first.sig)
        assertNull(f.sms[1].code)
        // 缺字段的行用默认值
        assertEquals("", f.sms[5].body)
        assertEquals(true, f.calls[0].block)
        assertEquals("NO ANSWER", f.calls[0].disposition)
        assertEquals(42, f.calls[1].billsec)
        assertEquals("r1", f.versions.rules)
    }

    @Test fun bearerHeaderAndSinceParam() = runBlocking {
        tokens.value = Credential.Bearer("bt")
        enqueue(200, "{}", "Content-Type" to "application/json")
        client.feedRaw(since = 1760000000)
        val req = tls.server.takeRequest()
        assertEquals("Bearer bt", req.headers["Authorization"])
        assertEquals("/api/feed?since=1760000000", req.target)
    }

    @Test fun unauthorizedBecomesSessionExpired() = runBlocking {
        tokens.value = Credential.Cookie("old")
        enqueue(401)
        try {
            client.fetchFeed(); fail()
        } catch (_: SessionExpiredException) {
        }
        assertNull(tokens.value)
        // 旧服务端未登录时给登录页 HTML
        tokens.value = Credential.Cookie("old")
        enqueue(200, "<html></html>", "Content-Type" to "text/html; charset=utf-8")
        try {
            client.fetchFeed(); fail()
        } catch (_: SessionExpiredException) {
        }
    }

    @Test fun sendHeadersAndErrors() = runBlocking {
        tokens.value = Credential.Cookie("ck")
        enqueue(200, """{"ok": true, "msg": "queued"}""")
        assertEquals(SendResult.Ok, client.send("13800000001", " 你好 "))
        val req = tls.server.takeRequest()
        assertEquals(client.origin, req.headers["Origin"])
        assertEquals("1", req.headers["X-Phonebox"])
        assertTrue(req.headers["Content-Type"]!!.startsWith("application/json"))
        assertEquals("""{"to":"13800000001","text":"你好"}""", req.body!!.utf8())

        enqueue(400, """{"ok": false, "msg": "invalid_number"}""")
        assertEquals(SendResult.Rejected("invalid_number"), client.send("13800000001", "x"))
        enqueue(401)
        assertEquals(SendResult.SessionExpired, client.send("13800000001", "x"))
    }

    @Test fun sendValidatesLocally() = runBlocking {
        tokens.value = Credential.Cookie("ck")
        assertEquals(SendResult.Rejected("invalid_number"), client.send("12", "x"))
        assertEquals(SendResult.Rejected("invalid_number"), client.send("138-0000", "x"))
        assertEquals(SendResult.Rejected("invalid_text"), client.send("13800000001", "  "))
        assertEquals(SendResult.Rejected("invalid_text"), client.send("13800000001", "a".repeat(601)))
        assertEquals(0, tls.server.requestCount)
    }

    @Test fun originOmitsDefaultPort() {
        assertEquals("https://sms.example.com", PhoneboxClient("https://sms.example.com/", "", tokens).origin)
        assertEquals("https://sms.example.com:8443", PhoneboxClient("https://sms.example.com:8443", "", tokens).origin)
    }

    private fun collect(lastId: String?): List<StreamEvent> = runBlocking {
        val q = LinkedBlockingQueue<StreamEvent>()
        client.openEvents(lastId) { q.add(it) }
        val out = mutableListOf<StreamEvent>()
        while (true) {
            val e = q.poll(5, TimeUnit.SECONDS) ?: break
            out += e
            if (e is StreamEvent.Closed || e is StreamEvent.Failed || e is StreamEvent.Unsupported ||
                e is StreamEvent.SessionExpired
            ) break
        }
        out
    }

    @Test fun sseParsesEventsAndSendsLastEventId() {
        tokens.value = Credential.Bearer("bt")
        val sms = """{"ts":1760000500,"from":"13800000002","body":"hi","kind":"个人"}"""
        val call = """{"ts":1760000501,"number":"13800000003","dir":"in","disposition":"ANSWERED","block":false}"""
        enqueue(
            200,
            "event: ping\ndata:\n\nid: 1760000500000-1\nevent: sms\ndata: $sms\n\nid: 1760000501000-2\nevent: call\ndata: $call\n\n",
            "Content-Type" to "text/event-stream",
        )
        val events = collect("1760000400000-9")
        assertEquals("1760000400000-9", tls.server.takeRequest().headers["Last-Event-ID"])
        assertEquals(StreamEvent.Opened, events[0])
        assertEquals(StreamEvent.Ping, events[1])
        val s = events[2] as StreamEvent.Sms
        assertEquals("1760000500000-1", s.id)
        assertEquals("hi", s.item.body)
        val c = events[3] as StreamEvent.Call
        assertEquals("13800000003", c.item.number)
        assertEquals(StreamEvent.Closed, events[4])
    }

    @Test fun sse404MeansUnsupported() {
        tokens.value = Credential.Cookie("ck")
        enqueue(404)
        assertEquals(listOf(StreamEvent.Unsupported), collect(null))
        enqueue(401)
        assertEquals(listOf(StreamEvent.SessionExpired), collect(null))
    }

    @Test fun cookieParsing() {
        assertEquals("v", cookieValue("pb_token=v; Path=/", "pb_token"))
        assertNull(cookieValue("other=v", "pb_token"))
        assertNull(cookieValue("pb_token=; Max-Age=0", "pb_token"))
    }

    @Test fun credentialEncodingRoundTrip() {
        listOf(Credential.Cookie("a:b"), Credential.Bearer("x")).forEach {
            assertEquals(it, Credential.decode(it.encode()))
        }
        assertNull(Credential.decode("garbage"))
    }

    @Test fun listsReadAndWrite() = runBlocking {
        tokens.value = Credential.Bearer("t")
        enqueue(200, """{"black":["+8613800000009","95000"],"white":[],"ok":true,"at":1}""", "Content-Type" to "application/json")
        assertEquals(AdminResult.Ok(BlockLists(listOf("+8613800000009", "95000"), emptyList(), true)), client.lists())
        assertEquals("Bearer t", tls.server.takeRequest().headers["Authorization"])

        enqueue(200, """{"ok":true,"msg":"ok"}""", "Content-Type" to "application/json")
        assertEquals(AdminResult.Ok(Unit), client.editList(BlockList.Black, " 13800000009 ", add = true))
        val post = tls.server.takeRequest()
        assertEquals("/api/lists", post.target)
        assertEquals("""{"list":"black","number":"13800000009","action":"add"}""", post.body!!.utf8())
        assertTrue(post.headers["Content-Type"]!!.startsWith("application/json"))
    }

    @Test fun listNumberValidatedLocallyLikeServer() = runBlocking {
        tokens.value = Credential.Bearer("t")
        listOf("12", "abc", "138 0000", "++123", "1".repeat(21), "12a3").forEach {
            assertEquals(it, AdminResult.Rejected("invalid_number"), client.editList(BlockList.White, it, add = true))
        }
        assertEquals(0, tls.server.requestCount)
        assertTrue(ListRules.valid("+8613800000009"))
    }

    @Test fun oldServerIsUnsupported() = runBlocking {
        tokens.value = Credential.Cookie("ck")
        enqueue(404)
        assertEquals(AdminResult.Unsupported, client.lists())
        // cookie 失效时旧服务端回登录页
        enqueue(200, "<html>login</html>", "Content-Type" to "text/html; charset=utf-8")
        assertEquals(AdminResult.Unsupported, client.hintOnly())
    }

    @Test fun settingsAndContacts() = runBlocking {
        tokens.value = Credential.Cookie("ck")
        enqueue(200, """{"ok":true,"hint_only":true}""", "Content-Type" to "application/json")
        assertEquals(AdminResult.Ok(true), client.setHintOnly(true))
        val r = tls.server.takeRequest()
        assertEquals("""{"hint_only":true}""", r.body!!.utf8())
        // cookie 写接口要过服务端 CSRF 校验
        assertEquals("1", r.headers["X-Phonebox"])
        assertEquals(client.origin, r.headers["Origin"])

        enqueue(200, """{"ok":true,"count":2}""", "Content-Type" to "application/json")
        assertEquals(AdminResult.Ok(2), client.uploadContacts(listOf("a".repeat(64), "b".repeat(64))))
        assertEquals("""{"hashes":["${"a".repeat(64)}","${"b".repeat(64)}"]}""", tls.server.takeRequest().body!!.utf8())

        enqueue(401)
        assertEquals(AdminResult.SessionExpired, client.hintOnly())
        assertNull(tokens.value)
    }
}
