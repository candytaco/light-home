package com.candytaco.lighthome.ha

import com.candytaco.lighthome.net.ServerConfig
import com.candytaco.lighthome.net.cfAccess
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * One authenticated Home Assistant WebSocket (`/api/websocket`).
 *
 * A single reader coroutine routes every incoming message by `id`: `result` messages complete the
 * waiting [command], `event` messages go to the matching [subscribe] flow. When the socket closes,
 * everything still waiting fails with [HaConnectionClosedException] and [closed] completes.
 */
class HaConnection private constructor(
    private val session: DefaultClientWebSocketSession,
    val haVersion: String,
    scope: CoroutineScope,
) {
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement?>>()
    private val subscriptions = ConcurrentHashMap<Int, Channel<JsonObject>>()

    @Volatile
    private var closeCause: HaConnectionClosedException? = null

    private val reader: Job = scope.launch { readLoop() }

    /** Completes when the socket is gone, for whatever reason. */
    val closed: Job get() = reader

    /** Sends a command and returns its `result` payload (null when HA sends none). */
    suspend fun command(
        type: String,
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
        fields: JsonObjectBuilder.() -> Unit = {},
    ): JsonElement? = request(nextId.getAndIncrement(), type, timeoutMs, fields)

    /**
     * Subscribes (e.g. `subscribe_entities`) and emits each event's `event` object. The flow ends
     * with [HaConnectionClosedException] when the socket closes. Subscriptions are not unsubscribed
     * on cancellation; they live exactly as long as this connection.
     */
    fun subscribe(type: String, fields: JsonObjectBuilder.() -> Unit = {}): Flow<JsonObject> = flow {
        val id = nextId.getAndIncrement()
        val events = Channel<JsonObject>(Channel.UNLIMITED)
        subscriptions[id] = events
        try {
            closeCause?.let { throw it }
            request(id, type, COMMAND_TIMEOUT_MS, fields)
            for (event in events) emit(event)
            throw closeCause ?: HaConnectionClosedException()
        } finally {
            subscriptions.remove(id)
        }
    }

    suspend fun callService(domain: String, service: String, entityId: String, data: JsonObject? = null) {
        command("call_service") {
            put("domain", domain)
            put("service", service)
            put("target", buildJsonObject { put("entity_id", entityId) })
            if (data != null && data.isNotEmpty()) put("service_data", data)
        }
    }

    suspend fun close() {
        withContext(NonCancellable) {
            runCatching { session.close() }
            reader.cancel()
        }
    }

    private suspend fun request(
        id: Int,
        type: String,
        timeoutMs: Long,
        fields: JsonObjectBuilder.() -> Unit,
    ): JsonElement? {
        val reply = CompletableDeferred<JsonElement?>()
        pending[id] = reply
        try {
            // shutDown() sets closeCause before failing pending replies, so checking after
            // registering can't miss a close that happens in between.
            closeCause?.let { throw it }
            val message = buildJsonObject {
                put("id", id)
                put("type", type)
                fields()
            }
            session.send(Frame.Text(message.toString()))
            return withTimeout(timeoutMs) { reply.await() }
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun readLoop() {
        var failure: Throwable? = null
        try {
            for (frame in session.incoming) {
                if (frame is Frame.Text) dispatch(frame.readText())
            }
        } catch (e: CancellationException) {
            failure = e
            throw e
        } catch (e: Throwable) {
            failure = e
        } finally {
            shutDown(failure)
        }
    }

    private fun dispatch(text: String) {
        when (val element = runCatching { HaJson.parseToJsonElement(text) }.getOrNull()) {
            is JsonObject -> handle(element)
            // Coalesced messages arrive as an array (only if that feature is enabled).
            is JsonArray -> element.forEach { (it as? JsonObject)?.let(::handle) }
            else -> Unit
        }
    }

    private fun handle(message: JsonObject) {
        val id = message.int("id") ?: return
        when (message.string("type")) {
            "result" -> {
                val reply = pending[id] ?: return
                if (message.boolean("success") == true) {
                    reply.complete(message["result"])
                } else {
                    val error = message.obj("error")
                    reply.completeExceptionally(
                        HaCommandException(
                            code = error?.string("code") ?: "unknown_error",
                            message = error?.string("message") ?: "Command failed",
                        ),
                    )
                }
            }
            "event" -> message.obj("event")?.let { subscriptions[id]?.trySend(it) }
            "pong" -> pending[id]?.complete(null)
        }
    }

    private fun shutDown(failure: Throwable?) {
        val cause = HaConnectionClosedException(failure)
        closeCause = cause
        pending.values.forEach { it.completeExceptionally(cause) }
        subscriptions.values.forEach { it.close(cause) }
        session.cancel()
    }

    companion object {
        private const val COMMAND_TIMEOUT_MS = 30_000L

        /**
         * Opens the socket (with the Access headers on the upgrade request) and runs HA's auth
         * handshake. Throws [HaAuthRejectedException] when HA refuses [accessToken].
         */
        suspend fun open(
            client: HttpClient,
            server: ServerConfig,
            accessToken: String,
            scope: CoroutineScope,
        ): HaConnection {
            val session = client.webSocketSession(server.webSocketUrl) { cfAccess(server.cfAccess) }
            try {
                val hello = session.receiveJson()
                if (hello.string("type") != "auth_required") {
                    throw HaProtocolException("expected auth_required, got ${hello.string("type")}")
                }
                val auth = buildJsonObject {
                    put("type", "auth")
                    put("access_token", accessToken)
                }
                session.send(Frame.Text(auth.toString()))
                val reply = session.receiveJson()
                return when (reply.string("type")) {
                    "auth_ok" -> HaConnection(
                        session = session,
                        haVersion = reply.string("ha_version") ?: hello.string("ha_version").orEmpty(),
                        scope = scope,
                    )
                    "auth_invalid" -> throw HaAuthRejectedException(reply.string("message") ?: "Access token rejected")
                    else -> throw HaProtocolException("unexpected auth reply ${reply.string("type")}")
                }
            } catch (e: Throwable) {
                session.cancel()
                throw e
            }
        }

        private suspend fun DefaultClientWebSocketSession.receiveJson(): JsonObject {
            while (true) {
                val frame = incoming.receive()
                if (frame is Frame.Text) {
                    return HaJson.parseToJsonElement(frame.readText()) as? JsonObject
                        ?: throw HaProtocolException("expected a JSON object")
                }
            }
        }
    }
}
