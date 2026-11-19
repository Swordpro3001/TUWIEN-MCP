package dev.testkit

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Flow
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters

/** A request as the fake server sees it. */
class Req(val method: String, val uri: URI, val headers: HttpHeaders, val body: String) {
    val path: String get() = uri.path
    /** Query and form parameters (form wins), URL-decoded. */
    val params: Map<String, String> by lazy {
        (listOfNotNull(uri.rawQuery) + if (headers.firstValue("Content-Type").orElse("").contains("form")) listOf(body) else emptyList())
            .flatMap { it.split('&') }.filter { it.isNotEmpty() }
            .associate { p -> p.substringBefore('=').dec() to p.substringAfter('=', "").dec() }
    }
    val json: JsonObject get() = Json.parseToJsonElement(body).jsonObject
    private fun String.dec() = URLDecoder.decode(this, Charsets.UTF_8)
}

class Resp(val status: Int = 200, val body: ByteArray = ByteArray(0), val headers: Map<String, List<String>> = emptyMap())

fun text(body: String, status: Int = 200, contentType: String = "application/json") =
    Resp(status, body.toByteArray(), mapOf("Content-Type" to listOf(contentType)))

fun redirect(location: String, status: Int = 303) = Resp(status, headers = mapOf("Location" to listOf(location)))

/** An [HttpClient] that answers every request with [handler] instead of going to the network. */
class FakeHttp(private val handler: (Req) -> Resp) : HttpClient() {
    val requests = mutableListOf<Req>()

    override fun <T> send(request: HttpRequest, bodyHandler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        val req = Req(request.method(), request.uri(), request.headers(), request.bodyPublisher().map { read(it) }.orElse(""))
        synchronized(requests) { requests += req }
        val r = handler(req)
        val headers = HttpHeaders.of(r.headers) { _, _ -> true }
        val sub = bodyHandler.apply(object : HttpResponse.ResponseInfo {
            override fun statusCode() = r.status
            override fun headers() = headers
            override fun version() = Version.HTTP_1_1
        })
        sub.onSubscribe(object : Flow.Subscription { override fun request(n: Long) {}; override fun cancel() {} })
        if (r.body.isNotEmpty()) sub.onNext(listOf(ByteBuffer.wrap(r.body))) // the JDK asserts non-empty buffers
        sub.onComplete()
        val body = sub.body.toCompletableFuture().get()
        return object : HttpResponse<T> {
            override fun statusCode() = r.status
            override fun request() = request
            override fun previousResponse() = Optional.empty<HttpResponse<T>>()
            override fun headers() = headers
            override fun body() = body
            override fun sslSession() = Optional.empty<javax.net.ssl.SSLSession>()
            override fun uri() = request.uri()
            override fun version() = Version.HTTP_1_1
        }
    }

    override fun <T> sendAsync(request: HttpRequest, bodyHandler: HttpResponse.BodyHandler<T>): CompletableFuture<HttpResponse<T>> =
        runCatching { CompletableFuture.completedFuture(send(request, bodyHandler)) }.getOrElse { CompletableFuture.failedFuture(it) }

    override fun <T> sendAsync(
        request: HttpRequest, bodyHandler: HttpResponse.BodyHandler<T>, pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?,
    ) = sendAsync(request, bodyHandler)

    private fun read(p: HttpRequest.BodyPublisher): String {
        val out = java.io.ByteArrayOutputStream()
        val done = CompletableFuture<Unit>()
        p.subscribe(object : Flow.Subscriber<ByteBuffer> {
            override fun onSubscribe(s: Flow.Subscription) = s.request(Long.MAX_VALUE)
            override fun onNext(b: ByteBuffer) { val a = ByteArray(b.remaining()); b.get(a); out.write(a) }
            override fun onError(t: Throwable) { done.completeExceptionally(t) }
            override fun onComplete() { done.complete(Unit) }
        })
        done.get()
        return out.toString(Charsets.UTF_8)
    }

    override fun cookieHandler() = Optional.empty<CookieHandler>()
    override fun connectTimeout() = Optional.empty<Duration>()
    override fun followRedirects() = Redirect.NEVER
    override fun proxy() = Optional.empty<ProxySelector>()
    override fun sslContext(): SSLContext = SSLContext.getDefault()
    override fun sslParameters() = SSLParameters()
    override fun authenticator() = Optional.empty<Authenticator>()
    override fun version() = Version.HTTP_1_1
    override fun executor() = Optional.empty<Executor>()
}

/** No-op MCP client connection (no progress token is sent, so notifications are never used). */
val noClient: ClientConnection = java.lang.reflect.Proxy.newProxyInstance(
    ClientConnection::class.java.classLoader, arrayOf(ClientConnection::class.java),
) { _, m, _ -> if (m.name == "getSessionId") "test" else throw UnsupportedOperationException(m.name) } as ClientConnection

/** Calls a registered tool the way an MCP client would. */
fun Server.call(tool: String, args: JsonObject = JsonObject(emptyMap())): CallToolResult = runBlocking {
    val t = tools[tool] ?: error("no tool $tool; have ${tools.keys}")
    t.handler(noClient, CallToolRequest(CallToolRequestParams(name = tool, arguments = args)))
}

/** Text of a result; pretty-printed JSON is compacted to `"key":value` so tests can match fragments. */
fun CallToolResult.text(): String =
    content.filterIsInstance<TextContent>().joinToString("\n") { it.text }.replace(Regex("\": "), "\":")

fun args(vararg kv: Pair<String, Any?>): JsonObject = JsonObject(kv.filter { it.second != null }.associate { (k, v) -> k to toJson(v) })

private fun toJson(v: Any?): JsonElement = when (v) {
    is JsonElement -> v
    is Number -> kotlinx.serialization.json.JsonPrimitive(v)
    is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
    is List<*> -> kotlinx.serialization.json.JsonArray(v.map { toJson(it) })
    else -> kotlinx.serialization.json.JsonPrimitive(v.toString())
}
