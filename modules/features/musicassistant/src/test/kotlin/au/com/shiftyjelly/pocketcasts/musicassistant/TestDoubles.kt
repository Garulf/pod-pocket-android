package au.com.shiftyjelly.pocketcasts.musicassistant

import au.com.shiftyjelly.pocketcasts.models.entity.BaseEpisode
import au.com.shiftyjelly.pocketcasts.musicassistant.auth.MaAccount
import au.com.shiftyjelly.pocketcasts.musicassistant.auth.MaAccountStore
import au.com.shiftyjelly.pocketcasts.musicassistant.mirror.EpisodeFinisher
import au.com.shiftyjelly.pocketcasts.musicassistant.mirror.UpNextEpisodes
import au.com.shiftyjelly.pocketcasts.musicassistant.player.RemotePlayback
import au.com.shiftyjelly.pocketcasts.musicassistant.player.RemotePlaybackStore
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl

class InMemoryAccountStore(initial: MaAccount? = null) : MaAccountStore {
    private val state = MutableStateFlow(initial)
    override val account: StateFlow<MaAccount?> = state

    override fun save(account: MaAccount?) {
        state.value = account
    }
}

class InMemoryPlaybackStore(var playback: RemotePlayback? = null) : RemotePlaybackStore {
    override fun load(): RemotePlayback? = playback

    override fun save(playback: RemotePlayback?) {
        this.playback = playback
    }
}

fun account(server: HttpUrl, gpodder: String? = "gpodder--Xk3dTq9a") = MaAccount(server, "long.lived.jwt", gpodder)

fun <T> CoroutineScope.record(flow: StateFlow<T>): List<T> {
    val seen = CopyOnWriteArrayList<T>()
    launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { flow.collect { seen += it } }
    return seen
}

suspend fun <T> StateFlow<T>.awaitValue(timeoutMs: Long = 10_000, predicate: (T) -> Boolean): T {
    return withTimeout(timeoutMs) { first(predicate) }
}

fun eventually(timeoutMs: Long = 10_000, check: () -> Boolean) = runBlocking {
    try {
        withTimeout(timeoutMs) {
            while (!check()) {
                delay(10)
            }
        }
    } catch (e: TimeoutCancellationException) {
        throw AssertionError("Condition was not met within $timeoutMs ms", e)
    }
}

class FakeUpNext(initial: List<BaseEpisode> = emptyList()) : UpNextEpisodes {
    val episodes = MutableStateFlow(initial)
    override val changes: Flow<List<BaseEpisode>> = episodes

    override fun current(): List<BaseEpisode> = episodes.value
}

class RecordingFinisher : EpisodeFinisher {
    val finished = CopyOnWriteArrayList<String>()

    override suspend fun finish(episodeUuids: List<String>) {
        finished += episodeUuids
    }
}
