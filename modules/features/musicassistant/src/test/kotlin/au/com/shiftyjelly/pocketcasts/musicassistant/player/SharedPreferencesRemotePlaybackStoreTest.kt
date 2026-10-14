package au.com.shiftyjelly.pocketcasts.musicassistant.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharedPreferencesRemotePlaybackStoreTest {
    private val preferences = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences(SharedPreferencesRemotePlaybackStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val playback = RemotePlayback("media_player.kitchen", "5b2f0e8c9d6a4f1b8e3c7a2d1f0e9b8a", "episode-a")

    @After
    fun tearDown() {
        preferences.edit().clear().commit()
    }

    @Test
    fun `keeps the playback across instances`() {
        SharedPreferencesRemotePlaybackStore(preferences).save(playback)

        assertEquals(playback, SharedPreferencesRemotePlaybackStore(preferences).load())
    }

    @Test
    fun `forgets the playback`() {
        val store = SharedPreferencesRemotePlaybackStore(preferences)
        store.save(playback)

        store.save(null)

        assertNull(store.load())
    }

    @Test
    fun `ignores a record it cannot read`() {
        preferences.edit().putString(SharedPreferencesRemotePlaybackStore.KEY_PLAYBACK, """{"queue_id": 3}""").commit()

        assertNull(SharedPreferencesRemotePlaybackStore(preferences).load())
    }
}
