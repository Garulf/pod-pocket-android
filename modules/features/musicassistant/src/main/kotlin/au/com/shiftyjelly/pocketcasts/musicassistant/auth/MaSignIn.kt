package au.com.shiftyjelly.pocketcasts.musicassistant.auth

import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaCommandException
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaConnection
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaConnectionFactory
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaJson
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaLoginResult
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaProvider
import com.squareup.moshi.JsonDataException
import java.io.IOException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl

sealed interface MaSignInResult {
    data class SignedIn(val token: String, val gpodderProviders: List<MaProvider>) : MaSignInResult

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
) : MaSigner {
    override suspend fun signIn(server: HttpUrl, username: String, password: String): MaSignInResult {
        val connection = connections.create()
        return try {
            withTimeout(timeoutMs) { exchange(connection, server, username, password) }
        } catch (e: TimeoutCancellationException) {
            MaSignInResult.Unreachable("Music Assistant did not answer")
        } catch (e: MaCommandException) {
            MaSignInResult.Rejected(e.details ?: "Music Assistant error ${e.code}")
        } catch (e: JsonDataException) {
            MaSignInResult.Unreachable("Not a Music Assistant server")
        } catch (e: IOException) {
            MaSignInResult.Unreachable(e.message ?: e.javaClass.simpleName)
        } finally {
            connection.close()
        }
    }

    private suspend fun exchange(connection: MaConnection, server: HttpUrl, username: String, password: String): MaSignInResult {
        connection.open(server)
        val login = MaJson.decode<MaLoginResult>(
            connection.call("auth/login", mapOf("username" to username, "password" to password, "device_name" to deviceName)),
        )
        val sessionToken = login.accessToken
        if (!login.success || sessionToken == null) {
            return MaSignInResult.Rejected(login.error ?: "Sign-in failed")
        }
        connection.call("auth", mapOf("token" to sessionToken))
        val token = connection.call("auth/token/create", mapOf("name" to deviceName)) as? String
            ?: return MaSignInResult.Rejected("Music Assistant did not create a token")
        val providers = MaJson.decodeList<MaProvider>(connection.call("providers"))
            .filter { it.type == MUSIC_PROVIDER && it.domain == GPODDER_DOMAIN }
        endSession(connection)
        return MaSignInResult.SignedIn(token, providers)
    }

    private suspend fun endSession(connection: MaConnection) {
        try {
            withTimeoutOrNull(LOGOUT_TIMEOUT_MS) { connection.call("auth/logout") }
        } catch (e: MaCommandException) {
            return
        } catch (e: IOException) {
            return
        }
    }

    companion object {
        const val GPODDER_DOMAIN = "gpodder"
        private const val MUSIC_PROVIDER = "music"
        private const val LOGOUT_TIMEOUT_MS = 3_000L
    }
}
