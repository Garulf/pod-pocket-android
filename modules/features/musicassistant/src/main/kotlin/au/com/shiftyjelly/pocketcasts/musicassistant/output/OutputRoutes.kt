package au.com.shiftyjelly.pocketcasts.musicassistant.output

import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaPlayer

interface OutputRoutes {
    fun selectPlayer(playerId: String)

    fun selectPhone()
}

sealed interface OutputNotice {
    data class PlayingOn(val playerName: String) : OutputNotice

    data object Reconnecting : OutputNotice

    data object SignInRequired : OutputNotice

    data class PlaybackFailed(val playerName: String) : OutputNotice
}

private val OUTPUT_PLAYER_TYPES = setOf("player", "stereo_pair", "group")

fun outputPlayers(players: Collection<MaPlayer>): List<MaPlayer> {
    return players
        .filter { it.enabled && it.available && !it.hideInUi && it.syncedTo == null && it.type in OUTPUT_PLAYER_TYPES }
        .sortedBy { it.name.lowercase() }
}
