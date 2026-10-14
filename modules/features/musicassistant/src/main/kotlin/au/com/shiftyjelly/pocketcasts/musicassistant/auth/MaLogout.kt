package au.com.shiftyjelly.pocketcasts.musicassistant.auth

import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaConnection
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaConnectionFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

fun interface MaTokenRevoker {
    suspend fun revoke(account: MaAccount)
}

class MaLogout(
    private val connections: MaConnectionFactory,
    private val timeoutMs: Long = 3_000,
) : MaTokenRevoker {
    override suspend fun revoke(account: MaAccount) {
        var connection: MaConnection? = null
        try {
            val created = connections.create()
            connection = created
            withTimeoutOrNull(timeoutMs) {
                created.open(account.serverUrl)
                created.call("auth", mapOf("token" to account.token))
                created.call("auth/logout")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Music Assistant token was not revoked: ${e.javaClass.simpleName}")
        } finally {
            connection?.close()
        }
    }
}
