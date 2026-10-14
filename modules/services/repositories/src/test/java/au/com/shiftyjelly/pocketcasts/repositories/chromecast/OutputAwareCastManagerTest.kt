package au.com.shiftyjelly.pocketcasts.repositories.chromecast

import au.com.shiftyjelly.pocketcasts.repositories.playback.Player
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlayerEvent
import au.com.shiftyjelly.pocketcasts.repositories.playback.RemoteOutput
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OutputAwareCastManagerTest {
    private val cast = FakeCastManager()
    private val remote = FakeRemoteOutput()

    @Test
    fun `behaves like Chromecast when no remote output exists`() = runTest(UnconfinedTestDispatcher()) {
        val manager = OutputAwareCastManager(cast, Optional.empty(), backgroundScope)
        cast.connected.value = true
        cast.playing = true

        assertTrue(manager.isConnected())
        assertTrue(manager.isConnectedFlow.value)
        assertTrue(manager.isPlaying())
        manager.endSession()
        assertEquals(1, cast.endedSessions)
    }

    @Test
    fun `reports the remote output as connected and playing`() = runTest(UnconfinedTestDispatcher()) {
        val manager = OutputAwareCastManager(cast, Optional.of(remote), backgroundScope)

        remote.active.value = true
        remote.playing = true

        assertTrue(manager.isConnected())
        assertTrue(manager.isConnectedFlow.value)
        assertTrue(manager.isPlaying())
        manager.endSession()
        assertEquals(1, remote.endedSessions)
        assertEquals(0, cast.endedSessions)
        remote.active.value = false
        assertFalse(manager.isConnectedFlow.value)
    }

    @Test
    fun `gives the session listener to both outputs`() = runTest(UnconfinedTestDispatcher()) {
        val manager = OutputAwareCastManager(cast, Optional.of(remote), backgroundScope)
        val listener = object : CastManager.SessionListener {
            override fun sessionStarted() = Unit

            override fun sessionEnded() = Unit

            override fun sessionReconnected() = Unit
        }

        manager.startSessionListener(listener)

        assertNotNull(cast.listener)
        assertSame(listener, remote.listener)
        manager.stopSessionListener()
        assertEquals(null, remote.listener)
    }

    @Test
    fun `keeps Chromecast session events from reaching a listener while the remote output is active`() = runTest(UnconfinedTestDispatcher()) {
        val manager = OutputAwareCastManager(cast, Optional.of(remote), backgroundScope)
        val listener = RecordingListener()
        manager.startSessionListener(listener)
        remote.active.value = true

        cast.listener?.sessionStarted()
        cast.listener?.sessionReconnected()
        cast.listener?.sessionEnded()
        cast.listener?.sessionFailed(7, CastManager.SessionFailureType.RESUME)

        assertEquals(emptyList<String>(), listener.events)
    }

    @Test
    fun `passes Chromecast session events on while the remote output is inactive`() = runTest(UnconfinedTestDispatcher()) {
        val manager = OutputAwareCastManager(cast, Optional.of(remote), backgroundScope)
        val listener = RecordingListener()
        manager.startSessionListener(listener)

        cast.listener?.sessionStarted()
        cast.listener?.sessionReconnected()
        cast.listener?.sessionEnded()
        cast.listener?.sessionFailed(7, CastManager.SessionFailureType.RESUME)
        remote.active.value = true
        remote.active.value = false
        cast.listener?.sessionEnded()

        assertEquals(listOf("started", "reconnected", "ended", "failed 7 RESUME", "ended"), listener.events)
    }

    @Test
    fun `still passes the remote output's own session events on`() = runTest(UnconfinedTestDispatcher()) {
        val manager = OutputAwareCastManager(cast, Optional.of(remote), backgroundScope)
        val listener = RecordingListener()
        manager.startSessionListener(listener)
        remote.active.value = true

        remote.listener?.sessionReconnected()

        assertEquals(listOf("reconnected"), listener.events)
    }

    @Test
    fun `follows the remote output when Chromecast is connected too`() = runTest(UnconfinedTestDispatcher()) {
        val manager = OutputAwareCastManager(cast, Optional.of(remote), backgroundScope)
        cast.connected.value = true
        cast.playing = true
        remote.active.value = true
        remote.playing = false

        assertTrue(manager.isConnected())
        assertFalse(manager.isPlaying())
        manager.endSession()
        assertEquals(1, remote.endedSessions)
        assertEquals(0, cast.endedSessions)
        remote.active.value = false
        assertTrue(manager.isConnected())
        assertTrue(manager.isPlaying())
    }

    private class RecordingListener : CastManager.SessionListener {
        val events = mutableListOf<String>()

        override fun sessionStarted() {
            events += "started"
        }

        override fun sessionEnded() {
            events += "ended"
        }

        override fun sessionReconnected() {
            events += "reconnected"
        }

        override fun sessionFailed(errorCode: Int, failureType: CastManager.SessionFailureType) {
            events += "failed $errorCode $failureType"
        }
    }

    private class FakeCastManager : CastManager {
        val connected = MutableStateFlow(false)
        var playing = false
        var endedSessions = 0
        var listener: CastManager.SessionListener? = null
        override val isConnectedFlow: StateFlow<Boolean> = connected

        override suspend fun isAvailable() = true

        override suspend fun isConnected() = connected.value

        override suspend fun endSession() {
            endedSessions++
        }

        override suspend fun startSessionListener(sessionListener: CastManager.SessionListener) {
            listener = sessionListener
        }

        override suspend fun stopSessionListener() {
            listener = null
        }

        override suspend fun isPlaying() = playing
    }

    private class FakeRemoteOutput : RemoteOutput {
        override val isActive = MutableStateFlow(false)
        val active get() = isActive
        var playing = false
        var endedSessions = 0
        var listener: CastManager.SessionListener? = null

        override fun createPlayer(onPlayerEvent: (Player, PlayerEvent) -> Unit): Player = error("not used")

        override fun setSessionListener(listener: CastManager.SessionListener?) {
            this.listener = listener
        }

        override suspend fun isPlaying() = playing

        override suspend fun endSession() {
            endedSessions++
        }
    }
}
