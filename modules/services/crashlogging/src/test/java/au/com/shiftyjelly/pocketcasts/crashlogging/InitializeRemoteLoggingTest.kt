package au.com.shiftyjelly.pocketcasts.crashlogging

import au.com.shiftyjelly.pocketcasts.crashlogging.fakes.FakeCrashLogging
import org.junit.Assert.assertTrue
import org.junit.Test

internal class InitializeRemoteLoggingTest {
    private val crashLogging = FakeCrashLogging()

    @Test
    fun `should initialize crash logging`() {
        InitializeRemoteLogging(crashLogging)()

        assertTrue(crashLogging.initialized)
    }
}
