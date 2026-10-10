package dev.bluehouse.enablevolte.components

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Column
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
        while (true) { status = ObserverState.summary(context); delay(3000) }
    }
    Column {
        Text("Optional LSPosed observer", style = MaterialTheme.typography.titleMedium)
        Text("Observes Samsung IMS registration callbacks. It does not change provisioning or carrier settings.")
        Switch(checked = enabled, onCheckedChange = {
            enabled = it
            prefs.edit().clear().putBoolean("enabled", it).apply()
        })
        Text(status)
        Text("Enable this APK in LSPosed and scope only com.sec.imsservice. A real heartbeat confirms loading; missing signatures stay inactive.")
    }
}
