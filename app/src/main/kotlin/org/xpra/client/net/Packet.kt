package org.xpra.client.net

/**
 * A decoded xpra packet: a list whose first element names the packet type.
 *
 * Everything the decoder produces is loosely typed ([Long] for every integer, [String] or
 * [ByteArray] for text and binary), so the accessors here do the narrowing in one place rather
 * than leaving casts scattered through the handlers. Every accessor is total: a missing or
 * wrongly-typed element yields the supplied default rather than throwing, because a packet we
 * cannot fully parse is usually still worth acting on in part.
 */
class Packet(private val items: List<Any?>) {

    val size: Int get() = items.size

    operator fun get(i: Int): Any? = items.getOrNull(i)

    /** The packet type, mapped through [CANONICAL_NAMES]. */
    val type: String = canonical(str(0))

    fun raw(i: Int): Any? = items.getOrNull(i)

    fun str(i: Int, default: String = ""): String = when (val v = items.getOrNull(i)) {
        is String -> v
        is ByteArray -> String(v, Charsets.UTF_8)
        null -> default
        else -> v.toString()
    }

    fun int(i: Int, default: Int = 0): Int = when (val v = items.getOrNull(i)) {
        is Number -> v.toInt()
        is Boolean -> if (v) 1 else 0
        else -> default
    }

    fun long(i: Int, default: Long = 0): Long = when (val v = items.getOrNull(i)) {
        is Number -> v.toLong()
        else -> default
    }

    fun bool(i: Int, default: Boolean = false): Boolean = when (val v = items.getOrNull(i)) {
        is Boolean -> v
        is Number -> v.toLong() != 0L
        else -> default
    }

    fun bytes(i: Int): ByteArray? = when (val v = items.getOrNull(i)) {
        is ByteArray -> v
        is String -> v.toByteArray(Charsets.UTF_8)
        else -> null
    }

    fun list(i: Int): List<Any?> = items.getOrNull(i) as? List<Any?> ?: emptyList()

    fun ints(i: Int): List<Int> = list(i).mapNotNull { (it as? Number)?.toInt() }

    fun strings(i: Int): List<String> = list(i).map {
        when (it) {
            is ByteArray -> String(it, Charsets.UTF_8)
            else -> it?.toString() ?: ""
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun dict(i: Int): Map<String, Any?> = items.getOrNull(i) as? Map<String, Any?> ?: emptyMap()

    override fun toString(): String {
        val args = items.drop(1).joinToString(", ") { describe(it) }
        return "$type($args)"
    }

    private fun describe(v: Any?): String = when (v) {
        is ByteArray -> "<${v.size} bytes>"
        is String -> if (v.length > 40) "\"${v.take(40)}…\"" else "\"$v\""
        is List<*> -> if (v.size > 8) "[${v.size} items]" else v.toString()
        else -> v.toString()
    }

    companion object {
        /**
         * Receive-side only. We always send the modern names from `xpra/net/packet_type.py`,
         * which every server we accept (`Hello.PROTOCOL_VERSION`) registers. But a v7 server
         * running with its default `BACKWARDS_COMPATIBLE=1` still *sends* the legacy names, to
         * every client: it picks them process-wide in its own `packet_type.py`. Canonicalising on
         * receipt keeps one handler per packet.
         */
        val CANONICAL_NAMES = mapOf(
            "new-window" to "window-create",
            "lost-window" to "window-destroy",
            "draw" to "window-draw",
            "eos" to "window-eos",
            "raise-window" to "window-raise",
            "restack-window" to "window-restack",
            "initiate-moveresize" to "window-initiate-moveresize",
            "configure-override-redirect" to "window-move-resize",
            "pointer-grab" to "window-grab",
            "pointer-ungrab" to "window-ungrab",
            "bell" to "window-bell",
            "notify_show" to "notification-show",
            "notify_close" to "notification-close",
            "disconnect" to "connection-close",
            "ping_echo" to "ping-echo",
            "set-clipboard-enabled" to "clipboard-status",
            // Deliberately absent: "new-override-redirect", which is window-create without the
            // `override-redirect` metadata flag, and "clipboard-token", whose arguments are
            // laid out differently from "clipboard-data". Both need handlers of their own.
        )

        fun canonical(name: String): String = CANONICAL_NAMES[name] ?: name
    }
}
