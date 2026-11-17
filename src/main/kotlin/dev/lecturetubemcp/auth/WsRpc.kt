package dev.lecturetubemcp.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class RpcException(message: String) : Exception(message)

/**
 * Minimal JSON-RPC-over-WebSocket client covering both
 * Chrome DevTools Protocol (`{id, result|error}`) and WebDriver BiDi
 * (`{type: "success"|"error", id, result|error, message}`).
 * Events (messages without a matching id) are ignored.
 */
class WsRpc private constructor(private val ws: WebSocket, private val pending: ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>) {
    private val ids = AtomicInteger(0)

    /** [sessionId] addresses a CDP target session (flattened mode); unused for BiDi. */
    suspend fun call(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Long = 15_000,
        sessionId: String? = null,
    ): JsonObject {
        val id = ids.incrementAndGet()
        val d = CompletableDeferred<JsonObject>()
        pending[id] = d
        val msg = buildJsonObject {
            put("id", id); put("method", method); put("params", params)
            if (sessionId != null) put("sessionId", sessionId)
        }
        ws.sendText(msg.toString(), true).await()
        val resp = try {
            withTimeout(timeoutMs) { d.await() }
        } finally {
            pending.remove(id)
        }
        val err = resp["error"]
        if (err != null || resp["type"]?.jsonPrimitive?.content == "error") {
            val text = (resp["message"] ?: err)?.toString() ?: "unknown error"
            throw RpcException("$method failed: $text")
        }
        return resp["result"]?.jsonObject ?: JsonObject(emptyMap())
    }

    fun close() {
        runCatching { ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye") }
        runCatching { ws.abort() }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        suspend fun connect(url: String): WsRpc {
            val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
            val listener = object : WebSocket.Listener {
                private val buf = StringBuilder()
                override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    buf.append(data)
                    if (last) {
                        val text = buf.toString(); buf.setLength(0)
                        runCatching {
                            val obj = json.parseToJsonElement(text).jsonObject
                            val id = obj["id"]?.jsonPrimitive?.intOrNull
                            if (id != null) pending[id]?.complete(obj)
                        }
                    }
                    webSocket.request(1)
                    return null
                }

                override fun onError(webSocket: WebSocket, error: Throwable) {
                    pending.values.forEach { it.completeExceptionally(error) }
                }

                override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                    pending.values.forEach { it.completeExceptionally(RpcException("browser connection closed")) }
                    return null
                }
            }
            val ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create(url), listener).await()
            return WsRpc(ws, pending)
        }
    }
}

internal fun JsonElement?.str(): String? = (this as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
