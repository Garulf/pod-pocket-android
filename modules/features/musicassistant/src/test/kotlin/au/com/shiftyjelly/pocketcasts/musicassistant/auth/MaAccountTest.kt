package au.com.shiftyjelly.pocketcasts.musicassistant.auth

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class MaAccountTest {
    @Test
    fun `does not print the token`() {
        val account = MaAccount("http://192.168.1.20:8095/".toHttpUrl(), "long.lived.jwt", "gpodder--Xk3dTq9a", "https://pods.example.com/|alice@example.com")

        assertFalse(account.toString().contains("long.lived.jwt"))
        assertFalse(account.toString().contains("alice"))
        assertEquals(
            "MaAccount(serverUrl=http://192.168.1.20:8095/, token=<redacted>, gpodderInstanceId=gpodder--Xk3dTq9a, gpodderOwner=<redacted>)",
            account.toString(),
        )
    }

    @Test
    fun `uses the gPodder provider only for the Pod Pocket user who chose it`() {
        val account = MaAccount("http://192.168.1.20:8095/".toHttpUrl(), "long.lived.jwt", "gpodder--Xk3dTq9a", "https://pods.example.com/|alice@example.com")

        assertEquals("gpodder--Xk3dTq9a", account.gpodderInstanceFor("https://pods.example.com/|alice@example.com"))
        assertNull(account.gpodderInstanceFor("https://pods.example.com/|bob@example.com"))
        assertNull(account.gpodderInstanceFor("https://other.example.com/|alice@example.com"))
        assertNull(account.gpodderInstanceFor(null))
    }
}
