package dev.bluehouse.enablevolte.pages

import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

private data class DiagnosticResult(
    val text: String,
    val export: String,
)

private fun runDiagnostic(command: String): String {
    val result =
        dev.bluehouse.enablevolte.RootCommands
            .run(command, 10, 32000)
    return if (result.timedOut) "Command timed out" else "Exit ${result.exitCode}: ${result.output}"
}

// Read only the relevant Samsung registration lines at the source. The full
// secims dump contains extensive historical logs and subscriber identifiers.
private fun samsungRegistrationSummary(): String =
    runDiagnostic(
        "dumpsys secims 2>&1 | grep -E 'SIM slot: \\[|state: \\[|enableService(Volte|Vowifi|Rcs|Vilte)=' | head -80",
    )

private fun privilegedSubscriptionSummary(): String =
    runDiagnostic(
        "dumpsys isub 2>&1 | grep -E '^(Active modem count=|  Logical SIM slot [0-9]+:|mSimState\\[[0-9]+\\]=)' | head -12",
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
        status.appendLine("App API subscriptions: ${ids.size} (may be permission-filtered)")
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
    val rootResult =
        try {
            runDiagnostic("id")
        } catch (e: Exception) {
            "Root command unavailable: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(180)}"
        }
    val rootAvailable = rootResult.contains("uid=0")
    status.appendLine("Root: ${if (rootAvailable) "available" else "unavailable"}")
    report.appendLine("Root check: $rootResult")
    val shizuku =
        try {
            val cls = Class.forName("rikka.shizuku.Shizuku")
            val binder = cls.getMethod("pingBinder").invoke(null) as? Boolean ?: false
            "Shizuku binder: ${if (binder) "reachable" else "unavailable"}"
        } catch (e: Exception) {
            "Shizuku status unavailable: ${e.javaClass.simpleName}"
        }
    status.appendLine(shizuku)
    report.appendLine(shizuku)
    // Identity is distinct from write capability. Each write verifies its result.
    val shizukuUid =
        try {
            val cls = Class.forName("rikka.shizuku.Shizuku")
            val uid = cls.getMethod("getUid").invoke(null) as? Int
            uid?.toString() ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    val routeStatus =
        when (shizukuUid) {
            "1000" -> "System UID 1000 binder detected (write access unverified)"
            "0" -> "Root UID 0 binder detected (write access unverified)"
            "2000" -> "Shell UID 2000 binder detected (limited permissions)"
            else -> "Binder backend UID unknown; TokenX route not verified"
        }
    status.appendLine(
        "Privilege route: " +
            dev.bluehouse.enablevolte.BackendStatus
                .describe(),
    )
    report.appendLine(
        "Privilege route: " +
            dev.bluehouse.enablevolte.BackendStatus
                .describe(),
    )
    status.appendLine("Carrier toggles: authorized app Binder or standalone root worker; results require readback")
    if (rootAvailable) {
        // Never modify IMS or carrier settings here.
        val subSummary =
            try {
                privilegedSubscriptionSummary()
            } catch (e: Exception) {
                "Subscription service query failed: ${e.javaClass.simpleName}"
            }
        status.appendLine("Privileged SIM inventory (source: dumpsys isub):")
        status.appendLine(subSummary)
        report.appendLine("Privileged subscription summary:\n$subSummary")
        val secims =
            try {
                samsungRegistrationSummary()
            } catch (e: Exception) {
                "Samsung registration query failed: ${e.javaClass.simpleName}"
            }
        val registrations =
            secims.lines().filter {
                it.contains("SIM slot: [") && it.contains("state: [")
            }
        status.appendLine("IMS registration (source: Samsung secims):")
        if (registrations.isEmpty()) {
            status.appendLine("Registration unknown: no parseable profile records returned; not proof of disconnection.")
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
        report.appendLine(
            "Samsung registration summary:\n" +
                status
                    .lines()
                    .filter {
                        it.startsWith("SIM slot ") && it.contains(" (")
                    }.joinToString("\n"),
        )
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
    var observerRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(3000)
            observerRevision++
        }
    }
    var exportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showAdvanced by remember { mutableStateOf(false) }
    var lastUpdated by remember { mutableStateOf("Not yet refreshed") }
    LaunchedEffect(refresh) {
        loading = true
        diagnostic =
            withContext(Dispatchers.IO) {
                try {
                    inspectIms(context.applicationContext)
                } catch (e: Exception) {
                    DiagnosticResult(
                        "Diagnostic error: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(250)}",
                        "Diagnostic failed: ${e.stackTraceToString().take(3000)}",
                    )
                }
            }
        lastUpdated = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        loading = false
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "SAMSUNG IMS · READ-ONLY",
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
        )
        Text("Live IMS dashboard", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
        Text(
            "Updated: $lastUpdated • Refresh to collect new readings",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        )
        val snapshot = diagnostic?.text.orEmpty()
        val modemCount = Regex("Active modem count=([0-9]+)").find(snapshot)?.groupValues?.getOrNull(1) ?: "Unknown"
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors =
                androidx.compose.material3.CardDefaults
                    .cardColors(containerColor = Color(0xFF22252D)),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("SIM overview · $modemCount active modems", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    (0..1).forEach { slot ->
                        val state =
                            Regex("mSimState\\[$slot\\]=([^\\n]+)")
                                .find(snapshot)
                                ?.groupValues
                                ?.getOrNull(1)
                                ?.trim() ?: "Unknown"
                        val subId = Regex("Logical SIM slot $slot: subId=([0-9]+)").find(snapshot)?.groupValues?.getOrNull(1) ?: "?"
                        Card(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors =
                                androidx.compose.material3.CardDefaults
                                    .cardColors(containerColor = Color(0xFF333744)),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text("SIM ${slot + 1}", style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                                Text(state, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                                Text("Slot $slot · subId $subId", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Text("SIM loaded does not mean IMS registered.", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
            }
        }
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("IMS services", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                listOf(
                    "VoLTE" to "Voice over LTE",
                    "VoWiFi" to "Wi-Fi calling",
                    "RCS" to "Rich communication services",
                ).forEach { (service, description) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(service)
                            Text(description, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                        }
                        val registrations =
                            snapshot.lines().filter {
                                it.startsWith("SIM slot ") && it.contains(" (")
                            }
                        val observed =
                            remember(observerRevision, service) {
                                dev.bluehouse.enablevolte.ObserverState
                                    .serviceSummary(context, service)
                            }
                        Text(
                            observed ?: if (registrations.isNotEmpty()) "Registration available • capability unknown" else "Unknown",
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Text(
                    "Capability and registration details are available in Advanced diagnostics.",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                )
            }
        }
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Backend manager", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                val root =
                    Regex("Root: ([^\\n]+)")
                        .find(snapshot)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.trim() ?: "Not checked"
                val shizuku =
                    Regex("Shizuku binder: ([^\\n]+)")
                        .find(snapshot)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.trim() ?: "Not checked"
                val route =
                    Regex("Privilege route: ([^\\n]+)")
                        .find(snapshot)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.trim() ?: "Not verified"
                Text("Root · $root")
                Text("Shizuku · $shizuku")
                Text("TokenX / privilege route · $route", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                Text(
                    "Backend identity does not establish IMS write permission.",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                )
            }
        }
        OutlinedButton(onClick = { showAdvanced = !showAdvanced }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showAdvanced) "Hide advanced diagnostics" else "Show advanced diagnostics")
        }
        if (showAdvanced) {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Text(
                    snapshot.ifBlank { "Collecting diagnostics…" },
                    modifier = Modifier.padding(16.dp),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (loading) CircularProgressIndicator()
        Button(onClick = { refresh++ }, enabled = !loading) { Text("Refresh diagnostics") }
        Button(onClick = {
            try {
                val file = File(context.getExternalFilesDir(null), "Galaxy_IMS_Diagnostics.txt")
                val text = diagnostic?.export ?: "No diagnostic results"
                exportUri =
                    dev.bluehouse.enablevolte.DiagnosticExports
                        .save(context, "Galaxy_IMS_Diagnostics.txt", text)
                exportStatus = "Saved to Download/Galaxy_IMS_Diagnostics.txt"
            } catch (e: Exception) {
                exportStatus = "Export failed: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}"
            }
        }, enabled = !loading && diagnostic != null) { Text("Export diagnostic report") }
        if (exportStatus.isNotEmpty()) Text(exportStatus)
        exportUri?.let { uri ->
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        "Share IMS diagnostic",
                    ),
                )
            }) { Text("Share diagnostic report") }
        }
        dev.bluehouse.enablevolte.components
            .ObserverPanel()

        Text("No IMS configuration, CSC, or emergency calling settings are modified.")
    }
}
