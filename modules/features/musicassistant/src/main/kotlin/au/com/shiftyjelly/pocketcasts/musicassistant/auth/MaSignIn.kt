package au.com.shiftyjelly.pocketcasts.musicassistant.auth

import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaCommandException
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaConnection
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaConnectionFactory
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaTimeoutException
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaJson
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaLoginResult
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaProvider
import com.squareup.moshi.JsonDataException
import java.io.IOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl

sealed interface MaSignInResult {
    data class SignedIn(val token: String, val gpodderProviders: List<MaProvider>) : MaSignInResult {
        override fun toString() = "SignedIn(token=<redacted>, gpodderProviders=$gpodderProviders)"
    }

    data class Rejected(val reason: String) : MaSignInResult

    data class Unreachable(val reason: String) : MaSignInResult
}

fun interface MaSigner {
    suspend fun signIn(server: HttpUrl, username: String, password: String): MaSignInResult
}

class MaSignIn(
    private val connections: MaConnectionFactory,
    private val deviceName: String,
    private val timeoutMs: Long = 20_000,
    private val logoutTimeoutMs: Long = 3_000,
) : MaSigner {
    override suspend fun signIn(server: HttpUrl, username: String, password: String): MaSignInResult {
        val connection = connections.create()
        var authenticated = false
        return try {
            withTimeout(timeoutMs) {
                exchange(connection, server, username, password) { authenticated = true }
            }
        } catch (e: TimeoutCancellationException) {
            MaSignInResult.Unreachable(DID_NOT_ANSWER)
        } catch (e: MaCommandException) {
            MaSignInResult.Rejected(e.details ?: "Music Assistant error ${e.code}")
        } catch (e: JsonDataException) {
            MaSignInResult.Unreachable(NOT_MUSIC_ASSISTANT)
        } catch (e: IOException) {
            MaSignInResult.Unreachable(describe(e))
        } finally {
            withContext(NonCancellable) {
                if (authenticated) {
                    endSession(connection)
                }
                connection.close()
            }
        }
    }

    private suspend fun exchange(
        connection: MaConnection,
        server: HttpUrl,
        username: String,
        password: String,
        onAuthenticated: () -> Unit,
    ): MaSignInResult {
        connection.open(server)
        val login = MaJson.decode<MaLoginResult>(
            connection.call("auth/login", mapOf("username" to username, "password" to password, "device_name" to deviceName)),
        )
        val sessionToken = login.accessToken
        if (!login.success || sessionToken == null) {
            return MaSignInResult.Rejected(login.error ?: "Sign-in failed")
        }
        connection.call("auth", mapOf("token" to sessionToken))
        onAuthenticated()
        val providers = gpodderProviders(connection)
        val token = connection.call("auth/token/create", mapOf("name" to deviceName)) as? String
            ?: return MaSignInResult.Rejected("Music Assistant did not create a token")
        return MaSignInResult.SignedIn(token, providers)
    }

    private suspend fun gpodderProviders(connection: MaConnection): List<MaProvider> {
        return try {
            gpodderProvidersIn(connection.call("providers"))
        } catch (e: MaCommandException) {
            emptyList()
        } catch (e: JsonDataException) {
            emptyList()
        }
    }

    private suspend fun endSession(connection: MaConnection) {
        try {
            withTimeoutOrNull(logoutTimeoutMs) { connection.call("auth/logout") }
        } catch (e: MaCommandException) {
            return
        } catch (e: IOException) {
            return
        }
    }

    private fun describe(failure: IOException): String {
        val causes = generateSequence<Throwable>(failure) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
        return when {
            causes.any { it is UnknownHostException } -> "Server not found"
            causes.any { it is ConnectException } -> "Server refused the connection"
            causes.any { it is SocketTimeoutException || it is MaTimeoutException } -> DID_NOT_ANSWER
            causes.any { it is ProtocolException } -> NOT_MUSIC_ASSISTANT
            else -> "Could not connect to the server"
        }
    }

    companion object {
        const val GPODDER_DOMAIN = "gpodder"
        internal const val MUSIC_PROVIDER = "music"
        private const val DID_NOT_ANSWER = "Music Assistant did not answer"
        private const val NOT_MUSIC_ASSISTANT = "Not a Music Assistant server"
        private const val MAX_CAUSE_DEPTH = 8
    }
}

internal fun gpodderProvidersIn(providersReply: Any?): List<MaProvider> {
    return MaJson.decodeList<MaProvider>(providersReply)
        .filter { it.type == MaSignIn.MUSIC_PROVIDER && it.domain == MaSignIn.GPODDER_DOMAIN }
}
