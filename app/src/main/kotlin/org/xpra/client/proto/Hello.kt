package org.xpra.client.proto

import kotlin.math.roundToInt

/**
 * The capability dictionary.
 *
 * The shape here is load-bearing in ways that are not obvious, so the non-obvious keys carry
 * the server-side reference that explains them. The governing rule is that **anything not
 * advertised is never sent to us**, which is how this client avoids having to handle window
 * icons, audio, notifications, file transfer or video at all.
 */
object Hello {

    /**
     * What we report as our version. The server runs `version_compat_check`
     * (`xpra/util/version.py:145`) over this and rejects anything below its own
     * `MIN_PROTOCOL_VERSION` (7.0) or unparseable. Claiming the version we were written against
     * takes the "identical major.minor" fast path.
     */
    const val CLIENT_VERSION = "8.0"

    /**
     * The oldest peer we are willing to talk to, checked by the server's `protocol_compat_check`
     * (`xpra/util/version.py:110`). Every server from 7.0 on registers the modern packet names
     * (`xpra/net/packet_type.py`), which are the only ones this client sends.
     */
    val PROTOCOL_VERSION = listOf(7, 0)

    /**
     * The only challenge digest we implement. Advertising just this one in both digest lists
     * forces the server's `choose_digest` (`xpra/net/digest.py:70`) to pick it, so there is
     * exactly one hash to support. sha1 and md5 are blocklisted server-side, and xor/des are
     * refused outright as salt digests.
     */
    const val DIGEST = "hmac+sha256"

    /**
     * The picture encodings we can paint. Every entry names a real wire encoding, because
     * `core` is the hard filter the server intersects with its own encoders
     * (`xpra/server/window/compress.py:927`). png/P and png/L are ordinary palette and
     * grayscale PNG payloads, all decodable by BitmapFactory.
     */
    val CORE_ENCODINGS = listOf("rgb24", "rgb32", "jpeg", "png", "png/P", "png/L", "webp", "void")

    /**
     * At the non-core level "rgb24" and "rgb32" are both aliased to plain "rgb"
     * (`xpra/server/subsystem/encoding.py:201`), so the two lists are not identical.
     */
    val ENCODING_OPTIONS = listOf("rgb", "jpeg", "png", "png/P", "png/L", "webp", "void")

    data class Display(
        val width: Int,
        val height: Int,
        val dpi: Int,
        val refreshRate: Int = 60,
    )

    fun build(
        display: Display,
        username: String,
        uuid: String,
        sessionId: String,
        hostname: String,
        doubleClickTimeMs: Int,
        doubleClickDistancePx: Int,
    ): Map<String, Any?> {
        val caps = LinkedHashMap<String, Any?>()

        caps["version"] = CLIENT_VERSION
        caps["protocol-version"] = PROTOCOL_VERSION
        caps["client_type"] = "kotlin"

        // Packet encoder negotiation. Without a match the server disconnects with
        // PROTOCOL_ERROR (xpra/server/core.py:1567). "encoders" is the modern key; the bare
        // boolean is what a backwards-compatible server falls back to.
        caps["encoders"] = listOf("rencodeplus")
        caps["rencodeplus"] = true

        // Ask the server to inline binary payloads instead of splitting them into out-of-band
        // chunks (socket_handler.py:629). This removes the entire chunk-reassembly path.
        caps["chunks"] = false

        // Packet-level compression, inbound only: our own packets are small input events.
        caps["compressors"] = listOf("lz4")
        caps["compression_level"] = 1

        // Subsystem activation: each of these gates a server-side mixin's is_needed().
        // Window forwarding is gated by a "window" dict (xpra/server/common.py:20), which must be
        // non-empty to count: without it the server never loads WindowsConnection and sends no
        // windows at all. "windows" is the boolean older servers look for instead.
        caps["window"] = mapOf("enabled" to true)
        caps["windows"] = true
        caps["keyboard"] = true
        caps["ping"] = true
        caps["network"] = mapOf("pings" to 5)
        caps["bandwidth"] = true
        caps["bandwidth-detection"] = true

        // "pointer" is the modern key, "mouse" the backwards-compatible one. The double-click
        // settings are passed through to X itself (xpra/client/subsystem/pointer.py:88), which
        // is worth doing on a touchscreen where taps are slower and less precise than clicks.
        caps["pointer"] = mapOf(
            "double_click" to mapOf(
                "time" to doubleClickTimeMs,
                "distance" to listOf(doubleClickDistancePx, doubleClickDistancePx),
            ),
        )
        caps["mouse"] = true

        // There is no cursor to draw on a touchscreen and no speaker worth ringing. Cursors are
        // turned off by leaving "cursor" out: when present it has to be a dict
        // (xpra/server/subsystem/cursor.py:39), and its absence is enough to keep the cursor
        // subsystem from loading (xpra/server/source/cursor.py:34).
        caps["bell"] = false

        caps["encodings"] = CORE_ENCODINGS
        caps["encoding"] = mapOf(
            "options" to ENCODING_OPTIONS,
            "core" to CORE_ENCODINGS,
            // Let the server choose raw RGB for small updates, JPEG or WebP when compression
            // is worthwhile, and PNG for lossless refreshes.
            "setting" to "auto",
            // The pixel layouts we can paint. This defaults to just ("RGB",) server-side
            // (xpra/server/source/encoding.py:69). Asking for RGBA/RGBX gets us the layout
            // Bitmap.Config.ARGB_8888 already holds in memory, so the server does the
            // conversion on its side via rgb_reformat() and we never touch the bytes.
            "rgb_formats" to listOf("RGBA", "RGBX", "RGB"),
            // We have an lz4 decompressor for packet framing anyway, so let the server
            // compress raw pixel runs too: it costs two lines in the paint path.
            "rgb_lz4" to true,
            "transparency" to true,
            "eos" to true,
            // An empty list means "no window icon encodings", so no window-icon packets.
            "window-icon" to emptyList<String>(),
        )

        // Restrict window metadata to what we actually apply. Omitting this gets the much
        // longer DEFAULT_METADATA_SUPPORTED list (xpra/constants.py:132). "override-redirect"
        // is included because a server with backwards compatibility disabled marks OR windows
        // there rather than using a distinct packet type.
        caps["metadata"] = mapOf(
            "supported" to listOf(
                "title", "window-type", "size-constraints", "override-redirect",
                "transient-for", "modal", "fullscreen", "maximized", "class-instance", "tray",
            ),
        )

        // The display dict is what loads DisplayConnection (xpra/server/source/display.py:31),
        // and without that mixin the desktop size we ask for is simply ignored -- which is the
        // whole mechanism behind the scaling factor.
        caps["display"] = displayCaps(display)
        // Legacy top-level duplicates, harmless on a modern server.
        caps["desktop_size"] = listOf(display.width, display.height)
        caps["screen_sizes"] = screenSizes(display)
        caps["dpi"] = mapOf("x" to display.dpi, "y" to display.dpi)

        caps["clipboard"] = mapOf(
            "enabled" to true,
            "want_targets" to true,
            "greedy" to true,
            "selections" to listOf("CLIPBOARD"),
            "preferred-targets" to listOf("UTF8_STRING", "STRING", "text/plain"),
        )

        caps["digest"] = listOf(DIGEST)
        caps["salt-digest"] = listOf(DIGEST)

        // uuid and session-id are optional, but if present they must pass valid_uuid() or the
        // server disconnects (xpra/server/subsystem/client_session.py:152). Hex digests do.
        caps["uuid"] = uuid
        caps["session-id"] = sessionId
        caps["hostname"] = hostname
        if (username.isNotEmpty()) {
            caps["username"] = username
            caps["user"] = username
        }

        return caps
    }

    fun displayCaps(d: Display): Map<String, Any?> = mapOf(
        "desktop_size" to listOf(d.width, d.height),
        "desktop_mode_size" to listOf(d.width, d.height),
        "screen_sizes" to screenSizes(d),
        "monitors" to mapOf("0" to monitor(d)),
        "refresh-rate" to d.refreshRate,
        "dpi" to mapOf("x" to d.dpi, "y" to d.dpi),
        // We attach a monitor descriptor ([monitorPosition]) to our window-map and pointer
        // packets, so that with `sharing=combine` the server can map our positions onto an area
        // of its display reserved for us (xpra/x11/subsystem/display.py:689).
        "monitor-relative" to true,
    )

    /**
     * Where a point of our desktop lies relative to the monitor it is on. We have exactly one
     * monitor, at the origin, so this is the point itself.
     */
    fun monitorPosition(x: Int, y: Int): Map<String, Any?> = mapOf(
        "index" to 0,
        "position" to listOf(x, y),
    )

    /**
     * The classic nested shape:
     * `[[name, w, h, w_mm, h_mm, [monitors], work_x, work_y, work_w, work_h]]`.
     */
    fun screenSizes(d: Display): List<Any?> = listOf(
        listOf(
            "Android", d.width, d.height, mm(d.width, d.dpi), mm(d.height, d.dpi),
            listOf(
                listOf("Screen", 0, 0, d.width, d.height, mm(d.width, d.dpi), mm(d.height, d.dpi)),
            ),
            0, 0, d.width, d.height,
        ),
    )

    private fun monitor(d: Display): Map<String, Any?> = mapOf(
        "geometry" to listOf(0, 0, d.width, d.height),
        "primary" to true,
        // Per-monitor rates are in mHz, unlike the Hz of the display-wide value above
        // (xpra/util/parsing.py:636).
        "refresh-rate" to d.refreshRate * 1000,
        "width-mm" to mm(d.width, d.dpi),
        "height-mm" to mm(d.height, d.dpi),
        "manufacturer" to "Android",
        "model" to android.os.Build.MODEL.orEmpty(),
        "workarea" to listOf(0, 0, d.width, d.height),
        "name" to "Screen",
    )

    private fun mm(px: Int, dpi: Int): Int = (px * 25.4 / dpi.coerceAtLeast(1)).roundToInt()
}
