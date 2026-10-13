package au.com.shiftyjelly.pocketcasts.selfhosted

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlsTest {
    @Test
    fun `normalizes what people type`() {
        val cases = mapOf(
            "pod.example.com" to "https://pod.example.com/",
            "  https://pod.example.com/  " to "https://pod.example.com/",
            "HTTPS://Pod.Example.com" to "https://pod.example.com/",
            "http://192.168.1.5:8000" to "http://192.168.1.5:8000/",
            "pod.example.com:8443" to "https://pod.example.com:8443/",
            "https://example.com/podpocket/" to "https://example.com/podpocket",
            "https://example.com//podpocket//" to "https://example.com/podpocket",
            "https://user:secret@example.com/?x=1#top" to "https://example.com/",
        )

        cases.forEach { (input, expected) ->
            assertEquals(input, expected, ServerUrls.parse(input).toString())
        }
    }

    @Test
    fun `rejects input that cannot be a server`() {
        val rejected = listOf(
            "",
            "   ",
            "https://",
            "ftp://example.com",
            "https://not a host",
            "https://api.pocketcasts.com",
            "static.pocketcasts.net",
            "public-api.wordpress.com",
        )

        rejected.forEach { input ->
            assertNull(input, ServerUrls.parse(input))
        }
    }
}
