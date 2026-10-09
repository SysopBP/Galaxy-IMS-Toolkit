package dev.bluehouse.enablevolte.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

private fun readSamsungIms(): String {
    val command = "dumpsys secims | sed -n '/Dump of RegistrationManager:/,/EventLog(RegiMgr):/p' | head -160"
    val process =
        ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
    if (!process.waitFor(12, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return "IMS diagnostic timed out"
    }
    if (process.exitValue() != 0) {
        return "Root or Samsung IMS diagnostic unavailable"
    }
    val output = process.inputStream.bufferedReader().use { it.readText().take(30000) }
    if (!output.contains("Dump of RegistrationManager:")) {
        return "Samsung RegistrationManager not found"
    }
    val slotPattern = Regex("""SIM slot:\s*\[?(\d+)\]?""")
    val statePattern = Regex("""state:\s*\[?(REGISTERED|REGISTERING|IDLE|CONNECTING|CONNECTED|DEREGISTERING)\]?""")
    val lines = output.lines()
    val summaries = mutableListOf<String>()
    var currentSlot: String? = null
    for (line in lines) {
        slotPattern.find(line)?.let { currentSlot = it.groupValues[1] }
        val state = statePattern.find(line)?.groupValues?.get(1)
        if (state != null && currentSlot != null) {
            val summary = "SIM ${currentSlot!!.toInt() + 1}: $state"
            if (!summaries.contains(summary)) summaries.add(summary)
        }
    }
    return if (summaries.isEmpty()) {
        "RegistrationManager detected; task format needs device validation"
    } else {
        summaries.joinToString(separator = "\n")
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
fun GalaxyImsSettings() {
    var result by remember { mutableStateOf("Reading Samsung IMS registration…") }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        result = withContext(Dispatchers.IO) {
            try {
                readSamsungIms()
            } catch (e: Exception) {
                "IMS diagnostic unavailable: ${e.javaClass.simpleName}"
            }
        }
        loading = false
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Samsung IMS - Read-only alpha")
        Text("Registration tasks are diagnostic data, not proof that carrier features are provisioned.")
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(result)
            }
        }
        if (loading) CircularProgressIndicator()
        Text("Global Settings editor: research mode. Configuration changes are disabled.")
        Text("No IMS configuration or emergency calling settings are modified.")
    }
}
