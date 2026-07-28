package org.xpra.client.ui.session

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The snap is the one thing that makes double-click work by touch: toolkits require the second
 * click within a few pixels of the first (GTK's `gtk-double-click-distance` defaults to 5) and
 * two taps never land that close, so the second tap is moved onto the first one's coordinates.
 */
class DoubleTapSnapTest {

    private val config = TouchConfig(
        touchSlopPx = 24f,
        longPressTimeoutMs = 500,
        doubleTapTimeoutMs = 300,
    )

    @Test
    fun `a nearby second tap is snapped onto the first`() {
        val snap = DoubleTapSnap(config)
        assertEquals(100 to 200, snap.resolve(100, 200, slopRoot = 24f))
        // Six pixels away: far enough that GTK would treat it as a separate click, close enough
        // that the user meant it as a double tap.
        assertEquals(100 to 200, snap.resolve(106, 197, slopRoot = 24f))
    }

    @Test
    fun `a distant second tap is left alone`() {
        val snap = DoubleTapSnap(config)
        snap.resolve(100, 200, slopRoot = 24f)
        assertEquals(400 to 500, snap.resolve(400, 500, slopRoot = 24f))
    }

    @Test
    fun `a slow second tap is left alone`() {
        val snap = DoubleTapSnap(config)
        snap.resolve(100, 200, slopRoot = 24f)
        Thread.sleep(config.doubleTapTimeoutMs + 60)
        assertEquals(106 to 197, snap.resolve(106, 197, slopRoot = 24f))
    }

    @Test
    fun `a triple tap keeps snapping to the original point`() {
        val snap = DoubleTapSnap(config)
        snap.resolve(100, 200, slopRoot = 24f)
        snap.resolve(104, 203, slopRoot = 24f)
        // Each tap resets the clock but keeps the snapped coordinates, so a triple-click (which
        // selects a line in most terminals) stays on target rather than drifting.
        assertEquals(100 to 200, snap.resolve(108, 196, slopRoot = 24f))
    }

    /**
     * The slop is supplied in root coordinates, so zooming in makes it tighter in root terms:
     * the same finger wobble covers fewer remote pixels, and the snap should not overreach.
     */
    @Test
    fun `slop is interpreted in root coordinates`() {
        val snap = DoubleTapSnap(config)
        snap.resolve(100, 200, slopRoot = 2f)
        assertEquals(106 to 197, snap.resolve(106, 197, slopRoot = 2f))
    }
}
