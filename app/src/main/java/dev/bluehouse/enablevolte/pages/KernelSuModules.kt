package dev.bluehouse.enablevolte.pages

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

private fun rootCommand(command: String): String {
    val result =
        dev.bluehouse.enablevolte.RootCommands
            .run(command, 45, 32000)
    return if (result.timedOut) "Command timed out" else "Exit ${result.exitCode}: ${result.output}"
}

private fun moduleInventory(): String =
    rootCommand(
        "if [ -d /data/adb/modules ]; then for p in /data/adb/modules/*; do " +
            "[ -d \"\$p\" ] || continue; id=\${p##*/}; " +
            "name=\$(grep '^name=' \"\$p/module.prop\" 2>/dev/null | head -1 | cut -d= -f2-); " +
            "version=\$(grep '^version=' \"\$p/module.prop\" 2>/dev/null | head -1 | cut -d= -f2-); " +
            "if [ -e \"\$p/disable\" ]; then state=Disabled; else state=Enabled; fi; " +
            "echo \"\$id | \$name | \$version | \$state\"; done; " +
            "else echo 'Module directory not accessible'; fi",
    )

private fun validateModuleZip(file: File): String? {
    if (file.length() <= 0 || file.length() > 150L * 1024 * 1024) return "ZIP must be 1–150 MB."
    return try {
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().toList()
            if (entries.size > 5000) return "Too many ZIP entries."
            if (entries.any { it.name.startsWith("/") || it.name.split('/').contains("..") }) return "Unsafe ZIP paths."
            val prop = zip.getEntry("module.prop") ?: return "Missing module.prop at ZIP root."
            val text =
                zip.getInputStream(prop).bufferedReader().use { reader ->
                    val buffer = CharArray(8193)
                    val count = reader.read(buffer)
                    require(count <= 8192) { "module.prop too large" }
                    String(buffer, 0, maxOf(count, 0))
                }
            if (!text.lineSequence().any { it.startsWith("id=") }) "module.prop is missing id." else null
        }
    } catch (e: Exception) {
        "Invalid ZIP: ${e.javaClass.simpleName}"
    }
}

@Composable
fun KernelSuModules(openImsModuleBuilder: () -> Unit = {}) {
    val context = LocalContext.current
    var inventory by remember { mutableStateOf("Tap Refresh to inspect installed modules.") }
    var selected by remember { mutableStateOf<File?>(null) }
    var selectedName by remember { mutableStateOf("") }
    var validation by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                selected?.delete()
                selected = null
                selectedName = uri.lastPathSegment?.substringAfterLast('/') ?: "module.zip"
                try {
                    val file = File.createTempFile("ksu_module_", ".zip", context.cacheDir)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= 150L * 1024 * 1024) { "ZIP exceeds 150 MB limit" }
                                output.write(buffer, 0, count)
                            }
                        }
                    } ?: error("Unable to read selected ZIP")
                    validation = validateModuleZip(file) ?: "ZIP structure validated. Install scripts have not been audited."
                    selected = file
                } catch (e: Exception) {
                    validation = "Import failed: ${e.message.orEmpty().take(120)}"
                }
            }
        }
    LaunchedEffect(refresh) {
        inventory =
            withContext(Dispatchers.IO) {
                try {
                    moduleInventory()
                } catch (e: Exception) {
                    "Root unavailable: ${e.javaClass.simpleName}"
                }
            }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("KERNEL SUITE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text("KernelSU Modules", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Inspect installed modules and install trusted ZIP packages. Installing modules executes privileged scripts.",
            style = MaterialTheme.typography.bodySmall,
        )
        Card(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(),
            colors =
                androidx.compose.material3.CardDefaults
                    .cardColors(
                        containerColor =
                            androidx.compose.ui.graphics
                                .Color(0xFF25202C),
                    ),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "GALAXY IMS • CSC MODULE",
                    color =
                        androidx.compose.ui.graphics
                            .Color(0xFFFF7A7A),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text("Built-in CSC module builder", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Prepare and export a KernelSU-compatible CSC reference ZIP from the IMS XML Lab. " +
                        "Current exports are inactive templates and do not apply carrier changes.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = openImsModuleBuilder, modifier = Modifier.fillMaxWidth()) {
                    Text("Open IMS CSC module builder", color = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
        Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Installed modules", style = MaterialTheme.typography.titleMedium)
                val moduleLines = inventory.lines().filter { it.contains(" | ") }
                if (moduleLines.isEmpty()) {
                    Text(inventory, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        "${moduleLines.size} installed • ${moduleLines.count { it.endsWith("Enabled") }} enabled",
                        color = Color(0xFFB8C5D6),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    moduleLines.forEach { line ->
                        val parts = line.split(" | ")
                        val active = parts.lastOrNull() == "Enabled"
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF242B36)),
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(
                                        parts.getOrElse(1) { parts[0] },
                                        modifier = Modifier.weight(1f),
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (active) "● Active" else "○ Off",
                                        color = if (active) Color(0xFF94DDB6) else Color(0xFFFFBD88),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                Text(
                                    parts[0],
                                    color = Color(0xFFB8C5D6),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                if (parts.size > 2) {
                                    Text(
                                        "Version ${parts[2]}",
                                        color = Color(0xFFB8C5D6),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }
                OutlinedButton(onClick = { refresh++ }, enabled = !busy) { Text("Refresh modules") }
            }
        }
        Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Install / update module", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                    enabled = !busy,
                ) { Text("Select module ZIP", color = Color.White) }
                if (selectedName.isNotEmpty()) Text(selectedName, style = MaterialTheme.typography.bodySmall)
                if (validation.isNotEmpty()) Text(validation, style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = { confirm = true },
                    enabled = selected != null && validation.startsWith("ZIP structure validated") && !busy,
                ) {
                    Text("Review installation")
                }
                if (result.isNotEmpty()) Text(result, style = MaterialTheme.typography.bodySmall)
                Text(
                    "A successful installation may require a reboot. This app never reboots automatically.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Install KernelSU module?") },
            text = {
                Text(
                    "Only proceed with a trusted ZIP. KernelSU installation scripts run as root and may affect boot or SystemUI. The ZIP format check is not a security audit.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    val file = selected ?: return@TextButton
                    busy = true
                    scope.launch {
                        result =
                            withContext(Dispatchers.IO) {
                                try {
                                    val path = file.absolutePath.replace("'", "'\\''")
                                    rootCommand("ksud module install '$path'")
                                } catch (e: Exception) {
                                    "Install failed: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(150)}"
                                }
                            }
                        busy = false
                        refresh++
                    }
                }) { Text("Install") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
