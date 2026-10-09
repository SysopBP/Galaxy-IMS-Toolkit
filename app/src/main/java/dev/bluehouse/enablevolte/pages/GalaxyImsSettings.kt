package dev.bluehouse.enablevolte.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private data class ImsTask(val slot: Int, val state: String, val profile: String, val pdn: String)

private fun readRegistration(): String {
    val process = ProcessBuilder("su", "-c", "dumpsys secims | sed -n '/Dump of RegistrationManager:/,/EventLog(RegiMgr):/p' | head -120").redirectErrorStream(true).start()
    val finished = process.waitFor(12, TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        return "ERROR: IMS diagnostic timed out"
    }
    if (process.exitValue() != 0) return "ERROR: Root access or IMS diagnostic unavailable"
    // Parse only the live RegistrationManager task block; never show raw SIP identities.
    val text = process.inputStream.bufferedReader().use { it.readText().take(2_000_000) }
    val start = text.indexOf("Dump of RegistrationManager:")
    if (start < 0) return "ERROR: Samsung RegistrationManager not found"
    val end = text.indexOf("EventLog(RegiMgr):", start).let { if (it < 0) text.length else it }
    val block = text.substring(start, end)
    val task = Regex("""SIM slot: \\[(\\d+)] state: \\[([^]]+)] IMS Profile: \\[Name : ([^,]+),[^\\n]*?pdn : ([^,\\]]+)""")
    val tasks = task.findAll(block).map {
        ImsTask(it.groupValues[1].toInt(), it.groupValues[2], it.groupValues[3], it.groupValues[4])
    }.filter { it.pdn.trim() == "ims" }.toList()
    if (tasks.isEmpty()) return "No active IMS PDN tasks found (or unsupported Samsung format)"
    return tasks.groupBy { it.slot }.toSortedMap().entries.joinToString("\\n\\n") { (slot, entries) ->
        val active = entries.firstOrNull { it.state == "REGISTERED" } ?: entries.first()
        "SIM ${slot + 1}: ${active.state}\\nCarrier profile: ${active.profile}\\nPDN: ${active.pdn}"
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
fun GalaxyImsSettings() {
    var result by remember { mutableStateOf("Reading Samsung IMS registration…") }
    var loading by remember { mutableStateOf(false) }
    suspend fun refresh() {
        loading = true
        result = withContext(Dispatchers.IO) {
            try { readRegistration() } catch (e: Exception) { "ERROR: ${e.javaClass.simpleName}" }
        }
        loading = false
    }
    LaunchedEffect(Unit) { refresh() }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Samsung IMS · Read-only alpha")
        Text("Current registration tasks are read from Samsung's RegistrationManager. Historical events are excluded.")
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(result)
            }
        }
        Button(onClick = { /* Refresh is handled by changing request state below */ }, enabled = false) {
            Text("Live editing not enabled")
        }
        if (loading) CircularProgressIndicator()
        Text("Global Settings editor: research mode. VoLTE, VoWiFi, RCS and other switches will remain read-only until effective per-SIM storage, backup and rollback are verified.")
        Text("No IMS configuration, emergency calling profile, or carrier provisioning is modified.")
    }
}
