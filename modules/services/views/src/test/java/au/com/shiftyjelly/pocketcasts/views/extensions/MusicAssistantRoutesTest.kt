package au.com.shiftyjelly.pocketcasts.views.extensions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicAssistantRoutesTest {
    private val speakers = listOf("Kitchen", "Office")

    @Test
    fun `hides a Chromecast named like a speaker`() {
        assertTrue(hide("Kitchen"))
    }

    @Test
    fun `keeps a Chromecast with a different name`() {
        assertFalse(hide("Bedroom"))
    }

    @Test
    fun `keeps the selected Chromecast`() {
        assertFalse(hide("Kitchen", isSelected = true))
    }

    @Test
    fun `ignores case and surrounding whitespace`() {
        assertTrue(hide("  kitchen "))
        assertTrue(hide("OFFICE", speakers = listOf(" office")))
    }

    @Test
    fun `keeps the speaker itself`() {
        assertFalse(hide("Kitchen (Music Assistant)", isMusicAssistantRoute = true))
        assertFalse(hide("Kitchen", isMusicAssistantRoute = true))
    }

    @Test
    fun `hides nothing without speakers`() {
        assertFalse(hide("Kitchen", speakers = emptyList()))
    }

    private fun hide(
        name: String,
        isMusicAssistantRoute: Boolean = false,
        isSelected: Boolean = false,
        speakers: List<String> = this.speakers,
    ) = shouldHideRoute(name, isMusicAssistantRoute, isSelected, speakers)
}
