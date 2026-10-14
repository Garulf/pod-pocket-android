package au.com.shiftyjelly.pocketcasts.musicassistant.protocol

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.IOException
import java.lang.reflect.Type
import timber.log.Timber

sealed interface MaIncoming {
    data class ServerInfo(val info: MaServerInfo) : MaIncoming

    data class Result(val messageId: String, val result: Any?, val partial: Boolean) : MaIncoming

    data class Error(val messageId: String, val code: Int, val details: String?) : MaIncoming

    data class Event(val event: String, val objectId: String?, val data: Any?) : MaIncoming
}

object MaJson {
    val moshi: Moshi = Moshi.Builder().build()

    private val mapAdapter: JsonAdapter<Map<String, Any?>> = moshi.adapter(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
    )

    fun parse(text: String): MaIncoming? {
        val message = try {
            mapAdapter.fromJson(text)
        } catch (e: IOException) {
            null
        } catch (e: JsonDataException) {
            null
        } ?: return null
        val messageId = message["message_id"]?.toString()
        return when {
            "event" in message -> (message["event"] as? String)?.let { MaIncoming.Event(it, message["object_id"] as? String, message["data"]) }
            "error_code" in message && messageId != null -> MaIncoming.Error(
                messageId = messageId,
                code = (message["error_code"] as? Number)?.toInt() ?: 0,
                details = message["details"] as? String,
            )
            ("result" in message || "partial" in message) && messageId != null -> MaIncoming.Result(messageId, message["result"], message["partial"] == true)
            "server_id" in message -> decodeOrNull<MaServerInfo>(message)?.let { MaIncoming.ServerInfo(it) }
            else -> null
        }
    }

    fun command(messageId: String, command: String, args: Map<String, Any?>): String {
        val message = buildMap {
            put("message_id", messageId)
            put("command", command)
            if (args.isNotEmpty()) put("args", args)
        }
        return mapAdapter.toJson(message)
    }

    inline fun <reified T : Any> decode(value: Any?): T {
        return decode(value, T::class.java)
    }

    inline fun <reified T : Any> decodeList(value: Any?): List<T> {
        return decodeList(value, T::class.java)
    }

    inline fun <reified T : Any> decodeOrNull(value: Any?): T? {
        return try {
            decode<T>(value)
        } catch (e: JsonDataException) {
            null
        }
    }

    fun <T : Any> decodeList(value: Any?, elementType: Class<T>): List<T> {
        val items = value as? List<*> ?: throw JsonDataException("Expected a list of $elementType")
        val adapter = moshi.adapter(elementType)
        return items.mapNotNull { item ->
            try {
                adapter.fromJsonValue(item)
            } catch (e: JsonDataException) {
                Timber.w("Skipping a Music Assistant ${elementType.simpleName} that could not be read: ${e.message}")
                null
            }
        }
    }

    fun <T : Any> decode(value: Any?, type: Type): T {
        return moshi.adapter<T>(type).fromJsonValue(value) ?: throw JsonDataException("Expected a value of $type")
    }
}
