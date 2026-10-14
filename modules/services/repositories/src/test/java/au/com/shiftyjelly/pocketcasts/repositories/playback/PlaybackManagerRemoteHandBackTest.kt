package au.com.shiftyjelly.pocketcasts.repositories.playback

import android.content.SharedPreferences
import android.os.Looper
import androidx.work.testing.WorkManagerTestInitHelper
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.models.to.PlaybackEffects
import au.com.shiftyjelly.pocketcasts.models.type.EpisodeDownloadStatus
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.preferences.UserSetting
import au.com.shiftyjelly.pocketcasts.repositories.chromecast.CastManager
import au.com.shiftyjelly.pocketcasts.repositories.chromecast.OutputAwareCastManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.AlternateEnclosureManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import java.util.Date
import java.util.Optional
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.mockingDetails
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PlaybackManagerRemoteHandBackTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val episode = PodcastEpisode(uuid = "episode", publishedDate = Date(), downloadUrl = "https://cdn.example.com/episode.mp3")
    private val upNextQueue: UpNextQueue = mock {
        on { isCurrentEpisode(any()) } doReturn true
    }
    private var outputConnected = false
    private val castManager: CastManager = mock {
        on { isConnected() } doSuspendableAnswer { outputConnected }
    }
    private val speakerPlayer: Player = mock {
        on { isRemote } doReturn true
    }
    private val phonePlayer: Player = mock {
        on { isRemote } doReturn false
    }
    private val playbackManager = playbackManager(castManager = castManager, playerManager = mock())

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `switches players when a speaker player outlived its output`() = runBlocking<Unit> {
        playbackManager.player = speakerPlayer

        playbackManager.playNowSync(episode)

        verify(upNextQueue).playNow(any(), anyOrNull(), any(), anyOrNull())
    }

    @Test
    fun `keeps the speaker player while its output is connected`() = runBlocking<Unit> {
        outputConnected = true
        playbackManager.player = speakerPlayer

        playbackManager.playNowSync(episode)

        verify(upNextQueue, never()).playNow(any(), anyOrNull(), any(), anyOrNull())
    }

    @Test
    fun `keeps the phone player while no output is connected`() = runBlocking<Unit> {
        playbackManager.player = phonePlayer

        playbackManager.playNowSync(episode)

        verify(upNextQueue, never()).playNow(any(), anyOrNull(), any(), anyOrNull())
    }

    @Test
    fun `moves to the speaker picked while a Chromecast plays`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(RuntimeEnvironment.getApplication())
        val downloaded = PodcastEpisode(uuid = "downloaded", publishedDate = Date(), downloadedFilePath = "/podcasts/downloaded.mp3", downloadStatus = EpisodeDownloadStatus.Downloaded)
        val queue: UpNextQueue = mock {
            on { currentEpisode } doReturn downloaded
        }
        val episodes: EpisodeManager = mock {
            on { findByUuid(downloaded.uuid) } doReturn downloaded
        }
        val chromecast = FakeChromecast()
        val speaker = FakeSpeakerOutput(speakerPlayer)
        val chromecastPlayer: Player = mock {
            on { isRemote } doReturn true
            on { getCurrentPositionMs() } doReturn 60_000
        }
        val players: PlayerFactory = mock {
            on { createCastPlayer(any()) } doReturn chromecastPlayer
            on { createSimplePlayer(any()) } doReturn phonePlayer
        }
        val castAware = OutputAwareCastManager(chromecast, Optional.of(speaker), scope)
        val manager = playbackManager(
            castManager = castAware,
            playerManager = OutputAwarePlayerFactory(players, Optional.of(speaker)),
            upNext = queue,
            episodes = episodes,
            settings = playableSettings(),
        )
        runBlocking { castAware.startSessionListener(sessionListenerOf(manager)) }
        manager.player = chromecastPlayer
        chromecast.connected.value = true
        chromecast.listener?.sessionStarted()

        speaker.isActive.value = true
        speaker.listener?.sessionStarted()
        chromecast.connected.value = false
        chromecast.listener?.sessionEnded()

        idleUntil { manager.player == null && mockingDetails(chromecastPlayer).invocations.any { it.method.name == "stop" } }
        idleUntil { manager.playbackStateRelay.blockingFirst().state == PlaybackState.State.PAUSED }
        manager.playQueue()
        idleUntil { manager.player != null }
        assertSame(speakerPlayer, manager.player)
    }

    private fun sessionListenerOf(manager: PlaybackManager) = object : CastManager.SessionListener {
        override fun sessionStarted() = manager.castConnected()

        override fun sessionEnded() = manager.castDisconnected()

        override fun sessionReconnected() = manager.castReconnected()
    }

    private fun playableSettings(): Settings {
        val preferences = mock<SharedPreferences>()
        val audioOnly = UserSetting.Mock(false, preferences)
        val noWarning = UserSetting.Mock(false, preferences)
        val noAutoSubscribe = UserSetting.Mock(false, preferences)
        val effects = UserSetting.Mock(PlaybackEffects(), preferences)
        return mock {
            on { this.audioOnly } doReturn audioOnly
            on { warnOnMeteredNetwork } doReturn noWarning
            on { autoSubscribeToPlayed } doReturn noAutoSubscribe
            on { globalPlaybackEffects } doReturn effects
        }
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

    private fun playbackManager(
        castManager: CastManager,
        playerManager: PlayerFactory,
        upNext: UpNextQueue = upNextQueue,
        episodes: EpisodeManager = mock(),
        settings: Settings = mock(),
    ) = PlaybackManager(
        settings = settings,
        podcastManager = mock(),
        episodeManager = episodes,
        statsManager = mock(),
        playerManager = playerManager,
        castManager = castManager,
        application = RuntimeEnvironment.getApplication(),
        playlistManager = mock(),
        downloadQueue = mock(),
        upNextQueue = upNext,
        notificationHelper = mock(),
        userEpisodeManager = mock(),
        eventHorizon = mock(),
        syncManager = mock(),
        bookmarkManager = mock(),
        showNotesManager = mock(),
        chapterManager = mock(),
        sleepTimer = mock(),
        playbackManagerNetworkWatcherFactory = mock(),
        applicationScope = scope,
        crashLogging = mock(),
        upNextHistoryManager = mock(),
        notificationManager = mock(),
        autoPlaySelector = mock(),
        browseTreeProvider = mock(),
        alternateEnclosureManager = mock<AlternateEnclosureManager> {
            on { findForEpisode(any()) } doReturn emptyList()
        },
        generatedChapterSeeker = mock(),
        playbackServiceErrorReporter = mock(),
    )

    private class FakeChromecast : CastManager {
        val connected = MutableStateFlow(false)
        var listener: CastManager.SessionListener? = null
        override val isConnectedFlow: StateFlow<Boolean> = connected

        override suspend fun isAvailable() = true

        override suspend fun isConnected() = connected.value

        override suspend fun endSession() = Unit

        override suspend fun startSessionListener(sessionListener: CastManager.SessionListener) {
            listener = sessionListener
        }

        override suspend fun stopSessionListener() {
            listener = null
        }

        override suspend fun isPlaying() = false
    }

    private class FakeSpeakerOutput(private val player: Player) : RemoteOutput {
        override val isActive = MutableStateFlow(false)
        var listener: CastManager.SessionListener? = null

        override fun createPlayer(onPlayerEvent: (Player, PlayerEvent) -> Unit): Player = player

        override fun setSessionListener(listener: CastManager.SessionListener?) {
            this.listener = listener
        }

        override suspend fun isPlaying() = false

        override suspend fun endSession() = Unit
    }
}
