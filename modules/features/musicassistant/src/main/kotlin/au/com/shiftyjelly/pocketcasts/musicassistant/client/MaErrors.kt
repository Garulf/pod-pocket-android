package au.com.shiftyjelly.pocketcasts.musicassistant.client

import java.io.IOException

class MaCommandException(val code: Int, val details: String?) : Exception("Music Assistant error $code: ${details.orEmpty()}") {
    val isAuthFailure: Boolean get() = code in AUTH_FAILURE_CODES

    companion object {
        const val MEDIA_NOT_FOUND = 2
        const val INVALID_COMMAND = 12
        private val AUTH_FAILURE_CODES = setOf(20, 21, 23)
    }
}

class MaConnectionClosedException(cause: Throwable? = null) : IOException("Music Assistant connection closed", cause)

class MaNotConnectedException : IOException("Not connected to Music Assistant")

class MaTimeoutException(what: String) : IOException("Music Assistant did not answer $what in time")
