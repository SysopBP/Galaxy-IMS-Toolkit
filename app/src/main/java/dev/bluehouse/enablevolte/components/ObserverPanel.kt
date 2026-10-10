package dev.bluehouse.enablevolte.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.bluehouse.enablevolte.ObserverState
import kotlinx.coroutines.delay

@Composable
fun ObserverPanel() {
    val context = LocalContext.current
    val prefs = remember { ObserverState.prefs(context) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("enabled", false)) }
    var status by remember { mutableStateOf(ObserverState.summary(context)) }
    LaunchedEffect(enabled) {
        while (true) {
            status = ObserverState.summary(context)
            delay(3000)
        }
    }
    Column {
        Text("Optional LSPosed observer", style = MaterialTheme.typography.titleMedium)
        Text("Observes Samsung IMS registration callbacks. It does not change provisioning or carrier settings.")
        Switch(checked = enabled, onCheckedChange = {
            enabled = it
            prefs
                .edit()
                .clear()
                .putBoolean("enabled", it)
                .apply()
        })
        Text(status)
        Text(
            "Enable this APK in LSPosed and scope only com.sec.imsservice. A real heartbeat confirms loading; missing signatures stay inactive.",
        )
    }
}
