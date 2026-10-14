package au.com.shiftyjelly.pocketcasts.musicassistant.settings

import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderLabelsTest {
    private val home = MaProvider("music", "gpodder", "gPodder (Pod Pocket)", "gpodder--Xk3dTq9a")
    private val old = MaProvider("music", "gpodder", "gPodder (old server)", "gpodder--Q2w3E4r5")
    private val twin = home.copy(instanceId = "gpodder--Z9y8X7w6")

    @Test
    fun `leaves out the instance id when names differ`() {
        assertNull(instanceIdWhenNamesClash(home, listOf(home, old)))
    }

    @Test
    fun `shows the instance id when two providers share a name`() {
        assertEquals("gpodder--Xk3dTq9a", instanceIdWhenNamesClash(home, listOf(home, twin)))
        assertEquals("gpodder--Z9y8X7w6", instanceIdWhenNamesClash(twin, listOf(home, twin)))
    }
}
