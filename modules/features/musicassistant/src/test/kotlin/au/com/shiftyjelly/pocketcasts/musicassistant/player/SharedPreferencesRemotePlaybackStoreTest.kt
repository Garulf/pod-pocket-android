package au.com.shiftyjelly.pocketcasts.musicassistant.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.musicassistant.mirror.MirroredItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharedPreferencesRemotePlaybackStoreTest {
    private val preferences = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences(SharedPreferencesRemotePlaybackStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val playback = RemotePlayback(
        "media_player.kitchen",
        listOf(
            MirroredItem("episode-a", "5b2f0e8c9d6a4f1b8e3c7a2d1f0e9b8a", "gpodder--Xk3dTq9a://podcast_episode/https://pod.example.com/feeds/show.xml https://pod.example.com/feeds/ep-a"),
            MirroredItem("episode-b", "0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f", "builtin://track/https://cdn.example.com/b.mp3"),
        ),
    )

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
    fun `keeps a stop the speaker still owes`() {
        val owed = playback.copy(stopOwed = true)
        SharedPreferencesRemotePlaybackStore(preferences).save(owed)

        assertEquals(owed, SharedPreferencesRemotePlaybackStore(preferences).load())
    }

    @Test
    fun `reads a record saved before stops could be owed`() {
        preferences.edit()
            .putString(
                SharedPreferencesRemotePlaybackStore.KEY_PLAYBACK,
                """{"queue_id":"media_player.kitchen","items":[{"episode_uuid":"episode-b","queue_item_id":"0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f","uri":"builtin://track/https://cdn.example.com/b.mp3"}]}""",
            )
            .commit()

        assertEquals(RemotePlayback("media_player.kitchen", playback.items.drop(1)), SharedPreferencesRemotePlaybackStore(preferences).load())
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
        assertFalse(preferences.contains(SharedPreferencesRemotePlaybackStore.KEY_PLAYBACK))
    }

    @Test
    fun `ignores a record from the previous version`() {
        preferences.edit()
            .putString(SharedPreferencesRemotePlaybackStore.KEY_PLAYBACK, """{"queue_id":"media_player.kitchen","queue_item_id":"5b2f","episode_uuid":"episode-a"}""")
            .commit()

        assertNull(SharedPreferencesRemotePlaybackStore(preferences).load())
        assertFalse(preferences.contains(SharedPreferencesRemotePlaybackStore.KEY_PLAYBACK))
    }
}
