package dev.bluehouse.enablevolte

import android.app.IActivityManager
import android.app.UiAutomationConnection
import android.content.ComponentName
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.os.IInterface
import android.os.PersistableBundle
import android.os.ServiceManager
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionInfo
import android.telephony.TelephonyFrameworkInitializer
import android.util.Log
import rikka.shizuku.Shizuku
import androidx.annotation.RequiresApi
import com.android.internal.telephony.ICarrierConfigLoader
import com.android.internal.telephony.IPhoneSubInfo
import com.android.internal.telephony.ISub
import com.android.internal.telephony.ITelephony
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object InterfaceCache {
    val cache = java.util.concurrent.ConcurrentHashMap<String, IInterface>()
}

open class Moder {
    @Suppress("ktlint:standard:property-naming")
    val KEY_IMS_USER_AGENT = "ims.ims_user_agent_string"

    protected inline fun <reified T : IInterface> loadCachedInterface(interfaceLoader: () -> T): T {
        InterfaceCache.cache[T::class.java.name]?.let {
            return it as T
        } ?: run {
            val i = interfaceLoader()
            InterfaceCache.cache[T::class.java.name] = i
            return i
        }
    }

    protected val carrierConfigLoader: ICarrierConfigLoader
        get() =
            ICarrierConfigLoader.Stub.asInterface(
                ShizukuBinderWrapper(
                    try {
                        TelephonyFrameworkInitializer
                            .getTelephonyServiceManager()
                            .carrierConfigServiceRegisterer
                            .get()
                    } catch (e: NoClassDefFoundError) {
                        ServiceManager.getService(Context.CARRIER_CONFIG_SERVICE)
                    }!!,
                ),
            )

    protected val telephony: ITelephony
        get() =
            ITelephony.Stub.asInterface(
                ShizukuBinderWrapper(
                    try {
                        TelephonyFrameworkInitializer
                            .getTelephonyServiceManager()
                            .telephonyServiceRegisterer
                            .get()
                    } catch (e: NoClassDefFoundError) {
                        ServiceManager.getService(Context.TELEPHONY_SERVICE)
                    }!!,
                ),
            )

    protected val phoneSubInfo: IPhoneSubInfo
        get() =
            IPhoneSubInfo.Stub.asInterface(
                ShizukuBinderWrapper(
                    try {
                        TelephonyFrameworkInitializer
                            .getTelephonyServiceManager()
                            .phoneSubServiceRegisterer
                            .get()
                    } catch (e: NoClassDefFoundError) {
                        ServiceManager.getService("iphonesubinfo")
                    }!!,
                ),
            )

    protected val sub: ISub
        get() =
            ISub.Stub.asInterface(
                ShizukuBinderWrapper(
                    try {
                        TelephonyFrameworkInitializer
                            .getTelephonyServiceManager()
                            .subscriptionServiceRegisterer
                            .get()
                    } catch (e: NoClassDefFoundError) {
                        ServiceManager.getService("isub")
                    }!!,
                ),
            )
}

class CarrierModer(
    private val context: Context,
) : Moder() {
    fun getActiveSubscriptionInfoForSimSlotIndex(index: Int): SubscriptionInfo? {
        val sub = this.loadCachedInterface { sub }
        return try {
            sub.getActiveSubscriptionInfoForSimSlotIndex(index, null, null)
        } catch (e: NoSuchMethodError) {
            val getActiveSubscriptionInfoForSimSlotIndexMethod =
                sub.javaClass.getMethod(
                    "getActiveSubscriptionInfoForSimSlotIndex",
                    Int::class.javaPrimitiveType,
                    String::class.java,
                )
            (getActiveSubscriptionInfoForSimSlotIndexMethod.invoke(sub, index, null) as? SubscriptionInfo)
        }
    }

    val subscriptions: List<SubscriptionInfo>
        get() {
            if (RootBackend.needed()) return RootBackend.subscriptions(context)
            val sub = this.loadCachedInterface { sub }
            try {
                return sub.getActiveSubscriptionInfoList(null, null, true) ?: emptyList()
            } catch (e: NoSuchMethodError) {
            }
            return try {
                val getActiveSubscriptionInfoListMethod =
                    sub.javaClass.getMethod(
                        "getActiveSubscriptionInfoList",
                        String::class.java,
                        String::class.java,
                    )
                (getActiveSubscriptionInfoListMethod.invoke(sub, null, null) as? List<SubscriptionInfo>) ?: emptyList()
            } catch (e: NoSuchMethodException) {
                val getActiveSubscriptionInfoListMethod =
                    sub.javaClass.getMethod(
                        "getActiveSubscriptionInfoList",
                        String::class.java,
                    )
                (getActiveSubscriptionInfoListMethod.invoke(sub, null) as? List<SubscriptionInfo>) ?: emptyList()
            }
        }

    val defaultSubId: Int
        get() {
            val sub = this.loadCachedInterface { sub }
            return sub.defaultSubId
        }

    val deviceSupportsIMS: Boolean
        get() {
            val res = Resources.getSystem()
            val volteConfigId = res.getIdentifier("config_device_volte_available", "bool", "android")
            return volteConfigId != 0 && res.getBoolean(volteConfigId)
        }
}

class SubscriptionModer(
    private val context: Context,
    val subscriptionId: Int,
) : Moder() {
    @Suppress("ktlint:standard:property-naming")
    private val TAG = "CarrierModer"

    private fun overrideConfigDirectly(bundle: Bundle?) {
        val iCclInstance = this.loadCachedInterface { carrierConfigLoader }
        if (bundle != null) {
            val args = toPersistableBundle(bundle)
            iCclInstance.overrideConfig(subscriptionId, args, true)
        } else {
            iCclInstance.overrideConfig(subscriptionId, null, true)
        }
    }

    private fun overrideConfigUsingBroker(bundle: Bundle?) {
        val completed = java.util.concurrent.CountDownLatch(1)
        var failure: String? = null
        val receiver = object : android.os.ResultReceiver(null) {
            override fun onReceiveResult(code: Int, data: Bundle?) {
                if (code != 0) failure = data?.getString("error") ?: "Carrier write failed"
                completed.countDown()
            }
        }
        val am =
            IActivityManager.Stub.asInterface(
                ShizukuBinderWrapper(
                    SystemServiceHelper.getSystemService(Context.ACTIVITY_SERVICE),
                ),
            )

        val arg =
            bundle?.let { Bundle(it) } ?: run {
                val empty = Bundle()
                empty.putBoolean("moder_clear", true)
                empty
            }
        arg.putInt("moder_subId", subscriptionId)
        arg.putParcelable("moder_result", receiver)

        val launched = am.startInstrumentation(
            ComponentName(context, Class.forName("dev.bluehouse.enablevolte.BrokerInstrumentation")),
            null,
            8,
            arg,
            null,
            UiAutomationConnection(),
            0,
            null,
        )
        check(launched) { "Carrier broker could not start" }
        check(completed.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
            "Carrier write timed out; refresh values before retrying"
        }
        failure?.let { throw IllegalStateException(it) }
    }

    /**
     * Only an authorized app Binder can attempt writes. Root and System are
     * eligible, but firmware permission checks and readback decide success.
     */
    private fun requireSystemWriteBackend() {
        val uid = try {
            if (!Shizuku.pingBinder() ||
                Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                -1
            } else {
                Shizuku.getUid()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot verify system binder identity", e)
            -1
        }
        if (uid != android.os.Process.SYSTEM_UID && uid != 0 && !RootBackend.available) {
            throw IllegalStateException(
                "Carrier write needs an authorized Root or System binder; detected UID $uid. " +
                    "Authorize this app through TokenX/Shizuku before retrying.",
            )
        }
    }

    private fun overrideConfig(bundle: Bundle?) {
        requireSystemWriteBackend()
        if (RootBackend.needed()) {
            RootBackend.write(context, subscriptionId, bundle)
            return
        }
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance()
        val securityPatchDate = sdf.parse(Build.VERSION.SECURITY_PATCH)
        if (securityPatchDate == null) {
            this.overrideConfigDirectly(bundle)
        } else {
            cal.time = securityPatchDate
            if (cal.get(Calendar.YEAR) > 2025 || (cal.get(Calendar.YEAR) == 2025 && cal.get(Calendar.MONTH) >= 9)) {
                this.overrideConfigUsingBroker(bundle)
            } else {
                this.overrideConfigDirectly(bundle)
            }
        }
    }

    private fun publishBundle(fn: (Bundle) -> Unit) {
        val overrideBundle = Bundle()
        fn(overrideBundle)
        if (!CarrierWrites.stage(this, overrideBundle)) applyVerified(overrideBundle)
    }

    fun updateCarrierConfig(
        key: String,
        value: Boolean,
    ) {
        Log.d(TAG, "Setting $key to $value")
        publishBundle { it.putBoolean(key, value) }
    }

    fun updateCarrierConfig(
        key: String,
        value: String,
    ) {
        Log.d(TAG, "Setting $key to $value")
        publishBundle { it.putString(key, value) }
    }

    fun updateCarrierConfig(
        key: String,
        value: Int,
    ) {
        Log.d(TAG, "Setting $key to $value")
        publishBundle { it.putInt(key, value) }
    }

    fun updateCarrierConfig(
        key: String,
        value: Long,
    ) {
        Log.d(TAG, "Setting $key to $value")
        publishBundle { it.putLong(key, value) }
    }

    fun updateCarrierConfig(
        key: String,
        value: IntArray,
    ) {
        Log.d(TAG, "Setting $key to $value")
        publishBundle { it.putIntArray(key, value) }
    }

    fun updateCarrierConfig(
        key: String,
        value: BooleanArray,
    ) {
        Log.d(TAG, "Setting $key to $value")
        publishBundle { it.putBooleanArray(key, value) }
    }

    fun updateCarrierConfig(
        key: String,
        value: Array<String>,
    ) {
        Log.d(TAG, "Setting $key to $value")
        publishBundle { it.putStringArray(key, value) }
    }

    fun updateCarrierConfig(
        key: String,
        value: LongArray,
    ) {
        Log.d(TAG, "Setting $key to $value")
        publishBundle { it.putLongArray(key, value) }
    }

    fun clearCarrierConfig() {
        requireSystemWriteBackend()
        CarrierBackup.save(context, subscriptionId, readConfig())
        this.overrideConfig(null)
        CarrierWrites.revision.value += 1
    }

    fun readConfig(): PersistableBundle =
        if (RootBackend.needed()) RootBackend.read(context, subscriptionId)
        else getConfigForSubId(loadCachedInterface { carrierConfigLoader }, subscriptionId)
            ?: throw IllegalStateException("Carrier config unavailable")

    internal fun applyVerified(values: Bundle) {
        requireSystemWriteBackend()
        CarrierBackup.save(context, subscriptionId, readConfig())
        overrideConfig(values)
        val expected = toPersistableBundle(values)
        repeat(30) {
            val actual = readConfig()
            if (expected.keySet().all { key ->
                    sameValue(expected.get(key), actual.get(key))
                }) return
            Thread.sleep(100)
        }
        throw IllegalStateException("Carrier write completed but readback differs; refresh before retrying")
    }

    private fun sameValue(left: Any?, right: Any?): Boolean {
        if (left is PersistableBundle && right is PersistableBundle)
            return left.keySet() == right.keySet() && left.keySet().all { sameValue(left.get(it), right.get(it)) }
        return java.util.Objects.deepEquals(left, right)
    }

    fun restoreSnapshot() = restoreValues(CarrierBackup.load(context, subscriptionId))

    fun restoreValues(values: PersistableBundle) {
        val current = readConfig()
        require(values.keySet().all { it in current.keySet() }) { "Snapshot has unsupported carrier keys" }
        applyVerified(Bundle().apply {
            values.keySet().forEach { key -> putIntoBundle(this, key, values.get(key)) }
        })
    }

    fun restartIMSRegistration() {
        if (CarrierWrites.restart(this)) return
        requireSystemWriteBackend()
        if (RootBackend.needed()) { RootBackend.reset(context, subscriptionId); return }
        val telephony = this.loadCachedInterface { telephony }
        val sub = this.loadCachedInterface { sub }
        telephony.resetIms(sub.getSlotIndex(this.subscriptionId))
    }

    fun getStringValue(key: String): String? {
        Log.d(TAG, "Resolving string value of key $key")
        val subscriptionId = this.subscriptionId
        if (subscriptionId < 0) {
            return ""
        }
        val config = readConfig()
        return config?.getString(key)
    }

    fun getBooleanValue(key: String): Boolean {
        Log.d(TAG, "Resolving boolean value of key $key")
        val subscriptionId = this.subscriptionId
        if (subscriptionId < 0) {
            return false
        }
        val config = readConfig()
        return config?.getBoolean(key) ?: false
    }

    fun getIntValue(key: String): Int {
        Log.d(TAG, "Resolving integer value of key $key")
        val subscriptionId = this.subscriptionId
        if (subscriptionId < 0) {
            return -1
        }
        val config = readConfig()
        return config?.getInt(key) ?: -1
    }

    fun getLongValue(key: String): Long {
        Log.d(TAG, "Resolving long value of key $key")
        val subscriptionId = this.subscriptionId
        if (subscriptionId < 0) {
            return -1
        }
        val config = readConfig()
        return config?.getLong(key) ?: -1L
    }

    fun getBooleanArrayValue(key: String): BooleanArray {
        Log.d(TAG, "Resolving boolean array value of key $key")
        val subscriptionId = this.subscriptionId
        if (subscriptionId < 0) {
            return booleanArrayOf()
        }
        val config = readConfig()
        return config?.getBooleanArray(key) ?: BooleanArray(0)
    }

    fun getIntArrayValue(key: String): IntArray {
        Log.d(TAG, "Resolving integer value of key $key")
        val subscriptionId = this.subscriptionId
        if (subscriptionId < 0) {
            return intArrayOf()
        }
        val config = readConfig()
        return config?.getIntArray(key) ?: IntArray(0)
    }

    fun getStringArrayValue(key: String): Array<String> {
        Log.d(TAG, "Resolving string array value of key $key")
        val subscriptionId = this.subscriptionId
        if (subscriptionId < 0) {
            return arrayOf()
        }
        val config = readConfig()
        return config?.getStringArray(key) ?: emptyArray()
    }

    fun getValue(key: String): Any? {
        Log.d(TAG, "Resolving value of key $key")
        val subscriptionId = this.subscriptionId
        if (subscriptionId < 0) {
            return null
        }
        val config = readConfig()
        return config?.get(key)
    }

    fun getConfigForSubId(
        iCclInstance: ICarrierConfigLoader,
        subscriptionId: Int,
    ): PersistableBundle? {
        try {
            return iCclInstance.getConfigForSubIdWithFeature(subscriptionId, iCclInstance.defaultCarrierServicePackageName, "")
        } catch (e: NoSuchMethodError) {
        }
        return try {
            iCclInstance.getConfigForSubId(subscriptionId, iCclInstance.defaultCarrierServicePackageName)
        } catch (e: NoSuchMethodError) {
            val getConfigForSubIdMethod =
                iCclInstance.javaClass.getMethod(
                    "getConfigForSubId",
                    Int::class.javaPrimitiveType,
                )
            (getConfigForSubIdMethod.invoke(iCclInstance, subscriptionId) as? PersistableBundle)
        }
    }

    val simSlotIndex: Int
        get() = if (RootBackend.needed()) RootBackend.subscriptions(context)
            .firstOrNull { it.subscriptionId == subscriptionId }?.simSlotIndex ?: -1
        else this.loadCachedInterface { sub }.getSlotIndex(subscriptionId)

    val isVoLteConfigEnabled: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL)

    val isVoNrConfigEnabled: Boolean
        @RequiresApi(VERSION_CODES.UPSIDE_DOWN_CAKE)
        get() =
            this.getBooleanValue(CarrierConfigManager.KEY_VONR_ENABLED_BOOL) &&
                this.getBooleanValue(CarrierConfigManager.KEY_VONR_SETTING_VISIBILITY_BOOL)

    val isCrossSIMConfigEnabled: Boolean
        get() {
            return if (Build.VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) {
                this.getBooleanValue(CarrierConfigManager.KEY_CARRIER_CROSS_SIM_IMS_AVAILABLE_BOOL) &&
                    this.getBooleanValue(CarrierConfigManager.KEY_ENABLE_CROSS_SIM_CALLING_ON_OPPORTUNISTIC_DATA_BOOL)
            } else {
                false
            }
        }

    val isVoWifiConfigEnabled: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL)

    val isVoWifiWhileRoamingEnabled: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_ROAMING_ENABLED_BOOL)

    val showIMSinSIMInfo: Boolean
        @RequiresApi(VERSION_CODES.R)
        get() = this.getBooleanValue(CarrierConfigManager.KEY_SHOW_IMS_REGISTRATION_STATUS_BOOL)

    val allowAddingAPNs: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_ALLOW_ADDING_APNS_BOOL)

    val showVoWifiMode: Boolean
        @RequiresApi(VERSION_CODES.R)
        get() = this.getBooleanValue(CarrierConfigManager.KEY_EDITABLE_WFC_MODE_BOOL)

    val showVoWifiRoamingMode: Boolean
        @RequiresApi(VERSION_CODES.R)
        get() = this.getBooleanValue(CarrierConfigManager.KEY_EDITABLE_WFC_ROAMING_MODE_BOOL)

    val wfcSpnFormatIndex: Int
        get() = this.getIntValue(CarrierConfigManager.KEY_WFC_SPN_FORMAT_IDX_INT)

    val carrierName: String?
        get() = if (RootBackend.needed()) RootBackend.subscriptions(context)
            .firstOrNull { it.subscriptionId == subscriptionId }?.carrierName?.toString()
        else this.loadCachedInterface { telephony }.getSubscriptionCarrierName(this.subscriptionId)

    val showVoWifiIcon: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_SHOW_WIFI_CALLING_ICON_IN_STATUS_BAR_BOOL)

    val alwaysDataRATIcon: Boolean
        @RequiresApi(VERSION_CODES.R)
        get() = this.getBooleanValue(CarrierConfigManager.KEY_ALWAYS_SHOW_DATA_RAT_ICON_BOOL)

    val supportWfcWifiOnly: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_CARRIER_WFC_SUPPORTS_WIFI_ONLY_BOOL)

    val isVtConfigEnabled: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_CARRIER_VT_AVAILABLE_BOOL)

    val ssOverUtEnabled: Boolean
        get() =
            if (Build.VERSION.SDK_INT >= VERSION_CODES.Q) {
                this.getBooleanValue(CarrierConfigManager.KEY_CARRIER_SUPPORTS_SS_OVER_UT_BOOL)
            } else {
                false
            }

    val ssOverCDMAEnabled: Boolean
        get() = this.getBooleanValue(CarrierConfigManager.KEY_SUPPORT_SS_OVER_CDMA_BOOL)

    val isShow4GForLteEnabled: Boolean
        @RequiresApi(VERSION_CODES.R)
        get() = this.getBooleanValue(CarrierConfigManager.KEY_SHOW_4G_FOR_LTE_DATA_ICON_BOOL)

    val isHideEnhancedDataIconEnabled: Boolean
        @RequiresApi(VERSION_CODES.R)
        get() = this.getBooleanValue(CarrierConfigManager.KEY_HIDE_LTE_PLUS_DATA_ICON_BOOL)

    val is4GPlusEnabled: Boolean
        get() =
            if (Build.VERSION.SDK_INT >= VERSION_CODES.Q) {
                this.getBooleanValue(CarrierConfigManager.KEY_EDITABLE_ENHANCED_4G_LTE_BOOL) &&
                    this.getBooleanValue(CarrierConfigManager.KEY_ENHANCED_4G_LTE_ON_BY_DEFAULT_BOOL) &&
                    !this.getBooleanValue(CarrierConfigManager.KEY_HIDE_ENHANCED_4G_LTE_BOOL)
            } else {
                this.getBooleanValue(CarrierConfigManager.KEY_EDITABLE_ENHANCED_4G_LTE_BOOL) &&
                    !this.getBooleanValue(CarrierConfigManager.KEY_HIDE_ENHANCED_4G_LTE_BOOL)
            }

    val isNRConfigEnabled: Boolean
        get() =
            if (Build.VERSION.SDK_INT >= VERSION_CODES.S) {
                this
                    .getIntArrayValue(CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY)
                    .contentEquals(intArrayOf(1, 2))
            } else {
                false
            }

    val userAgentConfig: String
        get() = this.getStringValue(KEY_IMS_USER_AGENT) ?: ""

    val isIMSRegistered: Boolean
        get() {
            if (RootBackend.needed()) return RootBackend.registered(context, subscriptionId)
            val telephony = this.loadCachedInterface { telephony }
            return telephony.isImsRegistered(this.subscriptionId)
        }
}
