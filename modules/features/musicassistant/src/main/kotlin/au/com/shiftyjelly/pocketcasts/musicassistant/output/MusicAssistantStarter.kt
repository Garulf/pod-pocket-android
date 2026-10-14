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
import dagger.Lazy
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dagger.hilt.internal.GeneratedComponentManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class MusicAssistantStarter(
    private val context: Context,
    private val accounts: Lazy<MaAccountStore>,
    private val session: Lazy<MaSession>,
    private val output: Lazy<MusicAssistantOutput>,
    private val scope: CoroutineScope,
) {
    private var started = false

    fun start() {
        if (started || Util.getAppPlatform(context) != AppPlatform.Phone) {
            return
        }
        started = true
        scope.launch(Dispatchers.IO) {
            try {
                val accounts = accounts.get()
                val session = session.get()
                val output = output.get()
                val foreground = withContext(Dispatchers.Main) {
                    val routes = MaRouteProvider(context, session, output, scope)
                    output.routes = routes
                    routes.start()
                    ProcessLifecycleOwner.get().lifecycle.currentStateFlow
                }
                MaConnectionPolicy(session, foreground.map { it.isAtLeast(Lifecycle.State.STARTED) }, output.needsConnection, accounts.account).launchIn(scope)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e("Music Assistant could not start: ${e.javaClass.simpleName}")
            }
        }
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
