package au.com.shiftyjelly.pocketcasts.repositories.playback

import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.chromecast.CastManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

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
    private val playbackManager = PlaybackManager(
        settings = mock<Settings>(),
        podcastManager = mock(),
        episodeManager = mock<EpisodeManager>(),
        statsManager = mock(),
        playerManager = mock(),
        castManager = castManager,
        application = RuntimeEnvironment.getApplication(),
        playlistManager = mock(),
        downloadQueue = mock(),
        upNextQueue = upNextQueue,
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
        alternateEnclosureManager = mock(),
        generatedChapterSeeker = mock(),
        playbackServiceErrorReporter = mock(),
    )

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
}
