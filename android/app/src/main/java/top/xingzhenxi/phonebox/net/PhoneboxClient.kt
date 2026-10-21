package top.xingzhenxi.phonebox.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 两种登录态：现网只有 cookie 会话；NAS 补丁上线后用长期设备令牌 */
sealed interface Credential {
    data class Cookie(val value: String) : Credential
    data class Bearer(val value: String) : Credential

    /** ConfigRepository 只存一个字符串，这里带上前缀区分类型 */
    fun encode(): String = when (this) {
        is Cookie -> "cookie:$value"
        is Bearer -> "bearer:$value"
    }

    companion object {
        fun decode(s: String?): Credential? = when {
            s == null -> null
            s.startsWith("bearer:") -> Bearer(s.removePrefix("bearer:"))
            s.startsWith("cookie:") -> Cookie(s.removePrefix("cookie:"))
            else -> null
        }
    }
}

/** 令牌存取抽象，生产实现见 [ConfigTokenStore]，测试用内存实现 */
interface TokenStore {
    suspend fun load(): Credential?
    suspend fun save(credential: Credential?)
}

sealed interface LoginResult {
    data class Success(val credential: Credential) : LoginResult
    data object WrongPin : LoginResult
    data class Locked(val retryAfterSeconds: Int) : LoginResult
    /** 服务器证书与填写的指纹不符 */
    data object CertMismatch : LoginResult
    /** 连不上：DNS 失败、拒绝连接、超时等 */
    data object Unreachable : LoginResult
    data class Failed(val message: String) : LoginResult
}

sealed interface SendResult {
    data object Ok : SendResult
    /** 本地或服务端校验不过；reason 与服务端一致：invalid_number / invalid_text 等 */
    data class Rejected(val reason: String) : SendResult
    data object SessionExpired : SendResult
    data class Failed(val message: String) : SendResult
}

class SessionExpiredException : IOException("session expired")
class HttpStatusException(val code: Int) : IOException("HTTP $code")

/** SSE 回调翻译成的事件；id 用于断线重连时的 Last-Event-ID */
sealed interface StreamEvent {
    data object Opened : StreamEvent
    data class Sms(val id: String?, val item: SmsItem) : StreamEvent
    data class Call(val id: String?, val item: CallItem) : StreamEvent
    data object Ping : StreamEvent
    /** 服务器没有 /api/events（现网），上层应改为轮询 */
    data object Unsupported : StreamEvent
    data object SessionExpired : StreamEvent
    data class Failed(val error: Throwable?) : StreamEvent
    data object Closed : StreamEvent
}

fun interface EventStream {
    fun cancel()
}

/** FeedSync 只依赖这两个能力，便于用假实现测试 */
interface FeedSource {
    suspend fun fetchFeed(): Feed
    suspend fun openEvents(lastEventId: String?, onEvent: (StreamEvent) -> Unit): EventStream
}

object SmsRules {
    // 与 server.py 的 SMS_RE 和 send_sms 的长度上限保持一致
    private val number = Regex("^[0-9+]{3,20}$")
    const val MAX_TEXT = 600

    /** 返回 null 表示通过，否则是与服务端同名的错误码 */
    fun check(to: String, text: String): String? = when {
        !number.matches(to.trim()) -> "invalid_number"
        text.trim().isEmpty() || text.trim().length > MAX_TEXT -> "invalid_text"
        else -> null
    }
}

/** 黑白名单与设置等管理接口（需要 NAS 补丁）的统一结果 */
sealed interface AdminResult<out T> {
    data class Ok<T>(val value: T) : AdminResult<T>
    /** 现网旧服务端没有这个接口 */
    data object Unsupported : AdminResult<Nothing>
    data object SessionExpired : AdminResult<Nothing>
    data class Rejected(val reason: String) : AdminResult<Nothing>
    data class Failed(val message: String) : AdminResult<Nothing>
}

/** [fresh] 为 false 表示服务端这次没能从 astdb 刷新，返回的是缓存 */
data class BlockLists(val black: List<String>, val white: List<String>, val fresh: Boolean)

enum class BlockList(val wire: String) { Black("black"), White("white") }

object ListRules {
    // 与 server.py 的 LIST_NUM_RE（fullmatch）一致
    private val number = Regex("\\+?[0-9]{3,20}")
    fun valid(n: String): Boolean = number.matches(n)
}

class PhoneboxClient(
    baseUrl: String,
    certFingerprint: String,
    private val tokens: TokenStore,
    baseHttp: OkHttpClient = OkHttpClient(),
) : FeedSource {
    private val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl().also {
        require(it.isHttps) { "phonebox 只允许 https" }
    }

    /** 服务端用 Origin 防 CSRF，必须与站点 origin 完全一致；默认端口不写 */
    val origin: String = buildString {
        append(base.scheme).append("://").append(base.host)
        if (base.port != HttpUrl.defaultPort(base.scheme)) append(':').append(base.port)
    }

    private val http: OkHttpClient = PinnedTls.configure(
        baseHttp.newBuilder().followRedirects(false).followSslRedirects(false),
        certFingerprint,
    ).build()

    // 心跳 25 秒一次，读超时放宽到两个心跳以上
    private val sseHttp: OkHttpClient = http.newBuilder().readTimeout(70, TimeUnit.SECONDS).build()

    private fun url(path: String): HttpUrl = base.newBuilder().addPathSegments(path.trimStart('/')).build()

    /**
     * 先试设备令牌接口；404 说明是现网旧服务端，退回 /login 拿 cookie。
     * PIN 只在内存里经过这一次，不落盘、不进日志。
     */
    suspend fun login(pin: String): LoginResult = withContext(Dispatchers.IO) {
        try {
            val form = FormBody.Builder().add("code", pin).build()
            val tokenReq = Request.Builder().url(url("api/token")).header("X-Phonebox", "1")
                .header("Origin", origin).post(form).build()
            http.newCall(tokenReq).execute().use { r ->
                when (r.code) {
                    404, 405 -> Unit
                    200 -> {
                        val token = r.body.string().let { FeedJson.json.parseToJsonElement(it) }
                            .jsonObject["token"]?.jsonPrimitive?.content
                        if (token.isNullOrEmpty()) return@withContext LoginResult.Failed("服务器没有返回令牌")
                        return@withContext saved(Credential.Bearer(token))
                    }
                    else -> return@withContext loginFailure(r)
                }
            }
            val loginReq = Request.Builder().url(url("login")).post(form).build()
            http.newCall(loginReq).execute().use { r ->
                if (r.code != 303) return@withContext loginFailure(r)
                val token = r.headers("Set-Cookie").firstNotNullOfOrNull { cookieValue(it, "pb_token") }
                if (token.isNullOrEmpty()) LoginResult.Failed("服务器没有返回会话") else saved(Credential.Cookie(token))
            }
        } catch (e: javax.net.ssl.SSLException) {
            if (generateSequence<Throwable>(e) { it.cause }.any { it is java.security.cert.CertificateException }) LoginResult.CertMismatch
            else LoginResult.Failed(e.message ?: e.javaClass.simpleName)
        } catch (e: IOException) {
            when (e) {
                is java.net.UnknownHostException, is java.net.ConnectException, is java.net.NoRouteToHostException,
                is java.net.SocketTimeoutException -> LoginResult.Unreachable
                else -> LoginResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun saved(c: Credential): LoginResult {
        tokens.save(c)
        return LoginResult.Success(c)
    }

    private fun loginFailure(r: Response): LoginResult = when (r.code) {
        403 -> LoginResult.WrongPin
        429 -> LoginResult.Locked(r.header("Retry-After")?.trim()?.toIntOrNull() ?: 0)
        else -> LoginResult.Failed("HTTP ${r.code}")
    }

    /** 吊销设备令牌（仅 Bearer 模式有意义）；本地令牌无论如何都清掉 */
    suspend fun logout() = withContext(Dispatchers.IO) {
        val c = tokens.load()
        if (c is Credential.Bearer) {
            runCatching {
                http.newCall(authed(Request.Builder().url(url("api/token")).delete(), c).build()).execute().close()
            }
        }
        tokens.save(null)
    }

    private fun authed(b: Request.Builder, c: Credential): Request.Builder = when (c) {
        is Credential.Cookie -> b.header("Cookie", "pb_token=${c.value}")
        is Credential.Bearer -> b.header("Authorization", "Bearer ${c.value}")
    }

    private suspend fun credentialOrThrow(): Credential = tokens.load() ?: throw SessionExpiredException()

    /** 原始 JSON，供缓存；[since] 为增量拉取（需要 NAS 补丁，旧服务端会忽略参数返回全量） */
    suspend fun feedRaw(since: Long? = null): String = withContext(Dispatchers.IO) {
        val c = credentialOrThrow()
        val u = url("api/feed").newBuilder().apply { if (since != null) addQueryParameter("since", since.toString()) }.build()
        http.newCall(authed(Request.Builder().url(u).get(), c).build()).execute().use { r ->
            when {
                r.code == 401 -> expired()
                // 旧服务端未登录时 /api/feed 返回 200 的登录页 HTML，同样视为会话失效
                r.isSuccessful && r.header("Content-Type").orEmpty().startsWith("text/html") -> expired()
                !r.isSuccessful -> throw HttpStatusException(r.code)
                else -> r.body.string()
            }
        }
    }

    private suspend fun expired(): Nothing {
        tokens.save(null)
        throw SessionExpiredException()
    }

    override suspend fun fetchFeed(): Feed = FeedJson.parse(feedRaw())

    suspend fun send(to: String, text: String): SendResult {
        SmsRules.check(to, text)?.let { return SendResult.Rejected(it) }
        val c = tokens.load() ?: return SendResult.SessionExpired
        val payload = buildJsonObject { put("to", to.trim()); put("text", text.trim()) }.toString()
        return withContext(Dispatchers.IO) {
            try {
                val req = authed(Request.Builder().url(url("api/send")), c)
                    .header("Origin", origin)
                    .header("X-Phonebox", "1")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().use { r ->
                    when (r.code) {
                        200 -> SendResult.Ok
                        401 -> { tokens.save(null); SendResult.SessionExpired }
                        400 -> SendResult.Rejected(msgOf(r.body.string()) ?: "rejected")
                        403 -> SendResult.Failed("服务器拒绝（Origin 不匹配）")
                        else -> SendResult.Failed("HTTP ${r.code}")
                    }
                }
            } catch (e: IOException) {
                SendResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun msgOf(body: String): String? = runCatching {
        FeedJson.json.parseToJsonElement(body).jsonObject["msg"]?.jsonPrimitive?.content
    }.getOrNull()

    suspend fun lists(): AdminResult<BlockLists> = admin(Request.Builder().url(url("api/lists")).get()) { body ->
        val o = FeedJson.json.parseToJsonElement(body).jsonObject
        fun arr(k: String) = o[k]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        BlockLists(arr("black"), arr("white"), o["ok"]?.jsonPrimitive?.booleanOrNull ?: false)
    }

    suspend fun editList(list: BlockList, number: String, add: Boolean): AdminResult<Unit> {
        val n = number.trim()
        if (!ListRules.valid(n)) return AdminResult.Rejected("invalid_number")
        val payload = buildJsonObject { put("list", list.wire); put("number", n); put("action", if (add) "add" else "del") }
        return admin(jsonPost("api/lists", payload.toString())) { }
    }

    suspend fun hintOnly(): AdminResult<Boolean> = admin(Request.Builder().url(url("api/settings")).get()) { body ->
        FeedJson.json.parseToJsonElement(body).jsonObject["hint_only"]?.jsonPrimitive?.booleanOrNull ?: false
    }

    suspend fun setHintOnly(value: Boolean): AdminResult<Boolean> =
        admin(jsonPost("api/settings", buildJsonObject { put("hint_only", value) }.toString())) { body ->
            FeedJson.json.parseToJsonElement(body).jsonObject["hint_only"]?.jsonPrimitive?.booleanOrNull ?: value
        }

    /** 整表替换；返回服务端记下的条数 */
    suspend fun uploadContacts(hashes: List<String>): AdminResult<Int> {
        val payload = buildJsonObject { put("hashes", JsonArray(hashes.map { JsonPrimitive(it) })) }.toString()
        return admin(jsonPost("api/contacts", payload)) { body ->
            FeedJson.json.parseToJsonElement(body).jsonObject["count"]?.jsonPrimitive?.intOrNull ?: hashes.size
        }
    }

    /** 上报推送 token；返回服务端是否配置了这个通道 */
    suspend fun registerPush(provider: String, token: String, pub: String?, auth: String?): AdminResult<Boolean> {
        val payload = buildJsonObject {
            put("provider", provider)
            put("token", token)
            if (pub != null && auth != null) { put("pub", pub); put("auth", auth) }
        }.toString()
        return admin(jsonPost("api/push/register", payload)) { body ->
            FeedJson.json.parseToJsonElement(body).jsonObject["configured"]?.jsonPrimitive?.booleanOrNull ?: false
        }
    }

    suspend fun unregisterPush(): AdminResult<Unit> = admin(Request.Builder().url(url("api/push/register")).delete()) { }

    /** 让服务端立刻给本机发一条测试推送 */
    suspend fun testPush(): AdminResult<Boolean> =
        admin(jsonPost("api/push/test", "{}")) { body -> FeedJson.json.parseToJsonElement(body).jsonObject["ok"]?.jsonPrimitive?.booleanOrNull ?: false }

    // cookie 方式写接口要 Origin + X-Phonebox；令牌方式带上也无妨
    private fun jsonPost(path: String, json: String): Request.Builder = Request.Builder().url(url(path))
        .header("Origin", origin).header("X-Phonebox", "1")
        .post(json.toRequestBody("application/json".toMediaType()))

    private suspend fun <T> admin(req: Request.Builder, parse: (String) -> T): AdminResult<T> {
        val c = tokens.load() ?: return AdminResult.SessionExpired
        return withContext(Dispatchers.IO) {
            try {
                http.newCall(authed(req, c).build()).execute().use { r ->
                    val html = r.header("Content-Type").orEmpty().startsWith("text/html")
                    when {
                        r.code == 401 -> { tokens.save(null); AdminResult.SessionExpired }
                        // 旧服务端：已登录时未知路径回 404；cookie 失效时回 200 登录页
                        r.code == 404 || r.code == 405 || (r.isSuccessful && html) -> AdminResult.Unsupported
                        r.code == 400 -> AdminResult.Rejected(msgOf(r.body.string()) ?: "rejected")
                        !r.isSuccessful -> AdminResult.Failed(msgOf(r.body.string()) ?: "HTTP ${r.code}")
                        else -> AdminResult.Ok(parse(r.body.string()))
                    }
                }
            } catch (e: IOException) {
                AdminResult.Failed(e.message ?: e.javaClass.simpleName)
            } catch (e: IllegalArgumentException) {
                // JSON 解析失败
                AdminResult.Failed("响应格式不对")
            }
        }
    }

    override suspend fun openEvents(lastEventId: String?, onEvent: (StreamEvent) -> Unit): EventStream {
        val c = tokens.load()
        if (c == null) {
            onEvent(StreamEvent.SessionExpired)
            return EventStream { }
        }
        val req = authed(Request.Builder().url(url("api/events")), c)
            .header("Accept", "text/event-stream")
            .apply { if (lastEventId != null) header("Last-Event-ID", lastEventId) }
            .build()
        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) = onEvent(StreamEvent.Opened)

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val ev = runCatching {
                    when (type) {
                        "sms" -> StreamEvent.Sms(id, FeedJson.parseSms(data))
                        "call" -> StreamEvent.Call(id, FeedJson.parseCall(data))
                        "ping" -> StreamEvent.Ping
                        else -> null
                    }
                }.getOrNull() ?: return
                onEvent(ev)
            }

            override fun onClosed(eventSource: EventSource) = onEvent(StreamEvent.Closed)

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                onEvent(
                    when (response?.code) {
                        404 -> StreamEvent.Unsupported
                        401 -> StreamEvent.SessionExpired
                        // 旧服务端对未知路径且未登录时回登录页（200 text/html），按“不支持”处理
                        200 -> StreamEvent.Unsupported
                        else -> StreamEvent.Failed(t)
                    },
                )
            }
        }
        val source = EventSources.createFactory(sseHttp).newEventSource(req, listener)
        return EventStream { source.cancel() }
    }
}

/** 从一条 Set-Cookie 里取指定名字的值；不处理属性 */
internal fun cookieValue(setCookie: String, name: String): String? {
    val first = setCookie.substringBefore(';').trim()
    val eq = first.indexOf('=')
    if (eq <= 0 || first.substring(0, eq).trim() != name) return null
    return first.substring(eq + 1).trim().takeIf { it.isNotEmpty() }
}
