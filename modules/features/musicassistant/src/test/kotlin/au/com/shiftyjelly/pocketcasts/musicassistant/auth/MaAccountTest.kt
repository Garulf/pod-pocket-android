package au.com.shiftyjelly.pocketcasts.musicassistant.auth

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MaAccountTest {
    @Test
    fun `does not print the token`() {
        val account = MaAccount("http://192.168.1.20:8095/".toHttpUrl(), "long.lived.jwt", "gpodder--Xk3dTq9a")

        assertFalse(account.toString().contains("long.lived.jwt"))
        assertEquals("MaAccount(serverUrl=http://192.168.1.20:8095/, token=<redacted>, gpodderInstanceId=gpodder--Xk3dTq9a)", account.toString())
    }
}
