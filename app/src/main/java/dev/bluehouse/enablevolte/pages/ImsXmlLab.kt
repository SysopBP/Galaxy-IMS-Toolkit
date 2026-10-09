package dev.bluehouse.enablevolte.pages

import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import android.util.Base64
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream

private data class XmlEntry(val path: String, val value: String)
private data class XmlDocument(val name: String, val entries: List<XmlEntry>, val error: String? = null, val originalXml: String = "")

/** Read-only, locally imported IMS XML inspection. Never writes telephony or IMS state. */
@Composable
fun ImsXmlLab() {
    val context = LocalContext.current
    var documents by remember { mutableStateOf<List<XmlDocument>>(emptyList()) }
    var discoveryStatus by remember { mutableStateOf("Scanning device IMS XML files…") }
    var rescan by remember { mutableIntStateOf(0) }
    LaunchedEffect(rescan) {
        discoveryStatus = "Scanning protected IMS configuration…"
        val result = withContext(Dispatchers.IO) { runCatching { discoverImsXml() } }
        result.onSuccess { found ->
            if (found.isNotEmpty()) documents = found
            discoveryStatus = if (found.isEmpty()) "No accessible IMS XML found. You can import files manually." else "${found.size} IMS XML files loaded from device (read-only)."
        }.onFailure { discoveryStatus = "Automatic scan unavailable: ${it.javaClass.simpleName}. Manual import is still available." }
    }
    var filter by remember { mutableStateOf("") }
    var selected by remember { mutableIntStateOf(0) }
    var comparison by remember { mutableIntStateOf(0) }
    var differencesOnly by remember { mutableStateOf(true) }
    var showResetConfirmation by remember { mutableStateOf(false) }
    var draftXml by remember { mutableStateOf("") }
    var editMode by remember { mutableStateOf(false) }
    var editorMessage by remember { mutableStateOf("") }
    var exportXml by remember { mutableStateOf("") }
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/xml"),
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(exportXml.toByteArray(Charsets.UTF_8))
                } ?: error("Cannot open export destination")
                editorMessage = "XML saved successfully. Device IMS settings were not changed."
            } catch (e: Exception) {
                editorMessage = "Export failed: ${e.message}"
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        documents = uris.take(12).map { uri -> readImsXml(context, uri) }
        selected = 0
        comparison = if (documents.size > 1) 1 else 0
        draftXml = documents.firstOrNull()?.originalXml.orEmpty()
        editMode = false
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("IMS XML Lab", style = MaterialTheme.typography.headlineSmall)
        Text("Read-only. Import Samsung imsconfig, imsprofile and imsswitch XML files. Nothing is changed on the device.")
        Spacer(Modifier.height(12.dp))
        Text(discoveryStatus)
        OutlinedButton(onClick = { rescan++ }) { Text("Reload device IMS XML") }
        Button(onClick = { picker.launch(arrayOf("text/xml", "application/xml", "text/*", "*/*")) }) {
            Text("Import XML files")
        }
        if (documents.isEmpty()) {
            Text("Select up to 12 XML files from your device. Files remain local.")
        } else {
            Text("${documents.size} files imported")
            documents.forEachIndexed { index, document ->
                TextButton(onClick = { selected = index; draftXml = document.originalXml; editMode = false; editorMessage = "" }) {
                    Text("${if (selected == index) "● " else ""}${document.name} (${document.entries.size} entries)")
                }
            }
            if (documents.size > 1) {
                Text("Compare against")
                documents.forEachIndexed { index, document ->
                    TextButton(onClick = { comparison = index }) {
                        Text("${if (comparison == index) "● " else ""}${document.name}")
                    }
                }
                Row {
                    Checkbox(checked = differencesOnly, onCheckedChange = { differencesOnly = it })
                    Text("Differences only")
                }
            }
            Row {
                OutlinedButton(onClick = { editMode = !editMode }) { Text(if (editMode) "Close editor" else "Edit XML") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { showResetConfirmation = true }) { Text("Reset") }
            }
            if (editMode) {
                Text("Editable draft — original imported XML remains unchanged.")
                OutlinedTextField(
                    value = draftXml,
                    onValueChange = { draftXml = it },
                    label = { Text("XML draft") },
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                    maxLines = 20,
                )
                Row {
                    Button(onClick = {
                        editorMessage = try {
                            validateImsXml(draftXml)
                            "XML is well-formed. Review carrier-specific values before using it."
                        } catch (e: Exception) {
                            "Invalid XML: ${e.message}"
                        }
                    }) { Text("Validate") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        try {
                            validateImsXml(draftXml)
                            exportXml = draftXml
                            exporter.launch("edited_" + (documents.getOrNull(selected)?.name ?: "ims.xml"))
                        } catch (e: Exception) {
                            editorMessage = "Cannot export invalid XML: ${e.message}"
                        }
                    }) { Text("Export edited XML") }
                }
                OutlinedButton(onClick = {
                    draftXml = documents.getOrNull(selected)?.originalXml.orEmpty()
                    editorMessage = "Draft restored from the imported original. Live IMS settings unchanged."
                }) { Text("Restore original draft") }
                if (editorMessage.isNotEmpty()) Text(editorMessage)
            }
            if (showResetConfirmation) {
                AlertDialog(
                    onDismissRequest = { showResetConfirmation = false },
                    title = { Text("Reset XML Lab?") },
                    text = { Text("Clear imported files, filters and comparisons. This does NOT restore live IMS or carrier settings.") },
                    confirmButton = {
                        TextButton(onClick = {
                            documents = emptyList()
                            filter = ""
                            selected = 0
                            comparison = 0
                            differencesOnly = true
                            draftXml = ""
                            editMode = false
                            editorMessage = ""
                            showResetConfirmation = false
                        }) { Text("Reset lab") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showResetConfirmation = false }) { Text("Cancel") }
                    },
                )
            }
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                label = { Text("Search element, attribute or value") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val current = documents.getOrNull(selected)
            if (current?.error != null) Text(current.error, color = MaterialTheme.colorScheme.error)
            val other = documents.getOrNull(comparison)
            val comparing = documents.size > 1 && comparison != selected && other?.error == null
            val left = current?.entries.orEmpty().withOccurrenceKeys()
            val right = if (comparing) other?.entries.orEmpty().withOccurrenceKeys() else emptyMap()
            val keys = if (comparing) (left.keys + right.keys).distinct() else left.keys.toList()
            val visible = keys.filter { key ->
                val before = left[key]
                val after = right[key]
                (!comparing || !differencesOnly || before != after) &&
                    (filter.isBlank() || key.contains(filter, true) ||
                        before.orEmpty().contains(filter, true) || after.orEmpty().contains(filter, true))
            }.take(1500)
            Text("${visible.size} entries shown${if (comparing) " (left vs right)" else ""}")
            LazyColumn {
                items(visible) { key ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(key, style = MaterialTheme.typography.labelMedium)
                        if (comparing) {
                            Row(Modifier.fillMaxWidth()) {
                                Text(left[key] ?: "—", Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                Text(right[key] ?: "—", Modifier.weight(1f))
                            }
                        } else {
                            Text(left[key].orEmpty())
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private fun readImsXml(context: android.content.Context, uri: Uri): XmlDocument {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: "Imported XML"
    return try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(2 * 1024 * 1024 + 1)
            var count = 0
            while (count < buffer.size) {
                val n = stream.read(buffer, count, buffer.size - count)
                if (n < 0) break
                count += n
            }
            require(count <= 2 * 1024 * 1024) { "File exceeds 2 MB inspection limit" }
            buffer.copyOf(count)
        } ?: error("Unable to open XML")
        parseImsXml(name, bytes)
    } catch (e: Exception) {
        XmlDocument(name, emptyList(), "Cannot inspect XML: ${e.message}")
    }
}

private fun discoverImsXml(): List<XmlDocument> {
    val directory = "/data/user_de/0/com.sec.imsservice/shared_prefs"
    val listing = ProcessBuilder("su", "-c",
        "find $directory -maxdepth 1 -type f -name 'ims*.xml' 2>/dev/null | sort | head -12"
    ).redirectErrorStream(true).start()
    val names = listing.inputStream.bufferedReader().readLines()
    require(listing.waitFor() == 0) { "Root IMS directory scan denied" }
    return names.mapNotNull { path ->
        val name = path.substringAfterLast('/')
        if (!Regex("^(imsconfig|imsprofile|imsswitch)_[0-9]+\\.xml$").matches(name)) return@mapNotNull null
        runCatching {
            // Base64 keeps XML and shell diagnostics separate; filenames are validated above.
            val proc = ProcessBuilder("su", "-c",
                "base64 $directory/$name | tr -d '\\n'"
            ).redirectErrorStream(true).start()
            val encoded = proc.inputStream.bufferedReader().readText().take(3_000_000)
            require(proc.waitFor() == 0) { "Protected XML read denied" }
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            require(bytes.size <= 2 * 1024 * 1024) { "XML exceeds inspection limit" }
            parseImsXml(name, bytes)
        }.getOrElse { XmlDocument(name, emptyList(), "Read failed: ${it.javaClass.simpleName}") }
    }
}

private fun parseImsXml(name: String, bytes: ByteArray): XmlDocument {
    return try {
        validateImsXml(bytes.toString(Charsets.UTF_8))
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), null)
        val stack = mutableListOf<String>()
        val entries = mutableListOf<XmlEntry>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && entries.size < 10000) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    require(stack.size < 64) { "XML nesting limit exceeded" }
                    stack.add(parser.name)
                    for (i in 0 until parser.attributeCount) {
                        entries.add(XmlEntry(stack.joinToString("/") + "/@" + parser.getAttributeName(i), parser.getAttributeValue(i).take(1000)))
                    }
                }
                XmlPullParser.TEXT -> {
                    val value = parser.text?.trim().orEmpty()
                    if (value.isNotEmpty()) entries.add(XmlEntry(stack.joinToString("/"), value.take(1000)))
                }
                XmlPullParser.END_TAG -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
            }
            event = parser.next()
        }
        XmlDocument(name, entries, originalXml = bytes.toString(Charsets.UTF_8))
    } catch (e: Exception) {
        XmlDocument(name, emptyList(), "Cannot parse XML: ${e.message}")
    }
}

/** Preserve duplicate XML paths by numbering occurrences in document order. */
private fun List<XmlEntry>.withOccurrenceKeys(): Map<String, String> {
    val counts = mutableMapOf<String, Int>()
    return associate { entry ->
        val n = (counts[entry.path] ?: 0) + 1
        counts[entry.path] = n
        "${entry.path} [${n}]" to entry.value
    }
}

/** Reject DTDs and external entities before saving a user-edited draft. */
private fun validateImsXml(xml: String) {
    require(xml.length <= 2 * 1024 * 1024) { "XML exceeds 2 MB limit" }
    require(!xml.contains("<!DOCTYPE", ignoreCase = true)) { "DOCTYPE is not allowed" }
    require(!xml.contains("<!ENTITY", ignoreCase = true)) { "ENTITY declarations are not allowed" }
    val parser = Xml.newPullParser()
    parser.setInput(java.io.StringReader(xml))
    var depth = 0
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG) {
            depth++
            require(depth <= 64) { "XML nesting limit exceeded" }
        }
        if (event == XmlPullParser.END_TAG) depth--
        event = parser.next()
    }
}
