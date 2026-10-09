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

private fun inspectIms(context: Context): DiagnosticResult {
    val report = StringBuilder("Galaxy IMS Toolkit - Read-only diagnostics\n")
    report.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
    report.appendLine("Android SDK: ${Build.VERSION.SDK_INT}")
    val status = StringBuilder()
    val telephony = context.getSystemService(TelephonyManager::class.java)
    val subscriptions = context.getSystemService(SubscriptionManager::class.java)
    try {
        val ids = subscriptions?.activeSubscriptionInfoList.orEmpty()
        status.appendLine("Active subscriptions: ${ids.size}")
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
    if (rootAvailable) {
        // Never modify IMS or carrier settings here.
        val secims = try { runDiagnostic("dumpsys secims") } catch (e: Exception) {
            "secims failed: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(180)}"
        }
        val summary = secims.lines().filter {
            it.contains("RegistrationManager", true) ||
                it.contains("REGISTERED", true) ||
                it.contains("REGISTERING", true) ||
                it.contains("SIM slot:", true)
        }.take(18)
        status.appendLine("Samsung IMS: ${if (summary.isEmpty()) "no registration summary detected" else "diagnostic output available"}")
        if (summary.isNotEmpty()) status.appendLine(summary.joinToString("\n"))
        report.appendLine("secims dump (limited):\n$secims")
        if (summary.isEmpty()) {
            val fallback = try { runDiagnostic("dumpsys ims") } catch (e: Exception) {
                "IMS fallback failed: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(180)}"
            }
            report.appendLine("IMS fallback (limited):\n$fallback")
            status.appendLine("Fallback: dumpsys ims attempted; see exported report")
        }
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
        Card(modifier = Modifier.fillMaxWidth()) {
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
