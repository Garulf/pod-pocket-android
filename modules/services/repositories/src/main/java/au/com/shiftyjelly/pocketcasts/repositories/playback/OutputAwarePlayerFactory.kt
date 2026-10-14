package au.com.shiftyjelly.pocketcasts.repositories.playback

import java.util.Optional

class OutputAwarePlayerFactory(
    private val players: PlayerFactory,
    remoteOutput: Optional<RemoteOutput>,
) : PlayerFactory {
    private val remote: RemoteOutput? = remoteOutput.orElse(null)

    override fun createCastPlayer(onPlayerEvent: (Player, PlayerEvent) -> Unit): Player {
        val output = remote?.takeIf { it.isActive.value } ?: return players.createCastPlayer(onPlayerEvent)
        return output.createPlayer(onPlayerEvent)
    }

    override fun createSimplePlayer(onPlayerEvent: (Player, PlayerEvent) -> Unit): Player {
        return players.createSimplePlayer(onPlayerEvent)
    }
}
