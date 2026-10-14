package au.com.shiftyjelly.pocketcasts.musicassistant.output

import au.com.shiftyjelly.pocketcasts.musicassistant.di.MusicAssistantModule
import au.com.shiftyjelly.pocketcasts.repositories.chromecast.CastManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MusicAssistantRemoteOutputTest {
    private val built = AtomicInteger()
    private val signals = RemoteSessionSignals()
    private val remote = MusicAssistantModule.provideRemoteOutput(signals) {
        built.incrementAndGet()
        error("The playback manager built the Music Assistant output")
    }

    @Test
    fun `answers the playback manager without building Music Assistant`() = runBlocking<Unit> {
        remote.setSessionListener(listener(CopyOnWriteArrayList()))

        assertFalse(remote.isActive.value)
        assertFalse(remote.isPlaying())
        remote.endSession()
        remote.setSessionListener(null)

        assertEquals(0, built.get())
    }

    @Test
    fun `passes session signals to the listener the playback manager registered`() {
        val heard = CopyOnWriteArrayList<String>()
        remote.setSessionListener(listener(heard))

        signals.setActive(true)
        signals.deliver { it.sessionStarted() }

        assertEquals(listOf("started"), heard.toList())
        assertEquals(true, remote.isActive.value)
        assertEquals(0, built.get())
    }

    private fun listener(heard: MutableList<String>) = object : CastManager.SessionListener {
        override fun sessionStarted() {
            heard += "started"
        }

        override fun sessionEnded() {
            heard += "ended"
        }

        override fun sessionReconnected() {
            heard += "reconnected"
        }
    }
}
