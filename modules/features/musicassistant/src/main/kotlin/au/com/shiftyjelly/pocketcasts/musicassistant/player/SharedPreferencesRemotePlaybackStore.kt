package au.com.shiftyjelly.pocketcasts.musicassistant.player

import android.content.SharedPreferences
import au.com.shiftyjelly.pocketcasts.musicassistant.protocol.MaJson
import com.squareup.moshi.JsonDataException
import java.io.IOException

class SharedPreferencesRemotePlaybackStore(
    private val preferences: SharedPreferences,
) : RemotePlaybackStore {
    private val adapter = MaJson.moshi.adapter(RemotePlayback::class.java)

    override fun load(): RemotePlayback? {
        val json = preferences.getString(KEY_PLAYBACK, null) ?: return null
        return try {
            adapter.fromJson(json)
        } catch (e: IOException) {
            null
        } catch (e: JsonDataException) {
            null
        }
    }

    override fun save(playback: RemotePlayback?) {
        val editor = preferences.edit()
        if (playback == null) {
            editor.remove(KEY_PLAYBACK)
        } else {
            editor.putString(KEY_PLAYBACK, adapter.toJson(playback))
        }
        editor.apply()
    }

    companion object {
        const val PREFERENCES_NAME = "pod_pocket_music_assistant_playback"
        const val KEY_PLAYBACK = "playback"
    }
}
