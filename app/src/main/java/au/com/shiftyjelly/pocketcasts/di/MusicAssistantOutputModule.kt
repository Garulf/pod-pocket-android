package au.com.shiftyjelly.pocketcasts.di

import au.com.shiftyjelly.pocketcasts.musicassistant.output.MusicAssistantRemoteOutput
import au.com.shiftyjelly.pocketcasts.repositories.playback.RemoteOutput
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
interface MusicAssistantOutputModule {
    @Binds
    fun bindRemoteOutput(output: MusicAssistantRemoteOutput): RemoteOutput
}
