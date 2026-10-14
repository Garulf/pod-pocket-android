package au.com.shiftyjelly.pocketcasts.profile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSectionTest {
    @Test
    fun `hides the Files section`() {
        assertFalse(ProfileSection.CloudFiles in ProfileSection.visibleEntries())
    }

    @Test
    fun `keeps the Stats section visible`() {
        assertTrue(ProfileSection.Stats in ProfileSection.visibleEntries())
    }
}
