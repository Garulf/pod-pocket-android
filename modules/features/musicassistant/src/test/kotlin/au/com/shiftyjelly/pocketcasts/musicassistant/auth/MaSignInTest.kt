package au.com.shiftyjelly.pocketcasts.musicassistant.auth

import au.com.shiftyjelly.pocketcasts.musicassistant.FakeMaServer
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaConnection
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaLoginResult
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaProvider
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class MaSignInTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val server = FakeMaServer()
    private val client = OkHttpClient()
    private val signIn = MaSignIn(connections = { MaConnection(client) }, deviceName = "Pod Pocket on Pixel 9", timeoutMs = ROOMY_TIMEOUT_MS)

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
        assertEquals("providers", server.next().name)
        assertEquals(FakeMaServer.Command("auth/token/create", mapOf("name" to "Pod Pocket on Pixel 9")), server.next())
        assertEquals("auth/logout", server.next().name)
    }

    @Test
    fun `passes on the reason a password is refused`() = runBlocking<Unit> {
        server.reply("auth/login", "login_failed")

        val result = signIn.signIn(server.url, "alice", "wrong")

        assertEquals(MaSignInResult.Rejected("Invalid username or password"), result)
    }

    @Test
    fun `reports a server that refuses the connection`() = runBlocking<Unit> {
        val url = server.url
        server.close()

        assertEquals(MaSignInResult.Unreachable("Server refused the connection"), signIn.signIn(url, "alice", "hunter2"))
    }

    @Test
    fun `reports a host that does not exist`() = runBlocking<Unit> {
        val unresolvable = OkHttpClient.Builder().dns { throw UnknownHostException(it) }.build()

        val result = MaSignIn(connections = { MaConnection(unresolvable) }, deviceName = "Pod Pocket", timeoutMs = ROOMY_TIMEOUT_MS)
            .signIn("http://no-such-host.invalid:8095/".toHttpUrl(), "alice", "hunter2")

        assertEquals(MaSignInResult.Unreachable("Server not found"), result)
    }

    @Test
    fun `reports a web server that is not Music Assistant`() = runBlocking<Unit> {
        val web = MockWebServer()
        web.enqueue(MockResponse().setBody("<html>router admin</html>"))
        web.start()

        try {
            val result = signIn.signIn(web.url("/"), "alice", "hunter2")

            assertEquals(MaSignInResult.Unreachable("Not a Music Assistant server"), result)
        } finally {
            web.shutdown()
        }
    }

    @Test
    fun `gives up on a server that never answers`() = runBlocking<Unit> {
        server.reply("auth/login")
        val impatient = MaSignIn(connections = { MaConnection(client) }, deviceName = "Pod Pocket", timeoutMs = 2_000)

        val result = impatient.signIn(server.url, "alice", "hunter2")

        assertEquals(MaSignInResult.Unreachable("Music Assistant did not answer"), result)
    }

    @Test
    fun `revokes the short-lived token when the long-lived one cannot be created`() = runBlocking<Unit> {
        signedInServer()
        server.reply("auth/token/create", "error_invalid_command")

        val result = signIn.signIn(server.url, "alice", "hunter2")

        assertEquals(MaSignInResult.Rejected("Can not seek items without duration."), result)
        server.awaitCommand("auth/logout")
    }

    @Test
    fun `revokes the short-lived token when the sign-in runs out of time`() = runBlocking<Unit> {
        signedInServer()
        server.reply("auth/token/create")

        val result = MaSignIn(connections = { MaConnection(client) }, deviceName = "Pod Pocket", timeoutMs = 5_000)
            .signIn(server.url, "alice", "hunter2")

        assertEquals(MaSignInResult.Unreachable("Music Assistant did not answer"), result)
        server.awaitCommand("auth/logout")
    }

    @Test
    fun `revokes the short-lived token when the caller gives up`() = runBlocking<Unit> {
        signedInServer()
        server.reply("auth/token/create")

        val attempt = async(Dispatchers.Default) { signIn.signIn(server.url, "alice", "hunter2") }
        server.awaitCommand("auth/token/create")
        attempt.cancelAndJoin()

        server.awaitCommand("auth/logout")
    }

    @Test
    fun `does not revoke anything when the password is refused`() = runBlocking<Unit> {
        server.reply("auth/login", "login_failed")

        signIn.signIn(server.url, "alice", "wrong")

        assertEquals("auth/login", server.next().name)
        assertNull(server.commands.poll(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `still signs in when the provider list cannot be read`() = runBlocking<Unit> {
        signedInServer()
        server.reply("providers", "error_invalid_command")

        val result = signIn.signIn(server.url, "alice", "hunter2")

        assertEquals(MaSignInResult.SignedIn("long.lived.jwt", emptyList()), result)
    }

    @Test
    fun `still signs in when the provider list is malformed`() = runBlocking<Unit> {
        signedInServer()
        server.reply("providers", "command_ok")

        val result = signIn.signIn(server.url, "alice", "hunter2")

        assertEquals(MaSignInResult.SignedIn("long.lived.jwt", emptyList()), result)
    }

    @Test
    fun `still signs in when the logout is refused`() = runBlocking<Unit> {
        signedInServer()
        server.reply("auth/logout", "error_invalid_command")

        val result = signIn.signIn(server.url, "alice", "hunter2")

        assertEquals(MaSignInResult.SignedIn("long.lived.jwt", gpodderProvider), result)
    }

    @Test
    fun `still signs in when the logout never answers`() = runBlocking<Unit> {
        signedInServer()
        server.reply("auth/logout")
        val slowLogout = MaSignIn(connections = { MaConnection(client) }, deviceName = "Pod Pocket", timeoutMs = ROOMY_TIMEOUT_MS, logoutTimeoutMs = 1_000)

        val result = slowLogout.signIn(server.url, "alice", "hunter2")

        assertEquals(MaSignInResult.SignedIn("long.lived.jwt", gpodderProvider), result)
    }

    @Test
    fun `still signs in when the socket closes during the logout`() = runBlocking<Unit> {
        signedInServer()
        server.reply("auth/logout")

        val attempt = async(Dispatchers.Default) { signIn.signIn(server.url, "alice", "hunter2") }
        server.awaitCommand("auth/logout")
        server.dropConnections()

        assertEquals(MaSignInResult.SignedIn("long.lived.jwt", gpodderProvider), attempt.await())
    }

    @Test
    fun `keeps the token out of printed results`() {
        val signedIn = MaSignInResult.SignedIn("long.lived.jwt", emptyList())
        val login = MaLoginResult(success = true, accessToken = "short.lived.jwt")

        assertFalse("long.lived.jwt" in signedIn.toString())
        assertFalse("short.lived.jwt" in login.toString())
        assertEquals(signedIn, MaSignInResult.SignedIn("long.lived.jwt", emptyList()))
        assertTrue(signedIn != MaSignInResult.SignedIn("other", emptyList()))
    }

    private val gpodderProvider = listOf(MaProvider("music", "gpodder", "gPodder (Pod Pocket)", "gpodder--Xk3dTq9a"))

    private fun signedInServer() {
        server.reply("auth/login", "login_ok")
        server.reply("auth", "auth_ok")
        server.reply("auth/token/create", "token_created")
        server.reply("providers", "providers")
        server.reply("auth/logout", "logout_ok")
    }

    private companion object {
        const val ROOMY_TIMEOUT_MS = 10_000L
    }

    private fun FakeMaServer.awaitCommand(name: String) {
        while (next().name != name) {
            continue
        }
    }
}
