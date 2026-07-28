package org.xpra.client

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.ViewConfiguration
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.xpra.client.client.XpraClient
import org.xpra.client.data.ConnectionUri
import org.xpra.client.data.SavedConnection
import org.xpra.client.data.Settings
import org.xpra.client.discovery.DiscoveredSession
import org.xpra.client.discovery.MdnsDiscovery
import org.xpra.client.proto.Hello
import kotlin.math.roundToInt

/**
 * Owns the session across configuration changes, which matters because rotating the device is
 * routine and reconnecting on every rotation would not be.
 */
class XpraViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = Settings(app)
    private val discovery = MdnsDiscovery(app)

    val discovered: StateFlow<List<DiscoveredSession>> = discovery.discover()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val savedConnections: StateFlow<List<SavedConnection>> = settings.savedConnections
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val scalingFactor: StateFlow<Float> = settings.scalingFactor
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.DEFAULT_SCALING_FACTOR)

    private val _client = MutableStateFlow<XpraClient?>(null)
    val client: StateFlow<XpraClient?> = _client.asStateFlow()

    /** Set when a URI fails to parse, so the connect screen can show why. */
    private val _connectError = MutableStateFlow<String?>(null)
    val connectError: StateFlow<String?> = _connectError.asStateFlow()

    /** Screen size in real pixels, updated by the UI on every layout and rotation. */
    private var screenWidthPx = 1080
    private var screenHeightPx = 1920
    private var screenDpi = 420

    fun connect(uriText: String, save: Boolean, label: String = "") {
        val parsed = ConnectionUri.parse(uriText)
        val uri = parsed.getOrElse {
            _connectError.value = it.message
            return
        }
        _connectError.value = null
        if (save) {
            viewModelScope.launch { settings.save(SavedConnection(uri.canonical(), label)) }
        }
        val client = XpraClient(
            scope = viewModelScope,
            host = uri.host,
            port = uri.port,
            password = uri.password.ifEmpty { null },
            display = virtualDisplay(scalingFactor.value),
        )
        // These have to be set before start(), because they go out in the hello and ask the
        // server to widen X's own double-click time and distance to touch-friendly values.
        val viewConfig = ViewConfiguration.get(getApplication())
        client.doubleClickTimeMs = ViewConfiguration.getDoubleTapTimeout()
        client.doubleClickDistancePx = viewConfig.scaledTouchSlop
        _client.value = client
        client.start()
    }

    fun disconnect() {
        _client.value?.disconnect()
        _client.value = null
    }

    fun clearError() {
        _connectError.value = null
    }

    fun forget(uri: String) {
        viewModelScope.launch { settings.forget(uri) }
    }

    fun setScalingFactor(value: Float) {
        viewModelScope.launch {
            settings.setScalingFactor(value)
            // A live session can just re-report its desktop; no reconnect needed.
            _client.value?.updateDisplay(virtualDisplay(value))
        }
    }

    /** Called on layout and rotation; re-reports the desktop if the session is up. */
    fun onScreenMetrics(widthPx: Int, heightPx: Int, dpi: Int) {
        if (widthPx == screenWidthPx && heightPx == screenHeightPx && dpi == screenDpi) return
        screenWidthPx = widthPx
        screenHeightPx = heightPx
        screenDpi = dpi
        _client.value?.updateDisplay(virtualDisplay(scalingFactor.value))
    }

    /**
     * The virtual desktop we ask the server for: the real screen divided by the scaling factor.
     * The server lays out and renders at that size, and the client draws the result magnified,
     * so a factor of 2 is a quarter of the pixels over the wire.
     */
    private fun virtualDisplay(factor: Float): Hello.Display {
        val f = factor.coerceAtLeast(0.5f)
        // Kept even: odd dimensions upset some video encoders and cost nothing to avoid.
        val w = (screenWidthPx / f).roundToInt() and 1.inv()
        val h = (screenHeightPx / f).roundToInt() and 1.inv()
        return Hello.Display(
            width = w.coerceAtLeast(64),
            height = h.coerceAtLeast(64),
            dpi = (screenDpi / f).roundToInt().coerceAtLeast(48),
        )
    }

    fun copyToDevice(text: String) {
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("xpra", text))
        _client.value?.consumeRemoteClipboard()
    }

    fun sendDeviceClipboard() {
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        if (!text.isNullOrEmpty()) _client.value?.sendClipboard(text)
    }

    override fun onCleared() {
        _client.value?.disconnect()
        super.onCleared()
    }
}
