package org.xpra.client.ui.session

import android.graphics.Paint
import android.graphics.Rect as AndroidRect
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import org.xpra.client.client.WindowsSnapshot
import org.xpra.client.client.XWindow

/**
 * Draws the current top-level window and any overlays through one shared [ViewTransform].
 *
 * Reading the live `Bitmap` each frame (rather than snapshotting it into an `ImageBitmap`)
 * means a paint that lands between frames simply shows up on the next one.
 */
@Composable
fun SessionCanvas(
    windows: WindowsSnapshot,
    transform: ViewTransform,
    modifier: Modifier = Modifier,
) {
    val paint = remember {
        Paint().apply {
            // Bilinear when magnifying, which is what a scaling factor of 2 means most of the time.
            isFilterBitmap = true
            isAntiAlias = false
        }
    }
    // A completed screen update publishes a new snapshot with a higher `frame`, so the
    // identity change is what schedules the redraw -- the bitmaps themselves are mutated in
    // place and Compose cannot see that.
    Canvas(modifier.background(Color(0xFF101012))) {
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            windows.current?.let { drawWindow(native, paint, it, transform) }
            // Overlays sit at their own root coordinates, so the same transform puts an
            // override-redirect menu exactly where the server placed it.
            windows.overlays.forEach { drawWindow(native, paint, it, transform) }
        }
    }
}

private fun drawWindow(
    canvas: android.graphics.Canvas,
    paint: Paint,
    window: XWindow,
    transform: ViewTransform,
) {
    synchronized(window.lock) {
        val bitmap = window.bitmap
        if (bitmap.isRecycled) return
        val rect = transform.rectFor(window.x, window.y, window.width, window.height)
        canvas.drawBitmap(
            bitmap,
            AndroidRect(0, 0, bitmap.width, bitmap.height),
            RectF(rect.left, rect.top, rect.right, rect.bottom),
            paint,
        )
    }
}
