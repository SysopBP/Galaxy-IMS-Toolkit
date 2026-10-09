package dev.bluehouse.enablevolte.pages

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.compose.runtime.remember
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.core.net.toUri
import androidx.navigation.NavController
import dev.bluehouse.enablevolte.BuildConfig
import dev.bluehouse.enablevolte.CarrierModer
import dev.bluehouse.enablevolte.R
import dev.bluehouse.enablevolte.ShizukuStatus
import dev.bluehouse.enablevolte.SubscriptionModer
import dev.bluehouse.enablevolte.checkShizukuPermission
import dev.bluehouse.enablevolte.components.BooleanPropertyView
import dev.bluehouse.enablevolte.components.ClickablePropertyView
import dev.bluehouse.enablevolte.components.HeaderText
import dev.bluehouse.enablevolte.components.StringPropertyView
import dev.bluehouse.enablevolte.getLatestAppVersion
import dev.bluehouse.enablevolte.uniqueName
import net.swiftzer.semver.SemVer
import rikka.shizuku.Shizuku

// Galaxy IMS alpha: refresh CI after fork Actions activation.
const val TAG = "HomeActivity:Home"

@Suppress("ktlint:standard:function-naming")
@Composable
fun Home(navController: NavController) {
    val carrierModer = CarrierModer(LocalContext.current)
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var shizukuEnabled by rememberSaveable { mutableStateOf(false) }
    var shizukuGranted by rememberSaveable { mutableStateOf(false) }
    var subscriptions by rememberSaveable { mutableStateOf(listOf<SubscriptionInfo>()) }
    var deviceIMSEnabled by rememberSaveable { mutableStateOf(false) }

    var isIMSRegistered by rememberSaveable { mutableStateOf(listOf<Boolean>()) }
    var newerVersion by rememberSaveable { mutableStateOf("") }

    fun loadFlags() {
        shizukuGranted = true
        subscriptions = carrierModer.subscriptions
        deviceIMSEnabled = carrierModer.deviceSupportsIMS

        if (subscriptions.isNotEmpty() && deviceIMSEnabled) {
            isIMSRegistered = subscriptions.map { SubscriptionModer(context, it.subscriptionId).isIMSRegistered }
        }
    }

    LaunchedEffect(Unit) {
        try {
            when (checkShizukuPermission(0)) {
                ShizukuStatus.GRANTED -> {
                    shizukuEnabled = true
                    loadFlags()
                }
                ShizukuStatus.NOT_GRANTED -> {
                    shizukuEnabled = true
                    Shizuku.addRequestPermissionResultListener { _, grantResult ->
                        if (grantResult == PackageManager.PERMISSION_GRANTED) {
                            loadFlags()
                        }
                    }
                }
                else -> {
                    shizukuEnabled = false
                    shizukuGranted = false
                }
            }
        } catch (e: IllegalStateException) {
            shizukuEnabled = false
        }
        getLatestAppVersion {
            Log.d(TAG, "Fetched version $it")
            try {
                val latest = SemVer.parse(it.removePrefix("v"))
                val current = SemVer.parse(BuildConfig.VERSION_NAME.removePrefix("v"))
                if (latest > current) newerVersion = it
            } catch (e: Exception) { Log.w(TAG, "Invalid release version", e) }
        }
    }

    Column(
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp).verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Galaxy overview", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Device and IMS status", color = Color(0xFFCDD3DD))
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xAA242A35)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("DEVICE", color = Color(0xFFB7C4DA), fontWeight = FontWeight.SemiBold)
                Text(Build.MODEL, style = androidx.compose.material3.MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }}  •  Android ${Build.VERSION.RELEASE}")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Security patch", color = Color(0xFFBAC3D0))
                    Text(Build.VERSION.SECURITY_PATCH)
                }
            }
        }
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xAA242A35)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("CONNECTIVITY", color = Color(0xFFB7C4DA), fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Shizuku service")
                    Text(if (shizukuEnabled) "Connected" else "Not connected", color = if (shizukuEnabled) Color(0xFF9ADBB6) else Color(0xFFFFC48C))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("App permission")
                    Text(if (shizukuGranted) "Granted" else "Not granted", color = if (shizukuGranted) Color(0xFF9ADBB6) else Color(0xFFFFC48C))
                }
                Text("TokenX UID 1000 is separate from app authorization.", color = Color(0xFFB5BECA), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            }
        }
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xAA242A35)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("IMS & SIM", color = Color(0xFFB7C4DA), fontWeight = FontWeight.SemiBold)
                Text("Active SIMs: ${subscriptions.size}")
                Text("Device IMS: ${if (deviceIMSEnabled) "Supported" else "Not verified"}")
                subscriptions.forEachIndexed { index, sub ->
                    val registered = isIMSRegistered.getOrNull(index)
                    Text("${sub.uniqueName}: ${when (registered) { true -> "Registered"; false -> "Not registered"; null -> "Unknown" }}")
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Galaxy IMS Toolkit", fontWeight = FontWeight.SemiBold)
            Text(BuildConfig.VERSION_NAME, color = Color(0xFFBBC4D0))
        }
        if (newerVersion.isNotEmpty()) {
            Text("Update available: $newerVersion", color = Color(0xFFB7C4DA))
        }
    }
}
