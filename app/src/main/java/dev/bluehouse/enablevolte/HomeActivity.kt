package dev.bluehouse.enablevolte

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.telephony.SubscriptionInfo
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import dev.bluehouse.enablevolte.components.OnLifecycleEvent
import dev.bluehouse.enablevolte.pages.Config
import dev.bluehouse.enablevolte.pages.DumpedConfig
import dev.bluehouse.enablevolte.pages.Editor
import dev.bluehouse.enablevolte.pages.GalaxyImsSettings
import dev.bluehouse.enablevolte.pages.Home
import dev.bluehouse.enablevolte.pages.ImsXmlLab
import dev.bluehouse.enablevolte.pages.KernelSuModules
import dev.bluehouse.enablevolte.pages.SamsungImsProfiles
import dev.bluehouse.enablevolte.ui.theme.EnableVoLTETheme
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import java.lang.IllegalStateException

data class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector,
)

val NavDestination.depth: Int get() = this.route?.let { route -> route.count { it == '/' } + 1 } ?: 0

class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        HiddenApiBypass.addHiddenApiExemptions("L")
        HiddenApiBypass.addHiddenApiExemptions("I")

        setContent {
            EnableVoLTETheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    PixelIMSApp()
                }
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PixelIMSApp() {
    val context = LocalContext.current
    val appearancePrefs = remember(context) { context.getSharedPreferences("ims_appearance", 0) }
    var themeMode by remember { mutableStateOf(appearancePrefs.getString("theme", "oneui") ?: "oneui") }
    var backgroundMode by remember { mutableStateOf(appearancePrefs.getString("background", "black") ?: "black") }
    var photoOpacity by remember { mutableStateOf(appearancePrefs.getFloat("photo_opacity", 0.35f)) }
    var backgroundUri by remember { mutableStateOf(appearancePrefs.getString("image_uri", "") ?: "") }
    var showAppearance by remember { mutableStateOf(false) }
    val backgroundPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    backgroundUri = uri.toString()
                    backgroundMode = "photo"
                    appearancePrefs
                        .edit()
                        .putString("image_uri", backgroundUri)
                        .putString("background", "photo")
                        .apply()
                } catch (e: Exception) {
                    Log.w("GalaxyIMS", "Background selection failed", e)
                }
            }
        }
    val navController = rememberNavController()
    val carrierModer = CarrierModer(context)
    val currentBackStackEntry by navController.currentBackStackEntryAsState()

    var subscriptions by remember { mutableStateOf(listOf<SubscriptionInfo>()) }
    val appScope = androidx.compose.runtime.rememberCoroutineScope()
    var loadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun loadApplication() {
        loadJob?.cancel()
        loadJob =
            appScope.launch {
                val loaded =
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            RootBackend.probe()
                            if (checkShizukuPermission(0) == ShizukuStatus.GRANTED || RootBackend.needed()) {
                                carrierModer.subscriptions
                            } else {
                                emptyList()
                            }
                        }.getOrDefault(emptyList())
                    }
                subscriptions = loaded
            }
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        val received =
            Shizuku.OnBinderReceivedListener {
                dev.bluehouse.enablevolte.InterfaceCache.cache
                    .clear()
                runCatching { loadApplication() }
            }
        val dead =
            Shizuku.OnBinderDeadListener {
                dev.bluehouse.enablevolte.InterfaceCache.cache
                    .clear()
                subscriptions = emptyList()
            }
        val permission = Shizuku.OnRequestPermissionResultListener { _, _ -> runCatching { loadApplication() } }
        val simChanged =
            object : android.content.BroadcastReceiver() {
                override fun onReceive(
                    c: android.content.Context,
                    intent: Intent,
                ) {
                    dev.bluehouse.enablevolte.InterfaceCache.cache
                        .clear()
                    runCatching { loadApplication() }
                }
            }
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            simChanged,
            android.content.IntentFilter("android.intent.action.ACTION_SUBINFO_RECORD_UPDATED"),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose {
            Shizuku.removeBinderReceivedListener(received)
            Shizuku.removeBinderDeadListener(dead)
            Shizuku.removeRequestPermissionResultListener(permission)
            context.unregisterReceiver(simChanged)
        }
    }
    OnLifecycleEvent { _, event ->
        if (event == Lifecycle.Event.ON_CREATE || event == Lifecycle.Event.ON_RESUME) {
            runCatching { loadApplication() }
        }
    }
    if (showAppearance) {
        AlertDialog(
            onDismissRequest = { showAppearance = false },
            title = { Text("Appearance") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Photo brightness: ${(photoOpacity * 100).toInt()}%")
                    androidx.compose.material3.Slider(value = photoOpacity, onValueChange = {
                        photoOpacity = it
                        appearancePrefs.edit().putFloat("photo_opacity", it).apply()
                    }, valueRange = 0f..1f)
                    Text("Theme")
                    listOf(
                        "oneui" to "One UI",
                        "miuix" to "MIUIX style",
                        "glass" to "Glass",
                        "material" to "Material",
                    ).forEach { (value, label) ->
                        TextButton(onClick = {
                            themeMode = value
                            appearancePrefs.edit().putString("theme", value).apply()
                        }) { Text(if (themeMode == value) "✓ $label" else label) }
                    }
                    Text("Background")
                    listOf("black" to "Black", "graphite" to "Graphite", "wine" to "Wine red").forEach { (value, label) ->
                        TextButton(onClick = {
                            backgroundMode = value
                            appearancePrefs.edit().putString("background", value).apply()
                        }) { Text(if (backgroundMode == value) "✓ $label" else label) }
                    }
                    TextButton(onClick = { backgroundPicker.launch(arrayOf("image/*")) }) {
                        Text("Choose background image")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAppearance = false }) { Text("Done") } },
        )
    }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        currentBackStackEntry?.destination?.label?.toString() ?: stringResource(R.string.app_name),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                },
                navigationIcon = {
                    if (currentBackStackEntry?.destination?.depth?.let { it > 1 } == true) {
                        IconButton(onClick = {
                            navController.popBackStack()
                        }, colors = IconButtonDefaults.filledIconButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary)) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Go back",
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showAppearance = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Appearance", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    if (currentBackStackEntry?.destination?.route == "home") {
                        IconButton(onClick = {
                            loadApplication()
                        }, colors = IconButtonDefaults.filledIconButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary)) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Refresh contents",
                            )
                        }
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor =
                            if (themeMode ==
                                "glass"
                            ) {
                                Color(0x88434B60)
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                    ),
            )
        },
        bottomBar = {
            if (currentBackStackEntry?.destination?.depth?.let { it == 1 } == true) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(if (themeMode == "miuix") 36.dp else 30.dp),
                    color =
                        when (themeMode) {
                            "glass" -> Color(0x66434B60)
                            "miuix" -> Color(0xA8323444)
                            else -> Color(0xB8222229)
                        },
                    border =
                        if (themeMode ==
                            "glass"
                        ) {
                            androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.23f))
                        } else {
                            null
                        },
                    tonalElevation = 8.dp,
                    shadowElevation = 12.dp,
                ) {
                    NavigationBar(
                        modifier = Modifier.height(58.dp),
                        containerColor = Color.Transparent,
                        tonalElevation = 0.dp,
                    ) {
                        val currentDestination = currentBackStackEntry?.destination
                        val items =
                            arrayListOf(
                                Screen("home", stringResource(R.string.home), Icons.Filled.Home),
                                Screen("ims-research", "IMS", Icons.Filled.Settings),
                                Screen("xml_lab", "XML Lab", Icons.Filled.Refresh),
                                Screen("ksu_modules", "Modules", Icons.Filled.Build),
                            )
                        for (subscription in subscriptions) {
                            items.add(
                                Screen(
                                    "config${subscription.subscriptionId}",
                                    "SIM ${subscriptions.indexOf(subscription) + 1}",
                                    Icons.Filled.Settings,
                                ),
                            )
                        }

                        items.forEach { screen ->
                            NavigationBarItem(
                                icon = { Icon(screen.icon, contentDescription = null, modifier = Modifier.height(19.dp)) },
                                label = {
                                    Text(screen.title, fontSize = 9.sp, maxLines = 1)
                                },
                                selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                                colors =
                                    NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color.White,
                                        selectedTextColor = Color.White,
                                        unselectedIconColor = Color(0xFFB8B8BF),
                                        unselectedTextColor = Color(0xFFB8B8BF),
                                        indicatorColor =
                                            when (themeMode) {
                                                "glass" -> Color(0x668FA7CF)
                                                "miuix" -> Color(0x996E8DFF)
                                                else -> Color(0x885A5A65)
                                            },
                                    ),
                                onClick = {
                                    navController.navigate(screen.route) {
                                        // Pop up to the start destination of the graph to
                                        // avoid building up a large stack of destinations
                                        // on the back stack as users select items
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = false
                                        }
                                        // Avoid multiple copies of the same destination when
                                        // reselecting the same item
                                        launchSingleTop = true
                                        // Restore state when reselecting a previously selected item
                                        restoreState = false
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            Modifier.fillMaxSize().background(
                when (backgroundMode) {
                    "graphite" -> Color(0xFF22252C)
                    "wine" -> Color(0xFF240D1B)
                    else -> Color.Black
                },
            ),
        ) {
            if (backgroundMode == "photo" && backgroundUri.isNotEmpty()) {
                val bitmap by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, backgroundUri) {
                    value =
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                context.contentResolver.openInputStream(Uri.parse(backgroundUri))?.use {
                                    BitmapFactory.decodeStream(it, null, options)
                                }
                                var sample = 1
                                while (options.outWidth / sample > 2048 || options.outHeight / sample > 2048) sample *= 2
                                options.inJustDecodeBounds = false
                                options.inSampleSize = sample
                                context.contentResolver.openInputStream(Uri.parse(backgroundUri))?.use {
                                    BitmapFactory.decodeStream(it, null, options)?.asImageBitmap()
                                }
                            }.getOrNull()
                        }
                }
                bitmap?.let { image ->
                    Image(
                        image,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        alpha = photoOpacity,
                    )
                }
            }
            NavHost(navController, startDestination = "home", Modifier.padding(innerPadding)) {
                composable("home", "Home") { Home(navController) }
                composable("ims-research", "Samsung IMS") { GalaxyImsSettings() }
                composable("xml_lab", "IMS XML Lab") { ImsXmlLab() }
                composable("profiles0", "SIM 1 IMS profiles") { SamsungImsProfiles(0) }
                composable("profiles1", "SIM 2 IMS profiles") { SamsungImsProfiles(1) }
                composable(
                    "ksu_modules",
                    "KernelSU Modules",
                ) { KernelSuModules(openImsModuleBuilder = { navController.navigate("xml_lab") }) }
                for (subscription in subscriptions) {
                    navigation(
                        startDestination = "config${subscription.subscriptionId}",
                        route = "config${subscription.subscriptionId}root",
                    ) {
                        composable(
                            "config${subscription.subscriptionId}",
                            "SIM config",
                        ) { Config(navController, subscription.subscriptionId) }
                        composable(
                            "config${subscription.subscriptionId}/dump",
                            "Config dump",
                        ) { DumpedConfig(context, subscription.subscriptionId) }
                        composable("config${subscription.subscriptionId}/edit", "Expert mode") { Editor(subscription.subscriptionId) }
                    }
                }
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Preview
@Composable
fun PixelIMSAppPreview() {
    EnableVoLTETheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            PixelIMSApp()
        }
    }
}
