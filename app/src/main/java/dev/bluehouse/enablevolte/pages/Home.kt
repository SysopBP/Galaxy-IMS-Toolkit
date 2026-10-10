package dev.bluehouse.enablevolte.pages

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
    val rootReady by dev.bluehouse.enablevolte.RootBackend.ready
        .collectAsState()
    val revision by dev.bluehouse.enablevolte.CarrierWrites.revision
        .collectAsState()
    val carrierModer = CarrierModer(LocalContext.current)
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var shizukuEnabled by remember { mutableStateOf(false) }
    var phoneStateGranted by remember { mutableStateOf(false) }
    var shizukuGranted by remember { mutableStateOf(false) }
    var subscriptions by remember { mutableStateOf(listOf<SubscriptionInfo>()) }
    var deviceIMSEnabled by remember { mutableStateOf(false) }

    var isIMSRegistered by remember { mutableStateOf(listOf<Boolean>()) }
    var newerVersion by remember { mutableStateOf("") }
    var permissionMessage by remember { mutableStateOf("") }
    var showPermissions by remember { mutableStateOf(false) }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            phoneStateGranted =
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
            permissionMessage =
                if (phoneStateGranted) "Android phone-state permission granted" else "Phone-state permission denied; review app settings."
        }

    fun requestAvailablePermissions() {
        val missing =
            listOf(Manifest.permission.READ_PHONE_STATE).filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            permissionMessage = "Android phone-state permission already granted."
        }
        try {
            if (checkShizukuPermission(0) == ShizukuStatus.NOT_GRANTED) {
                if (Shizuku.pingBinder()) {
                    permissionMessage = "Requesting Shizuku authorization…"
                    Shizuku.requestPermission(7001)
                } else {
                    permissionMessage = "Shizuku service is not connected."
                }
            }
        } catch (e: Exception) {
            permissionMessage = "Shizuku unavailable: " + e.javaClass.simpleName
        }
    }

    fun loadFlags() {
        shizukuEnabled = Shizuku.pingBinder()
        shizukuGranted = shizukuEnabled && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        phoneStateGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        subscriptions = carrierModer.subscriptions
        deviceIMSEnabled = carrierModer.deviceSupportsIMS

        if (subscriptions.isNotEmpty() && deviceIMSEnabled) {
            isIMSRegistered = subscriptions.map { SubscriptionModer(context, it.subscriptionId).isIMSRegistered }
        }
    }

    DisposableEffect(Unit) {
        val permissionListener =
            Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
                if (requestCode == 7001) {
                    shizukuGranted = grantResult == PackageManager.PERMISSION_GRANTED
                    permissionMessage = if (shizukuGranted) "Shizuku authorization granted" else "Shizuku authorization denied"
                    if (shizukuGranted) runCatching { loadFlags() }
                }
            }
        val binderListener =
            Shizuku.OnBinderReceivedListener {
                shizukuEnabled = true
                runCatching { loadFlags() }
            }
        val deadListener =
            Shizuku.OnBinderDeadListener {
                shizukuEnabled = false
                shizukuGranted = false
            }
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(deadListener)
        onDispose {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
            Shizuku.removeBinderReceivedListener(binderListener)
            Shizuku.removeBinderDeadListener(deadListener)
        }
    }

    LaunchedEffect(rootReady, revision) {
        runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                loadFlags()
            }
        }.onFailure { Log.w(TAG, "Permission status refresh failed", it) }
        getLatestAppVersion {
            Log.d(TAG, "Fetched version $it")
            try {
                val latest = SemVer.parse(it.removePrefix("v"))
                val current = SemVer.parse(BuildConfig.VERSION_NAME.removePrefix("v"))
                if (latest > current) newerVersion = it
            } catch (e: Exception) {
                Log.w(TAG, "Invalid release version", e)
            }
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
                    Text(
                        if (shizukuEnabled) "Connected" else "Not connected",
                        color = if (shizukuEnabled) Color(0xFF9ADBB6) else Color(0xFFFFC48C),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("App permission")
                    Text(
                        if (shizukuGranted) "Granted" else "Not granted",
                        color = if (shizukuGranted) Color(0xFF9ADBB6) else Color(0xFFFFC48C),
                    )
                }
                Text(
                    "TokenX UID 1000 is separate from app authorization.",
                    color = Color(0xFFB5BECA),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
            }
        }
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xAA242A35)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("PERMISSION MANAGER", color = Color(0xFFB7C4DA), fontWeight = FontWeight.SemiBold)
                Text("Request Android phone-state access and Shizuku authorization. IMS privileges require separate verification.")
                Button(
                    onClick = { requestAvailablePermissions() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled =
                        !phoneStateGranted || !shizukuGranted,
                ) {
                    Text(
                        if (phoneStateGranted &&
                            shizukuGranted
                        ) {
                            "Available permissions granted"
                        } else {
                            "Enable available permissions"
                        },
                        color = Color.White,
                    )
                }
                OutlinedButton(onClick = { showPermissions = !showPermissions }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showPermissions) "Hide permission details" else "View permission details")
                }
                if (showPermissions) {
                    val granted = phoneStateGranted
                    Text("Phone state: " + if (granted) "Granted" else "Not granted")
                    Text("Shizuku authorization: " + if (shizukuGranted) "Granted" else "Not granted")
                    Text(
                        dev.bluehouse.enablevolte.BackendStatus
                            .describe(),
                    )
                    Text("Carrier write capability: checked and read back for each operation")
                }
                if (permissionMessage.isNotBlank()) Text(permissionMessage)
            }
        }
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xAA242A35)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("IMS & SIM", color = Color(0xFFB7C4DA), fontWeight = FontWeight.SemiBold)
                Text(
                    if (shizukuGranted ||
                        dev.bluehouse.enablevolte.RootBackend.available
                    ) {
                        "App-visible SIMs: ${subscriptions.size}"
                    } else {
                        "App-visible SIMs: Unknown (authorization required)"
                    },
                )
                Text("Device IMS: ${if (deviceIMSEnabled) "Supported" else "Not verified"}")
                subscriptions.forEachIndexed { index, sub ->
                    val registered = isIMSRegistered.getOrNull(index)
                    Text(
                        "${sub.uniqueName}: ${when (registered) {
                            true -> "Registered"
                            false -> "Not registered"
                            null -> "Unknown"
                        }}",
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Galaxy IMS Toolkit", fontWeight = FontWeight.SemiBold)
            Text(BuildConfig.VERSION_NAME, color = Color(0xFFBBC4D0))
        }
        OutlinedButton(
            onClick = { navController.navigate("profiles0") },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("SIM 1 stored IMS profiles") }
        OutlinedButton(
            onClick = { navController.navigate("profiles1") },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("SIM 2 stored IMS profiles") }
        if (newerVersion.isNotEmpty()) {
            Text("Update available: $newerVersion", color = Color(0xFFB7C4DA))
        }
    }
}
