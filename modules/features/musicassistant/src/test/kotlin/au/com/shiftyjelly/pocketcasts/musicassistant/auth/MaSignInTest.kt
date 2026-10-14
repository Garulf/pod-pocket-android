package au.com.shiftyjelly.pocketcasts.musicassistant.auth

import au.com.shiftyjelly.pocketcasts.musicassistant.FakeMaServer
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaConnection
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaProvider
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class MaSignInTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val server = FakeMaServer()
    private val client = OkHttpClient()
    private val signIn = MaSignIn(connections = { MaConnection(client) }, deviceName = "Pod Pocket on Pixel 9", timeoutMs = 3_000)

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `trades the password for a long-lived token`() = runBlocking<Unit> {
        server.reply("auth/login", "login_ok")
        server.reply("auth", "auth_ok")
        server.reply("auth/token/create", "token_created")
        server.reply("providers", "providers")
        server.reply("auth/logout", "logout_ok")

        val result = signIn.signIn(server.url, "alice", "hunter2")

        assertEquals(
            MaSignInResult.SignedIn(
                token = "long.lived.jwt",
                gpodderProviders = listOf(MaProvider("music", "gpodder", "gPodder (Pod Pocket)", "gpodder--Xk3dTq9a")),
            ),
            result,
        )
        assertEquals(
            FakeMaServer.Command("auth/login", mapOf("username" to "alice", "password" to "hunter2", "device_name" to "Pod Pocket on Pixel 9")),
            server.next(),
        )
        assertEquals(FakeMaServer.Command("auth", mapOf("token" to "short.lived.jwt")), server.next())
        assertEquals(FakeMaServer.Command("auth/token/create", mapOf("name" to "Pod Pocket on Pixel 9")), server.next())
        assertEquals("providers", server.next().name)
        assertEquals("auth/logout", server.next().name)
    }

    @Test
    fun `passes on the reason a password is refused`() = runBlocking<Unit> {
        server.reply("auth/login", "login_failed")

        val result = signIn.signIn(server.url, "alice", "wrong")

        assertEquals(MaSignInResult.Rejected("Invalid username or password"), result)
    }

    @Test
    fun `reports a server that cannot be reached`() = runBlocking<Unit> {
        val url = server.url
        server.close()

        assertTrue(signIn.signIn(url, "alice", "hunter2") is MaSignInResult.Unreachable)
    }

    @Test
    fun `reports a web server that is not Music Assistant`() = runBlocking<Unit> {
        val web = MockWebServer()
        web.enqueue(MockResponse().setBody("<html>router admin</html>"))
        web.start()

        val result = signIn.signIn(web.url("/"), "alice", "hunter2")

        web.shutdown()
        assertTrue(result is MaSignInResult.Unreachable)
    }

    @Test
    fun `gives up on a server that never answers`() = runBlocking<Unit> {
        server.reply("auth/login")

        val result = signIn.signIn("${server.url}".toHttpUrl(), "alice", "hunter2")

        assertEquals(MaSignInResult.Unreachable("Music Assistant did not answer"), result)
    }
}
