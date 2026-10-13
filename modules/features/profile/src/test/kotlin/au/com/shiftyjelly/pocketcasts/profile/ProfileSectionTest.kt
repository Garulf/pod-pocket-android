package au.com.shiftyjelly.pocketcasts.profile

import org.junit.Assert.assertFalse
import org.junit.Test

class ProfileSectionTest {
    @Test
    fun `hides the Files section`() {
        assertFalse(ProfileSection.CloudFiles in ProfileSection.visibleEntries())
    }
}
