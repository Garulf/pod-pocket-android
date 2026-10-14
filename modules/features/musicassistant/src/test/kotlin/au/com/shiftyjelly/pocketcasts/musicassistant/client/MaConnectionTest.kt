package au.com.shiftyjelly.pocketcasts.musicassistant.client

import au.com.shiftyjelly.pocketcasts.musicassistant.FakeMaServer
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaJson
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
}
