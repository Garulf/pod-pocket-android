package au.com.shiftyjelly.pocketcasts.musicassistant.output

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import au.com.shiftyjelly.pocketcasts.musicassistant.R

class ToastOutputNotices(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun show(notice: OutputNotice) {
        val text = when (notice) {
            is OutputNotice.PlayingOn -> context.getString(R.string.pod_pocket_ma_notice_playing_on, notice.playerName)
            OutputNotice.Reconnecting -> context.getString(R.string.pod_pocket_ma_notice_reconnecting)
            OutputNotice.SignInRequired -> context.getString(R.string.pod_pocket_ma_notice_sign_in)
            is OutputNotice.PlaybackFailed -> context.getString(R.string.pod_pocket_ma_notice_failed, notice.playerName)
        }
        mainHandler.post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }
}
