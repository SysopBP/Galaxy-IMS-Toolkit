package dev.bluehouse.enablevolte.pages

import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.telephony.CarrierConfigManager
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.navigation.NavController
import dev.bluehouse.enablevolte.CarrierModer
import dev.bluehouse.enablevolte.R
import dev.bluehouse.enablevolte.ShizukuStatus
import dev.bluehouse.enablevolte.SubscriptionModer
import dev.bluehouse.enablevolte.checkShizukuPermission
import dev.bluehouse.enablevolte.components.BooleanPropertyView
import dev.bluehouse.enablevolte.components.ClickablePropertyView
import dev.bluehouse.enablevolte.components.HeaderText
import dev.bluehouse.enablevolte.components.InfiniteLoadingDialog
import dev.bluehouse.enablevolte.components.RadioSelectPropertyView
import dev.bluehouse.enablevolte.components.UserAgentPropertyView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.lang.IllegalStateException

@Suppress("ktlint:standard:function-naming")
@Composable
fun Config(
    navController: NavController,
    subId: Int,
) {
    val TAG = "HomeActivity:Config"

    val moder = SubscriptionModer(LocalContext.current, subId)
    val carrierModer = CarrierModer(LocalContext.current)
    val carrierName = runCatching { moder.carrierName }.getOrNull()
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val cannotFindKeyText = stringResource(R.string.cannot_find_key)
    var readError by rememberSaveable { mutableStateOf("") }
    var resumeRevision by rememberSaveable { mutableIntStateOf(0) }
    dev.bluehouse.enablevolte.components.OnLifecycleEvent { _, event ->
        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeRevision++
    }
    var configurable by rememberSaveable { mutableStateOf(false) }
    var voLTEEnabled by rememberSaveable { mutableStateOf(false) }
    var voNREnabled by rememberSaveable { mutableStateOf(false) }
    var crossSIMEnabled by rememberSaveable { mutableStateOf(false) }
    var voWiFiEnabled by rememberSaveable { mutableStateOf(false) }
    var voWiFiEnabledWhileRoaming by rememberSaveable { mutableStateOf(false) }
    var showIMSinSIMInfo by rememberSaveable { mutableStateOf(false) }
    var allowAddingAPNs by rememberSaveable { mutableStateOf(false) }
    var showVoWifiMode by rememberSaveable { mutableStateOf(false) }
    var showVoWifiRoamingMode by rememberSaveable { mutableStateOf(false) }
    var wfcSpnFormatIndex by rememberSaveable { mutableIntStateOf(0) }
    var showVoWifiIcon by rememberSaveable { mutableStateOf(false) }
    var alwaysDataRATIcon by rememberSaveable { mutableStateOf(false) }
    var supportWfcWifiOnly by rememberSaveable { mutableStateOf(false) }
    var vtEnabled by rememberSaveable { mutableStateOf(false) }
    var ssOverUtEnabled by rememberSaveable { mutableStateOf(false) }
    var ssOverCDMAEnabled by rememberSaveable { mutableStateOf(false) }
    var show4GForLteEnabled by rememberSaveable { mutableStateOf(false) }
    var hideEnhancedDataIconEnabled by rememberSaveable { mutableStateOf(false) }
    var is4GPlusEnabled by rememberSaveable { mutableStateOf(false) }
    var configuredUserAgent: String? by rememberSaveable { mutableStateOf("") }
    var configurableItems by rememberSaveable { mutableStateOf<Map<String, String>>(mapOf()) }
    var reversedConfigurableItems by rememberSaveable { mutableStateOf<Map<String, String>>(mapOf()) }
    var loading by rememberSaveable { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val simSlotIndex = runCatching { moder.simSlotIndex }.getOrDefault(-1)
    val perform =
        dev.bluehouse.enablevolte.components
            .rememberPrivilegedAction()
    var snapshotMessage by rememberSaveable { mutableStateOf("") }
    val snapshotPicker =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts
                .OpenDocument(),
        ) { uri ->
            if (uri != null) {
                perform {
                    val text =
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(4096)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size() + count <= 4 * 1024 * 1024) { "Snapshot too large" }
                                output.write(buffer, 0, count)
                            }
                            output.toString("UTF-8")
                        } ?: error("Cannot read snapshot")
                    moder.restoreValues(
                        dev.bluehouse.enablevolte.CarrierBackup
                            .import(context, subId, text),
                    )
                    snapshotMessage = "Imported carrier snapshot restored and readback verified"
                }
            }
        }
    val writeRevision by dev.bluehouse.enablevolte.CarrierWrites.revision
        .collectAsState()
    // Distinct identities: System for carrier writes; Root for module/file operations.
    var rootAvailable by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        rootAvailable =
            withContext(Dispatchers.IO) {
                try {
                    val process = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
                    val completed = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                    if (!completed) {
                        process.destroyForcibly()
                        false
                    } else {
                        process.exitValue() == 0 &&
                            process.inputStream.bufferedReader().use { it.readText().trim() } == "0"
                    }
                } catch (_: Exception) {
                    false
                }
            }
    }

    fun loadFlags() {
        Log.d(TAG, "loadFlags")
        configurableItems =
            listOf(CarrierConfigManager::class.java, *CarrierConfigManager::class.java.declaredClasses)
                .map {
                    it.declaredFields.filter { field ->
                        field.name != "KEY_PREFIX" && field.name.startsWith("KEY_")
                    }
                }.flatten()
                .associate { field -> field.name to field.get(field) as String }
        reversedConfigurableItems = configurableItems.entries.associate { (k, v) -> v to k }
        voLTEEnabled = moder.isVoLteConfigEnabled
        voNREnabled = VERSION.SDK_INT >= VERSION_CODES.UPSIDE_DOWN_CAKE && moder.isVoNrConfigEnabled
        crossSIMEnabled = moder.isCrossSIMConfigEnabled
        voWiFiEnabled = moder.isVoWifiConfigEnabled
        voWiFiEnabledWhileRoaming = moder.isVoWifiWhileRoamingEnabled
        showIMSinSIMInfo = VERSION.SDK_INT >= VERSION_CODES.R && moder.showIMSinSIMInfo
        allowAddingAPNs = moder.allowAddingAPNs
        showVoWifiMode = VERSION.SDK_INT >= VERSION_CODES.R && moder.showVoWifiMode
        showVoWifiRoamingMode = VERSION.SDK_INT >= VERSION_CODES.R && moder.showVoWifiRoamingMode
        wfcSpnFormatIndex = moder.wfcSpnFormatIndex
        showVoWifiIcon = moder.showVoWifiIcon
        alwaysDataRATIcon = VERSION.SDK_INT >= VERSION_CODES.R && moder.alwaysDataRATIcon
        supportWfcWifiOnly = moder.supportWfcWifiOnly
        vtEnabled = moder.isVtConfigEnabled
        ssOverUtEnabled = moder.ssOverUtEnabled
        ssOverCDMAEnabled = moder.ssOverCDMAEnabled
        show4GForLteEnabled = VERSION.SDK_INT >= VERSION_CODES.R && moder.isShow4GForLteEnabled
        hideEnhancedDataIconEnabled = VERSION.SDK_INT >= VERSION_CODES.R && moder.isHideEnhancedDataIconEnabled
        is4GPlusEnabled = moder.is4GPlusEnabled
        configuredUserAgent =
            try {
                moder.userAgentConfig
            } catch (e: java.lang.NullPointerException) {
                null
            }
    }

    LaunchedEffect(subId, writeRevision, resumeRevision) {
        loading = true
        readError = ""
        configurable = false
        try {
            val loaded =
                withContext(Dispatchers.IO) {
                    if (subId >= 0 &&
                        (
                            checkShizukuPermission(0) == ShizukuStatus.GRANTED ||
                                dev.bluehouse.enablevolte.RootBackend
                                    .needed()
                        ) &&
                        carrierModer.deviceSupportsIMS
                    ) {
                        loadFlags()
                        true
                    } else {
                        false
                    }
                }
            configurable = loaded
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unable to load SIM $subId", e)
            readError = "${e.javaClass.simpleName}: ${e.message ?: "Carrier settings unavailable"}"
        } catch (e: LinkageError) {
            Log.e(TAG, "Unsupported firmware API for SIM $subId", e)
            readError = "Firmware API unavailable: ${e.javaClass.simpleName}"
        } finally {
            loading = false
        }
    }

    if (loading) {
        InfiniteLoadingDialog()
    } else {
        Column(modifier = Modifier.padding(Dp(16f)).verticalScroll(scrollState)) {
            val systemWriteReady =
                try {
                    Shizuku.pingBinder() &&
                        Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                        (Shizuku.getUid() == android.os.Process.SYSTEM_UID || Shizuku.getUid() == 0)
                } catch (_: Exception) {
                    false
                } ||
                    dev.bluehouse.enablevolte.RootBackend
                        .needed()
            HeaderText(text = "Modification backends")
            Text("Root UID 0: " + if (rootAvailable) "Available for KernelSU and protected files" else "Unavailable")
            Text(
                dev.bluehouse.enablevolte.BackendStatus
                    .describe(),
            )
            Text("SIM ${simSlotIndex + 1} · subscription ID $subId")
            Text("Root and System binders may attempt carrier writes. Firmware permission checks still apply.")
            if (!systemWriteReady || !configurable) {
                if (readError.isNotEmpty()) Text(readError)
                HeaderText(text = "Privileged backend required")
                Text(
                    "Carrier toggles are disabled until this app is authorized through a verified " +
                        "standalone root worker or Root/System Shizuku binder. " +
                        "A separate terminal session " +
                        "does not grant this app system write access.",
                )
                Text("Read-only IMS diagnostics remain available from the Samsung IMS tab.")
            } else {
                HeaderText(text = stringResource(R.string.feature_toggles))
                BooleanPropertyView(label = stringResource(R.string.enable_volte), toggled = voLTEEnabled) {
                    voLTEEnabled =
                        if (voLTEEnabled) {
                            moder.updateCarrierConfig(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL, false)
                            false
                        } else {
                            moder.updateCarrierConfig(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL, true)
                            moder.restartIMSRegistration()
                            true
                        }
                }

                BooleanPropertyView(
                    label = stringResource(R.string.enable_vonr),
                    toggled = voNREnabled,
                    minSdk = VERSION_CODES.UPSIDE_DOWN_CAKE,
                ) {
                    if (VERSION.SDK_INT >= VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        voNREnabled =
                            if (voNREnabled) {
                                moder.updateCarrierConfig(CarrierConfigManager.KEY_VONR_ENABLED_BOOL, false)
                                moder.updateCarrierConfig(CarrierConfigManager.KEY_VONR_SETTING_VISIBILITY_BOOL, false)
                                false
                            } else {
                                moder.updateCarrierConfig(CarrierConfigManager.KEY_VONR_ENABLED_BOOL, true)
                                moder.updateCarrierConfig(CarrierConfigManager.KEY_VONR_SETTING_VISIBILITY_BOOL, true)
                                moder.restartIMSRegistration()
                                true
                            }
                    }
                }

                BooleanPropertyView(
                    label = stringResource(R.string.enable_crosssim),
                    toggled = crossSIMEnabled,
                    minSdk = VERSION_CODES.TIRAMISU,
                ) {
                    if (VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) {
                        crossSIMEnabled =
                            if (crossSIMEnabled) {
                                moder.updateCarrierConfig(CarrierConfigManager.KEY_CARRIER_CROSS_SIM_IMS_AVAILABLE_BOOL, false)
                                moder.updateCarrierConfig(
                                    CarrierConfigManager.KEY_ENABLE_CROSS_SIM_CALLING_ON_OPPORTUNISTIC_DATA_BOOL,
                                    false,
                                )
                                false
                            } else {
                                moder.updateCarrierConfig(CarrierConfigManager.KEY_CARRIER_CROSS_SIM_IMS_AVAILABLE_BOOL, true)
                                moder.updateCarrierConfig(
                                    CarrierConfigManager.KEY_ENABLE_CROSS_SIM_CALLING_ON_OPPORTUNISTIC_DATA_BOOL,
                                    true,
                                )
                                moder.restartIMSRegistration()
                                true
                            }
                    }
                }
                BooleanPropertyView(label = stringResource(R.string.enable_vowifi), toggled = voWiFiEnabled) {
                    voWiFiEnabled =
                        if (voWiFiEnabled) {
                            moder.updateCarrierConfig(CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL, false)
                            false
                        } else {
                            moder.updateCarrierConfig(CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL, true)
                            moder.restartIMSRegistration()
                            true
                        }
                }
                BooleanPropertyView(label = stringResource(R.string.enable_vowifi_while_roamed), toggled = voWiFiEnabledWhileRoaming) {
                    voWiFiEnabledWhileRoaming =
                        if (voWiFiEnabledWhileRoaming) {
                            moder.updateCarrierConfig(CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_ROAMING_ENABLED_BOOL, false)
                            false
                        } else {
                            moder.updateCarrierConfig(CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_ROAMING_ENABLED_BOOL, true)
                            moder.restartIMSRegistration()
                            true
                        }
                }
                if (VERSION.SDK_INT >= VERSION_CODES.Q) {
                    BooleanPropertyView(
                        label = stringResource(R.string.enable_ss_over_ut),
                        toggled = ssOverUtEnabled,
                    ) {
                        ssOverUtEnabled =
                            if (ssOverUtEnabled) {
                                moder.updateCarrierConfig(
                                    CarrierConfigManager.KEY_CARRIER_SUPPORTS_SS_OVER_UT_BOOL,
                                    false,
                                )
                                false
                            } else {
                                moder.updateCarrierConfig(
                                    CarrierConfigManager.KEY_CARRIER_SUPPORTS_SS_OVER_UT_BOOL,
                                    true,
                                )
                                moder.restartIMSRegistration()
                                true
                            }
                    }
                }
                BooleanPropertyView(label = stringResource(R.string.enable_ss_over_cdma), toggled = ssOverCDMAEnabled) {
                    ssOverCDMAEnabled =
                        if (ssOverCDMAEnabled) {
                            moder.updateCarrierConfig(CarrierConfigManager.KEY_SUPPORT_SS_OVER_CDMA_BOOL, false)
                            false
                        } else {
                            moder.updateCarrierConfig(CarrierConfigManager.KEY_SUPPORT_SS_OVER_CDMA_BOOL, true)
                            moder.restartIMSRegistration()
                            true
                        }
                }
                BooleanPropertyView(label = stringResource(R.string.enable_video_calling_vt), toggled = vtEnabled) {
                    vtEnabled =
                        if (vtEnabled) {
                            moder.updateCarrierConfig(CarrierConfigManager.KEY_CARRIER_VT_AVAILABLE_BOOL, false)
                            false
                        } else {
                            moder.updateCarrierConfig(CarrierConfigManager.KEY_CARRIER_VT_AVAILABLE_BOOL, true)
                            moder.restartIMSRegistration()
                            true
                        }
                }
                if (VERSION.SDK_INT >= VERSION_CODES.Q) {
                    BooleanPropertyView(
                        label = stringResource(R.string.enable_enhanced_4g_lte_plus),
                        toggled = is4GPlusEnabled,
                    ) {
                        is4GPlusEnabled =
                            if (is4GPlusEnabled) {
                                moder.updateCarrierConfig(
                                    CarrierConfigManager.KEY_EDITABLE_ENHANCED_4G_LTE_BOOL,
                                    false,
                                )
                                moder.updateCarrierConfig(
                                    CarrierConfigManager.KEY_ENHANCED_4G_LTE_ON_BY_DEFAULT_BOOL,
                                    false,
                                )
                                moder.updateCarrierConfig(
                                    CarrierConfigManager.KEY_HIDE_ENHANCED_4G_LTE_BOOL,
                                    true,
                                )
                                false
                            } else {
                                moder.updateCarrierConfig(
                                    CarrierConfigManager.KEY_EDITABLE_ENHANCED_4G_LTE_BOOL,
                                    true,
                                )
                                moder.updateCarrierConfig(
                                    CarrierConfigManager.KEY_ENHANCED_4G_LTE_ON_BY_DEFAULT_BOOL,
                                    true,
                                )
                                moder.updateCarrierConfig(
                                    CarrierConfigManager.KEY_HIDE_ENHANCED_4G_LTE_BOOL,
                                    false,
                                )
                                true
                            }
                    }
                }
                BooleanPropertyView(label = stringResource(R.string.allow_adding_apns), toggled = allowAddingAPNs) {
                    allowAddingAPNs =
                        if (allowAddingAPNs) {
                            moder.updateCarrierConfig(CarrierConfigManager.KEY_ALLOW_ADDING_APNS_BOOL, false)
                            false
                        } else {
                            moder.updateCarrierConfig(CarrierConfigManager.KEY_ALLOW_ADDING_APNS_BOOL, true)
                            true
                        }
                }

                HeaderText(text = stringResource(R.string.string_values))
                UserAgentPropertyView(label = stringResource(R.string.user_agent), value = configuredUserAgent) {
                    moder.updateCarrierConfig(moder.KEY_IMS_USER_AGENT, it)
                    configuredUserAgent = it
                }

                HeaderText(text = stringResource(R.string.cosmetic_toggles))
                if (VERSION.SDK_INT >= VERSION_CODES.R) {
                    BooleanPropertyView(
                        label = stringResource(R.string.show_vowifi_preference_in_settings),
                        toggled = showVoWifiMode,
                        minSdk = VERSION_CODES.R,
                    ) {
                        showVoWifiMode =
                            if (showVoWifiMode) {
                                moder.updateCarrierConfig(
                                    CarrierConfigManager.KEY_EDITABLE_WFC_MODE_BOOL,
                                    false,
                                )
                                false
                            } else {
                                moder.updateCarrierConfig(
                                    CarrierConfigManager.KEY_EDITABLE_WFC_MODE_BOOL,
                                    true,
                                )
                                moder.restartIMSRegistration()
                                true
                            }
                    }
                }
                if (VERSION.SDK_INT >= VERSION_CODES.R) {
                    BooleanPropertyView(
                        label = stringResource(R.string.show_vowifi_roaming_preference_in_settings),
                        toggled = showVoWifiRoamingMode,
                        minSdk = VERSION_CODES.R,
                    ) {
                        showVoWifiRoamingMode =
                            if (showVoWifiRoamingMode) {
                                moder.updateCarrierConfig(
                                    CarrierConfigManager.KEY_EDITABLE_WFC_ROAMING_MODE_BOOL,
                                    false,
                                )
                                false
                            } else {
                                moder.updateCarrierConfig(
                                    CarrierConfigManager.KEY_EDITABLE_WFC_ROAMING_MODE_BOOL,
                                    true,
                                )
                                moder.restartIMSRegistration()
                                true
                            }
                    }
                }
                RadioSelectPropertyView(
                    label = stringResource(R.string.wi_fi_calling_carrier_name_format),
                    values =
                        arrayOf(
                            "%s".format(carrierName),
                            "%s Wi-Fi Calling".format(carrierName),
                            "WLAN Call",
                            "%s WLAN Call".format(carrierName),
                            "%s Wi-Fi".format(carrierName),
                            "WiFi Calling | %s".format(carrierName),
                            "%s VoWifi".format(carrierName),
                            "Wi-Fi Calling",
                            "Wi-Fi",
                            "WiFi Calling",
                            "VoWifi",
                            "%s WiFi Calling".format(carrierName),
                            "WiFi Call",
                        ),
                    selectedIndex = wfcSpnFormatIndex,
                ) {
                    moder.updateCarrierConfig(CarrierConfigManager.KEY_WFC_SPN_FORMAT_IDX_INT, it)
                    wfcSpnFormatIndex = it
                }
                BooleanPropertyView(label = stringResource(R.string.show_wifi_only_for_vowifi), toggled = supportWfcWifiOnly) {
                    supportWfcWifiOnly =
                        if (supportWfcWifiOnly) {
                            moder.updateCarrierConfig(CarrierConfigManager.KEY_CARRIER_WFC_SUPPORTS_WIFI_ONLY_BOOL, false)
                            false
                        } else {
                            moder.updateCarrierConfig(CarrierConfigManager.KEY_CARRIER_WFC_SUPPORTS_WIFI_ONLY_BOOL, true)
                            moder.restartIMSRegistration()
                            true
                        }
                }
                BooleanPropertyView(label = stringResource(R.string.show_vowifi_icon), toggled = showVoWifiIcon) {
                    showVoWifiIcon =
                        if (showVoWifiIcon) {
                            moder.updateCarrierConfig(CarrierConfigManager.KEY_SHOW_WIFI_CALLING_ICON_IN_STATUS_BAR_BOOL, false)
                            false
                        } else {
                            moder.updateCarrierConfig(CarrierConfigManager.KEY_SHOW_WIFI_CALLING_ICON_IN_STATUS_BAR_BOOL, true)
                            true
                        }
                }
                if (VERSION.SDK_INT >= VERSION_CODES.R) {
                    BooleanPropertyView(
                        label = stringResource(R.string.always_show_data_icon),
                        toggled = alwaysDataRATIcon,
                        minSdk = VERSION_CODES.R,
                    ) {
                        alwaysDataRATIcon =
                            if (alwaysDataRATIcon) {
                                moder.updateCarrierConfig(
                                    CarrierConfigManager.KEY_ALWAYS_SHOW_DATA_RAT_ICON_BOOL,
                                    false,
                                )
                                false
                            } else {
                                moder.updateCarrierConfig(
                                    CarrierConfigManager.KEY_ALWAYS_SHOW_DATA_RAT_ICON_BOOL,
                                    true,
                                )
                                true
                            }
                    }
                    BooleanPropertyView(
                        label = stringResource(R.string.show_4g_for_lte_data_icon),
                        toggled = show4GForLteEnabled,
                        minSdk = VERSION_CODES.R,
                    ) {
                        show4GForLteEnabled =
                            if (show4GForLteEnabled) {
                                moder.updateCarrierConfig(
                                    CarrierConfigManager.KEY_SHOW_4G_FOR_LTE_DATA_ICON_BOOL,
                                    false,
                                )
                                false
                            } else {
                                moder.updateCarrierConfig(
                                    CarrierConfigManager.KEY_SHOW_4G_FOR_LTE_DATA_ICON_BOOL,
                                    true,
                                )
                                true
                            }
                    }
                    BooleanPropertyView(
                        label = stringResource(R.string.hide_enhanced_data_icon),
                        toggled = hideEnhancedDataIconEnabled,
                        minSdk = VERSION_CODES.R,
                    ) {
                        hideEnhancedDataIconEnabled =
                            if (hideEnhancedDataIconEnabled) {
                                moder.updateCarrierConfig(
                                    CarrierConfigManager.KEY_HIDE_LTE_PLUS_DATA_ICON_BOOL,
                                    false,
                                )
                                false
                            } else {
                                moder.updateCarrierConfig(
                                    CarrierConfigManager.KEY_HIDE_LTE_PLUS_DATA_ICON_BOOL,
                                    true,
                                )
                                true
                            }
                    }
                    BooleanPropertyView(
                        label = stringResource(R.string.show_ims_status_in_sim_status),
                        toggled = showIMSinSIMInfo,
                        minSdk = VERSION_CODES.R,
                    ) {
                        showIMSinSIMInfo =
                            if (showIMSinSIMInfo) {
                                moder.updateCarrierConfig(
                                    CarrierConfigManager.KEY_SHOW_IMS_REGISTRATION_STATUS_BOOL,
                                    false,
                                )
                                false
                            } else {
                                moder.updateCarrierConfig(
                                    CarrierConfigManager.KEY_SHOW_IMS_REGISTRATION_STATUS_BOOL,
                                    true,
                                )
                                true
                            }
                    }
                }

                if (VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) {
                    val statusBarManager: StatusBarManager = context.getSystemService(StatusBarManager::class.java)

                    HeaderText(text = stringResource(R.string.qstile))
                    ClickablePropertyView(
                        label = stringResource(R.string.add_status_tile),
                        value = "",
                    ) {
                        statusBarManager.requestAddTileService(
                            ComponentName(
                                context,
                                // TODO: what happens if someone tries to use this feature from a triple(or even dual)-SIM phone?
                                Class.forName("dev.bluehouse.enablevolte.SIM${simSlotIndex + 1}IMSStatusQSTileService"),
                            ),
                            context.getString(R.string.qs_status_tile_title, (simSlotIndex + 1).toString()),
                            Icon.createWithResource(context, R.drawable.ic_launcher_foreground),
                            {},
                            {},
                        )
                    }
                    ClickablePropertyView(
                        label = stringResource(R.string.add_toggle_tile),
                        value = "",
                    ) {
                        statusBarManager.requestAddTileService(
                            ComponentName(
                                context,
                                Class.forName("dev.bluehouse.enablevolte.SIM${simSlotIndex + 1}VoLTEConfigToggleQSTileService"),
                            ),
                            context.getString(R.string.qs_toggle_tile_title, (simSlotIndex + 1).toString()),
                            Icon.createWithResource(context, R.drawable.ic_launcher_foreground),
                            {},
                            {},
                        )
                    }
                }
                HeaderText(text = stringResource(R.string.miscellaneous))
                ClickablePropertyView(
                    label = stringResource(R.string.reset_all_settings),
                    value = stringResource(R.string.reverts_to_carrier_default),
                ) {
                    perform { moder.clearCarrierConfig() }
                }
                ClickablePropertyView(
                    label = stringResource(R.string.expert_mode),
                    value = "",
                ) {
                    navController.navigate("config$subId/edit")
                }
                ClickablePropertyView(
                    label = stringResource(R.string.dump_config),
                    value = "",
                ) {
                    navController.navigate("config$subId/dump")
                }
                ClickablePropertyView(
                    label = stringResource(R.string.restart_ims_registration),
                    value = "",
                ) {
                    perform { moder.restartIMSRegistration() }
                }
                ClickablePropertyView(
                    "Back up current carrier configuration",
                    "Saves effective carrier values; excludes modem provisioning",
                ) {
                    perform {
                        dev.bluehouse.enablevolte.CarrierBackup
                            .save(context, subId, moder.readConfig())
                        snapshotMessage = "Current effective carrier configuration backed up"
                    }
                }
                ClickablePropertyView("Export saved carrier snapshot", "Save a checksummed copy to Download") {
                    perform {
                        dev.bluehouse.enablevolte.CarrierBackup
                            .export(context, subId)
                        snapshotMessage = "Carrier snapshot saved to Download"
                    }
                }
                ClickablePropertyView("Import and restore carrier snapshot", "Requires matching device firmware and subscription ID") {
                    snapshotPicker.launch(arrayOf("application/json", "*/*"))
                }
                if (snapshotMessage.isNotEmpty()) Text(snapshotMessage)
                ClickablePropertyView("Restore previous carrier snapshot", "Restores the last saved effective configuration for this SIM") {
                    perform { moder.restoreSnapshot() }
                }
            }
        }
    }
}
