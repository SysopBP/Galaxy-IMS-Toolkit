package dev.bluehouse.enablevolte

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.graphics.BitmapFactory
import android.content.pm.PackageManager
import android.os.Bundle
import android.telephony.SubscriptionInfo
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
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
import dev.bluehouse.enablevolte.ui.theme.EnableVoLTETheme
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
    var backgroundUri by remember { mutableStateOf(appearancePrefs.getString("image_uri", "") ?: "") }
    var showAppearance by remember { mutableStateOf(false) }
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                backgroundUri = uri.toString()
                backgroundMode = "photo"
                appearancePrefs.edit().putString("image_uri", backgroundUri).putString("background", "photo").apply()
            } catch (e: Exception) {
                Log.w("GalaxyIMS", "Background selection failed", e)
            }
        }
    }
    val navController = rememberNavController()
    val carrierModer = CarrierModer(context)
    val currentBackStackEntry by navController.currentBackStackEntryAsState()

    var subscriptions by rememberSaveable { mutableStateOf(listOf<SubscriptionInfo>()) }
    var navBuilder by remember {
        mutableStateOf<NavGraphBuilder.() -> Unit>({
            composable("ims-research", "Samsung IMS") {
                    GalaxyImsSettings()
                }
            composable("home", context.resources.getString(R.string.home)) {
                Home(navController)
            }
        })
    }

    fun generateInitialNavBuilder(): (NavGraphBuilder.() -> Unit) =
        {
            composable("ims-research", "Samsung IMS") { GalaxyImsSettings() }
            composable("home", "Home") {
                Home(navController)
            }
        }

    fun generateNavBuilder(): (NavGraphBuilder.() -> Unit) =
        {
            composable("ims-research", "Samsung IMS") {
                GalaxyImsSettings()
            }
            composable("home", context.resources.getString(R.string.home)) {
                Home(navController)
            }
            for (subscription in subscriptions) {
                navigation(startDestination = "config${subscription.subscriptionId}", route = "config${subscription.subscriptionId}root") {
                    composable("config${subscription.subscriptionId}", context.resources.getString(R.string.sim_config)) {
                        Config(navController, subscription.subscriptionId)
                    }
                    composable("config${subscription.subscriptionId}/dump", context.resources.getString(R.string.config_dump_viewer)) {
                        DumpedConfig(context, subscription.subscriptionId)
                    }
                    composable("config${subscription.subscriptionId}/edit", context.resources.getString(R.string.expert_mode)) {
                        Editor(subscription.subscriptionId)
                    }
                }
            }
        }

    fun loadApplication() {
        val shizukuStatus = checkShizukuPermission(0)
        try {
            when (shizukuStatus) {
                ShizukuStatus.GRANTED -> {
                    Log.d(dev.bluehouse.enablevolte.pages.TAG, "Shizuku granted")
                    subscriptions = carrierModer.subscriptions
                    navBuilder = generateNavBuilder()
                }
                ShizukuStatus.NOT_GRANTED -> {
                    Shizuku.addRequestPermissionResultListener { _, grantResult ->
                        if (grantResult == PackageManager.PERMISSION_GRANTED) {
                            Log.d(dev.bluehouse.enablevolte.pages.TAG, "Shizuku granted")
                            subscriptions = carrierModer.subscriptions
                            navBuilder = generateNavBuilder()
                        }
                    }
                }
                else -> {
                    subscriptions = listOf()
                    navBuilder = generateInitialNavBuilder()
                }
            }
        } catch (_: IllegalStateException) {
        }
    }

    OnLifecycleEvent { _, event ->
        if (event == Lifecycle.Event.ON_CREATE) {
            loadApplication()
        }
    }
    if (showAppearance) {
        AlertDialog(
            onDismissRequest = { showAppearance = false },
            title = { Text("Appearance") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Theme")
                    listOf("oneui" to "One UI", "miuix" to "MIUIX inspired").forEach { (value, label) ->
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
                        Icon(Icons.Filled.Palette, contentDescription = "Appearance", tint = MaterialTheme.colorScheme.onPrimary)
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary),
            )
        },
        bottomBar = {
            if (currentBackStackEntry?.destination?.depth?.let { it == 1 } == true) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(if (themeMode == "miuix") 36.dp else 30.dp),
                    color = if (themeMode == "miuix") Color(0xA8323444) else Color(0xB8222229),
                    tonalElevation = 8.dp,
                    shadowElevation = 12.dp,
                ) {
                    NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
                    val currentDestination = currentBackStackEntry?.destination
                    val items =
                        arrayListOf(
                            Screen("home", stringResource(R.string.home), Icons.Filled.Home),
                            Screen("ims-research", "Samsung IMS", Icons.Filled.Settings),
                        )
                    for (subscription in subscriptions) {
                        items.add(
                            Screen("config${subscription.subscriptionId}", subscription.uniqueName, Icons.Filled.Settings),
                        )
                    }

                    items.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = null) },
                            label = {
                                Text(screen.title)
                            },
                            selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.White,
                                selectedTextColor = Color.White,
                                unselectedIconColor = Color(0xFFB8B8BF),
                                unselectedTextColor = Color(0xFFB8B8BF),
                                indicatorColor = if (themeMode == "miuix") Color(0x996E8DFF) else Color(0x885A5A65),
                            ),
                            onClick = {
                                navController.navigate(screen.route) {
                                    // Pop up to the start destination of the graph to
                                    // avoid building up a large stack of destinations
                                    // on the back stack as users select items
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    // Avoid multiple copies of the same destination when
                                    // reselecting the same item
                                    launchSingleTop = true
                                    // Restore state when reselecting a previously selected item
                                    restoreState = true
                                }
                            },
                        )
                    }
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().background(
            when (backgroundMode) {
                "graphite" -> Color(0xFF22252C)
                "wine" -> Color(0xFF240D1B)
                else -> Color.Black
            },
        )) {
            if (backgroundMode == "photo" && backgroundUri.isNotEmpty()) {
                val bitmap = remember(backgroundUri) {
                    try {
                        context.contentResolver.openInputStream(Uri.parse(backgroundUri))?.use {
                            BitmapFactory.decodeStream(it)?.asImageBitmap()
                        }
                    } catch (_: Exception) { null }
                }
                if (bitmap != null) {
                    Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.35f)
                }
            }
            NavHost(navController, startDestination = "home", Modifier.padding(innerPadding), builder = navBuilder)
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
