package au.com.shiftyjelly.pocketcasts.musicassistant

import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaJson
import com.squareup.moshi.Types
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

class FakeMaServer : Closeable {
    data class Command(val name: String, val args: Map<String, Any?>)

    private val server = MockWebServer()
    private val replies = ConcurrentHashMap<String, List<String>>()
    private val onceReplies = ConcurrentHashMap<String, ConcurrentLinkedQueue<List<String>>>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    val commands = LinkedBlockingQueue<Command>()

    @Volatile
    var acceptConnections = true

    @Volatile
    var connections = 0
        private set

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!acceptConnections) {
                    return MockResponse().setResponseCode(503)
                }
                connections++
                return MockResponse().withWebSocketUpgrade(Listener())
            }
        }
        server.start()
    }

    val url: HttpUrl get() = server.url("/")

    fun signedIn(): FakeMaServer = apply {
        reply("auth", "auth_ok")
        reply("time", "time")
        reply("players/all", "players_all")
        reply("player_queues/all", "queues_all")
    }

    fun reply(command: String, vararg fixtures: String) {
        replies[command] = fixtures.toList()
    }

    fun replyOnce(command: String, vararg fixtures: String) {
        onceReplies.getOrPut(command) { ConcurrentLinkedQueue() }.add(fixtures.toList())
    }

    fun push(fixture: String) {
        val text = fixture(fixture)
        sockets.forEach { it.send(text) }
    }

    fun dropConnections() {
        sockets.forEach { it.close(GOING_AWAY, null) }
        sockets.clear()
    }

    fun next(timeoutSeconds: Long = 5): Command {
        return commands.poll(timeoutSeconds, TimeUnit.SECONDS) ?: error("No command reached the fake Music Assistant server")
    }

    fun nextExcept(vararg ignored: String): Command {
        while (true) {
            val command = next()
            if (command.name !in ignored) {
                return command
            }
        }
    }

    override fun close() {
        dropConnections()
        server.shutdown()
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sockets += webSocket
            webSocket.send(fixture("server_info"))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = MAP_ADAPTER.fromJson(text)!!
            val name = message["command"] as String
            @Suppress("UNCHECKED_CAST")
            val args = message["args"] as? Map<String, Any?> ?: emptyMap()
            commands += Command(name, args)
            val fixtures = onceReplies[name]?.poll() ?: replies[name] ?: listOf("command_ok")
            fixtures.forEach { webSocket.send(withMessageId(fixture(it), message["message_id"] as String)) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            sockets -= webSocket
        }
    }

    companion object {
        private val MAP_TYPE = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        private val MAP_ADAPTER = MaJson.moshi.adapter<Map<String, Any?>>(MAP_TYPE).serializeNulls()
        private const val GOING_AWAY = 1001

        fun fixture(name: String): String {
            return requireNotNull(FakeMaServer::class.java.getResource("/ma/$name.json")) { "Missing fixture $name" }.readText()
        }

        fun withMessageId(json: String, messageId: String): String {
            val message = MAP_ADAPTER.fromJson(json)!!.toMutableMap()
            message["message_id"] = messageId
            return MAP_ADAPTER.toJson(message)
        }
    }
}
