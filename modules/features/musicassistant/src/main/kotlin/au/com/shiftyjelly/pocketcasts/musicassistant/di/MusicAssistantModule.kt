package au.com.shiftyjelly.pocketcasts.musicassistant.di

import android.content.Context
import android.os.Build
import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.musicassistant.auth.MaAccountStore
import au.com.shiftyjelly.pocketcasts.musicassistant.auth.MaSignIn
import au.com.shiftyjelly.pocketcasts.musicassistant.auth.MaSigner
import au.com.shiftyjelly.pocketcasts.musicassistant.auth.SharedPreferencesMaAccountStore
import au.com.shiftyjelly.pocketcasts.musicassistant.auth.encryptedPreferences
import au.com.shiftyjelly.pocketcasts.musicassistant.auth.freshEncryptedPreferences
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaConnection
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaConnectionFactory
import au.com.shiftyjelly.pocketcasts.musicassistant.client.MaSession
import au.com.shiftyjelly.pocketcasts.musicassistant.media.EpisodeSourceLookup
import au.com.shiftyjelly.pocketcasts.musicassistant.media.MaMediaResolver
import au.com.shiftyjelly.pocketcasts.musicassistant.media.ServerEpisodeSourceLookup
import au.com.shiftyjelly.pocketcasts.musicassistant.output.MusicAssistantOutput
import au.com.shiftyjelly.pocketcasts.musicassistant.output.MusicAssistantRemoteOutput
import au.com.shiftyjelly.pocketcasts.musicassistant.output.MusicAssistantStarter
import au.com.shiftyjelly.pocketcasts.musicassistant.output.RemoteSessionSignals
import au.com.shiftyjelly.pocketcasts.musicassistant.output.ToastOutputNotices
import au.com.shiftyjelly.pocketcasts.musicassistant.player.RemotePlaybackStore
import au.com.shiftyjelly.pocketcasts.musicassistant.player.SharedPreferencesRemotePlaybackStore
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.servers.di.NoCacheTokened
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Module
@InstallIn(SingletonComponent::class)
object MusicAssistantModule {
    @Provides
    @Singleton
    fun provideAccountStore(@ApplicationContext context: Context): MaAccountStore {
        return SharedPreferencesMaAccountStore(
            preferences = encryptedPreferences(context, SharedPreferencesMaAccountStore.PREFERENCES_NAME),
            resetPreferences = { freshEncryptedPreferences(context, SharedPreferencesMaAccountStore.PREFERENCES_NAME) },
        )
    }

    @Provides
    @Singleton
    fun providePlaybackStore(@ApplicationContext context: Context): RemotePlaybackStore {
        return SharedPreferencesRemotePlaybackStore(context.getSharedPreferences(SharedPreferencesRemotePlaybackStore.PREFERENCES_NAME, Context.MODE_PRIVATE))
    }

    @Provides
    @Singleton
    fun provideConnectionFactory(): MaConnectionFactory {
        val client = MaConnection.pingingClient(OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build())
        return MaConnectionFactory { MaConnection(client) }
    }

    @Provides
    @Singleton
    fun provideSession(@ApplicationScope scope: CoroutineScope, accounts: MaAccountStore, connections: MaConnectionFactory): MaSession {
        return MaSession(scope, accounts, connections)
    }

    @Provides
    @Singleton
    fun provideEpisodeSources(@NoCacheTokened client: Lazy<OkHttpClient>): EpisodeSourceLookup {
        return ServerEpisodeSourceLookup(episodeSourceCalls(client), Settings.SERVER_API_URL.toHttpUrl())
    }

    @Provides
    @Singleton
    fun provideResolver(session: MaSession, sources: EpisodeSourceLookup, accounts: MaAccountStore): MaMediaResolver {
        return MaMediaResolver(session, sources, { accounts.account.value?.gpodderInstanceId })
    }

    @Provides
    fun provideSigner(connections: MaConnectionFactory): MaSigner {
        return MaSignIn(connections, deviceName = "Pod Pocket on ${Build.MODEL}")
    }

    @Provides
    @Singleton
    fun provideSessionSignals(): RemoteSessionSignals = RemoteSessionSignals()

    @Provides
    @Singleton
    fun provideOutput(
        session: MaSession,
        resolver: MaMediaResolver,
        playbackStore: RemotePlaybackStore,
        signals: RemoteSessionSignals,
        @ApplicationScope scope: CoroutineScope,
        @ApplicationContext context: Context,
    ): MusicAssistantOutput {
        return MusicAssistantOutput(
            session = session,
            resolver = resolver,
            playbackStore = playbackStore,
            scope = scope,
            signals = signals,
            notify = ToastOutputNotices(context)::show,
        )
    }

    @Provides
    @Singleton
    fun provideRemoteOutput(signals: RemoteSessionSignals, output: Lazy<MusicAssistantOutput>): MusicAssistantRemoteOutput {
        return MusicAssistantRemoteOutput(signals, output)
    }

    @Provides
    @Singleton
    fun provideStarter(
        @ApplicationContext context: Context,
        accounts: Lazy<MaAccountStore>,
        session: Lazy<MaSession>,
        output: Lazy<MusicAssistantOutput>,
        @ApplicationScope scope: CoroutineScope,
    ): MusicAssistantStarter {
        return MusicAssistantStarter(context, accounts, session, output, scope)
    }
}

internal const val EPISODE_SOURCE_TIMEOUT_SECONDS = 15L

internal fun episodeSourceCalls(client: Lazy<OkHttpClient>): Call.Factory {
    val limited by lazy { client.get().newBuilder().callTimeout(EPISODE_SOURCE_TIMEOUT_SECONDS, TimeUnit.SECONDS).build() }
    return object : Call.Factory {
        override fun newCall(request: Request): Call = limited.newCall(request)
    }
}
