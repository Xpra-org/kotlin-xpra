package org.xpra.client.ui.session

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transform is what makes "one window fullscreen, padded to keep its aspect ratio" work,
 * and its inverse is what makes a tap land where it looks like it should. Both directions are
 * tested here because a mismatch between them is invisible until you zoom in and start missing
 * what you aim at.
 */
class ViewTransformTest {

    @Test
    fun `a window is centred with equal padding`() {
        // A 4:3 window in a tall portrait viewport: it should fill the width and be letterboxed
        // top and bottom.
        val t = ViewTransform.fit(
            winX = 0, winY = 0, winW = 800, winH = 600,
            viewW = 1080f, viewH = 2400f, zoom = 1f, pan = Offset.Zero,
        )
        assertEquals(1080f / 800f, t.scale, 1e-5f)
        assertEquals(0f, t.offsetX, 1e-3f)

        val rect = t.rectFor(0, 0, 800, 600)
        assertEquals(0f, rect.left, 1e-3f)
        assertEquals(1080f, rect.right, 1e-3f)
        // Equal padding above and below.
        assertEquals(rect.top, 2400f - rect.bottom, 1e-3f)
        assertTrue("expected letterboxing", rect.top > 0f)
    }

    @Test
    fun `root coordinates survive a round trip`() {
        val t = ViewTransform.fit(
            winX = 553, winY = 138, winW = 499, winH = 316,
            viewW = 1080f, viewH = 2400f, zoom = 1f, pan = Offset.Zero,
        )
        // The window's own origin and centre must map back to themselves.
        for ((x, y) in listOf(553 to 138, 802 to 296, 1051 to 453)) {
            val view = Offset(t.toViewX(x), t.toViewY(y))
            val (backX, backY) = t.toRoot(view)
            assertEquals(x, backX)
            assertEquals(y, backY)
        }
    }

    @Test
    fun `round trip still holds when zoomed and panned`() {
        // This is the case that matters: after a pinch, a tap must still resolve to the pixel
        // it appears to be over.
        val t = ViewTransform.fit(
            winX = 100, winY = 200, winW = 640, winH = 480,
            viewW = 1080f, viewH = 2400f, zoom = 3.5f, pan = Offset(-120f, 340f),
        )
        for ((x, y) in listOf(100 to 200, 420 to 440, 739 to 679)) {
            val view = Offset(t.toViewX(x), t.toViewY(y))
            val (backX, backY) = t.toRoot(view)
            assertEquals(x, backX)
            assertEquals(y, backY)
        }
    }

    @Test
    fun `overlays land where the server placed them`() {
        // An override-redirect menu at root (600, 300) inside a window whose origin is
        // (553, 138) must draw at the same offset from the window as the server intended.
        val t = ViewTransform.fit(
            winX = 553, winY = 138, winW = 499, winH = 316,
            viewW = 1080f, viewH = 2400f, zoom = 1f, pan = Offset.Zero,
        )
        val window = t.rectFor(553, 138, 499, 316)
        val menu = t.rectFor(600, 300, 120, 90)
        assertEquals((600 - 553) * t.scale, menu.left - window.left, 1e-3f)
        assertEquals((300 - 138) * t.scale, menu.top - window.top, 1e-3f)
        assertEquals(120 * t.scale, menu.width, 1e-3f)
    }

    @Test
    fun `pan is clamped to the overhang`() {
        // At zoom 1 the window fits, so there is nothing to pan and it stays centred.
        // Compared componentwise: clamping against a zero bound can yield -0.0f, which is
        // arithmetically identical but not bit-identical to Offset.Zero.
        val atRest = ViewTransform.clampPan(
            Offset(500f, -900f), 800, 600, 1080f, 2400f, zoom = 1f,
        )
        assertEquals(0f, atRest.x, 1e-6f)
        assertEquals(0f, atRest.y, 1e-6f)

        // Zoomed in, panning is limited to how far the window sticks out of the viewport.
        val zoom = 4f
        val clamped = ViewTransform.clampPan(
            Offset(99999f, -99999f), 800, 600, 1080f, 2400f, zoom,
        )
        val scale = minOf(1080f / 800f, 2400f / 600f) * zoom
        assertEquals((800 * scale - 1080f) / 2f, clamped.x, 1e-2f)
        // At 4x the window is taller than the viewport too (600 * 5.4 = 3240 > 2400), so the
        // vertical pan is clamped to that overhang rather than pinned at zero.
        assertEquals(-(600 * scale - 2400f) / 2f, clamped.y, 1e-2f)
    }

    @Test
    fun `degenerate sizes do not produce NaN`() {
        val t = ViewTransform.fit(0, 0, 0, 0, 0f, 0f, 1f, Offset.Zero)
        assertEquals(1f, t.scale, 1e-5f)
        val (x, y) = t.toRoot(Offset(10f, 10f))
        assertEquals(10, x)
        assertEquals(10, y)
    }
}
