package au.com.shiftyjelly.pocketcasts.musicassistant.auth

import au.com.shiftyjelly.pocketcasts.musicassistant.FakeMaServer
import au.com.shiftyjelly.pocketcasts.musicassistant.account
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaConnection
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class MaLogoutTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val server = FakeMaServer()
    private val client = OkHttpClient()

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `revokes the stored token by signing in with it and logging out`() = runBlocking<Unit> {
        server.reply("auth", "auth_ok")
        server.reply("auth/logout", "logout_ok")

        logout().revoke(account(server.url))

        assertEquals(FakeMaServer.Command("auth", mapOf("token" to "long.lived.jwt")), server.next())
        assertEquals("auth/logout", server.next().name)
    }

    @Test
    fun `gives up on a server that never answers the logout`() = runBlocking<Unit> {
        server.reply("auth", "auth_ok")
        server.reply("auth/logout")

        logout(timeoutMs = 1_000).revoke(account(server.url))

        assertEquals("auth", server.next().name)
        assertEquals("auth/logout", server.next().name)
    }

    @Test
    fun `leaves quietly when the server is gone`() = runBlocking<Unit> {
        val account = account(server.url)
        server.close()

        logout().revoke(account)
    }

    @Test
    fun `leaves quietly when Music Assistant no longer knows the token`() = runBlocking<Unit> {
        server.reply("auth", "auth_invalid_token")

        logout().revoke(account(server.url))

        assertEquals("auth", server.next().name)
    }

    @Test
    fun `leaves quietly when no connection can be created`() = runBlocking<Unit> {
        MaLogout(connections = { error("no client") }).revoke(account(server.url))
    }

    private fun logout(timeoutMs: Long = 10_000) = MaLogout(connections = { MaConnection(client) }, timeoutMs = timeoutMs)
}
