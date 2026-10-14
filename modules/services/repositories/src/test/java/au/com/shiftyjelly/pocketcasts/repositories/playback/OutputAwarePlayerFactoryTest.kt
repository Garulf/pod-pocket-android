package au.com.shiftyjelly.pocketcasts.repositories.playback

import au.com.shiftyjelly.pocketcasts.models.entity.BaseEpisode
import au.com.shiftyjelly.pocketcasts.models.entity.Podcast
import au.com.shiftyjelly.pocketcasts.models.to.PlaybackEffects
import au.com.shiftyjelly.pocketcasts.repositories.chromecast.CastManager
import java.util.Optional
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class OutputAwarePlayerFactoryTest {
    private val players = object : PlayerFactory {
        override fun createCastPlayer(onPlayerEvent: (Player, PlayerEvent) -> Unit): Player = NamedPlayer("Cast")

        override fun createSimplePlayer(onPlayerEvent: (Player, PlayerEvent) -> Unit): Player = NamedPlayer("Phone")
    }
    private val remote = object : RemoteOutput {
        override val isActive = MutableStateFlow(false)

        override fun createPlayer(onPlayerEvent: (Player, PlayerEvent) -> Unit): Player = NamedPlayer("Music Assistant")

        override fun setSessionListener(listener: CastManager.SessionListener?) = Unit

        override suspend fun isPlaying() = false

        override suspend fun endSession() = Unit
    }

    @Test
    fun `creates the Music Assistant player while a speaker is chosen`() {
        val factory = OutputAwarePlayerFactory(players, Optional.of(remote))

        assertEquals("Cast", factory.createCastPlayer { _, _ -> }.name)
        remote.isActive.value = true
        assertEquals("Music Assistant", factory.createCastPlayer { _, _ -> }.name)
        assertEquals("Phone", factory.createSimplePlayer { _, _ -> }.name)
    }

    @Test
    fun `creates Chromecast players when the app has no remote output`() {
        val factory = OutputAwarePlayerFactory(players, Optional.empty())

        assertEquals("Cast", factory.createCastPlayer { _, _ -> }.name)
    }

    private class NamedPlayer(override val name: String) : Player {
        override val isRemote = false
        override val isStreaming = false
        override val filePath: String? = null
        override val url: String? = null
        override val episodeUuid: String? = null
        override val isDownloading = false
        override val onPlayerEvent: (Player, PlayerEvent) -> Unit = { _, _ -> }

        override suspend fun load(currentPositionMs: Int) = Unit

        override suspend fun getCurrentPositionMs() = 0

        override suspend fun play(currentPositionMs: Int) = Unit

        override suspend fun pause() = Unit

        override suspend fun stop() = Unit

        override suspend fun setPlaybackEffects(playbackEffects: PlaybackEffects) = Unit

        override suspend fun seekToTimeMs(positionMs: Int) = Unit

        override suspend fun isPlaying() = false

        override suspend fun isBuffering() = false

        override suspend fun durationMs(): Int? = null

        override suspend fun bufferedUpToMs() = 0

        override suspend fun bufferedPercentage() = 0

        override fun supportsTrimSilence() = false

        override fun supportsVolumeBoost() = false

        override fun supportsVideo() = false

        override fun setVolume(volume: Float) = Unit

        override fun setPodcast(podcast: Podcast?) = Unit

        override fun setEpisode(episode: BaseEpisode, preferStream: Boolean) = Unit
    }
}
