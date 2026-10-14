package au.com.shiftyjelly.pocketcasts.musicassistant.output

import android.content.Context
import android.os.Looper
import androidx.mediarouter.media.MediaRouter
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.musicassistant.FakeMaServer
import au.com.shiftyjelly.pocketcasts.musicassistant.InMemoryAccountStore
import au.com.shiftyjelly.pocketcasts.musicassistant.InMemoryPlaybackStore
import au.com.shiftyjelly.pocketcasts.musicassistant.account
import au.com.shiftyjelly.pocketcasts.musicassistant.awaitValue
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaConnection
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSession
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSessionState
import au.com.shiftyjelly.pocketcasts.musicassistant.media.MaMediaResolver
import au.com.shiftyjelly.pocketcasts.musicassistant.player.RemotePlayback
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

// No JUnit Timeout rule: it would run the test off the main looper thread that MediaRouter requires. Every wait below is bounded.
@RunWith(RobolectricTestRunner::class)
class MaRouteProviderRoutingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = FakeMaServer().signedIn()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile
    private var retryDelayMs = 60_000L
    private val session = MaSession(scope, InMemoryAccountStore(account(server.url)), { MaConnection(OkHttpClient()) }, retryDelayMs = { retryDelayMs })
    private val store = InMemoryPlaybackStore()
    private val notices = CopyOnWriteArrayList<OutputNotice>()
    private val output = MusicAssistantOutput(
        session = session,
        resolver = MaMediaResolver(session, { emptyMap() }, { null }),
        playbackStore = store,
        scope = scope,
        notify = { notices += it },
    )
    private val provider = MaRouteProvider(context, session, output, scope, describe = ::label)
    private val router = MediaRouter.getInstance(context)

    @Before
    fun connect() {
        output.routes = provider
        session.start()
        runBlocking { session.state.awaitValue { it is MaSessionState.Connected } }
        provider.start()
        idleUntil { routeFor(KITCHEN) != null && routeFor(DOWNSTAIRS) != null }
    }

    @After
    fun tearDown() {
        router.removeProvider(provider)
        shadowOf(Looper.getMainLooper()).idle()
        // Robolectric resets the framework MediaRouter between tests but not the androidx router that wraps it.
        MediaRouter.resetGlobalRouter()
        session.stop()
        scope.cancel()
        server.close()
    }

    @Test
    fun `plays on a speaker while its route is selected`() {
        routeFor(KITCHEN)!!.select()
        assertEquals(KITCHEN, output.activePlayerId.value)

        router.unselect(MediaRouter.UNSELECT_REASON_STOPPED)

        idleUntil { output.activePlayerId.value == null }
    }

    @Test
    fun `keeps the selected speaker while the connection is down`() {
        routeFor(KITCHEN)!!.select()

        server.dropConnections()
        runBlocking { session.state.awaitValue { it is MaSessionState.Reconnecting } }

        idleUntil { routeFor(KITCHEN)?.description == RECONNECTING }
        assertTrue(routeFor(KITCHEN)!!.isEnabled)
        assertTrue(routeFor(KITCHEN)!!.isSelected)
        assertFalse(routeFor(DOWNSTAIRS)!!.isEnabled)
        assertEquals(KITCHEN, output.activePlayerId.value)
    }

    @Test
    fun `hands back to the phone when the selected speaker becomes unavailable`() {
        routeFor(KITCHEN)!!.select()

        server.push("event_player_updated_unavailable")

        idleUntil { output.activePlayerId.value == null }
        assertNull(routeFor(KITCHEN))
        assertFalse(router.selectedRoute.provider.providerInstance === provider)
    }

    @Test
    fun `returns to the phone only from the speaker that failed`() {
        routeFor(KITCHEN)!!.select()
        routeFor(DOWNSTAIRS)!!.select()
        idleUntil { output.activePlayerId.value == DOWNSTAIRS }

        provider.selectPhone(KITCHEN)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(DOWNSTAIRS, output.activePlayerId.value)
        assertTrue(routeFor(DOWNSTAIRS)!!.isSelected)
        provider.selectPhone(DOWNSTAIRS)
        idleUntil { output.activePlayerId.value == null }
        assertFalse(routeFor(DOWNSTAIRS)!!.isSelected)
    }

    @Test
    fun `gives up a selection whose speaker does not show up and lets the attach try again`() {
        server.push("event_player_updated_unavailable")
        idleUntil { routeFor(KITCHEN) == null }
        store.playback = RemotePlayback(KITCHEN, ITEM_A, "episode-a")

        output.attachIfPlaying()
        output.attachIfPlaying()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(notices.isEmpty())

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(MaRouteProvider.SELECTION_TIMEOUT_MS))
        server.pushEdited("event_player_updated_unavailable") { message ->
            @Suppress("UNCHECKED_CAST")
            message["data"] = (message["data"] as Map<String, Any?>) + ("available" to true)
        }
        idleUntil { routeFor(KITCHEN)?.isEnabled == true }
        assertNull(output.activePlayerId.value)

        output.attachIfPlaying()
        idleUntil { output.activePlayerId.value == KITCHEN }
        assertEquals(listOf<OutputNotice>(OutputNotice.PlayingOn("Kitchen")), notices.toList())
    }

    @Test
    fun `keeps waiting for a selection only until it lands`() {
        store.playback = RemotePlayback(KITCHEN, ITEM_A, "episode-a")
        output.attachIfPlaying()
        idleUntil { output.activePlayerId.value == KITCHEN }

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(MaRouteProvider.SELECTION_TIMEOUT_MS))

        assertEquals(KITCHEN, output.activePlayerId.value)
        assertTrue(routeFor(KITCHEN)!!.isSelected)
    }

    @Test
    fun `labels the selected speaker when Music Assistant wants a new sign-in`() {
        routeFor(KITCHEN)!!.select()
        retryDelayMs = 20L
        server.reply("auth", "auth_invalid_token")

        server.dropConnections()
        runBlocking { session.state.awaitValue { it == MaSessionState.SignInRequired } }

        idleUntil { routeFor(KITCHEN)?.description == SIGN_IN }
        assertTrue(routeFor(KITCHEN)!!.isSelected)
        assertTrue(routeFor(KITCHEN)!!.isEnabled)
        assertFalse(routeFor(DOWNSTAIRS)!!.isEnabled)
        assertEquals(SIGN_IN, routeFor(DOWNSTAIRS)!!.description)
    }

    @Test
    fun `lists no speakers while disconnected except the one playing`() {
        routeFor(KITCHEN)!!.select()

        session.stop()

        idleUntil { routeFor(DOWNSTAIRS) == null }
        assertTrue(routeFor(KITCHEN)!!.isSelected)
        assertEquals(RECONNECTING, routeFor(KITCHEN)!!.description)
        router.unselect(MediaRouter.UNSELECT_REASON_STOPPED)
        idleUntil { routeFor(KITCHEN) == null }
    }

    @Test
    fun `publishes the routes again only when they change`() {
        val published = provider.descriptor

        routeFor(KITCHEN)!!.select()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(KITCHEN, output.activePlayerId.value)
        assertSame(published, provider.descriptor)
    }

    @Test
    fun `sets the speaker volume from its route`() {
        val kitchen = routeFor(KITCHEN)!!
        kitchen.select()

        kitchen.requestSetVolume(50)

        val command = generateSequence { server.next() }.first { it.name == "players/cmd/volume_set" }
        assertEquals(KITCHEN, command.args["player_id"])
        assertEquals(50.0, (command.args["volume_level"] as Number).toDouble(), 0.0)
    }

    private fun label(state: MaSessionState) = when (state) {
        is MaSessionState.Connected -> CONNECTED
        MaSessionState.SignInRequired -> SIGN_IN
        else -> RECONNECTING
    }

    private fun routeFor(playerId: String): MediaRouter.RouteInfo? {
        return router.routes.firstOrNull { it.provider.providerInstance === provider && it.id.endsWith(":${MaRouteProvider.routeId(playerId)}") }
    }

    private fun idleUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) {
                return
            }
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Condition was not met within $timeoutMs ms")
            }
            Thread.sleep(10)
        }
    }

    private companion object {
        const val KITCHEN = "media_player.kitchen"
        const val DOWNSTAIRS = "syncgroup_1"
        const val ITEM_A = "5b2f0e8c9d6a4f1b8e3c7a2d1f0e9b8a"
        const val CONNECTED = "Music Assistant"
        const val RECONNECTING = "Reconnecting"
        const val SIGN_IN = "Sign in again"
    }
}
