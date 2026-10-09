package dev.bluehouse.enablevolte.pages

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

private fun rootCommand(command: String): String {
    val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
    val output = StringBuilder()
    val reader = Thread {
        process.inputStream.bufferedReader().use { stream ->
            val buffer = CharArray(1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.length < 12000) output.append(buffer, 0, minOf(count, 12000 - output.length))
            }
        }
    }
    reader.start()
    if (!process.waitFor(45, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        reader.join(1000)
        return "Command timed out; inspect KernelSU manager before retrying."
    }
    reader.join(1000)
    return "Exit ${process.exitValue()}: ${output.toString().take(6000)}"
}

private fun moduleInventory(): String = rootCommand(
    "if [ -d /data/adb/modules ]; then for p in /data/adb/modules/*; do " +
    "[ -d \"\$p\" ] || continue; id=\${p##*/}; " +
    "name=\$(grep '^name=' \"\$p/module.prop\" 2>/dev/null | head -1 | cut -d= -f2-); " +
    "version=\$(grep '^version=' \"\$p/module.prop\" 2>/dev/null | head -1 | cut -d= -f2-); " +
    "if [ -e \"\$p/disable\" ]; then state=Disabled; else state=Enabled; fi; " +
    "echo \"\$id | \$name | \$version | \$state\"; done; " +
    "else echo 'Module directory not accessible'; fi"
)

private fun validateModuleZip(file: File): String? {
    if (file.length() <= 0 || file.length() > 150L * 1024 * 1024) return "ZIP must be 1–150 MB."
    return try {
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().toList()
            if (entries.size > 5000) return "Too many ZIP entries."
            if (entries.any { it.name.startsWith("/") || it.name.split('/').contains("..") }) return "Unsafe ZIP paths."
            val prop = zip.getEntry("module.prop") ?: return "Missing module.prop at ZIP root."
            val text = zip.getInputStream(prop).bufferedReader().use { it.readText().take(8192) }
            if (!text.lineSequence().any { it.startsWith("id=") }) "module.prop is missing id." else null
        }
    } catch (e: Exception) { "Invalid ZIP: ${e.javaClass.simpleName}" }
}

@Composable
fun KernelSuModules() {
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
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            selected?.delete()
            selected = null
            selectedName = uri.lastPathSegment?.substringAfterLast('/') ?: "module.zip"
            try {
                val file = File.createTempFile("ksu_module_", ".zip", context.cacheDir)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Unable to read selected ZIP")
                validation = validateModuleZip(file) ?: "ZIP structure validated. Install scripts have not been audited."
                selected = file
            } catch (e: Exception) { validation = "Import failed: ${e.message.orEmpty().take(120)}" }
        }
    }
    LaunchedEffect(refresh) {
        inventory = withContext(Dispatchers.IO) {
            try { moduleInventory() } catch (e: Exception) { "Root unavailable: ${e.javaClass.simpleName}" }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("KERNEL SUITE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text("KernelSU Modules", style = MaterialTheme.typography.headlineSmall)
        Text("Inspect installed modules and install trusted ZIP packages. Installing modules executes privileged scripts.",
            style = MaterialTheme.typography.bodySmall)
        Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Installed modules", style = MaterialTheme.typography.titleMedium)
                Text(inventory, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { refresh++ }, enabled = !busy) { Text("Refresh modules") }
            }
        }
        Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Install / update module", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                    enabled = !busy) { Text("Select module ZIP") }
                if (selectedName.isNotEmpty()) Text(selectedName, style = MaterialTheme.typography.bodySmall)
                if (validation.isNotEmpty()) Text(validation, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { confirm = true },
                    enabled = selected != null && validation.startsWith("ZIP structure validated") && !busy) {
                    Text("Review installation")
                }
                if (result.isNotEmpty()) Text(result, style = MaterialTheme.typography.bodySmall)
                Text("A successful installation may require a reboot. This app never reboots automatically.",
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Install KernelSU module?") },
        text = { Text("Only proceed with a trusted ZIP. KernelSU installation scripts run as root and may affect boot or SystemUI. The ZIP format check is not a security audit.") },
        confirmButton = {
            TextButton(onClick = {
                confirm = false
                val file = selected ?: return@TextButton
                busy = true
                scope.launch {
                    result = withContext(Dispatchers.IO) {
                        try {
                            val path = file.absolutePath.replace("'", "'\\''")
                            rootCommand("ksud module install '$path'")
                        } catch (e: Exception) { "Install failed: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(150)}" }
                    }
                    busy = false
                    refresh++
                }
            }) { Text("Install") }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}
