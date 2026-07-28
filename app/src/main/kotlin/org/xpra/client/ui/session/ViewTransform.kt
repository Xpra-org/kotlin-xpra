package org.xpra.client.ui.session

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.roundToInt

/**
 * The single mapping between the server's root coordinates and the view.
 *
 * The current top-level window is fitted into the view preserving its aspect ratio — that is
 * the padding the session shows around a window that does not match the screen shape — and
 * user zoom is applied on top. Every window, top-level and overlay alike, is drawn by pushing
 * its root rectangle through this same transform, so an override-redirect menu lands exactly
 * where the server placed it.
 *
 * Input runs [toRoot], the exact inverse, which is why zoom and pan cost nothing in the input
 * path: a tap while zoomed still resolves to the pixel it appears to be over.
 */
@Immutable
data class ViewTransform(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val rootX: Int,
    val rootY: Int,
) {
    fun toViewX(x: Int): Float = (x - rootX) * scale + offsetX
    fun toViewY(y: Int): Float = (y - rootY) * scale + offsetY

    fun rectFor(x: Int, y: Int, w: Int, h: Int): Rect = Rect(
        left = toViewX(x),
        top = toViewY(y),
        right = toViewX(x) + w * scale,
        bottom = toViewY(y) + h * scale,
    )

    /**
     * View coordinates back to the absolute root coordinates the server expects.
     *
     * Rounds rather than truncates. Truncation looks like the obvious choice — "which pixel is
     * the finger over" is a floor — but `scale` is a float, so a coordinate that should land
     * exactly on a pixel boundary computes as a hair below it and floors to the *previous*
     * pixel. That is a systematic off-by-one across the whole window, not an edge case.
     * Rounding makes this a true inverse of [toViewX]/[toViewY]; the resulting half-pixel shift
     * in which root pixel a touch resolves to is immaterial for a fingertip.
     */
    fun toRoot(point: Offset): Pair<Int, Int> {
        val x = ((point.x - offsetX) / scale).roundToInt() + rootX
        val y = ((point.y - offsetY) / scale).roundToInt() + rootY
        return x to y
    }

    companion object {
        /**
         * Fits [winW] x [winH] at root ([winX], [winY]) into a [viewW] x [viewH] viewport,
         * then applies [zoom] and [pan].
         */
        fun fit(
            winX: Int, winY: Int, winW: Int, winH: Int,
            viewW: Float, viewH: Float,
            zoom: Float, pan: Offset,
        ): ViewTransform {
            if (winW <= 0 || winH <= 0 || viewW <= 0f || viewH <= 0f) {
                return ViewTransform(1f, 0f, 0f, winX, winY)
            }
            val base = minOf(viewW / winW, viewH / winH)
            val scale = base * zoom
            // Centre the window, then shift by the pan. At zoom 1 the pan is clamped away, so
            // the window sits centred with even padding on both sides.
            val offsetX = (viewW - winW * scale) / 2f + pan.x
            val offsetY = (viewH - winH * scale) / 2f + pan.y
            return ViewTransform(scale, offsetX, offsetY, winX, winY)
        }

        /**
         * Keeps the window from being dragged off-screen. When it is smaller than the viewport
         * in an axis it stays centred there; otherwise panning is limited to its own overhang.
         */
        fun clampPan(pan: Offset, winW: Int, winH: Int, viewW: Float, viewH: Float, zoom: Float): Offset {
            if (winW <= 0 || winH <= 0 || viewW <= 0f || viewH <= 0f) return Offset.Zero
            val base = minOf(viewW / winW, viewH / winH)
            val scale = base * zoom
            val overhangX = ((winW * scale - viewW) / 2f).coerceAtLeast(0f)
            val overhangY = ((winH * scale - viewH) / 2f).coerceAtLeast(0f)
            return Offset(
                pan.x.coerceIn(-overhangX, overhangX),
                pan.y.coerceIn(-overhangY, overhangY),
            )
        }
    }
}
