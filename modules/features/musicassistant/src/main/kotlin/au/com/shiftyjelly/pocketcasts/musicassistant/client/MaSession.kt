package au.com.shiftyjelly.pocketcasts.musicassistant.client

import au.com.shiftyjelly.pocketcasts.musicassistant.auth.MaAccountStore
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaIncoming
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaJson
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaPlayer
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaQueue
import com.squareup.moshi.JsonDataException
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

sealed interface MaSessionState {
    data object NotConfigured : MaSessionState

    data object Disconnected : MaSessionState

    data object Connecting : MaSessionState

    data class Connected(val serverName: String?) : MaSessionState

    data class Reconnecting(val attempt: Int) : MaSessionState

    data object SignInRequired : MaSessionState
}

interface MaSessionControl {
    fun start()

    fun stop()

    fun restart()
}

class MaSession(
    private val scope: CoroutineScope,
    private val accounts: MaAccountStore,
    private val connections: MaConnectionFactory,
    private val retryDelayMs: (attempt: Int) -> Long = ::defaultRetryDelayMs,
    private val wallClock: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val callTimeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS,
) : MaRemote, MaSessionControl {
    private val _state = MutableStateFlow<MaSessionState>(MaSessionState.Disconnected)
    val state: StateFlow<MaSessionState> = _state.asStateFlow()

    private val _players = MutableStateFlow<Map<String, MaPlayer>>(emptyMap())
    override val players: StateFlow<Map<String, MaPlayer>> = _players.asStateFlow()

    private val _queues = MutableStateFlow<Map<String, MaQueue>>(emptyMap())
    override val queues: StateFlow<Map<String, MaQueue>> = _queues.asStateFlow()

    @Volatile
    private var connection: MaConnection? = null

    @Volatile
    private var clockOffset = 0.0

    private var job: Job? = null

    @Synchronized
    override fun start() {
        if (job?.isActive == true) {
            return
        }
        job = scope.launch { run() }
    }

    @Synchronized
    override fun stop() {
        job?.cancel()
        job = null
        connection?.close()
        connection = null
        _state.value = MaSessionState.Disconnected
    }

    override fun restart() {
        stop()
        start()
    }

    override suspend fun call(command: String, args: Map<String, Any?>): Any? {
        val current = connection ?: throw MaNotConnectedException()
        return withinTimeout(command) { current.call(command, args) }
    }

    override fun serverNow(): Double = wallClock() + clockOffset

    private suspend fun run() {
        val owner = currentCoroutineContext().job
        var attempt = 0
        while (true) {
            val account = accounts.account.value
            if (account == null) {
                publish(owner, MaSessionState.NotConfigured)
                return
            }
            publish(owner, if (attempt == 0) MaSessionState.Connecting else MaSessionState.Reconnecting(attempt))
            val current = connections.create()
            try {
                val info = withinTimeout("open") { current.open(account.serverUrl) }
                withinTimeout("auth") { current.call("auth", mapOf("token" to account.token)) }
                clockOffset = withinTimeout("time") { measureClockOffset(current) }
                _players.value = loadPlayers(current)
                _queues.value = loadQueues(current)
                publishConnected(owner, current, info.name)
                attempt = 0
                for (event in current.events) {
                    apply(event)
                }
            } catch (e: MaCommandException) {
                if (e.isAuthFailure) {
                    publish(owner, MaSessionState.SignInRequired)
                    return
                }
                Timber.w("Music Assistant refused a command: ${e.message}")
            } catch (e: IOException) {
                Timber.w("Music Assistant connection failed: ${e.message}")
            } catch (e: JsonDataException) {
                Timber.w("Music Assistant sent something unexpected: ${e.message}")
            } finally {
                disconnect(current)
            }
            currentCoroutineContext().ensureActive()
            attempt++
            publish(owner, MaSessionState.Reconnecting(attempt))
            delay(retryDelayMs(attempt))
        }
    }

    @Synchronized
    private fun publish(owner: Job, next: MaSessionState) {
        if (owner.isActive) {
            _state.value = next
        }
    }

    @Synchronized
    private fun publishConnected(owner: Job, current: MaConnection, serverName: String?) {
        owner.ensureActive()
        connection = current
        _state.value = MaSessionState.Connected(serverName)
    }

    @Synchronized
    private fun disconnect(current: MaConnection) {
        if (connection === current) {
            connection = null
        }
        current.close()
    }

    private suspend fun loadPlayers(current: MaConnection): Map<String, MaPlayer> {
        val result = withinTimeout("players/all") { current.call("players/all") }
        return MaJson.decodeList<MaPlayer>(result).associateBy { it.playerId }
    }

    private suspend fun loadQueues(current: MaConnection): Map<String, MaQueue> {
        val result = withinTimeout("player_queues/all") { current.call("player_queues/all") }
        return MaJson.decodeList<MaQueue>(result).associateBy { it.queueId }
    }

    private suspend fun measureClockOffset(current: MaConnection): Double {
        val before = wallClock()
        val server = (current.call("time") as? Number)?.toDouble() ?: return 0.0
        val after = wallClock()
        return server - (before + after) / 2
    }

    private suspend fun <T> withinTimeout(what: String, block: suspend () -> T): T {
        val answer = withTimeoutOrNull(callTimeoutMs) { Answer(block()) }
        return (answer ?: throw MaTimeoutException(what)).value
    }

    private class Answer<T>(val value: T)

    private fun apply(event: MaIncoming.Event) {
        when (event.event) {
            "player_added", "player_updated" -> MaJson.decodeOrNull<MaPlayer>(event.data)?.let { player ->
                _players.update { it + (player.playerId to player) }
            }

            "player_removed" -> event.objectId?.let { id -> _players.update { it - id } }

            "queue_added", "queue_updated" -> MaJson.decodeOrNull<MaQueue>(event.data)?.let { queue ->
                _queues.update { it + (queue.queueId to queue) }
            }

            "queue_time_updated" -> {
                val id = event.objectId ?: return
                val elapsed = (event.data as? Number)?.toDouble() ?: return
                _queues.update { queues ->
                    val queue = queues[id] ?: return@update queues
                    queues + (id to queue.copy(elapsedTime = elapsed, elapsedTimeLastUpdated = serverNow()))
                }
            }
        }
    }

    companion object {
        const val DEFAULT_CALL_TIMEOUT_MS = 15_000L
    }
}

fun defaultRetryDelayMs(attempt: Int): Long {
    return (1000L shl (attempt - 1).coerceIn(0, 6)).coerceAtMost(60_000L)
}
