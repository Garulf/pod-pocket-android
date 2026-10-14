package au.com.shiftyjelly.pocketcasts.musicassistant.client

import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaIncoming
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaJson
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaServerInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

fun interface MaConnectionFactory {
    fun create(): MaConnection
}

class MaConnection(private val client: OkHttpClient) {
    private val nextMessageId = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, PendingCall>()
    private val serverInfo = CompletableDeferred<MaServerInfo>()
    private val closed = CompletableDeferred<Unit>()
    private val eventChannel = Channel<MaIncoming.Event>(Channel.UNLIMITED)

    @Volatile
    private var socket: WebSocket? = null

    val events: ReceiveChannel<MaIncoming.Event> get() = eventChannel

    suspend fun open(server: HttpUrl): MaServerInfo {
        val url = server.newBuilder().addPathSegment("ws").build()
        socket = client.newWebSocket(Request.Builder().url(url).build(), Listener())
        return serverInfo.await()
    }

    suspend fun call(command: String, args: Map<String, Any?> = emptyMap()): Any? {
        val webSocket = socket ?: throw MaConnectionClosedException()
        val messageId = nextMessageId.getAndIncrement().toString()
        val call = PendingCall()
        pending[messageId] = call
        try {
            if (closed.isCompleted || !webSocket.send(MaJson.command(messageId, command, args))) {
                throw MaConnectionClosedException()
            }
            return call.result.await()
        } finally {
            pending.remove(messageId)
        }
    }

    suspend fun awaitClosed() {
        closed.await()
    }

    fun close() {
        socket?.close(NORMAL_CLOSURE, null)
        fail(MaConnectionClosedException())
    }

    private fun fail(cause: Throwable) {
        val error = cause as? MaConnectionClosedException ?: MaConnectionClosedException(cause)
        serverInfo.completeExceptionally(error)
        pending.values.forEach { it.result.completeExceptionally(error) }
        eventChannel.close()
        closed.complete(Unit)
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            when (val message = MaJson.parse(text)) {
                is MaIncoming.ServerInfo -> serverInfo.complete(message.info)
                is MaIncoming.Result -> pending[message.messageId]?.accept(message)
                is MaIncoming.Error -> pending[message.messageId]?.result?.completeExceptionally(MaCommandException(message.code, message.details))
                is MaIncoming.Event -> eventChannel.trySend(message)
                null -> Unit
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            fail(MaConnectionClosedException())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            fail(t)
        }
    }

    private class PendingCall {
        val result = CompletableDeferred<Any?>()
        private val parts = mutableListOf<Any?>()

        @Synchronized
        fun accept(message: MaIncoming.Result) {
            val items = message.result as? List<*>
            if (message.partial) {
                items?.let(parts::addAll)
                return
            }
            result.complete(if (parts.isEmpty()) message.result else parts + items.orEmpty())
        }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}
