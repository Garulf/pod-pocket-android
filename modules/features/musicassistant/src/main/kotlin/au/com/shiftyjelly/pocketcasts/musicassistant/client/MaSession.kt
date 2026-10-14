package au.com.shiftyjelly.pocketcasts.musicassistant.client

import au.com.shiftyjelly.pocketcasts.musicassistant.auth.MaAccount
import au.com.shiftyjelly.pocketcasts.musicassistant.auth.MaAccountStore
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaIncoming
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaJson
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaPlayer
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaQueue
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
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
import okhttp3.HttpUrl
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
    private val monotonicClock: () -> Double = { System.nanoTime() / NANOS_PER_SECOND },
    private val random: () -> Double = { Random.nextDouble() },
) : MaRemote, MaSessionControl {
    private val _state = MutableStateFlow<MaSessionState>(MaSessionState.Disconnected)
    val state: StateFlow<MaSessionState> = _state.asStateFlow()

    private val _players = MutableStateFlow<Map<String, MaPlayer>>(emptyMap())
    override val players: StateFlow<Map<String, MaPlayer>> = _players.asStateFlow()

    private val _queues = MutableStateFlow<Map<String, MaQueue>>(emptyMap())
    override val queues: StateFlow<Map<String, MaQueue>> = _queues.asStateFlow()

    @Volatile
    private var connection: MaConnection? = null

    override val isConnected: Boolean get() = connection != null

    @Volatile
    private var clockOffset = 0.0

    private var knownServer: HttpUrl? = null

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
        if (_state.value != MaSessionState.SignInRequired) {
            _state.value = MaSessionState.Disconnected
        }
    }

    @Synchronized
    override fun restart() {
        stop()
        start()
    }

    override suspend fun call(command: String, args: Map<String, Any?>, timeoutMs: Long?): Any? {
        val current = connection ?: throw MaNotConnectedException()
        return withinTimeout(command, timeoutMs ?: callTimeoutMs) { current.call(command, args) }
    }

    override fun serverNow(): Double = wallClock() + clockOffset

    private suspend fun run() {
        val owner = currentCoroutineContext().job
        var attempt = 0
        while (true) {
            val account = accounts.account.value
            adopt(owner, account?.serverUrl)
            if (account == null) {
                publish(owner, MaSessionState.NotConfigured)
                return
            }
            publish(owner, if (attempt == 0) MaSessionState.Connecting else MaSessionState.Reconnecting(attempt))
            val stayedUp = try {
                serve(owner, account)
            } catch (e: CancellationException) {
                throw e
            } catch (e: MaCommandException) {
                if (e.isAuthFailure) {
                    publish(owner, MaSessionState.SignInRequired)
                    return
                }
                Timber.w("Music Assistant refused a command with code ${e.code}")
                false
            } catch (e: Exception) {
                Timber.w("Music Assistant connection failed: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
            currentCoroutineContext().ensureActive()
            attempt = if (stayedUp) 1 else attempt + 1
            publish(owner, MaSessionState.Reconnecting(attempt))
            delay(withJitter(retryDelayMs(attempt)))
        }
    }

    private suspend fun serve(owner: Job, account: MaAccount): Boolean {
        val current = connections.create()
        try {
            val info = withinTimeout("open") { current.open(account.serverUrl) }
            withinTimeout("auth") { current.call("auth", mapOf("token" to account.token)) }
            val offset = measureClockOffset(current)
            val loadedPlayers = loadPlayers(current)
            val loadedQueues = loadQueues(current)
            publishConnected(owner, current, info.name, offset, loadedPlayers, loadedQueues)
            val connectedAt = monotonicClock()
            for (event in current.events) {
                owner.ensureActive()
                apply(owner, event)
            }
            return monotonicClock() - connectedAt >= STABLE_CONNECTION_SECONDS
        } finally {
            disconnect(current)
        }
    }

    private fun withJitter(delayMs: Long): Long = delayMs + (delayMs * MAX_JITTER * random()).toLong()

    @Synchronized
    private fun adopt(owner: Job, serverUrl: HttpUrl?) {
        if (!owner.isActive || serverUrl == knownServer) {
            return
        }
        knownServer = serverUrl
        _players.value = emptyMap()
        _queues.value = emptyMap()
        clockOffset = 0.0
    }

    @Synchronized
    private fun publish(owner: Job, next: MaSessionState) {
        if (owner.isActive) {
            _state.value = next
        }
    }

    @Synchronized
    private fun publishConnected(
        owner: Job,
        current: MaConnection,
        serverName: String?,
        offset: Double,
        loadedPlayers: Map<String, MaPlayer>,
        loadedQueues: Map<String, MaQueue>,
    ) {
        owner.ensureActive()
        clockOffset = offset
        _players.value = loadedPlayers
        _queues.value = loadedQueues
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
        val wallBefore = wallClock()
        val monotonicBefore = monotonicClock()
        val reply = try {
            withinTimeout("time") { current.call("time") }
        } catch (e: MaCommandException) {
            if (e.isAuthFailure) {
                throw e
            }
            Timber.w("Music Assistant refused the time command with code ${e.code}")
            return 0.0
        }
        val server = (reply as? Number)?.toDouble()
        if (server == null) {
            Timber.w("Music Assistant answered the time command with something that is not a number")
            return 0.0
        }
        val roundTrip = monotonicClock() - monotonicBefore
        return server - (wallBefore + roundTrip / 2)
    }

    private suspend fun <T> withinTimeout(what: String, timeoutMs: Long = callTimeoutMs, block: suspend () -> T): T {
        val answer = withTimeoutOrNull(timeoutMs) { Answer(block()) }
        return (answer ?: throw MaTimeoutException(what)).value
    }

    private class Answer<T>(val value: T)

    @Synchronized
    private fun apply(owner: Job, event: MaIncoming.Event) {
        if (!owner.isActive) {
            return
        }
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
        const val STABLE_CONNECTION_SECONDS = 30.0
        private const val MAX_JITTER = 0.2
        private const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}

fun defaultRetryDelayMs(attempt: Int): Long {
    return (1000L shl (attempt - 1).coerceIn(0, 6)).coerceAtMost(60_000L)
}
