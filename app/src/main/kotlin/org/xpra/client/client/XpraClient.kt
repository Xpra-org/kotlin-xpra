package org.xpra.client.client

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.xpra.client.net.Packet
import org.xpra.client.net.XpraConnection
import org.xpra.client.proto.Challenge
import org.xpra.client.proto.Hello

const val TAG = "xpra"

sealed interface SessionState {
    data class Connecting(val status: String) : SessionState
    data object PasswordRequired : SessionState
    data object Connected : SessionState
    data class Failed(val reason: String) : SessionState
    data object Disconnected : SessionState
}

/** An immutable view of the window stack for the UI to render. */
data class WindowsSnapshot(
    val topLevel: List<XWindow> = emptyList(),
    val overlays: List<XWindow> = emptyList(),
    val current: XWindow? = null,
    /** Bumped whenever pixels change, so Compose knows to recompose. */
    val frame: Int = 0,
)

/**
 * The session state machine.
 *
 * Follows go-xpra's concurrency model: **one coroutine owns all mutable state and is the only
 * writer**. The socket reader, the ping timer and the UI all post into a single channel, and
 * synthetic packets ("connection-lost", "send-ping") reuse the packet type as the internal
 * message type so there is exactly one event loop.
 */
class XpraClient(
    private val scope: CoroutineScope,
    private val host: String,
    private val port: Int,
    private val password: String?,
    display: Hello.Display,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Connecting("Connecting…"))
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _windows = MutableStateFlow(WindowsSnapshot())
    val windows: StateFlow<WindowsSnapshot> = _windows.asStateFlow()

    /** The virtual desktop we report; see the scaling factor in XpraViewModel. */
    var display: Hello.Display = display
        private set

    private val connection = XpraConnection(scope) { e -> Log.w(TAG, "connection error", e) }
    private val byWid = LinkedHashMap<Int, XWindow>()
    private var currentWid: Int? = null
    private var focusedWid: Int = 0
    private var frame = 0

    private val uuid = Challenge.randomHex(16)
    private val sessionId = Challenge.randomHex(16)
    private var pendingPassword: String? = password
    private var wheelMotion = false
    private var loop: Job? = null
    private var pingTimer: Job? = null

    /** Latest text the server put on its clipboard, offered to the user by the widget. */
    private val _remoteClipboard = MutableStateFlow<String?>(null)
    val remoteClipboard: StateFlow<String?> = _remoteClipboard.asStateFlow()

    var doubleClickTimeMs: Int = 400
    var doubleClickDistancePx: Int = 24

    fun start() {
        loop = scope.launch(Dispatchers.IO) {
            try {
                _state.value = SessionState.Connecting("Connecting to $host:$port…")
                connection.connect(host, port)
                _state.value = SessionState.Connecting("Negotiating…")
                sendHello(null, null)
                pingTimer = startPingTimer()
                for (packet in connection.incoming) handle(packet)
            } catch (e: Throwable) {
                Log.w(TAG, "session ended", e)
                if (_state.value !is SessionState.Failed) {
                    _state.value = SessionState.Failed(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    fun disconnect() {
        runCatching { connection.send("connection-close", "client exit") }
        connection.close()
        pingTimer?.cancel()
        loop?.cancel()
        byWid.values.forEach { it.recycle() }
        byWid.clear()
        _state.value = SessionState.Disconnected
    }

    /** Supplies a password after [SessionState.PasswordRequired] and retries the challenge. */
    fun providePassword(value: String) {
        pendingPassword = value
        challengePacket?.let { retryChallenge(it) }
    }

    private var challengePacket: Packet? = null

    // ------------------------------------------------------------------ packet handling

    private fun handle(packet: Packet) {
        when (packet.type) {
            "hello" -> onHello(packet)
            "challenge" -> onChallenge(packet)
            "startup-complete" -> _state.value = SessionState.Connected
            "window-create", "new-override-redirect" -> onWindowCreate(packet)
            "window-metadata" -> onWindowMetadata(packet)
            "window-move-resize" -> onWindowMoveResize(packet)
            "window-resized" -> onWindowResized(packet)
            "window-destroy" -> onWindowDestroy(packet)
            "window-raise" -> onWindowRaise(packet)
            "window-draw" -> onDraw(packet)
            "window-eos" -> Unit // no video decoders to reset yet
            "ping" -> connection.send("ping-echo", packet.long(1), 0, 0, 0, -1, packet.str(3))
            "ping-echo" -> Unit
            "clipboard-data" -> onClipboardData(packet)
            "clipboard-token" -> onLegacyClipboardToken(packet)
            "clipboard-request" -> onClipboardRequest(packet)
            "clipboard-status" -> Unit
            "connection-close" -> _state.value = SessionState.Failed(
                listOf(packet.str(1), packet.str(2)).filter { it.isNotEmpty() }.joinToString(": ")
            )
            "connection-lost" -> _state.value = SessionState.Failed(packet.str(1))
            "send-ping" -> connection.send("ping", System.currentTimeMillis())
            else -> Log.d(TAG, "unhandled: ${packet.type}")
        }
    }

    private fun onHello(packet: Packet) {
        val h = packet.dict(1)
        Log.i(TAG, "server ${h["version"]} display=${h["display"]} session=${h["session_name"]}")
        // Same rule as xpra/client/subsystem/pointer.py:399: a server that cannot inject
        // fractional wheel motion emulates it with button events, unless it says otherwise.
        wheelMotion = h["wheel.precise"] == true || h["wheel.emulation"] != false
        _state.value = SessionState.Connecting("Waiting for windows…")
        // Some servers never send startup-complete; the first window is enough to proceed.
    }

    private fun onChallenge(packet: Packet) {
        challengePacket = packet
        val pw = pendingPassword
        if (pw.isNullOrEmpty()) {
            _state.value = SessionState.PasswordRequired
            return
        }
        retryChallenge(packet)
    }

    private fun retryChallenge(packet: Packet) {
        val pw = pendingPassword ?: return
        try {
            _state.value = SessionState.Connecting("Authenticating…")
            val reply = Challenge.reply(
                serverSalt = packet.bytes(1) ?: ByteArray(0),
                digest = packet.str(3),
                saltDigest = if (packet.size >= 5) packet.str(4) else "xor",
                password = pw,
            )
            sendHello(reply.response, reply.clientSalt)
        } catch (e: Challenge.UnsupportedDigestException) {
            _state.value = SessionState.Failed(e.message ?: "unsupported authentication")
        }
    }

    private fun sendHello(challengeResponse: String?, clientSalt: ByteArray?) {
        val caps = LinkedHashMap<String, Any?>(
            Hello.build(
                display = display,
                username = System.getProperty("user.name").orEmpty(),
                uuid = uuid,
                sessionId = sessionId,
                hostname = android.os.Build.MODEL.orEmpty().ifEmpty { "android" },
                doubleClickTimeMs = doubleClickTimeMs,
                doubleClickDistancePx = doubleClickDistancePx,
            )
        )
        // The reply to a challenge is a second complete hello, not a packet of its own.
        if (challengeResponse != null) {
            caps["challenge_response"] = challengeResponse
            if (clientSalt != null) caps["challenge_client_salt"] = clientSalt
        }
        connection.send("hello", caps)
    }

    /**
     * The server disconnects us on PING_TIMEOUT if we go quiet
     * (`xpra/server/subsystem/ping.py:84`), so this is not optional.
     */
    private fun startPingTimer() = scope.launch {
        while (true) {
            delay(5_000)
            connection.post("send-ping")
        }
    }

    // ------------------------------------------------------------------ windows

    private fun onWindowCreate(packet: Packet) {
        val wid = packet.int(1)
        val metadata = packet.dict(6)
        // A modern server folds override-redirect into window-create via metadata, a
        // backwards-compatible v7 server sends new-override-redirect instead, so both count.
        val or = packet.type == "new-override-redirect" || metadata["override-redirect"] == true

        val window = XWindow(
            wid = wid,
            x = packet.int(2), y = packet.int(3),
            width = packet.int(4), height = packet.int(5),
            overrideRedirect = or,
            metadata = metadata,
        )
        byWid[wid] = window

        // Override-redirect windows are placed by the server and must not be mapped or focused
        // (xpra/client/.../manager.py: window-map is only sent for regular windows).
        if (!or) {
            connection.send(
                "window-map", wid, window.x, window.y, window.width, window.height,
                emptyMap<String, Any>(), emptyMap<String, Any>(),
            )
            window.mapped = true
            // New top-level windows take over the screen.
            if (!window.isOverlay) setCurrentWindow(wid)
        }
        if (_state.value is SessionState.Connecting) _state.value = SessionState.Connected
        publish()
    }

    private fun onWindowMetadata(packet: Packet) {
        val window = byWid[packet.int(1)] ?: return
        window.applyMetadata(packet.dict(2))
        publish()
    }

    private fun onWindowMoveResize(packet: Packet) {
        val window = byWid[packet.int(1)] ?: return
        window.x = packet.int(2)
        window.y = packet.int(3)
        window.resize(packet.int(4), packet.int(5))
        publish()
    }

    private fun onWindowResized(packet: Packet) {
        val window = byWid[packet.int(1)] ?: return
        window.resize(packet.int(2), packet.int(3))
        publish()
    }

    private fun onWindowDestroy(packet: Packet) {
        val wid = packet.int(1)
        val window = byWid.remove(wid) ?: return
        window.recycle()
        if (currentWid == wid) {
            // Promote the most recently created remaining top-level window.
            val next = byWid.values.lastOrNull { !it.isOverlay }
            currentWid = next?.wid
            next?.let { sendFocus(it.wid) }
        }
        publish()
    }

    private fun onWindowRaise(packet: Packet) {
        val window = byWid[packet.int(1)] ?: return
        if (!window.isOverlay) setCurrentWindow(window.wid)
        publish()
    }

    /** Makes [wid] the window shown fullscreen and tells the server where keys should go. */
    fun setCurrentWindow(wid: Int) {
        if (byWid[wid] == null) return
        currentWid = wid
        sendFocus(wid)
        publish()
    }

    private fun sendFocus(wid: Int) {
        if (focusedWid == wid) return
        focusedWid = wid
        connection.send("window-focus", wid, emptyList<String>())
    }

    private fun onDraw(packet: Packet) {
        val wid = packet.int(1)
        val sequence = packet.long(8)
        val w = packet.int(4)
        val h = packet.int(5)
        val window = byWid[wid]
        if (window == null) {
            ack(sequence, wid, w, h, DrawPainter.WINDOW_NOT_FOUND, "window $wid not found")
            return
        }
        val startNs = System.nanoTime()
        try {
            DrawPainter.paint(window, packet)
        } catch (e: Throwable) {
            Log.w(TAG, "paint failed for window $wid", e)
            // Failures must still be acked, or the server's damage accounting stalls and it
            // stops sending updates for this window entirely.
            ack(sequence, wid, w, h, DrawPainter.DECODE_ERROR, e.message ?: "decode error")
            return
        }
        // Microseconds, and a genuine zero would read as "skipped", so floor it at 1.
        val decodeTimeUs = ((System.nanoTime() - startNs) / 1000).toInt().coerceAtLeast(1)
        ack(sequence, wid, w, h, decodeTimeUs, "")

        if (DrawPainter.isLastOfFrame(packet)) {
            window.generation++
            frame++
            publish()
        }
    }

    private fun ack(sequence: Long, wid: Int, w: Int, h: Int, decodeTimeUs: Int, message: String) {
        // ("window-ack", wid, width, height, sequence, decode_time, message):
        // xpra/server/subsystem/window.py:410
        connection.send("window-ack", wid, w, h, sequence, decodeTimeUs, message)
    }

    private fun publish() {
        val current = currentWid?.let { byWid[it] } ?: byWid.values.firstOrNull { !it.isOverlay }
        currentWid = current?.wid
        _windows.value = WindowsSnapshot(
            topLevel = byWid.values.filter { !it.isOverlay },
            overlays = byWid.values.filter { it.isOverlay },
            current = current,
            frame = frame,
        )
    }

    // ------------------------------------------------------------------ input

    /**
     * The window under a root coordinate, preferring overlays because they are drawn on top.
     * Sending events against the window they actually landed on is what lets a popup menu
     * track the finger rather than the window behind it.
     */
    private fun widAt(x: Int, y: Int): Int? {
        byWid.values.reversed().firstOrNull { w ->
            w.isOverlay && x >= w.x && y >= w.y && x < w.x + w.width && y < w.y + w.height
        }?.let { return it.wid }
        return currentWid
    }

    /**
     * Pointer motion in absolute root coordinates. `device_id = -1` tells the server not to
     * track per-device sequence numbers (`xpra/server/subsystem/pointer.py:590`), which is
     * what we want since we do not number our events.
     */
    fun sendPointer(x: Int, y: Int) {
        val wid = widAt(x, y) ?: return
        connection.send("pointer-motion", -1, 0, wid, listOf(x, y), emptyMap<String, Any>())
    }

    fun sendButton(button: Int, pressed: Boolean, x: Int, y: Int, modifiers: List<String> = emptyList()) {
        val wid = widAt(x, y) ?: return
        val props = if (modifiers.isEmpty()) emptyMap() else mapOf("modifiers" to modifiers)
        connection.send("pointer-button", -1, 0, wid, button, pressed, listOf(x, y), props)
    }

    /**
     * A scroll step. When the server handles wheel motion one packet carries a fractional
     * distance in 1/1000ths of a click (and is negated); otherwise it has to be discrete button
     * clicks.
     */
    fun sendWheel(button: Int, clicks: Float, x: Int, y: Int) {
        val wid = widAt(x, y) ?: return
        if (wheelMotion) {
            connection.send(
                "pointer-wheel", wid, button, -(clicks * 1000).toInt(), listOf(x, y),
                emptyList<String>(), emptyList<Int>(), emptyMap<String, Any>(),
            )
        } else {
            repeat(clicks.toInt().coerceIn(0, 10)) {
                sendButton(button, true, x, y)
                sendButton(button, false, x, y)
            }
        }
    }

    fun sendKey(keyname: String, pressed: Boolean, modifiers: List<String>, char: Char? = null) {
        if (keyname.isEmpty()) return
        val wid = currentWid ?: 0
        val keyval = char?.code ?: 0
        val keystr = char?.toString().orEmpty()
        // An absent modifier list has to encode as an empty list, not as a null. Group 0: we
        // do not track keyboard layout groups (xpra/server/subsystem/keyboard.py:220).
        val attrs = mapOf(
            "modifiers" to modifiers,
            "keyval" to keyval,
            "string" to keystr,
            "keycode" to keyval,
            "group" to 0,
        )
        connection.send("keyboard-event", wid, keyname, pressed, attrs)
    }

    /** Sends a character as a press/release pair, wrapping any latched modifiers around it. */
    fun typeChar(c: Char, modifiers: List<String> = emptyList()) {
        val name = Keysyms.forChar(c)
        if (name.isEmpty()) return
        val held = modifiers.mapNotNull { Keysyms.modifierKeyName(it).takeIf(String::isNotEmpty) }
        held.forEach { sendKey(it, true, emptyList()) }
        sendKey(name, true, modifiers, c)
        sendKey(name, false, modifiers, c)
        held.reversed().forEach { sendKey(it, false, emptyList()) }
    }

    fun typeNamedKey(keyname: String, modifiers: List<String> = emptyList()) {
        val held = modifiers.mapNotNull { Keysyms.modifierKeyName(it).takeIf(String::isNotEmpty) }
        held.forEach { sendKey(it, true, emptyList()) }
        sendKey(keyname, true, modifiers)
        sendKey(keyname, false, modifiers)
        held.reversed().forEach { sendKey(it, false, emptyList()) }
    }

    fun closeWindow(wid: Int) = connection.send("window-close", wid)

    // ------------------------------------------------------------------ display and clipboard

    /** Re-reports the virtual desktop after a rotation or a scaling-factor change. */
    fun updateDisplay(newDisplay: Hello.Display) {
        display = newDisplay
        connection.send(
            "display-configure",
            mapOf(
                "desktop-size" to listOf(newDisplay.width, newDisplay.height),
                "monitors" to Hello.displayCaps(newDisplay)["monitors"],
                "dpi" to mapOf("x" to newDisplay.dpi, "y" to newDisplay.dpi),
                "vrefresh" to newDisplay.refreshRate,
            ),
        )
    }

    /**
     * `("clipboard-data", selection, options)`, where `options["data"]` maps each target to
     * `[dtype, dformat, wire_encoding, wire_data]` (xpra/clipboard/core.py:351).
     */
    private fun onClipboardData(packet: Packet) {
        val data = packet.dict(2)["data"] as? Map<*, *> ?: return
        val item = TEXT_TARGETS.firstNotNullOfOrNull { data[it] as? List<*> } ?: return
        val text = when (val d = item.getOrNull(3)) {
            is ByteArray -> d.toString(Charsets.UTF_8)
            is String -> d
            else -> ""
        }
        if (text.isNotEmpty()) _remoteClipboard.value = text
    }

    /** What a backwards-compatible v7 server sends instead of [onClipboardData]. */
    private fun onLegacyClipboardToken(packet: Packet) {
        // ("clipboard-token", selection, targets, target, dtype, dformat, wire_encoding, data, …)
        val text = packet.bytes(7)?.toString(Charsets.UTF_8).orEmpty()
        if (text.isNotEmpty()) _remoteClipboard.value = text
    }

    private fun onClipboardRequest(packet: Packet) {
        val requestId = packet.int(1)
        val selection = packet.str(2)
        val text = localClipboard
        if (text.isNullOrEmpty()) {
            connection.send("clipboard-contents-none", requestId, selection)
        } else {
            connection.send(
                "clipboard-contents", requestId, selection, "UTF8_STRING", 8, "bytes",
                text.toByteArray(Charsets.UTF_8),
            )
        }
    }

    /** What we would hand the server if it asked; set by the clipboard widget. */
    var localClipboard: String? = null

    /** Offers text to the server's clipboard. */
    fun sendClipboard(text: String) {
        localClipboard = text
        connection.send(
            "clipboard-data", "CLIPBOARD",
            mapOf(
                "claim" to true,
                "greedy" to true,
                "synchronous" to true,
                "targets" to listOf("text/plain", "UTF8_STRING"),
                "data" to mapOf(
                    "UTF8_STRING" to listOf("UTF8_STRING", 8, "bytes", text.toByteArray(Charsets.UTF_8)),
                ),
            ),
        )
    }

    fun consumeRemoteClipboard() {
        _remoteClipboard.value = null
    }

    private companion object {
        val TEXT_TARGETS = listOf("UTF8_STRING", "text/plain", "STRING")
    }
}
