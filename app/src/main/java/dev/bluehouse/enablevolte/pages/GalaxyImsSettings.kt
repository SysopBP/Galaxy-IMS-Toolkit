package dev.bluehouse.enablevolte.pages

import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

private data class DiagnosticResult(val text: String, val export: String)

private fun runDiagnostic(command: String): String {
    val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
    // Drain output concurrently so dumpsys cannot deadlock on a full pipe.
    val output = StringBuilder()
    val reader = Thread {
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.take(500).forEach { line ->
                    if (output.length < 32000) output.appendLine(line.take(500))
                }
            }
        } catch (_: Exception) { }
    }
    reader.start()
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        reader.join(1000)
        return "Timed out after 10 seconds"
    }
    reader.join(1000)
    return "Exit ${process.exitValue()}: ${output.toString().take(12000)}"
}

// Read only the relevant Samsung registration lines at the source. The full
// secims dump contains extensive historical logs and subscriber identifiers.
private fun samsungRegistrationSummary(): String = runDiagnostic(
    "dumpsys secims 2>&1 | grep -E 'SIM slot: \\[|state: \\[|enableService(Volte|Vowifi|Rcs|Vilte)=' | tail -80"
)

private fun privilegedSubscriptionSummary(): String = runDiagnostic(
    "dumpsys isub 2>&1 | grep -E '^(Active modem count=|  Logical SIM slot [0-9]+:|mSimState\\[[0-9]+\\]=)' | head -12"
)

private fun inspectIms(context: Context): DiagnosticResult {
    val report = StringBuilder("Galaxy IMS Toolkit - Read-only diagnostics\n")
    report.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
    report.appendLine("Android SDK: ${Build.VERSION.SDK_INT}")
    val status = StringBuilder()
    val telephony = context.getSystemService(TelephonyManager::class.java)
    val subscriptions = context.getSystemService(SubscriptionManager::class.java)
    try {
        val ids = subscriptions?.activeSubscriptionInfoList.orEmpty()
        status.appendLine("App-visible active subscriptions: ${ids.size}")
        ids.forEach { sub ->
            status.appendLine("SIM slot ${sub.simSlotIndex + 1}: subscription ${sub.subscriptionId}")
            try {
                val ims = telephony?.createForSubscriptionId(sub.subscriptionId)
                status.appendLine("  IMS registered: ${ims?.isImsRegistered ?: "unknown"}")
                status.appendLine("  VoLTE available: ${ims?.isVolteAvailable ?: "unknown"}")
                status.appendLine("  Wi-Fi calling available: ${ims?.isWifiCallingAvailable ?: "unknown"}")
            } catch (e: Exception) {
                status.appendLine("  Telephony access restricted: ${e.javaClass.simpleName}")
            }
        }
    } catch (e: Exception) {
        status.appendLine("Subscription access restricted: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(180)}")
    }
    report.appendLine(status)
    val rootResult = try { runDiagnostic("id") } catch (e: Exception) {
        "Root command unavailable: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(180)}"
    }
    val rootAvailable = rootResult.contains("uid=0")
    status.appendLine("Root: ${if (rootAvailable) "available" else "unavailable"}")
    report.appendLine("Root check: $rootResult")
    val shizuku = try {
        val cls = Class.forName("rikka.shizuku.Shizuku")
        val binder = cls.getMethod("pingBinder").invoke(null) as? Boolean ?: false
        "Shizuku binder: ${if (binder) "reachable" else "unavailable"}"
    } catch (e: Exception) { "Shizuku status unavailable: ${e.javaClass.simpleName}" }
    status.appendLine(shizuku)
    report.appendLine(shizuku)
    // Binder identity is diagnostic only; this app does not yet invoke TokenX's
    // privileged command transport. Never infer write authorization from UID alone.
    val shizukuUid = try {
        val cls = Class.forName("rikka.shizuku.Shizuku")
        val uid = cls.getMethod("getUid").invoke(null) as? Int
        uid?.toString() ?: "unknown"
    } catch (_: Exception) { "unknown" }
    val routeStatus = when (shizukuUid) {
        "1000" -> "System UID 1000 binder detected (write access unverified)"
        "0" -> "Root UID 0 binder detected (write access unverified)"
        "2000" -> "Shell UID 2000 binder detected (limited permissions)"
        else -> "Binder backend UID unknown; TokenX route not verified"
    }
    status.appendLine("Privilege route: $routeStatus")
    report.appendLine("Privilege route: $routeStatus")
    status.appendLine("Carrier toggles: existing Shizuku/instrumentation route; TokenX routing not enabled")
    if (rootAvailable) {
        // Never modify IMS or carrier settings here.
        val subSummary = try { privilegedSubscriptionSummary() } catch (e: Exception) {
            "Subscription service query failed: ${e.javaClass.simpleName}"
        }
        status.appendLine("Android subscription service (privileged):")
        status.appendLine(subSummary)
        report.appendLine("Privileged subscription summary:\\n$subSummary")
        val secims = try { samsungRegistrationSummary() } catch (e: Exception) {
            "Samsung registration query failed: ${e.javaClass.simpleName}"
        }
        val registrations = secims.lines().filter {
            it.contains("SIM slot: [") && it.contains("state: [")
        }
        status.appendLine("Samsung IMS registration (per profile):")
        if (registrations.isEmpty()) {
            status.appendLine("No registration records returned; service output may be restricted.")
        } else {
            registrations.forEach { line ->
                // Only report the profile identity and state, not SIP addresses or IPs.
                val slot = Regex("SIM slot: \\[([0-9]+)\\]").find(line)?.groupValues?.get(1)
                val state = Regex("state: \\[([^]]+)\\]").find(line)?.groupValues?.get(1)
                val profile = Regex("Name : ([^,\\]]+)").find(line)?.groupValues?.get(1)
                if (slot != null && state != null) {
                    status.appendLine("SIM slot ${slot.toInt() + 1}: $state (${profile ?: "IMS profile"})")
                }
            }
        }
        report.appendLine("Samsung registration summary:\\n" + status.lines().filter {
            it.startsWith("SIM slot ") && it.contains(" (")
        }.joinToString("\\n"))
        status.appendLine("Capability flags indicate configuration, not confirmed service use.")

    } else {
        status.appendLine("Samsung dumpsys diagnostics require working root.")
    }
    status.appendLine("Read-only: no IMS, CSC, or emergency calling settings modified.")
    return DiagnosticResult(status.toString(), report.toString())
}

@Suppress("ktlint:standard:function-naming")
@Composable
fun GalaxyImsSettings() {
    val context = LocalContext.current
    var diagnostic by remember { mutableStateOf<DiagnosticResult?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableStateOf(0) }
    var exportStatus by remember { mutableStateOf("") }
    LaunchedEffect(refresh) {
        loading = true
        diagnostic = withContext(Dispatchers.IO) {
            try { inspectIms(context.applicationContext) } catch (e: Exception) {
                DiagnosticResult("Diagnostic error: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(250)}", "Diagnostic failed: ${e.stackTraceToString().take(3000)}")
            }
        }
        loading = false
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Samsung IMS — Diagnostics v2 (read-only)")
        Text("IMS registration and availability do not prove carrier provisioning.")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color(0x88303740)),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(diagnostic?.text ?: "Collecting IMS diagnostics…")
            }
        }
        if (loading) CircularProgressIndicator()
        Button(onClick = { refresh++ }, enabled = !loading) { Text("Refresh diagnostics") }
        Button(onClick = {
            try {
                val file = File(context.getExternalFilesDir(null), "Galaxy_IMS_Diagnostics.txt")
                file.writeText(diagnostic?.export ?: "No diagnostic results")
                exportStatus = "Saved: ${file.absolutePath}"
            } catch (e: Exception) {
                exportStatus = "Export failed: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}"
            }
        }, enabled = !loading && diagnostic != null) { Text("Export diagnostic report") }
        if (exportStatus.isNotEmpty()) Text(exportStatus)
        Text("No IMS configuration, CSC, or emergency calling settings are modified.")
    }
}
