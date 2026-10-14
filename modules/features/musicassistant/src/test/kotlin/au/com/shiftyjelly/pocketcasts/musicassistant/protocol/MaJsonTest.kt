package au.com.shiftyjelly.pocketcasts.musicassistant.protocol

import au.com.shiftyjelly.pocketcasts.musicassistant.FakeMaServer.Companion.fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaJsonTest {
    @Test
    fun `reads the server info sent on connect`() {
        val message = MaJson.parse(fixture("server_info")) as MaIncoming.ServerInfo

        assertEquals("1f2e3d4c5b6a", message.info.serverId)
        assertEquals(87, message.info.schemaVersion)
        assertEquals("Music Assistant", message.info.name)
    }

    @Test
    fun `reads results, errors and events`() {
        val result = MaJson.parse(fixture("token_created")) as MaIncoming.Result
        val error = MaJson.parse(fixture("auth_invalid_token")) as MaIncoming.Error
        val event = MaJson.parse(fixture("event_queue_time_updated")) as MaIncoming.Event

        assertEquals(MaIncoming.Result("3", "long.lived.jwt", partial = false), result)
        assertEquals(MaIncoming.Error("2", 23, "Invalid or expired token"), error)
        assertEquals("queue_time_updated", event.event)
        assertEquals("media_player.kitchen", event.objectId)
        assertEquals(900.5, event.data)
    }

    @Test
    fun `keeps a null result as a result`() {
        assertEquals(MaIncoming.Result("11", null, partial = false), MaJson.parse(fixture("command_ok")))
    }

    @Test
    fun `marks partial results`() {
        val partial = MaJson.parse(fixture("podcast_episodes_partial")) as MaIncoming.Result

        assertTrue(partial.partial)
    }

    @Test
    fun `ignores text that is not a message`() {
        assertNull(MaJson.parse("not json"))
        assertNull(MaJson.parse("[1, 2]"))
        assertNull(MaJson.parse("""{"hello": "world"}"""))
    }

    @Test
    fun `decodes players with their features`() {
        val result = MaJson.parse(fixture("players_all")) as MaIncoming.Result

        val players = MaJson.decodeList<MaPlayer>(result.result)

        assertEquals(listOf("Kitchen", "Office", "Downstairs", "Bathroom"), players.map { it.name })
        assertTrue(players[0].canSetVolume)
        assertEquals(35, players[0].volumeLevel)
        assertFalse(players[1].available)
        assertEquals("group", players[2].type)
        assertEquals("media_player.kitchen", players[3].syncedTo)
    }

    @Test
    fun `decodes a playing queue with its podcast episode`() {
        val event = MaJson.parse(fixture("event_queue_updated_playing")) as MaIncoming.Event

        val queue = MaJson.decode<MaQueue>(event.data)

        assertEquals("media_player.kitchen", queue.queueId)
        assertTrue(queue.isPlaying)
        assertEquals(754.2, queue.elapsedTime, 0.0)
        assertEquals(1_792_000_000.0, queue.elapsedTimeLastUpdated, 0.0)
        val item = queue.currentItem!!
        assertEquals("5b2f0e8c9d6a4f1b8e3c7a2d1f0e9b8a", item.queueItemId)
        assertEquals(1800, item.duration)
        assertEquals(
            "gpodder--Xk3dTq9a://podcast_episode/https://pod.example.com/feeds/show.xml https://pod.example.com/feeds/ep-a",
            item.mediaItem!!.uri,
        )
        assertEquals("https://cdn.example.com/a.mp3", item.mediaItem.providerMappings.single().url)
    }

    @Test
    fun `computes elapsed time from the server clock`() {
        val queue = MaQueue(queueId = "q", state = MaQueue.STATE_PLAYING, elapsedTime = 100.0, elapsedTimeLastUpdated = 1000.0, playbackSpeed = 1.5)

        assertEquals(115.0, queue.elapsedAt(serverNow = 1010.0), 0.0001)
        assertEquals(100.0, queue.copy(state = MaQueue.STATE_PAUSED).elapsedAt(serverNow = 1010.0), 0.0)
        assertEquals(100.0, queue.elapsedAt(serverNow = 990.0), 0.0)
    }

    @Test
    fun `decodes providers`() {
        val result = MaJson.parse(fixture("providers")) as MaIncoming.Result

        val providers = MaJson.decodeList<MaProvider>(result.result)

        assertEquals(MaProvider("music", "gpodder", "gPodder (Pod Pocket)", "gpodder--Xk3dTq9a"), providers[0])
    }

    @Test
    fun `writes commands in the Music Assistant envelope`() {
        val text = MaJson.command("7", "player_queues/seek", mapOf("queue_id" to "media_player.kitchen", "position" to 90))

        assertEquals("""{"message_id":"7","command":"player_queues/seek","args":{"queue_id":"media_player.kitchen","position":90}}""", text)
        assertEquals("""{"message_id":"8","command":"players/all"}""", MaJson.command("8", "players/all", emptyMap()))
    }
}
