package org.xpra.client.ui.session

import android.widget.EditText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import org.xpra.client.client.WindowsSnapshot
import org.xpra.client.client.XpraClient

/**
 * The connected session: one top-level window fullscreen with popups composited on top, plus
 * the overlays a touch device needs.
 */
@Composable
fun SessionScreen(
    client: XpraClient,
    windows: WindowsSnapshot,
    scalingFactor: Float,
    onScalingFactor: (Float) -> Unit,
    onDisconnect: () -> Unit,
    onCopyToDevice: (String) -> Unit,
    onSendDeviceClipboard: () -> Unit,
) {
    var viewWidth by remember { mutableFloatStateOf(0f) }
    var viewHeight by remember { mutableFloatStateOf(0f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var showSwitcher by remember { mutableStateOf(false) }
    var keyboardVisible by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<EditText?>(null) }
    val (latchedModifiers, toggleModifier) = rememberLatchedModifiers()
    val remoteClipboard by client.remoteClipboard.collectAsState()

    val viewConfiguration = LocalViewConfiguration.current
    val haptics = LocalHapticFeedback.current

    val current = windows.current
    val transform = ViewTransform.fit(
        winX = current?.x ?: 0,
        winY = current?.y ?: 0,
        winW = current?.width ?: 1,
        winH = current?.height ?: 1,
        viewW = viewWidth,
        viewH = viewHeight,
        zoom = zoom,
        pan = pan,
    )

    val config = remember(viewConfiguration) {
        TouchConfig(
            touchSlopPx = viewConfiguration.touchSlop,
            longPressTimeoutMs = viewConfiguration.longPressTimeoutMillis,
            doubleTapTimeoutMs = viewConfiguration.doubleTapTimeoutMillis,
        )
    }
    val snap = remember(config) { DoubleTapSnap(config) }

    // Keep the server's idea of double-click matched to this device's, so the remote toolkit
    // is lenient in the same way our snapping is.
    remember(config) {
        client.doubleClickTimeMs = config.doubleTapTimeoutMs.toInt()
        client.doubleClickDistancePx = config.touchSlopPx.toInt()
        true
    }

    val target = remember(client) {
        object : TouchTarget {
            override fun motion(rootX: Int, rootY: Int) = client.sendPointer(rootX, rootY)

            override fun button(button: Int, pressed: Boolean, rootX: Int, rootY: Int) =
                client.sendButton(button, pressed, rootX, rootY)

            override fun wheel(button: Int, clicks: Float, rootX: Int, rootY: Int) =
                client.sendWheel(button, clicks, rootX, rootY)

            override fun zoomBy(factor: Float, focus: Offset) {
                val next = (zoom * factor).coerceIn(1f, config.maxZoom)
                zoom = next
                pan = ViewTransform.clampPan(
                    pan, current?.width ?: 1, current?.height ?: 1, viewWidth, viewHeight, next,
                )
            }

            override fun panBy(delta: Offset) {
                pan = ViewTransform.clampPan(
                    pan + delta, current?.width ?: 1, current?.height ?: 1,
                    viewWidth, viewHeight, zoom,
                )
            }

            override fun longPressFeedback() {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        SessionCanvas(
            windows = windows,
            transform = transform,
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged {
                    viewWidth = it.width.toFloat()
                    viewHeight = it.height.toFloat()
                }
                .pointerInput(client, config) {
                    xpraGestures(config, snap, target) { transform }
                },
        )

        // The IME needs a focusable native view to attach to; it stays invisible.
        HiddenKeyboardInput(
            client = client,
            modifiers = { latchedModifiers.toList() },
            modifier = Modifier.size(1.dp),
            onEditorReady = { editor = it },
        )

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .imePadding()
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            remoteClipboard?.let { text ->
                Snackbar(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    action = {
                        TextButton(onClick = { onCopyToDevice(text) }) { Text("Copy") }
                    },
                ) {
                    Text("Remote clipboard: ${text.take(60)}", maxLines = 2)
                }
            }

            if (keyboardVisible) {
                ModifierRow(
                    client = client,
                    latched = latchedModifiers,
                    onToggle = toggleModifier,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SmallFloatingActionButton(onClick = { showSwitcher = true }) {
                    Icon(Icons.Default.Menu, contentDescription = "Windows")
                }
                SmallFloatingActionButton(onClick = {
                    keyboardVisible = !keyboardVisible
                    if (keyboardVisible) editor?.showIme() else editor?.hideIme()
                }) {
                    Icon(Icons.Default.Keyboard, contentDescription = "Keyboard")
                }
                SmallFloatingActionButton(onClick = onSendDeviceClipboard) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Send clipboard")
                }
                if (zoom > 1f) {
                    SmallFloatingActionButton(onClick = {
                        zoom = 1f
                        pan = Offset.Zero
                    }) { Text("1:1") }
                }
            }
        }

        if (showSwitcher) {
            WindowSwitcher(
                windows = windows.topLevel,
                currentWid = windows.current?.wid,
                scalingFactor = scalingFactor,
                onSelect = {
                    client.setCurrentWindow(it)
                    zoom = 1f
                    pan = Offset.Zero
                    showSwitcher = false
                },
                onClose = { client.closeWindow(it) },
                onScalingFactor = onScalingFactor,
                onDisconnect = onDisconnect,
                onDismiss = { showSwitcher = false },
            )
        }
    }
}
