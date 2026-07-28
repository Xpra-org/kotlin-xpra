package org.xpra.client.ui.session

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Turns touch into xpra pointer events.
 *
 * There is no prior art for this: go-xpra and rust-xpra are mouse-only, the html5 client does
 * nothing with touch beyond turning a one-finger drag into wheel events, and the server has no
 * touch or gesture protocol at all — everything has to be synthesised into pointer and button
 * events.
 *
 * The design problem is that a touchscreen gives absolute, discontinuous positions with no
 * hover state, while X11 applications expect a pointer that moves continuously and hovers
 * before it clicks. Two rules follow, and they are what make the result feel right:
 *
 *  1. **Always send a motion event before a press.** That is what generates the enter and
 *     motion-notify the remote toolkit needs for hover highlighting, tooltips and
 *     drag-and-drop. Without it the pointer teleports and menus stop highlighting.
 *  2. **Never synthesize double-clicks.** The remote toolkit does its own detection from the
 *     timing of two press events, so the two press/release pairs just have to arrive with
 *     their natural timing. What *does* need handling is that two taps never land on the same
 *     pixel, and toolkits require the second click within a few pixels of the first — hence
 *     [DoubleTapSnap] below.
 */
class TouchConfig(
    val touchSlopPx: Float,
    val longPressTimeoutMs: Long,
    val doubleTapTimeoutMs: Long,
    /** Root pixels of finger travel per wheel click when the server has no precise wheel. */
    val scrollPixelsPerClick: Float = 40f,
    val maxZoom: Float = 8f,
)

/** Callbacks into the session; all coordinates are already in root space. */
interface TouchTarget {
    fun motion(rootX: Int, rootY: Int)
    fun button(button: Int, pressed: Boolean, rootX: Int, rootY: Int)
    fun wheel(button: Int, clicks: Float, rootX: Int, rootY: Int)
    fun zoomBy(factor: Float, focus: Offset)
    fun panBy(delta: Offset)
    fun longPressFeedback()
}

/**
 * Remembers the last tap so a second one nearby can be snapped onto it.
 *
 * GTK's `gtk-double-click-distance` defaults to 5 pixels, and two taps land nowhere near that
 * close, so without this a "double tap" reaches the application as two unrelated single
 * clicks. Snapping the second tap to the first one's coordinates is the single change that
 * makes double-click work reliably by touch.
 */
class DoubleTapSnap(private val config: TouchConfig) {
    private var lastX = 0
    private var lastY = 0
    private var lastAt = 0L

    fun resolve(rootX: Int, rootY: Int, slopRoot: Float): Pair<Int, Int> {
        val now = System.currentTimeMillis()
        val withinTime = now - lastAt <= config.doubleTapTimeoutMs
        val withinDistance = abs(rootX - lastX) <= slopRoot && abs(rootY - lastY) <= slopRoot
        val result = if (withinTime && withinDistance) lastX to lastY else rootX to rootY
        lastX = result.first
        lastY = result.second
        lastAt = now
        return result
    }
}

private enum class Phase { UP, MOVED, MULTI }

/**
 * The gesture loop. Deliberately built on the raw pointer stream rather than
 * `detectTapGestures`: `onSingleTapConfirmed` and friends wait out the double-tap timeout
 * (~300ms) before firing, which is very visible latency on a remote desktop, and it is the
 * wrong layer anyway — in X11 a click *is* a press followed by a release.
 */
suspend fun PointerInputScope.xpraGestures(
    config: TouchConfig,
    snap: DoubleTapSnap,
    target: TouchTarget,
    transform: () -> ViewTransform,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val tf = transform()
        val slopRoot = config.touchSlopPx / tf.scale

        // The press is deferred until we know what this gesture is, but the *motion* goes out
        // straight away: it costs nothing and it is what gives the remote side hover state.
        val (downX, downY) = tf.toRoot(down.position)
        target.motion(downX, downY)

        // Phase 1: classify. Whichever happens first wins — a second finger, movement past the
        // touch slop, a release, or the long-press timeout.
        val phase = withTimeoutOrNull(config.longPressTimeoutMs) {
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } > 1) return@withTimeoutOrNull Phase.MULTI
                val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                if (!change.pressed) return@withTimeoutOrNull Phase.UP
                if ((change.position - down.position).getDistance() > config.touchSlopPx) {
                    return@withTimeoutOrNull Phase.MOVED
                }
            }
            @Suppress("UNREACHABLE_CODE") Phase.UP
        }

        when (phase) {
            // Held still past the timeout: a right-click, which is the only way to reach a
            // context menu on a touchscreen.
            null -> {
                target.longPressFeedback()
                target.button(3, true, downX, downY)
                target.button(3, false, downX, downY)
                waitForRelease(down.id)
            }

            // A tap. Press and release at the snapped position, immediately: the remote
            // toolkit times the gap between presses itself.
            Phase.UP -> {
                val (x, y) = snap.resolve(downX, downY, slopRoot)
                target.motion(x, y)
                target.button(1, true, x, y)
                target.button(1, false, x, y)
            }

            // A drag with button 1 held, which is what text selection and scrollbars need.
            Phase.MOVED -> {
                target.button(1, true, downX, downY)
                var lastX = downX
                var lastY = downY
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (change.positionChanged()) {
                            // Coalesced to one packet per event rather than replaying the
                            // historical samples: a remote desktop wants low latency more than
                            // sample fidelity.
                            val (x, y) = transform().toRoot(change.position)
                            lastX = x
                            lastY = y
                            target.motion(x, y)
                        }
                    }
                } finally {
                    // Never leave a button stuck down, whatever ends the gesture.
                    target.button(1, false, lastX, lastY)
                }
            }

            Phase.MULTI -> multiTouch(config, target, transform)
        }
    }
}

/**
 * Two fingers: pinch to zoom, or drag to scroll.
 *
 * The mode is locked in once one clearly dominates, so a scroll does not creep in zoom and a
 * pinch does not scroll the remote window at the same time.
 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.multiTouch(
    config: TouchConfig,
    target: TouchTarget,
    transform: () -> ViewTransform,
) {
    var zooming: Boolean? = null
    var lastCentroid: Offset? = null
    var lastDistance = 0f
    var scrollAccumulator = 0f

    while (true) {
        val event = awaitPointerEvent()
        val pressed = event.changes.filter { it.pressed }
        if (pressed.size < 2) break

        val a = pressed[0].position
        val b = pressed[1].position
        val centroid = (a + b) / 2f
        val distance = (a - b).getDistance()

        val previousCentroid = lastCentroid
        if (previousCentroid == null) {
            lastCentroid = centroid
            lastDistance = distance
            continue
        }

        val centroidDelta = centroid - previousCentroid
        val distanceDelta = distance - lastDistance

        if (zooming == null) {
            // Wait until one axis of the gesture is unambiguous before committing.
            if (abs(distanceDelta) > config.touchSlopPx) zooming = true
            else if (centroidDelta.getDistance() > config.touchSlopPx) zooming = false
        }

        when (zooming) {
            true -> if (lastDistance > 0f) target.zoomBy(distance / lastDistance, centroid)
            false -> {
                // Zoom is a purely client-side view transform, so panning while zoomed in
                // moves the view; the scroll wheel is what reaches the server.
                target.panBy(centroidDelta)
                scrollAccumulator += centroidDelta.y / transform().scale
                val perClick = config.scrollPixelsPerClick
                while (abs(scrollAccumulator) >= perClick) {
                    val (rx, ry) = transform().toRoot(centroid)
                    // Direct manipulation: dragging content upwards scrolls down.
                    val button = if (scrollAccumulator < 0) 5 else 4
                    target.wheel(button, 1f, rx, ry)
                    scrollAccumulator -= perClick * if (scrollAccumulator < 0) -1 else 1
                }
            }
            null -> Unit
        }

        lastCentroid = centroid
        lastDistance = distance
    }

    // Drain the remaining finger so the next gesture starts clean.
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.none { it.pressed }) break
    }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.waitForRelease(
    id: androidx.compose.ui.input.pointer.PointerId,
) {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == id }
        if (change == null || !change.pressed) return
        if (event.changes.none { it.pressed }) return
    }
}
