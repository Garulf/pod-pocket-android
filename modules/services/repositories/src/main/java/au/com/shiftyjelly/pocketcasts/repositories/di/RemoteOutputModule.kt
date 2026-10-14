package au.com.shiftyjelly.pocketcasts.repositories.di

import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.repositories.chromecast.CastManager
import au.com.shiftyjelly.pocketcasts.repositories.chromecast.CastManagerImpl
import au.com.shiftyjelly.pocketcasts.repositories.chromecast.OutputAwareCastManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.OutputAwarePlayerFactory
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlayerFactory
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlayerFactoryImpl
import au.com.shiftyjelly.pocketcasts.repositories.playback.RemoteOutput
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.Optional
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

@Module
@InstallIn(SingletonComponent::class)
object RemoteOutputModule {
    @Provides
    @Singleton
    fun provideCastManager(
        cast: CastManagerImpl,
        remoteOutput: Optional<RemoteOutput>,
        @ApplicationScope scope: CoroutineScope,
    ): CastManager = OutputAwareCastManager(cast, remoteOutput, scope)

    @Provides
    @Singleton
    fun providePlayerFactory(
        players: PlayerFactoryImpl,
        remoteOutput: Optional<RemoteOutput>,
    ): PlayerFactory = OutputAwarePlayerFactory(players, remoteOutput)
}

@Module
@InstallIn(SingletonComponent::class)
interface OptionalRemoteOutputModule {
    @BindsOptionalOf
    fun remoteOutput(): RemoteOutput
}
