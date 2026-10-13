package au.com.shiftyjelly.pocketcasts.account.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogInStateTest {
    @Test
    fun `accepts a username instead of an email`() {
        val state = LogInState(email = "alice", password = "pw", noNetworkErrorMessage = "offline")

        assertTrue(state.isEmailValid)
        assertTrue(state.isPasswordValid)
    }

    @Test
    fun `rejects blank credentials`() {
        val state = LogInState(email = " ", password = "", noNetworkErrorMessage = "offline")

        assertFalse(state.isEmailValid)
        assertFalse(state.isPasswordValid)
    }
}
