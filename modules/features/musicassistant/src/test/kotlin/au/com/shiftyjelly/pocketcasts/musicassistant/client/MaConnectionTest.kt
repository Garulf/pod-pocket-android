package au.com.shiftyjelly.pocketcasts.musicassistant.client

import au.com.shiftyjelly.pocketcasts.musicassistant.FakeMaServer
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaJson
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaMediaItem
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class MaConnectionTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val server = FakeMaServer()
    private val connection = MaConnection(OkHttpClient())

    @After
    fun tearDown() {
        connection.close()
        server.close()
    }

    @Test
    fun `opens the websocket and reads the server info`() = runBlocking<Unit> {
        val info = withTimeout(5_000) { connection.open(server.url) }

        assertEquals("2.11.0", info.serverVersion)
    }

    @Test
    fun `answers a command with its result`() = runBlocking<Unit> {
        server.reply("auth/token/create", "token_created")
        connection.open(server.url)

        val token = withTimeout(5_000) { connection.call("auth/token/create", mapOf("name" to "Pod Pocket")) }

        assertEquals("long.lived.jwt", token)
        assertEquals(FakeMaServer.Command("auth/token/create", mapOf("name" to "Pod Pocket")), server.next())
    }

    @Test
    fun `joins partial results into one list`() = runBlocking<Unit> {
        server.reply("music/podcasts/podcast_episodes", "podcast_episodes_partial", "podcast_episodes_final")
        connection.open(server.url)

        val episodes = MaJson.decodeList<MaMediaItem>(withTimeout(5_000) { connection.call("music/podcasts/podcast_episodes") })

        assertEquals(listOf("Episode A", "Episode B", "Episode C has a guid with a space"), episodes.map { it.name })
    }

    @Test
    fun `turns an error result into an exception`() = runBlocking<Unit> {
        server.reply("player_queues/seek", "error_invalid_command")
        connection.open(server.url)

        val error = assertThrows(MaCommandException::class.java) {
            runBlocking { withTimeout(5_000) { connection.call("player_queues/seek") } }
        }

        assertEquals(MaCommandException.INVALID_COMMAND, error.code)
        assertEquals("Can not seek items without duration.", error.details)
    }

    @Test
    fun `delivers events in order`() = runBlocking<Unit> {
        connection.open(server.url)

        server.push("event_queue_updated_playing")
        server.push("event_queue_time_updated")

        assertEquals("queue_updated", withTimeout(5_000) { connection.events.receive() }.event)
        assertEquals("queue_time_updated", withTimeout(5_000) { connection.events.receive() }.event)
    }

    @Test
    fun `fails a waiting command when the connection drops`() = runBlocking<Unit> {
        server.reply("never/answered")
        connection.open(server.url)
        val waiting = async(Dispatchers.IO) { runCatching { connection.call("never/answered") } }
        server.next()

        server.dropConnections()

        val failure = withTimeout(5_000) { waiting.await() }.exceptionOrNull()
        assertEquals(MaConnectionClosedException::class.java, failure?.javaClass)
    }

    @Test
    fun `connects below the base path of the server`() = runBlocking<Unit> {
        withTimeout(5_000) { connection.open(server.url.newBuilder().addPathSegment("ma").build()) }

        assertEquals(listOf("/ma/ws"), server.requestPaths)
    }

    @Test
    fun `keeps https and the base path when building the websocket address`() = runBlocking<Unit> {
        val requested = mutableListOf<String>()
        val offline = MaConnection(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    requested += chain.request().url.toString()
                    throw IOException("offline")
                }
                .build(),
        )

        assertThrows(MaConnectionClosedException::class.java) {
            runBlocking { withTimeout(5_000) { offline.open("https://ma.example.com/ma".toHttpUrl()) } }
        }

        assertEquals(listOf("https://ma.example.com/ma/ws"), requested)
    }

    @Test
    fun `fails to open when the server refuses the upgrade`() = runBlocking<Unit> {
        server.acceptConnections = false

        assertThrows(MaConnectionClosedException::class.java) {
            runBlocking { withTimeout(5_000) { connection.open(server.url) } }
        }

        assertThrows(MaConnectionClosedException::class.java) {
            runBlocking { withTimeout(5_000) { connection.call("players/all") } }
        }
        assertTrue(withTimeout(5_000) { connection.events.receiveCatching() }.isClosed)
        assertEquals(1, server.requests)
        assertTrue(server.commands.isEmpty())
    }

    @Test
    fun `refuses commands after it was closed`() = runBlocking<Unit> {
        connection.open(server.url)
        connection.close()

        assertThrows(MaConnectionClosedException::class.java) {
            runBlocking { withTimeout(5_000) { connection.call("players/all") } }
        }

        assertTrue(server.commands.isEmpty())
    }

    @Test
    fun `does not open a new socket after it was closed`() = runBlocking<Unit> {
        connection.close()

        assertThrows(MaConnectionClosedException::class.java) {
            runBlocking { withTimeout(5_000) { connection.open(server.url) } }
        }

        delay(200)
        assertEquals(0, server.requests)
    }

    @Test
    fun `closes the socket when the open is cancelled`() = runBlocking<Unit> {
        server.greetClients = false
        val opening = launch(Dispatchers.IO) { connection.open(server.url) }
        waitUntil { server.openSockets == 1 }

        opening.cancelAndJoin()

        waitUntil { server.openSockets == 0 }
        assertThrows(MaConnectionClosedException::class.java) {
            runBlocking { withTimeout(5_000) { connection.call("players/all") } }
        }
    }

    @Test
    fun `closes the socket when the server never sends its info`() = runBlocking<Unit> {
        server.greetClients = false

        val info = withTimeoutOrNull(500) { connection.open(server.url) }

        assertNull(info)
        waitUntil { server.openSockets == 0 }
    }

    @Test
    fun `keeps working after a waiting command was cancelled`() = runBlocking<Unit> {
        server.reply("never/answered")
        server.reply("auth/token/create", "token_created")
        connection.open(server.url)
        val waiting = launch(Dispatchers.IO) { connection.call("never/answered") }
        server.next()

        waiting.cancelAndJoin()

        assertEquals("long.lived.jwt", withTimeout(5_000) { connection.call("auth/token/create") })
    }

    @Test
    fun `matches concurrent commands to their own results`() = runBlocking<Unit> {
        server.reply("auth/token/create", "token_created")
        server.reply("time", "time")
        connection.open(server.url)

        val results = withTimeout(5_000) {
            listOf(
                async(Dispatchers.IO) { connection.call("auth/token/create") },
                async(Dispatchers.IO) { connection.call("time") },
            ).awaitAll()
        }

        assertEquals(listOf("long.lived.jwt", 1_792_000_002.5), results)
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) {
                delay(10)
            }
        }
    }
}
