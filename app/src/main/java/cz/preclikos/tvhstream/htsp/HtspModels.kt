package cz.preclikos.tvhstream.htsp

data class HtspMessage(
    val method: String?,               // null pro reply, pokud to tak máš
    val seq: Int?,                     // seq pro korelaci
    val fields: Map<String, Any?>,     // decoded map
    val rawPayload: ByteArray? = null  // pro muxpkt TS bytes (pokud rovnou vytáhneš)
) {

    fun int(key: String): Int? = when (val v = fields[key]) {
        is Int -> v
        is Long -> v.toInt()
        is Short -> v.toInt()
        is Byte -> v.toInt()
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }

    fun long(key: String): Long? = when (val v = fields[key]) {
        is Long -> v
        is Int -> v.toLong()
        is Short -> v.toLong()
        is Byte -> v.toLong()
        is Number -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }

    fun bool(key: String): Boolean? = when (val v = fields[key]) {
        is Boolean -> v
        is Int -> v != 0
        is Long -> v != 0L
        is String -> when (v.lowercase()) {
            "1", "true", "yes", "y" -> true
            "0", "false", "no", "n" -> false
            else -> null
        }

        else -> null
    }

    fun str(key: String): String? = when (val v = fields[key]) {
        is String -> v
        else -> null
    }

    fun bin(key: String): ByteArray? = when (val v = fields[key]) {
        is ByteArray -> v
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    fun map(key: String): Map<String, Any?>? = fields[key] as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun list(key: String): List<Any?>? = fields[key] as? List<Any?>

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HtspMessage) return false

        if (seq != other.seq) return false
        if (method != other.method) return false
        if (fields != other.fields) return false

        val a = rawPayload
        val b = other.rawPayload
        if (a === null && b === null) return true
        if (a === null || b === null) return false
        if (!a.contentEquals(b)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = seq ?: 0
        result = 31 * result + (method?.hashCode() ?: 0)
        result = 31 * result + fields.hashCode()
        result = 31 * result + (rawPayload?.contentHashCode() ?: 0)
        return result
    }
}

sealed interface HtspEvent {
    data class ServerMessage(val msg: HtspMessage) : HtspEvent
    data class ConnectionError(val error: Throwable) : HtspEvent
}

data class ChannelUi(
    val id: Int,
    val name: String,
    val icon: String?
)

data class EpgEventEntry(
    val eventId: Int,
    val channelId: Int,
    val start: Long,   // epoch seconds (nebo ms – sjednoť si to)
    val stop: Long,
    val title: String,
    val summary: String? = null
)

data class SubscriptionStatus
    (
    val id: Int,
    val state: String? = null,   // "Running" / "No input" / "Scrambled" / ...
    val subscriptionError: String? = null,
)