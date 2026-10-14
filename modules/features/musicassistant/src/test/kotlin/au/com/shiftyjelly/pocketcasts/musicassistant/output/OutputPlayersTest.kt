package au.com.shiftyjelly.pocketcasts.musicassistant.output

import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaPlayer
import org.junit.Assert.assertEquals
import org.junit.Test

class OutputPlayersTest {
    @Test
    fun `lists speakers and groups a person can pick`() {
        val players = listOf(
            MaPlayer("kitchen", "kitchen", "player", available = true),
            MaPlayer("office", "Office", "player", available = false),
            MaPlayer("group", "Downstairs", "group", available = true),
            MaPlayer("bath", "Bathroom", "player", available = true, syncedTo = "kitchen"),
            MaPlayer("airplay", "Kitchen AirPlay", "protocol", available = true),
            MaPlayer("hidden", "Hidden", "player", available = true, hideInUi = true),
            MaPlayer("off", "Disabled", "player", available = true, enabled = false),
            MaPlayer("screen", "Display", "display", available = true),
        )

        assertEquals(listOf("group", "kitchen", "office"), outputPlayers(players).map { it.playerId })
    }
}
