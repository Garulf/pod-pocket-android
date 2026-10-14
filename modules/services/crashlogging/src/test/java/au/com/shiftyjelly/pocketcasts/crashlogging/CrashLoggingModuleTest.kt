package au.com.shiftyjelly.pocketcasts.crashlogging

import au.com.shiftyjelly.pocketcasts.crashlogging.di.CrashLoggingModule
import org.junit.Assert.assertSame
import org.junit.Test

internal class CrashLoggingModuleTest {
    @Test
    fun `binds the no-op crash logger so Sentry is never started`() {
        assertSame(NoOpCrashLogging, CrashLoggingModule.provideCrashLogging())
    }
}
