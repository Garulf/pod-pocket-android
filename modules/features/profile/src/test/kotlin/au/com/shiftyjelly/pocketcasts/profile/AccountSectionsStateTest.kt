package au.com.shiftyjelly.pocketcasts.profile

import au.com.shiftyjelly.pocketcasts.profile.winback.WinbackInitParams
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSectionsStateTest {
    private val signedInPatron = AccountSectionsState(
        isSubscribedToNewsLetter = false,
        email = "listener@example.com",
        winbackInitParams = WinbackInitParams.Empty,
        canChangeCredentials = true,
        canUpgradeAccount = false,
        canCancelSubscription = true,
    )

    @Test
    fun `hides the rows the server cannot serve`() {
        val sections = signedInPatron.availableSections

        assertTrue(AccountSection.unsupportedByServer.none { it in sections })
    }

    @Test
    fun `keeps sign out visible`() {
        assertTrue(AccountSection.SignOut in signedInPatron.availableSections)
    }

    @Test
    fun `does not offer changing the avatar through Gravatar`() {
        assertFalse(AccountSection.ChangeAvatar in signedInPatron.availableSections)
    }
}
