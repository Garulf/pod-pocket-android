package au.com.shiftyjelly.pocketcasts.musicassistant.output

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleInitializer
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.startup.Initializer
import au.com.shiftyjelly.pocketcasts.musicassistant.auth.MaAccountStore
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSession
import au.com.shiftyjelly.pocketcasts.utils.AppPlatform
import au.com.shiftyjelly.pocketcasts.utils.Util
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dagger.hilt.internal.GeneratedComponentManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.map

class MusicAssistantStarter(
    private val context: Context,
    private val session: MaSession,
    private val output: MusicAssistantOutput,
    private val accounts: MaAccountStore,
    private val routeProvider: MaRouteProvider,
    private val scope: CoroutineScope,
) {
    private var started = false

    fun start() {
        if (started || Util.getAppPlatform(context) != AppPlatform.Phone) {
            return
        }
        started = true
        output.routes = routeProvider
        routeProvider.start()
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) }
        MaConnectionPolicy(session, foreground, output.needsConnection, accounts.account).launchIn(scope)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MusicAssistantEntryPoint {
    fun starter(): MusicAssistantStarter
}

class MusicAssistantInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val application = context.applicationContext
        if (Util.getAppPlatform(context) != AppPlatform.Phone || application !is GeneratedComponentManager<*>) {
            return
        }
        // Hilt builds the application component in Application.onCreate, which runs after the startup provider.
        Handler(Looper.getMainLooper()).post {
            EntryPointAccessors.fromApplication(application, MusicAssistantEntryPoint::class.java).starter().start()
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = listOf(ProcessLifecycleInitializer::class.java)
}
