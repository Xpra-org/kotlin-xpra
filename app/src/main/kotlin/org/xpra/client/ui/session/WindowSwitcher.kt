package org.xpra.client.ui.session

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import org.xpra.client.client.XWindow
import org.xpra.client.data.Settings

/**
 * The list of top-level windows, since only one is on screen at a time.
 *
 * Thumbnails are copies rather than the live bitmap: the session keeps painting into the
 * original while this is open, and a scaled copy taken under the window's lock is cheap enough
 * at this size.
 */
@Composable
fun WindowSwitcher(
    windows: List<XWindow>,
    currentWid: Int?,
    scalingFactor: Float,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onScalingFactor: (Float) -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(onClick = onDismiss),
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                "Windows",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(windows, key = { it.wid }) { window ->
                    WindowCard(
                        window = window,
                        selected = window.wid == currentWid,
                        onSelect = { onSelect(window.wid) },
                        onClose = { onClose(window.wid) },
                    )
                }
                if (windows.isEmpty()) {
                    item {
                        Text(
                            "No windows yet. Start one on the server, e.g. " +
                                "xpra control :7 start xterm",
                            color = Color(0xFFBBBBBB),
                        )
                    }
                }
            }

            Text(
                "Scaling factor",
                color = Color.White,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            Text(
                "The server renders at screen size divided by this, so a higher factor means " +
                    "fewer pixels over the wire and larger remote controls.",
                color = Color(0xFF999999),
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Settings.SCALING_CHOICES.forEach { choice ->
                    val selected = choice == scalingFactor
                    Button(
                        onClick = { onScalingFactor(choice) },
                        colors = if (selected) {
                            androidx.compose.material3.ButtonDefaults.buttonColors()
                        } else {
                            androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                contentColor = Color.White,
                            )
                        },
                    ) {
                        Text(if (choice == choice.toInt().toFloat()) "${choice.toInt()}" else "$choice")
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onDismiss) { Text("Close") }
                TextButton(onClick = onDisconnect) { Text("Disconnect", color = Color(0xFFFF8A80)) }
            }
        }
    }
}

@Composable
private fun WindowCard(
    window: XWindow,
    selected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Color(0xFF2A3B55) else Color(0xFF1C1C1F),
        ),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val thumbnail = rememberThumbnail(window)
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .height(64.dp)
                        .aspectRatio(
                            (window.width.toFloat() / window.height.coerceAtLeast(1))
                                .coerceIn(0.3f, 3f)
                        )
                        .clip(RoundedCornerShape(4.dp)),
                )
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    window.title.ifEmpty { "Window ${window.wid}" },
                    color = Color.White,
                    maxLines = 2,
                )
                Text(
                    "${window.width}×${window.height}",
                    color = Color(0xFF999999),
                    fontSize = 12.sp,
                )
            }
            TextButton(onClick = onClose) { Text("Close", color = Color(0xFFFF8A80)) }
        }
    }
}

/** A small snapshot taken under the window lock, so a concurrent paint cannot tear it. */
@Composable
private fun rememberThumbnail(window: XWindow): Bitmap? =
    androidx.compose.runtime.remember(window.wid, window.generation) {
        synchronized(window.lock) {
            val source = window.bitmap
            if (source.isRecycled || source.width <= 0) return@remember null
            val targetHeight = 128
            val targetWidth = (source.width * targetHeight / source.height.coerceAtLeast(1))
                .coerceIn(1, 512)
            runCatching {
                Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
            }.getOrNull()
        }
    }
