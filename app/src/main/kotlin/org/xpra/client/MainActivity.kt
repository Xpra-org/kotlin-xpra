package org.xpra.client

import android.os.Bundle
import android.util.DisplayMetrics
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.xpra.client.client.SessionState
import org.xpra.client.ui.connect.ConnectScreen
import org.xpra.client.ui.connecting.ConnectingScreen
import org.xpra.client.ui.session.SessionScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { XpraApp() }
            }
        }
    }
}

@Composable
fun XpraApp(vm: XpraViewModel = viewModel()) {
    val client by vm.client.collectAsState()
    val scalingFactor by vm.scalingFactor.collectAsState()

    // Feed real screen metrics to the view model, which turns them into the virtual desktop we
    // report. Recomposes on rotation, which is what re-reports the size to the server.
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    LaunchedEffect(configuration, density) {
        vm.onScreenMetrics(
            widthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() },
            heightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() },
            dpi = (density.density * DisplayMetrics.DENSITY_DEFAULT).toInt(),
        )
    }

    val activeClient = client
    if (activeClient == null) {
        val discovered by vm.discovered.collectAsState()
        val saved by vm.savedConnections.collectAsState()
        val error by vm.connectError.collectAsState()
        ConnectScreen(
            discovered = discovered,
            saved = saved,
            scalingFactor = scalingFactor,
            error = error,
            onConnect = vm::connect,
            onForget = vm::forget,
            onScalingFactor = vm::setScalingFactor,
            onErrorShown = vm::clearError,
        )
        return
    }

    val state by activeClient.state.collectAsState()
    val windows by activeClient.windows.collectAsState()

    when (val s = state) {
        is SessionState.Connecting -> ConnectingScreen(
            status = s.status,
            passwordRequired = false,
            onPassword = {},
            onCancel = vm::disconnect,
        )
        SessionState.PasswordRequired -> ConnectingScreen(
            status = "This session needs a password",
            passwordRequired = true,
            onPassword = activeClient::providePassword,
            onCancel = vm::disconnect,
        )
        is SessionState.Failed -> ConnectingScreen(
            status = "Could not connect: ${s.reason}",
            passwordRequired = false,
            onPassword = {},
            onCancel = vm::disconnect,
        )
        SessionState.Disconnected -> Text("Disconnected")
        SessionState.Connected -> SessionScreen(
            client = activeClient,
            windows = windows,
            scalingFactor = scalingFactor,
            onScalingFactor = vm::setScalingFactor,
            onDisconnect = vm::disconnect,
            onCopyToDevice = vm::copyToDevice,
            onSendDeviceClipboard = vm::sendDeviceClipboard,
        )
    }
}
