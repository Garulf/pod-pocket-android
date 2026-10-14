package au.com.shiftyjelly.pocketcasts.musicassistant.output

import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.musicassistant.FakeMaServer
import au.com.shiftyjelly.pocketcasts.musicassistant.InMemoryAccountStore
import au.com.shiftyjelly.pocketcasts.musicassistant.InMemoryPlaybackStore
import au.com.shiftyjelly.pocketcasts.musicassistant.account
import au.com.shiftyjelly.pocketcasts.musicassistant.awaitValue
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaConnection
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSession
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSessionState
import au.com.shiftyjelly.pocketcasts.musicassistant.eventually
import au.com.shiftyjelly.pocketcasts.musicassistant.media.MaMediaResolver
import au.com.shiftyjelly.pocketcasts.musicassistant.mirror.MirroredItem
import au.com.shiftyjelly.pocketcasts.musicassistant.player.MusicAssistantPlayer
import au.com.shiftyjelly.pocketcasts.musicassistant.player.RemotePlayback
import au.com.shiftyjelly.pocketcasts.repositories.chromecast.CastManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.Player
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlayerEvent
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class MusicAssistantOutputTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val server = FakeMaServer().signedIn()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val session = MaSession(scope, InMemoryAccountStore(account(server.url)), { MaConnection(OkHttpClient()) }, retryDelayMs = { 20L })
    private val store = InMemoryPlaybackStore()
    private val notices = CopyOnWriteArrayList<OutputNotice>()
    private val signals = CopyOnWriteArrayList<String>()
    private val routes = RecordingRoutes()
    private val output = MusicAssistantOutput(
        session = session,
        resolver = MaMediaResolver(session, { emptyMap() }, { null }),
        playbackStore = store,
        scope = scope,
        notify = { notices += it },
    )

    @After
    fun tearDown() {
        session.stop()
        scope.cancel()
        server.close()
    }

    @Test
    fun `starts a session when a speaker is chosen and ends it when the phone is chosen`() {
        output.setSessionListener(listener())

        output.onRouteSelected(KITCHEN)
        output.onRouteSelected(KITCHEN)
        assertTrue(output.isActive.value)
        output.onRouteUnselected("media_player.office")
        output.onRouteUnselected(KITCHEN)

        assertEquals(listOf("started", "ended"), signals)
        assertFalse(output.isActive.value)
    }

    @Test
    fun `ends the old session before it starts one on another speaker`() {
        output.setSessionListener(listener())

        output.onRouteSelected(KITCHEN)
        output.onRouteSelected(OFFICE)

        assertEquals(listOf("started", "ended", "started"), signals)
        assertEquals(OFFICE, output.activePlayerId.value)
    }

    @Test
    fun `holds a signal until the playback manager listens`() {
        output.onRouteSelected(KITCHEN)

        output.setSessionListener(listener())

        assertEquals(listOf("started"), signals)
    }

    @Test
    fun `is active before the playback manager hears about the session`() {
        val activeWhenHeard = CopyOnWriteArrayList<Boolean>()
        output.setSessionListener(
            object : CastManager.SessionListener {
                override fun sessionStarted() {
                    activeWhenHeard += output.isActive.value
                }

                override fun sessionEnded() {
                    activeWhenHeard += output.isActive.value
                }

                override fun sessionReconnected() {
                    activeWhenHeard += output.isActive.value
                }
            },
        )

        output.onRouteSelected(KITCHEN)
        output.onRouteUnselected(KITCHEN)

        assertEquals(listOf(true, false), activeWhenHeard.toList())
    }

    @Test
    fun `creates a player for the chosen speaker`() = runBlocking<Unit> {
        session.start()
        session.state.awaitValue { it is MaSessionState.Connected }
        output.onRouteSelected(KITCHEN)

        val player = output.createPlayer { _, _ -> }

        assertTrue(player is MusicAssistantPlayer)
        assertEquals("Kitchen", player.name)
    }

    @Test
    fun `reports the speaker queue as playing before any player exists`() = runBlocking<Unit> {
        session.start()
        session.state.awaitValue { it is MaSessionState.Connected }
        assertFalse(output.isPlaying())

        output.onRouteSelected(KITCHEN)
        assertTrue(output.isPlaying())

        server.push("event_queue_updated_paused")
        session.queues.awaitValue { !it.getValue(KITCHEN).isPlaying }
        assertFalse(output.isPlaying())
    }

    @Test
    fun `attaches to the speaker this phone left playing`() = runBlocking<Unit> {
        store.playback = RemotePlayback(KITCHEN, listOf(MirroredItem("episode-a", ITEM_A, URI_A)))
        output.routes = routes
        output.setSessionListener(listener())

        session.start()

        eventually { routes.selected == listOf(KITCHEN) }
        output.onRouteSelected(KITCHEN)
        assertEquals(listOf("reconnected"), signals)
        assertEquals(listOf<OutputNotice>(OutputNotice.PlayingOn("Kitchen")), notices.toList())
        val events = CopyOnWriteArrayList<PlayerEvent>()
        output.createPlayer { _, event -> events += event }
        eventually { (events.lastOrNull() as? PlayerEvent.RemoteMetadataNotMatched)?.remoteEpisodeUuid == "episode-a" }
    }

    @Test
    fun `asks the attached player again when the playback manager did not hear the first announcement`() = runBlocking<Unit> {
        store.playback = RemotePlayback(KITCHEN, listOf(MirroredItem("episode-a", ITEM_A, URI_A)))
        output.routes = routes
        session.start()
        eventually { routes.selected == listOf(KITCHEN) }
        output.onRouteSelected(KITCHEN)
        val announcements = AtomicInteger()
        val heard = CopyOnWriteArrayList<PlayerEvent>()

        output.createPlayer { _, event ->
            if (event is PlayerEvent.RemoteMetadataNotMatched && announcements.incrementAndGet() > 1) {
                heard += event
            }
        }

        eventually { heard.isNotEmpty() }
    }

    @Test
    fun `forgets a session the speaker has moved on from`() = runBlocking<Unit> {
        store.playback = RemotePlayback(KITCHEN, listOf(MirroredItem("episode-a", "an-item-from-yesterday", URI_A)))
        output.routes = routes

        session.start()
        session.state.awaitValue { it is MaSessionState.Connected }

        eventually { store.playback == null }
        assertTrue(routes.selected.isEmpty())
        assertTrue(signals.isEmpty())
    }

    @Test
    fun `does not attach while the playback manager still holds a speaker player`() = runBlocking<Unit> {
        output.routes = routes
        output.setSessionListener(listener())
        session.start()
        session.state.awaitValue { it is MaSessionState.Connected }
        output.onRouteSelected(KITCHEN)
        val kept = output.createPlayer { _, _ -> }
        output.onRouteUnselected(KITCHEN)
        store.playback = RemotePlayback(KITCHEN, listOf(MirroredItem("episode-a", ITEM_A, URI_A)))

        output.attachIfPlaying()

        assertTrue(routes.selected.isEmpty())
        assertEquals(listOf("started", "ended"), signals)
        kept.stop()
        store.playback = RemotePlayback(KITCHEN, listOf(MirroredItem("episode-a", ITEM_A, URI_A)))
        output.attachIfPlaying()
        assertEquals(listOf(KITCHEN), routes.selected.toList())
    }

    @Test
    fun `asks for the speaker once and says nothing until the selection lands`() = runBlocking<Unit> {
        store.playback = RemotePlayback(KITCHEN, listOf(MirroredItem("episode-a", ITEM_A, URI_A)))
        output.routes = routes
        session.start()
        eventually { routes.selected == listOf(KITCHEN) }

        output.attachIfPlaying()
        output.attachIfPlaying()

        assertEquals(listOf(KITCHEN), routes.selected.toList())
        assertTrue(notices.isEmpty())
        output.onRouteSelected(KITCHEN)
        assertEquals(listOf<OutputNotice>(OutputNotice.PlayingOn("Kitchen")), notices.toList())
    }

    @Test
    fun `keeps needing the connection until the playback manager lets go of the player`() = runBlocking<Unit> {
        session.start()
        session.state.awaitValue { it is MaSessionState.Connected }
        assertFalse(output.needsConnection.value)

        output.onRouteSelected(KITCHEN)
        val kept = output.createPlayer { _, _ -> }
        output.onRouteUnselected(KITCHEN)
        assertTrue(output.needsConnection.value)

        kept.stop()
        assertFalse(output.needsConnection.value)
    }

    @Test
    fun `stops keeping the connection for a held player after the grace period`() = runBlocking<Unit> {
        val graceful = MusicAssistantOutput(
            session = session,
            resolver = MaMediaResolver(session, { emptyMap() }, { null }),
            playbackStore = store,
            scope = scope,
            heldPlayerGraceMs = 300,
            notify = { },
        )
        session.start()
        session.state.awaitValue { it is MaSessionState.Connected }
        graceful.onRouteSelected(KITCHEN)
        graceful.createPlayer { _, _ -> }

        graceful.onRouteUnselected(KITCHEN)
        assertTrue(graceful.needsConnection.value)

        graceful.needsConnection.awaitValue { !it }
        graceful.onRouteSelected(KITCHEN)
        assertTrue(graceful.needsConnection.value)
    }

    @Test
    fun `tries the attach again once the route selection was given up`() = runBlocking<Unit> {
        store.playback = RemotePlayback(KITCHEN, listOf(MirroredItem("episode-a", ITEM_A, URI_A)))
        output.routes = routes
        session.start()
        eventually { routes.selected == listOf(KITCHEN) }

        output.onRouteSelectionAbandoned(KITCHEN)
        output.attachIfPlaying()

        assertEquals(listOf(KITCHEN, KITCHEN), routes.selected.toList())
    }

    @Test
    fun `stays connected in the background until a pulled back player is released`() = runBlocking<Unit> {
        val foreground = MutableStateFlow(false)
        val accounts = InMemoryAccountStore(account(server.url))
        MaConnectionPolicy(session, foreground, output.needsConnection, accounts.account).launchIn(scope)
        output.onRouteSelected(KITCHEN)
        session.state.awaitValue { it is MaSessionState.Connected }
        val kept = output.createPlayer { _, _ -> }

        output.onRouteUnselected(KITCHEN)
        assertTrue(session.state.value is MaSessionState.Connected)
        kept.stop()

        session.state.awaitValue { it == MaSessionState.Disconnected }
    }

    @Test
    fun `ignores a failure that arrives after the user picked another speaker`() = runBlocking<Unit> {
        server.reply("player_queues/play_media", "error_media_not_found")
        output.routes = routes
        output.onRouteSelected(KITCHEN)
        session.start()
        session.state.awaitValue { it is MaSessionState.Connected }
        val first = output.createPlayer { _, _ -> }
        first.setEpisode(PodcastEpisode(uuid = "e", publishedDate = Date(), downloadUrl = "https://cdn.example.com/e.mp3"))
        output.onRouteSelected(OFFICE)
        val second = output.createPlayer { _, _ -> }
        second.setEpisode(PodcastEpisode(uuid = "e", publishedDate = Date(), downloadUrl = "https://cdn.example.com/e.mp3"))

        first.play(0)
        second.play(0)

        eventually { routes.phoneSelections >= 1 }
        assertEquals(listOf(OFFICE), routes.phone.toList())
        assertEquals(listOf<OutputNotice>(OutputNotice.PlaybackFailed("Office")), notices.toList())
    }

    @Test
    fun `delivers each session signal exactly once while the listener registers`() {
        repeat(200) {
            val received = CopyOnWriteArrayList<String>()
            val racing = MusicAssistantOutput(session, MaMediaResolver(session, { emptyMap() }, { null }), store, scope) { }
            val start = CountDownLatch(1)
            val threads = listOf(
                thread { start.await(); racing.onRouteSelected(KITCHEN) },
                thread {
                    start.await()
                    racing.setSessionListener(
                        object : CastManager.SessionListener {
                            override fun sessionStarted() {
                                received += "started"
                            }

                            override fun sessionEnded() = Unit

                            override fun sessionReconnected() = Unit
                        },
                    )
                },
            )
            start.countDown()
            threads.forEach { it.join() }

            assertEquals(listOf("started"), received.toList())
        }
    }

    @Test
    fun `goes back to the phone when playback fails`() = runBlocking<Unit> {
        server.reply("player_queues/play_media", "error_media_not_found")
        output.routes = routes
        output.onRouteSelected(KITCHEN)
        session.start()
        session.state.awaitValue { it is MaSessionState.Connected }
        val player = output.createPlayer { _, _ -> }
        player.setEpisode(PodcastEpisode(uuid = "e", publishedDate = Date(), downloadUrl = "https://cdn.example.com/e.mp3"))

        player.play(0)

        eventually { routes.phoneSelections == 1 }
        assertEquals(listOf<OutputNotice>(OutputNotice.PlaybackFailed("Kitchen")), notices.toList())
    }

    @Test
    fun `lets playback finish failing before the route changes`() = runBlocking<Unit> {
        server.reply("player_queues/play_media", "error_media_not_found")
        output.onRouteSelected(KITCHEN)
        session.start()
        session.state.awaitValue { it is MaSessionState.Connected }
        val player = output.createPlayer { _, _ -> }
        player.setEpisode(PodcastEpisode(uuid = "e", publishedDate = Date(), downloadUrl = "https://cdn.example.com/e.mp3"))
        output.routes = StoppingRoutes(player)

        player.play(0)

        eventually { !output.isActive.value }
    }

    @Test
    fun `tells the user when the connection drops or the token is refused`() = runBlocking<Unit> {
        session.start()
        session.state.awaitValue { it is MaSessionState.Connected }
        output.onRouteSelected(KITCHEN)

        server.reply("auth", "auth_invalid_token")
        server.dropConnections()

        eventually { notices.toList() == listOf(OutputNotice.Reconnecting, OutputNotice.SignInRequired) }
    }

    @Test
    fun `asks for a new sign-in once per refused token`() = runBlocking<Unit> {
        server.reply("auth", "auth_invalid_token")
        session.start()
        eventually { notices.toList() == listOf(OutputNotice.SignInRequired) }

        repeat(3) {
            session.stop()
            session.start()
        }
        Thread.sleep(200)

        assertEquals(listOf<OutputNotice>(OutputNotice.SignInRequired), notices.toList())
    }

    @Test
    fun `asks the route provider to return to the phone`() = runBlocking<Unit> {
        output.routes = routes
        output.onRouteSelected(KITCHEN)

        output.endSession()

        assertEquals(listOf(KITCHEN), routes.phone.toList())
    }

    private fun listener() = object : CastManager.SessionListener {
        override fun sessionStarted() {
            signals += "started"
        }

        override fun sessionEnded() {
            signals += "ended"
        }

        override fun sessionReconnected() {
            signals += "reconnected"
        }
    }

    private class RecordingRoutes : OutputRoutes {
        val selected = CopyOnWriteArrayList<String>()
        val phone = CopyOnWriteArrayList<String>()
        val phoneSelections: Int get() = phone.size

        override fun selectPlayer(playerId: String) {
            selected += playerId
        }

        override fun selectPhone(playerId: String) {
            phone += playerId
        }
    }

    private inner class StoppingRoutes(private val player: Player) : OutputRoutes {
        override fun selectPlayer(playerId: String) = Unit

        override fun selectPhone(playerId: String) {
            runBlocking { player.stop() }
            output.onRouteUnselected(KITCHEN)
        }
    }

    private companion object {
        const val KITCHEN = "media_player.kitchen"
        const val OFFICE = "media_player.office"
        const val ITEM_A = "5b2f0e8c9d6a4f1b8e3c7a2d1f0e9b8a"
        const val URI_A = "builtin://track/https://cdn.example.com/a.mp3"
    }
}
