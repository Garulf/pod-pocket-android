package au.com.shiftyjelly.pocketcasts.selfhosted.di

import android.content.Context
import au.com.shiftyjelly.pocketcasts.selfhosted.OkHttpServerConnectionChecker
import au.com.shiftyjelly.pocketcasts.selfhosted.SelfHostedBlockNetworkInterceptor
import au.com.shiftyjelly.pocketcasts.selfhosted.SelfHostedRewriteInterceptor
import au.com.shiftyjelly.pocketcasts.selfhosted.ServerConnectionChecker
import au.com.shiftyjelly.pocketcasts.selfhosted.ServerUrlStore
import au.com.shiftyjelly.pocketcasts.selfhosted.SharedPreferencesServerUrlStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object SelfHostedModule {
    @Provides
    @Singleton
    fun provideServerUrlStore(@ApplicationContext context: Context): ServerUrlStore {
        val preferences = context.getSharedPreferences(SharedPreferencesServerUrlStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
        return SharedPreferencesServerUrlStore(preferences)
    }

    @Provides
    @Singleton
    fun provideRewriteInterceptor(store: ServerUrlStore): SelfHostedRewriteInterceptor {
        return SelfHostedRewriteInterceptor { store.serverUrl }
    }

    @Provides
    @Singleton
    fun provideBlockNetworkInterceptor(): SelfHostedBlockNetworkInterceptor {
        return SelfHostedBlockNetworkInterceptor()
    }

    @Provides
    @Singleton
    fun provideServerConnectionChecker(): ServerConnectionChecker {
        val client = OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
        return OkHttpServerConnectionChecker(client)
    }
}
