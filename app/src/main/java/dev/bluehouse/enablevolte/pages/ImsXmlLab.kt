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
    var category by remember { mutableStateOf("All") }
    var cacheStatus by remember { mutableStateOf("") }
    var discoveryStatus by remember { mutableStateOf("Scanning device IMS XML files…") }
    var backendStatus by remember { mutableStateOf("Root backend not checked") }
    var systemBackendStatus by remember { mutableStateOf("TokenX System UID 1000 not checked") }
    var shizukuBackendStatus by remember { mutableStateOf("Shizuku not checked") }
    var hookStatus by remember { mutableStateOf("Hook heartbeat not received") }
    var showHookDiagnostics by remember { mutableStateOf(false) }
    var rescan by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf("") }
    var valueType by remember { mutableStateOf("All") }
    var selected by remember { mutableIntStateOf(0) }
    var comparison by remember { mutableIntStateOf(0) }
    var differencesOnly by remember { mutableStateOf(true) }
    var showResetConfirmation by remember { mutableStateOf(false) }
    var draftXml by remember { mutableStateOf("") }
    var editMode by remember { mutableStateOf(false) }
    var editorMessage by remember { mutableStateOf("") }
    var exportXml by remember { mutableStateOf("") }
    var snapshotXml by remember { mutableStateOf("") }
    var snapshotStatus by remember { mutableStateOf("") }
    var backupHashVerified by remember { mutableStateOf(false) }
    val backupHistory = remember { context.getSharedPreferences("ims_backup_history", 0) }
    var recentBackup by remember {
        mutableStateOf(backupHistory.getString("last_backup", "").orEmpty())
    }
    var backupRecords by remember {
        mutableStateOf(backupHistory.getString("backup_records", "").orEmpty())
    }
    var expectedBackupHash by remember { mutableStateOf("") }
    var backupVerification by remember { mutableStateOf("") }

    var previewChanges by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var previewMessage by remember { mutableStateOf("") }
    var showDraftDiff by remember { mutableStateOf(false) }
    LaunchedEffect(rescan) {
        if (documents.isEmpty()) {
            val cached = withContext(Dispatchers.IO) { loadImsCache(context) }
            if (cached.isNotEmpty()) {
                documents = cached
                cacheStatus = "Loaded ${cached.size} saved local copies"
            }
        }
        discoveryStatus = "Scanning protected IMS and CSC XML…"
        val result = withContext(Dispatchers.IO) {
            backendStatus = checkImsRootBackend()
            systemBackendStatus = checkImsSystemBackend()
            shizukuBackendStatus = checkImsShizukuBackend()
            runCatching { discoverImsXml() }
        }
        result.onSuccess { found ->
            if (found.isNotEmpty()) {
                val savedCount = withContext(Dispatchers.IO) { saveImsCache(context, found) }
                cacheStatus = "Saved $savedCount private local copies"
                documents = found
                selected = 0
                comparison = if (found.size > 1) 1 else 0
                draftXml = found.first().originalXml
                previewChanges = emptyMap()
            }
            discoveryStatus = if (found.isEmpty()) {
                "No accessible IMS or CSC XML found. You can import files manually."
            } else {
                "${found.size} IMS/CSC XML copies loaded from device (read-only)."
            }
        }.onFailure {
            discoveryStatus = "Automatic scan unavailable: ${it.javaClass.simpleName}."
        }
    }
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
    val snapshotExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/xml"),
    ) { uri ->
        if (uri != null) {
            snapshotStatus = try {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(snapshotXml.toByteArray(Charsets.UTF_8))
                } ?: error("Unable to open backup destination")
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(snapshotXml.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                backupHashVerified = false
                val saved = "${uri.lastPathSegment.orEmpty().take(80)} | SHA-256: $digest"
                val updatedRecords = (listOf(saved) + backupRecords.split("\n"))
                    .filter { it.isNotBlank() }.distinct().take(10).joinToString("\n")
                backupHistory.edit()
                    .putString("last_backup", saved)
                    .putString("backup_records", updatedRecords)
                    .apply()
                recentBackup = saved
                backupRecords = updatedRecords
                "Original XML snapshot saved. SHA-256: $digest"
            } catch (e: Exception) {
                backupHashVerified = false
                "Snapshot failed: ${e.message}"
            }
        }
    }

    val backupVerifier = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            backupVerification = try {
                val expected = expectedBackupHash.trim().lowercase(java.util.Locale.ROOT)
                require(Regex("[0-9a-f]{64}").matches(expected)) {
                    "Enter the original 64-character SHA-256 checksum"
                }
                val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                    val data = stream.readBytes()
                    require(data.size <= 2 * 1024 * 1024) { "Backup exceeds 2 MB limit" }
                    data
                } ?: error("Unable to open backup")
                val actual = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(bytes).joinToString("") { "%02x".format(it) }
                if (actual == expected) {
                    backupHashVerified = true
                    "Verified: backup matches the supplied SHA-256 checksum."
                } else {
                    backupHashVerified = false
                    "Checksum mismatch: do not use this backup for restoration."
                }
            } catch (e: Exception) {
                backupHashVerified = false
                "Verification failed: ${e.message}"
            }
        }
    }
    var cscBuilderStatus by remember { mutableStateOf("") }
    var cscTargetPath by remember { mutableStateOf("") }
    var cscOverlayConfirmed by remember { mutableStateOf(false) }
    var cscInputName by remember { mutableStateOf("") }
    var cscInputXml by remember { mutableStateOf("") }
    var cscValidation by remember { mutableStateOf("No CSC file selected") }
    var cscBaselineXml by remember { mutableStateOf("") }
    var cscDiffStatus by remember { mutableStateOf("Select two CSC XML files to compare.") }
    val cscBaselinePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            cscDiffStatus = try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Cannot read baseline")
                require(bytes.size <= 2 * 1024 * 1024) { "Baseline exceeds 2 MB" }
                val baseline = bytes.toString(Charsets.UTF_8)
                val parser = Xml.newPullParser()
                parser.setInput(java.io.StringReader(baseline))
                while (parser.next() != XmlPullParser.END_DOCUMENT) { }
                cscBaselineXml = baseline
                "Baseline CSC XML validated. Compare it with the selected candidate."
            } catch (e: Exception) {
                cscBaselineXml = ""
                "Baseline invalid: ${e.javaClass.simpleName}"
            }
        }
    }
    val cscPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            cscValidation = try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Cannot open CSC file")
                require(bytes.size <= 2 * 1024 * 1024) { "CSC file exceeds 2 MB" }
                val xml = bytes.toString(Charsets.UTF_8)
                val parser = Xml.newPullParser()
                parser.setInput(java.io.StringReader(xml))
                while (parser.next() != XmlPullParser.END_DOCUMENT) { }
                cscInputName = uri.lastPathSegment.orEmpty().take(100)
                cscInputXml = xml
                "CSC XML valid (${bytes.size} bytes). Preview only; not included in module."
            } catch (e: Exception) {
                cscInputXml = ""
                cscInputName = ""
                "CSC validation failed: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}"
            }
        }
    }
    val cscExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            cscBuilderStatus = try {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    java.util.zip.ZipOutputStream(stream).use { zip ->
                        val files = mapOf(
                            "module.prop" to (
                                "id=galaxy_ims_csc_draft\n" +
                                    "name=Galaxy IMS CSC Draft\n" +
                                    "version=0.1\nversionCode=1\n" +
                                    "author=Galaxy IMS Toolkit\n" +
                                    "description=Inactive CSC overlay template; no changes installed\n"
                            ),
                            "README.txt" to (
                                "CSC MODULE DRAFT - INACTIVE\n" +
                                    "No system overlay is included. This package does not modify CSC.\n" +
                                    "Verify the correct CSC path and target firmware before adding files.\n" +
                                    "Keep an independent backup and recovery plan before installation.\n" +
                                    "To roll back an installed module, disable or remove it in KernelSU.\n"
                            ),
                        )
                        val candidateHashForArchive = if (cscInputXml.isNotEmpty()) {
                            java.security.MessageDigest.getInstance("SHA-256")
                                .digest(cscInputXml.toByteArray(Charsets.UTF_8))
                                .joinToString("") { "%02x".format(it) }
                        } else "none"
                        val baselineHashForArchive = if (cscBaselineXml.isNotEmpty()) {
                            java.security.MessageDigest.getInstance("SHA-256")
                                .digest(cscBaselineXml.toByteArray(Charsets.UTF_8))
                                .joinToString("") { "%02x".format(it) }
                        } else "none"
                        val audit = "CSC module export audit\\n" +
                            "Proposed target: " + cscTargetPath + "\\n" +
                            "Candidate SHA-256: " + candidateHashForArchive + "\\n" +
                            "Baseline SHA-256: " + baselineHashForArchive + "\\n" +
                            "Overlay confirmed in UI: " + cscOverlayConfirmed + "\\n" +
                            "Status: INACTIVE; candidate and baseline are reference-only\\n"
                        val safeFiles = files + mapOf(
                            "service.sh" to "#!/system/bin/sh\\n# Inactive by design; no mounts or service restarts.\\nexit 0\\n",
                            "customize.sh" to ("#!/system/bin/sh\\n" +
                                "ui_print '- Inactive CSC reference module'\\n"),
                            "audit.txt" to audit,
                            "reference/candidate.xml" to cscInputXml,
                            "reference/baseline.xml" to cscBaselineXml
                        )
                        safeFiles.forEach { (name, contents) ->
                            zip.putNextEntry(java.util.zip.ZipEntry(name))
                            zip.write(contents.toByteArray(Charsets.UTF_8))
                            zip.closeEntry()
                        }
                    }
                } ?: error("Unable to create module archive")
                "CSC draft ZIP exported. Inactive template only; no CSC files changed."
            } catch (e: Exception) {
                "CSC draft export failed: ${e.javaClass.simpleName}"
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        documents = uris.take(12).map { uri -> readImsXml(context, uri) }
        selected = 0
        comparison = if (documents.size > 1) 1 else 0
        draftXml = documents.firstOrNull()?.originalXml.orEmpty()
        editMode = false
        previewChanges = emptyMap()
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("IMS XML Lab", style = MaterialTheme.typography.headlineSmall)
        Text("Read-only. Import Samsung imsconfig, imsprofile and imsswitch XML files. Nothing is changed on the device.")
        Spacer(Modifier.height(12.dp))
        Text(discoveryStatus)
        if (cacheStatus.isNotEmpty()) Text(cacheStatus)
        Text("Local library: private app storage; originals are never changed.")
        Row {
            listOf("All", "IMS", "CSC", "Carrier JSON").forEach { choice ->
                TextButton(onClick = { category = choice }) { Text(choice) }
            }
        }
        Text("Category: $category")
        val carrierCodes = documents.mapNotNull { doc ->
            Regex("/(?:optics/configs|prism/etc)/carriers/([A-Z0-9]+)/")
                .find(doc.name)?.groupValues?.getOrNull(1)
        }.distinct().sorted()
        Text("Carrier CSC profiles: ${carrierCodes.size}")
        Text("Available codes: " + carrierCodes.joinToString(", ").take(400))
        Text("Active carrier not verified; profiles listed are not necessarily enabled.")
        var showCscCatalog by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { showCscCatalog = !showCscCatalog }) {
            Text(if (showCscCatalog) "Hide CSC feature reference" else "CSC feature reference")
        }
        if (showCscCatalog) {
            Text("Reference keys from OneUI_CSC_Features; not verified on this firmware.")
            val referenceKeys = listOf(
                "CarrierFeature_RIL_SupportVolte" to "VoLTE capability",
                "CarrierFeature_Setting_DisableNetworkMode" to "Network mode restrictions",
                "CarrierFeature_VoiceCall_ConfigOpStyleForMobileNetSetting" to "Mobile network settings",
                "CarrierFeature_VoiceCall_ConfigOpStyleForVolte" to "VoLTE UI behavior",
                "CarrierFeature_SystemUI_ConfigOpBrandingForIndicatorIcon" to "Network indicator branding",
                "CscFeature_Setting_SupportRealTimeNetworkSpeed" to "Network speed display",
                "CscFeature_VoiceCall_ConfigRecording" to "Call recording configuration",
                "CscFeature_Setting_EnableMenuBlockCallMsg" to "Call and message blocking menu",
                "CscFeature_VoiceCall_ConfigOpStyleForImsFunction" to "IMS call presentation"
            )
            referenceKeys.forEach { (key, label) ->
                Text("$label — $key", style = MaterialTheme.typography.labelSmall)
            }
            Text("Reference only • no values applied • support varies by CSC and firmware.")
        }
        Text("Matching copies: " + documents.count { document ->
            when (category) {
                "IMS" -> document.name.contains("com.sec.imsservice") || document.name.substringAfterLast("/").startsWith("ims")
                "CSC" -> document.name.contains("/optics/") && !document.name.endsWith(".json")
                "Carrier JSON" -> document.name.endsWith(".json")
                else -> true
            }
        })
        Text("Backend Manager", style = MaterialTheme.typography.titleMedium)
        Text(backendStatus, style = MaterialTheme.typography.labelMedium)
        Text(systemBackendStatus, style = MaterialTheme.typography.labelMedium)
        Text(shizukuBackendStatus, style = MaterialTheme.typography.labelMedium)
        Text("Identity checks do not grant IMS write permissions.")
        OutlinedButton(onClick = { showHookDiagnostics = !showHookDiagnostics }) {
            Text(if (showHookDiagnostics) "Hide Xposed diagnostics" else "Xposed / IMS diagnostics")
        }
        if (showHookDiagnostics) {
            Text("LSPosed companion: not connected")
            Text(hookStatus)
            Text("Live registration events: unavailable until companion module is installed and scoped.")
            Text("No system_server hooks or runtime overrides are enabled.")
        }
        var showCscBuilder by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { showCscBuilder = !showCscBuilder }) {
            Text(if (showCscBuilder) "Hide CSC Module Builder" else "Open CSC Module Builder")
        }
        if (showCscBuilder) {
        Text("CSC Module Builder — safe draft", style = MaterialTheme.typography.titleMedium)
        Text("Generate an inactive KernelSU module template. No CSC overlay or carrier changes.")
        Text("Package preview", style = MaterialTheme.typography.titleMedium)
        Text("module.prop — module metadata")
        Text("README.txt — safety and rollback guidance")
        Text("No system/ overlay is packaged; ZIP is an inactive template.")
        if (cscInputXml.isNotEmpty()) {
            val candidateHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(cscInputXml.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            Text("Candidate CSC SHA-256: $candidateHash")
        }
        if (cscBaselineXml.isNotEmpty()) {
            val baselineHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(cscBaselineXml.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            Text("Baseline CSC SHA-256: $baselineHash")
        }
        Text("Neither CSC file is inserted into the module ZIP.")
        OutlinedTextField(
            value = cscTargetPath,
            onValueChange = { cscTargetPath = it.take(160); cscOverlayConfirmed = false },
            label = { Text("Proposed CSC target path (preview only)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        val cscPathValid = cscTargetPath.startsWith("/system/") &&
            !cscTargetPath.contains("..") &&
            cscTargetPath.endsWith(".xml") &&
            cscTargetPath.none { it.code == 0 || it.code == 10 || it.code == 13 }
        Text(if (cscPathValid) "Target format valid (not device-verified)" else
            "Enter an absolute /system/...xml path without traversal.")
        Checkbox(
            checked = cscOverlayConfirmed,
            onCheckedChange = { cscOverlayConfirmed = it },
            enabled = cscPathValid && cscInputXml.isNotEmpty(),
        )
        Text("I understand this path is not verified against device firmware.")
        Text(if (cscOverlayConfirmed && cscPathValid) {
            "Overlay planning confirmed. Export remains an inactive ZIP template."
        } else {
            "Overlay planning locked until path validation and acknowledgement."
        })
        OutlinedButton(onClick = {
            cscPicker.launch(arrayOf("text/xml", "application/xml", "*/*"))
        }) { Text("Select and validate CSC XML") }
        Text(cscValidation)
        OutlinedButton(onClick = {
            cscBaselinePicker.launch(arrayOf("text/xml", "application/xml", "*/*"))
        }) { Text("Select baseline CSC XML") }
        Text(cscDiffStatus)
        if (cscInputXml.isNotEmpty() && cscBaselineXml.isNotEmpty()) {
            val before = cscBaselineXml.lines()
            val after = cscInputXml.lines()
            val removed = before.filterNot { it in after }.take(15)
            val added = after.filterNot { it in before }.take(15)
            Text("CSC comparison preview (line-level, max 15 per side)")
            Text("Removed / changed baseline lines: ${removed.size} shown")
            removed.forEach { Text("- ${it.take(150)}") }
            Text("Added / changed candidate lines: ${added.size} shown")
            added.forEach { Text("+ ${it.take(150)}") }
            Text("Preview only. This does not validate carrier compatibility.")
        }
        if (cscInputXml.isNotEmpty()) {
            Text("Selected: $cscInputName")
            Text("XML preview: ${cscInputXml.take(500)}")
        }
        OutlinedButton(onClick = {
            cscExporter.launch("Galaxy_IMS_CSC_Draft.zip")
        }) { Text("Export CSC module draft ZIP") }
        if (cscBuilderStatus.isNotEmpty()) Text(cscBuilderStatus)
        }
        OutlinedButton(onClick = { rescan++ }) { Text("Reload device IMS + CSC XML") }
        Button(onClick = { picker.launch(arrayOf("text/xml", "application/xml", "text/*", "*/*")) }) {
            Text("Import XML files")
        }
        if (documents.isEmpty()) {
            Text("Select up to 12 XML files from your device. Files remain local.")
        } else {
            Text("${documents.size} files imported")
            OutlinedButton(onClick = {
                val document = documents.getOrNull(selected)
                if (document == null || document.error != null || document.originalXml.isBlank()) {
                    snapshotStatus = "Select a valid XML file to back up."
                } else {
                    snapshotXml = document.originalXml
                    val timestamp = java.text.SimpleDateFormat(
                        "yyyyMMdd_HHmmss", java.util.Locale.US,
                    ).format(java.util.Date())
                    snapshotExporter.launch("IMS_original_${timestamp}_${document.name}")
                }
            }) { Text("Back up selected original XML") }
            if (snapshotStatus.isNotEmpty()) Text(snapshotStatus)
            if (recentBackup.isNotEmpty()) Text("Most recent backup: $recentBackup")
            if (backupRecords.isNotEmpty()) {
                Text("Backup history (up to 10 local records)")
                backupRecords.split("\n").filter { it.isNotBlank() }.forEach { record ->
                    Text(record, style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedTextField(
                value = expectedBackupHash,
                onValueChange = { expectedBackupHash = it.take(64) },
                label = { Text("Original backup SHA-256") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedButton(onClick = {
                backupVerifier.launch(arrayOf("application/xml", "text/xml", "*/*"))
            }) { Text("Verify saved XML backup") }
            if (backupVerification.isNotEmpty()) Text(backupVerification)

            if (backupHashVerified) Text("Backup checksum verified against selected file.")
            documents.forEachIndexed { index, document ->
                TextButton(onClick = { selected = index; draftXml = document.originalXml; editMode = false; editorMessage = ""; previewChanges = emptyMap() }) {
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
            Text("Option previews are local only; they do not modify XML or IMS.")
            if (previewChanges.isNotEmpty()) {
                Text("${previewChanges.size} staged option previews")
                Button(onClick = {
                    previewMessage = try {
                        val updated = applyImsAttributePreviews(
                            documents.getOrNull(selected)?.originalXml.orEmpty(),
                            previewChanges,
                        )
                        validateImsXml(updated)
                        draftXml = updated
                        editMode = true
                        "Changes staged in exportable draft."
                    } catch (e: Exception) {
                        "Cannot stage draft: ${e.message}"
                    }
                }) { Text("Stage attribute changes") }
                if (previewMessage.isNotEmpty()) Text(previewMessage)
                OutlinedButton(onClick = { previewChanges = emptyMap() }) {
                    Text("Reset option previews")
                }
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
                OutlinedButton(onClick = { showDraftDiff = !showDraftDiff }) {
                    Text(if (showDraftDiff) "Hide draft comparison" else "Compare original vs draft")
                }
                if (showDraftDiff) {
                    val source = documents.getOrNull(selected)
                    val draft = parseImsXml("draft", draftXml.toByteArray(Charsets.UTF_8))
                    if (draft.error != null) {
                        Text("Draft is invalid: ${draft.error}", color = MaterialTheme.colorScheme.error)
                    } else {
                        val originalEntries = source?.entries.orEmpty().withOccurrenceKeys()
                        val draftEntries = draft.entries.withOccurrenceKeys()
                        val changedKeys = (originalEntries.keys + draftEntries.keys).distinct().filter { key ->
                            originalEntries[key] != draftEntries[key]
                        }
                        Text("${changedKeys.size} changed entries compared with original")
                        changedKeys.take(100).forEach { key ->
                            Text(key, style = MaterialTheme.typography.labelMedium)
                            Text("Original: ${originalEntries[key] ?: "—"}")
                            Text("Draft: ${draftEntries[key] ?: "—"}")
                            HorizontalDivider()
                        }
                        if (changedKeys.size > 100) Text("Showing first 100 differences")
                    }
                }
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
                            previewChanges = emptyMap()
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
            Row {
                listOf("All", "Boolean", "Number", "Text").forEach { type ->
                    TextButton(onClick = { valueType = type }) {
                        Text(if (valueType == type) "● $type" else type)
                    }
                }
            }
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
                (valueType == "All" || classifyImsValue(before.orEmpty()) == valueType) &&
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
                            val stored = left[key].orEmpty()
                            val kind = classifyImsValue(stored)
                            val preview = previewChanges[key] ?: stored
                            if (kind == "Boolean") {
                                val enabled = preview.equals("true", true) || preview == "1"
                                Checkbox(
                                    checked = enabled,
                                    onCheckedChange = { checked ->
                                        val next = if (stored == "0" || stored == "1") {
                                            if (checked) "1" else "0"
                                        } else {
                                            checked.toString()
                                        }
                                        previewChanges = previewChanges + (key to next)
                                    },
                                )
                            } else {
                                OutlinedTextField(
                                    value = preview,
                                    onValueChange = { next ->
                                        previewChanges = previewChanges + (key to next.take(1000))
                                    },
                                    label = { Text("$kind preview") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            if (preview != stored) Text("Original: $stored")
                            Text("Preview only • not applied", style = MaterialTheme.typography.labelSmall)
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

private fun applyImsAttributePreviews(original: String, previews: Map<String, String>): String {
    require(original.length <= 2 * 1024 * 1024) { "XML too large" }
    val entries = parseImsXml("draft", original.toByteArray(Charsets.UTF_8))
    require(entries.error == null) { "Invalid source XML" }
    val keys = entries.entries.withOccurrenceKeys()
    require(previews.keys.all { it.contains("/@") && !it.contains("#") && it in keys }) {
        "Only unique XML attributes can be staged"
    }
    val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    factory.isXIncludeAware = false
    factory.isExpandEntityReferences = false
    val document = factory.newDocumentBuilder().parse(
        org.xml.sax.InputSource(java.io.StringReader(original)),
    )
    val seen = mutableSetOf<String>()
    fun visit(element: org.w3c.dom.Element, path: String) {
        val attrs = element.attributes
        for (index in 0 until attrs.length) {
            val attr = attrs.item(index)
            val key = "$path/@${attr.nodeName}"
            val value = previews[key] ?: continue
            require(seen.add(key)) { "Ambiguous repeated attribute" }
            attr.nodeValue = value
        }
        val children = element.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is org.w3c.dom.Element) visit(child, "$path/${child.tagName}")
        }
    }
    visit(document.documentElement, document.documentElement.tagName)
    require(seen.containsAll(previews.keys)) { "Some attributes were not found" }
    val transformer = javax.xml.transform.TransformerFactory.newInstance().newTransformer()
    val output = java.io.StringWriter()
    transformer.transform(
        javax.xml.transform.dom.DOMSource(document),
        javax.xml.transform.stream.StreamResult(output),
    )
    return output.toString()
}
private fun classifyImsValue(value: String): String = when {
    value.equals("true", true) || value.equals("false", true) -> "Boolean"
    value == "0" || value == "1" -> "Boolean"
    value.toDoubleOrNull() != null -> "Number"
    else -> "Text"
}

private fun checkImsRootBackend(): String {
    return runCatching {
        val process = ProcessBuilder("su", "-c", "id -u")
            .redirectErrorStream(true).start()
        val uid = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0 && uid == "0") {
            "Root backend: UID 0 verified • XML access read-only"
        } else {
            "Root backend unavailable (UID: ${uid.take(24)})"
        }
    }.getOrElse { "Root backend unavailable: ${it.javaClass.simpleName}" }
}

private fun discoverImsXml(): List<XmlDocument> {
    // Prioritize paths verified on SM-S948U1, Android 17. No live files are modified.
    val directories = listOf(
        "/data/user_de/0/com.sec.imsservice/shared_prefs",
        "/data/user/0/com.sec.imsservice/shared_prefs",
        "/optics/configs/carriers",
        "/prism/etc/carriers",
    )
    val found = mutableListOf<XmlDocument>()
    val seen = mutableSetOf<String>()
    val safePath = Regex("^/[a-zA-Z0-9_./-]+[.](xml|json)$")
    for (directory in directories) {
        val command = "find $directory -maxdepth 6 -type f " +
            "\\( -name '*.xml' -o -name '*.json' \\) 2>/dev/null | head -250"
        val listing = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true).start()
        val paths = listing.inputStream.bufferedReader().readLines()
        listing.waitFor()
        for (path in paths) {
            if (!safePath.matches(path) || !seen.add(path)) continue
            val name = path.substringAfterLast('/')
            val isIms = directory.contains("imsservice")
            val isCarrierCsc = path.contains("/conf/") &&
                (name == "customer.xml" || name == "cscfeature.xml" ||
                    name == "customer_carrier_feature.json")
            val isCarrierIms = name == "imsupdate.json"
            if (!isIms && !isCarrierCsc && !isCarrierIms) continue
            val document = runCatching {
                val proc = ProcessBuilder("su", "-c", "base64 $path")
                    .redirectErrorStream(true).start()
                val encoded = proc.inputStream.bufferedReader().readText().take(3_000_000)
                require(proc.waitFor() == 0) { "Root read denied" }
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                require(bytes.size <= 2 * 1024 * 1024) { "File exceeds inspection limit" }
                if (name.endsWith(".json")) {
                    parseCarrierJson(path, bytes)
                } else {
                    parseImsXml(path, bytes)
                }
            }.getOrElse {
                XmlDocument(path, emptyList(), "Read failed: " + it.javaClass.simpleName)
            }
            found.add(document)
            if (found.size >= 180) return found
        }
    }
    return found
}

private fun saveImsCache(context: android.content.Context, docs: List<XmlDocument>): Int {
    val dir = java.io.File(context.filesDir, "ims_csc_library")
    dir.mkdirs()
    var total = 0
    var count = 0
    val manifest = org.json.JSONArray()
    for (doc in docs) {
        if (doc.error != null || doc.originalXml.isEmpty()) continue
        val bytes = doc.originalXml.toByteArray(Charsets.UTF_8)
        if (bytes.size > 2_000_000 || total + bytes.size > 96_000_000) continue
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(doc.name.toByteArray()).joinToString("") { "%02x".format(it) }
        val filename = "$hash.dat"
        java.io.File(dir, filename).writeBytes(bytes)
        manifest.put(org.json.JSONObject().put("path", doc.name).put("file", filename))
        total += bytes.size
        count++
    }
    java.io.File(dir, "index.json").writeText(manifest.toString())
    return count
}

private fun loadImsCache(context: android.content.Context): List<XmlDocument> = runCatching {
    val dir = java.io.File(context.filesDir, "ims_csc_library")
    val index = java.io.File(dir, "index.json")
    if (!index.isFile) return@runCatching emptyList()
    val array = org.json.JSONArray(index.readText())
    (0 until array.length()).mapNotNull { i ->
        val record = array.getJSONObject(i)
        val path = record.getString("path")
        val filename = record.getString("file")
        if (!Regex("[0-9a-f]{64}[.]dat").matches(filename)) return@mapNotNull null
        val file = java.io.File(dir, filename)
        if (!file.isFile || file.length() > 2_000_000) return@mapNotNull null
        val bytes = file.readBytes()
        if (path.endsWith(".json")) parseCarrierJson(path, bytes) else parseImsXml(path, bytes)
    }
}.getOrDefault(emptyList())

private fun parseCarrierJson(name: String, bytes: ByteArray): XmlDocument = try {
    val text = bytes.toString(Charsets.UTF_8)
    val value = org.json.JSONTokener(text).nextValue()
    require(value is org.json.JSONObject || value is org.json.JSONArray) { "Expected JSON object or array" }
    val entries = mutableListOf<XmlEntry>()
    fun walk(node: Any?, path: String, depth: Int) {
        if (entries.size >= 10000 || depth > 20) return
        when (node) {
            is org.json.JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext() && entries.size < 10000) {
                    val key = keys.next()
                    walk(node.opt(key), "$path/$key", depth + 1)
                }
            }
            is org.json.JSONArray -> {
                for (i in 0 until minOf(node.length(), 1000)) {
                    walk(node.opt(i), "$path/$i", depth + 1)
                }
            }
            else -> entries.add(XmlEntry(path, node.toString().take(1000)))
        }
    }
    walk(value, "json", 0)
    XmlDocument(name, entries, originalXml = text)
} catch (e: Exception) {
    XmlDocument(name, emptyList(), "Invalid JSON: " + e.javaClass.simpleName)
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

/** Verify a separately provisioned TokenX UID-1000 route without requesting writes. */
private fun checkImsSystemBackend(): String = runCatching {
    val process = ProcessBuilder("sh", "-c", "command -v rish").redirectErrorStream(true).start()
    val command = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() != 0 || command.isBlank()) {
        "TokenX System UID 1000: client unavailable (not verified)"
    } else {
        "TokenX System UID 1000: client detected; connection not verified"
    }
}.getOrElse { "TokenX System UID 1000: unavailable" }

/** Detect installed Shizuku manager; runtime authorization is a separate check. */
private fun checkImsShizukuBackend(): String = runCatching {
    val process = ProcessBuilder("sh", "-c", "pm path moe.shizuku.privileged.api")
        .redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    if (process.waitFor() == 0 && output.contains("package:")) {
        "Shizuku: manager installed; binder authorization not verified"
    } else {
        "Shizuku: manager not detected"
    }
}.getOrElse { "Shizuku: detection unavailable" }
