package au.com.shiftyjelly.pocketcasts.crashlogging

import com.automattic.android.tracks.crashlogging.CrashLogging
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InitializeRemoteLogging @Inject constructor(
    private val crashLogging: CrashLogging,
) {
    operator fun invoke() {
        crashLogging.initialize()
    }
}
