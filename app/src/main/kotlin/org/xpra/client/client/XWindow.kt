package org.xpra.client.client

import android.graphics.Bitmap
import android.graphics.Canvas

/**
 * One server-side window and its backing store.
 *
 * Geometry is in the **absolute root coordinates** of the virtual desktop, which is also the
 * coordinate space input events are reported in, so the same transform serves both directions.
 * The [bitmap] is window-local.
 */
class XWindow(
    val wid: Int,
    var x: Int,
    var y: Int,
    var width: Int,
    var height: Int,
    val overrideRedirect: Boolean,
    metadata: Map<String, Any?>,
) {
    /**
     * Held while painting and while compositing. The paint happens on the client coroutine and
     * the composite on the main thread, and without this a large draw can be read half-written.
     */
    val lock = Any()

    var bitmap: Bitmap = Bitmap.createBitmap(
        width.coerceAtLeast(1),
        height.coerceAtLeast(1),
        Bitmap.Config.ARGB_8888,
    )
        private set

    var title: String = ""
        private set
    var windowTypes: List<String> = emptyList()
        private set
    var transientFor: Int? = null
        private set
    var isTray: Boolean = false
        private set
    var isModal: Boolean = false
        private set

    /** Set once the window has been mapped, so we never map twice or map an OR window. */
    var mapped: Boolean = false

    /** Bumped on every completed screen update, so the UI can tell frames apart. */
    var generation: Int = 0

    init {
        applyMetadata(metadata)
    }

    /**
     * Window types that the server places itself and that should be composited over the
     * current top-level window rather than replacing it.
     */
    private val popupTypes = setOf("DROPDOWN_MENU", "POPUP_MENU", "MENU", "COMBO", "TOOLTIP", "DND", "SPLASH")

    /**
     * Overlays are drawn on top of the current top-level window: override-redirect windows
     * (menus, tooltips), anything transient for another window, and the popup window types.
     * Everything else is a top-level window that can take over the screen.
     */
    val isOverlay: Boolean
        get() = overrideRedirect || isTray || transientFor != null ||
            windowTypes.any { it in popupTypes }

    fun applyMetadata(metadata: Map<String, Any?>) {
        metadata["title"]?.let { title = asString(it) }
        metadata["window-type"]?.let { v ->
            windowTypes = (v as? List<*>)?.map { asString(it) } ?: listOf(asString(v))
        }
        // A transient-for pointing at nothing (0) means "no parent".
        if (metadata.containsKey("transient-for")) {
            transientFor = (metadata["transient-for"] as? Number)?.toInt()?.takeIf { it != 0 }
        }
        metadata["tray"]?.let { isTray = it == true }
        metadata["modal"]?.let { isModal = it == true }
    }

    /** Resizes the backing store, preserving whatever pixels still fit. */
    fun resize(newWidth: Int, newHeight: Int) {
        val w = newWidth.coerceAtLeast(1)
        val h = newHeight.coerceAtLeast(1)
        width = newWidth
        height = newHeight
        if (bitmap.width == w && bitmap.height == h) return
        synchronized(lock) {
            val replacement = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(replacement).drawBitmap(bitmap, 0f, 0f, null)
            val old = bitmap
            bitmap = replacement
            old.recycle()
        }
    }

    fun recycle() {
        synchronized(lock) { bitmap.recycle() }
    }

    private fun asString(v: Any?): String = when (v) {
        is ByteArray -> String(v, Charsets.UTF_8)
        else -> v?.toString().orEmpty()
    }
}
