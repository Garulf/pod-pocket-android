package au.com.shiftyjelly.pocketcasts.musicassistant.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MaServerUrlsTest {
    @Test
    fun `fills in the Music Assistant defaults for a bare host`() {
        val cases = mapOf(
            "192.168.1.20" to "http://192.168.1.20:8095/",
            " music.local " to "http://music.local:8095/",
            "192.168.1.20:8095" to "http://192.168.1.20:8095/",
            "music.local:9000/" to "http://music.local:9000/",
            "https://ma.example.com" to "https://ma.example.com/",
            "http://192.168.1.20:8095/ma/" to "http://192.168.1.20:8095/ma",
            "https://user:secret@ma.example.com/?x=1#top" to "https://ma.example.com/",
        )

        cases.forEach { (input, expected) ->
            assertEquals(input, expected, MaServerUrls.parse(input).toString())
        }
    }

    @Test
    fun `rejects text that is not a server`() {
        listOf("", "  ", "http://", "ftp://music.local", "not a host").forEach { input ->
            assertNull(input, MaServerUrls.parse(input))
        }
    }
}
