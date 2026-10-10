package dev.bluehouse.enablevolte.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val IMS_PREFS = "/data/user_de/0/com.sec.imsservice/shared_prefs"
private val SWITCH_NAMES = listOf("ims", "volte", "vowifi", "mmtel", "rcs", "vilte", "video", "datachannel")

private fun rootRead(file: String): String {
    require(Regex("^(imsprofile|imsswitch|imsconfig)_[01]\\.xml$").matches(file))
    return dev.bluehouse.enablevolte.RootCommands.run("cat $IMS_PREFS/$file").requireSuccess()
}

private fun digest(input: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(16)

private fun switches(xml: String): List<String> {
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    val document = factory.newDocumentBuilder().parse(xml.byteInputStream())
    val entries = document.getElementsByTagName("*")
    return (0 until entries.length)
        .mapNotNull { index ->
        val node = entries.item(index)
        val attributes = node.attributes ?: return@mapNotNull null
        val name = attributes.getNamedItem("name")?.nodeValue ?: return@mapNotNull null
        if (SWITCH_NAMES.none { name.contains(it, ignoreCase = true) }) return@mapNotNull null
        val value = attributes.getNamedItem("value")?.nodeValue ?: node.textContent.orEmpty()
        "$name: ${value.take(40)}"
        }
        .distinct()
        .sorted()
}

private fun snapshot(slot: Int): String {
    val profile = rootRead("imsprofile_$slot.xml")
    val switch = rootRead("imsswitch_$slot.xml")
    val otherSlot = 1 - slot
    val otherProfile = runCatching { rootRead("imsprofile_$otherSlot.xml") }.getOrNull()
    val otherSwitch = runCatching { rootRead("imsswitch_$otherSlot.xml") }.getOrNull()
    // Do not expose full profiles, provisioning identities, or raw configuration XML.
    return buildString {
        appendLine("Slot $slot • stored Samsung IMS settings")
        appendLine("Profile comparison: ${if (otherProfile == null) "other SIM unavailable" else if (profile == otherProfile) "identical" else "different"}")
        appendLine("Switch comparison: ${if (otherSwitch == null) "other SIM unavailable" else if (switch == otherSwitch) "identical" else "different"}")
        appendLine("Profile fingerprint: ${digest(profile)}")
        appendLine("Switch fingerprint: ${digest(switch)}")
        appendLine()
        appendLine("Stored IMS switches (not live registration):")
        val values = switches(switch)
        if (values.isEmpty()) appendLine("No recognizable switch entries") else values.forEach { appendLine(it) }
        appendLine()
        appendLine("Live registration and carrier provisioning are not verified here.")
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
fun SamsungImsProfiles(slot: Int) {
    var result by remember(slot) { mutableStateOf("Reading protected IMS configuration…") }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(slot, refresh) {
        result = withContext(Dispatchers.IO) {
            try {
                snapshot(slot)
            } catch (e: Exception) {
                "Unable to read IMS configuration. Verify KernelSU root permission for this app. ${e.javaClass.simpleName}"
            }
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Text("Samsung IMS Profiles", style = MaterialTheme.typography.headlineSmall)
        Text("Read-only • SIM slot $slot", style = MaterialTheme.typography.labelMedium)
        Button(onClick = { refresh++ }) { Text("Refresh") }
        Text(result, style = MaterialTheme.typography.bodyMedium)
    }
}
