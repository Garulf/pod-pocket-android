package au.com.shiftyjelly.pocketcasts.musicassistant.auth

import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaCommandException
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaConnectionFactory
import com.squareup.moshi.JsonDataException
import java.io.IOException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

fun interface MaTokenRevoker {
    suspend fun revoke(account: MaAccount)
}

class MaLogout(
    private val connections: MaConnectionFactory,
    private val timeoutMs: Long = 3_000,
) : MaTokenRevoker {
    override suspend fun revoke(account: MaAccount) {
        val connection = connections.create()
        try {
            withTimeoutOrNull(timeoutMs) {
                connection.open(account.serverUrl)
                connection.call("auth", mapOf("token" to account.token))
                connection.call("auth/logout")
            }
        } catch (e: MaCommandException) {
            return
        } catch (e: IOException) {
            return
        } catch (e: JsonDataException) {
            return
        } finally {
            withContext(NonCancellable) { connection.close() }
        }
    }
}
