package org.xpra.client.ui.connect

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.xpra.client.data.SavedConnection
import org.xpra.client.data.Settings
import org.xpra.client.discovery.DiscoveredSession

/**
 * The first screen: sessions found over mDNS, saved connections, and manual entry, plus the
 * scaling factor.
 */
@Composable
fun ConnectScreen(
    discovered: List<DiscoveredSession>,
    saved: List<SavedConnection>,
    scalingFactor: Float,
    error: String?,
    onConnect: (uri: String, save: Boolean, label: String) -> Unit,
    onForget: (String) -> Unit,
    onScalingFactor: (Float) -> Unit,
    onErrorShown: () -> Unit,
) {
    var manualUri by remember { mutableStateOf("") }
    var saveIt by remember { mutableStateOf(true) }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Xpra", fontSize = 28.sp, fontWeight = FontWeight.Medium)
        }

        item { SectionHeader("On this network") }
        if (discovered.isEmpty()) {
            item {
                Text(
                    "Searching for xpra sessions…",
                    color = androidx.compose.ui.graphics.Color.Gray,
                )
            }
        }
        items(discovered, key = { it.uuid }) { session ->
            Card(
                Modifier.fillMaxWidth().clickable {
                    onConnect(session.uri, false, session.label)
                }
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(session.label, fontWeight = FontWeight.Medium)
                    Text(
                        buildString {
                            if (session.username.isNotEmpty()) append("${session.username}@")
                            append("${session.host}:${session.port}")
                            if (session.display.isNotEmpty()) append(" ${session.display}")
                            if (session.sessionType.isNotEmpty()) append("  ·  ${session.sessionType}")
                            if (session.clients > 0) append("  ·  ${session.clients} connected")
                        },
                        fontSize = 12.sp,
                        color = androidx.compose.ui.graphics.Color.Gray,
                    )
                }
            }
        }

        if (saved.isNotEmpty()) {
            item { SectionHeader("Saved") }
            items(saved, key = { it.uri }) { connection ->
                Card(
                    Modifier.fillMaxWidth().clickable { onConnect(connection.uri, false, "") }
                ) {
                    Row(
                        Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            if (connection.label.isNotEmpty()) {
                                Text(connection.label, fontWeight = FontWeight.Medium)
                            }
                            Text(connection.uri, fontSize = 12.sp)
                        }
                        TextButton(onClick = { onForget(connection.uri) }) { Text("Forget") }
                    }
                }
            }
        }

        item { SectionHeader("Connect to") }
        item {
            OutlinedTextField(
                value = manualUri,
                onValueChange = {
                    manualUri = it
                    if (error != null) onErrorShown()
                },
                label = { Text("tcp://host:port/") },
                placeholder = { Text("192.168.0.10:10000") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = saveIt, onCheckedChange = { saveIt = it })
                Text("Save this connection")
            }
        }
        item {
            Button(
                onClick = { onConnect(manualUri, saveIt, "") },
                enabled = manualUri.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Connect") }
        }

        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionHeader("Scaling factor") }
        item {
            Text(
                "The server renders at the screen size divided by this. A factor of 2 means a " +
                    "quarter of the pixels over the wire and finger-sized remote controls.",
                fontSize = 12.sp,
                color = androidx.compose.ui.graphics.Color.Gray,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Settings.SCALING_CHOICES.forEach { choice ->
                    val selected = choice == scalingFactor
                    Button(
                        onClick = { onScalingFactor(choice) },
                        colors = if (selected) {
                            ButtonDefaults.buttonColors()
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        },
                    ) {
                        Text(if (choice == choice.toInt().toFloat()) "${choice.toInt()}" else "$choice")
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}
