package au.com.shiftyjelly.pocketcasts.musicassistant.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.musicassistant.account
import au.com.shiftyjelly.pocketcasts.musicassistant.auth.SharedPreferencesMaAccountStore
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSessionControl
import au.com.shiftyjelly.pocketcasts.musicassistant.output.MaConnectionPolicy
import au.com.shiftyjelly.pocketcasts.preferences.AccessToken
import au.com.shiftyjelly.pocketcasts.selfhosted.SelfHostedRewriteInterceptor
import au.com.shiftyjelly.pocketcasts.servers.di.InterceptorModule
import au.com.shiftyjelly.pocketcasts.servers.di.NetworkModule
import au.com.shiftyjelly.pocketcasts.servers.sync.TokenHandler
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MusicAssistantWiringTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `asks the Pod Pocket server for episode sources with the signed-in token`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"episodes": []}"""))
        val tokens = object : TokenHandler {
            override suspend fun getAccessToken() = AccessToken("pod-pocket-token")

            override fun invalidateAccessToken() = Unit
        }
        val raw = OkHttpClient.Builder().addInterceptor(SelfHostedRewriteInterceptor { server.url("/") }).build()
        val interceptors = InterceptorModule.provideNoCacheTokenedInterceptors(
            InterceptorModule.provideTokenInterceptor(tokens),
            Interceptor { chain -> chain.proceed(chain.request()) },
        )
        val noCacheTokened = NetworkModule().provideNoCacheTokenedClient(raw, interceptors)

        withTimeout(10_000) { MusicAssistantModule.provideEpisodeSources(noCacheTokened).find(listOf("e1")) }

        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/api/podpocket/episode_sources", request.path)
        assertEquals("Bearer pod-pocket-token", request.getHeader("Authorization"))
    }

    @Test
    fun `signing in and out reconnects once each through the connection policy`() = runTest(UnconfinedTestDispatcher()) {
        val preferences = context.getSharedPreferences(SharedPreferencesMaAccountStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
        val accounts = SharedPreferencesMaAccountStore(preferences = preferences)
        val calls = mutableListOf<String>()
        val session = object : MaSessionControl {
            override fun start() {
                calls += "start"
            }

            override fun stop() {
                calls += "stop"
            }

            override fun restart() {
                calls += "restart"
            }
        }
        MaConnectionPolicy(session, MutableStateFlow(true), MutableStateFlow(false), accounts.account).launchIn(backgroundScope)

        accounts.save(account("http://192.168.1.20:8095/".toHttpUrl()))
        accounts.save(null)

        assertEquals(listOf("start", "restart", "restart"), calls)
    }
}
