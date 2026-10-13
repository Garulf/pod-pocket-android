package au.com.shiftyjelly.pocketcasts.servers.podcast

import au.com.shiftyjelly.pocketcasts.servers.di.NetworkModule
import org.junit.Assert.assertEquals
import org.junit.Test

class GeneratedTranscriptsDisabledTest {
    private val adapter = NetworkModule().provideMoshi().adapter(EpisodeInfo::class.java)

    @Test
    fun `ignores the generated transcript flag from the server`() {
        val episodeInfo = adapter.fromJson(
            """
            {
              "uuid": "episode-uuid",
              "url": "https://example.com/episode.mp3",
              "published": "2026-06-11T00:00:00Z",
              "has_generated_transcript": true
            }
            """.trimIndent(),
        )

        assertEquals(false, episodeInfo?.toEpisode("podcast-uuid")?.hasGeneratedTranscript)
    }
}
