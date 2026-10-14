package au.com.shiftyjelly.pocketcasts.musicassistant.protocol

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.IOException
import java.lang.reflect.Type

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
        return decode(value, Types.newParameterizedType(List::class.java, T::class.java))
    }

    inline fun <reified T : Any> decodeOrNull(value: Any?): T? {
        return try {
            decode<T>(value)
        } catch (e: JsonDataException) {
            null
        }
    }

    fun <T : Any> decode(value: Any?, type: Type): T {
        return moshi.adapter<T>(type).fromJsonValue(value) ?: throw JsonDataException("Expected a value of $type")
    }
}
