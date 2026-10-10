package dev.bluehouse.enablevolte

import android.os.Parcel
import android.os.PersistableBundle
import android.os.ServiceManager
import android.telephony.SubscriptionInfo
import android.util.Base64
import com.android.internal.telephony.ICarrierConfigLoader
import com.android.internal.telephony.ISub
import com.android.internal.telephony.ITelephony
import org.lsposed.hiddenapibypass.HiddenApiBypass

/** Isolated UID-0 process. No code is injected into Samsung IMS or system_server. */
object RootCarrierClient {
    @JvmStatic fun main(args: Array<String>) {
        try {
            check(android.os.Process.myUid() == 0) { "Root worker is not UID 0" }
            HiddenApiBypass.addHiddenApiExemptions("L")
            val action = args[0]
            val id = args.getOrNull(1)?.toIntOrNull() ?: -1
            val loader = ICarrierConfigLoader.Stub.asInterface(ServiceManager.getService("carrier_config"))
            val sub = ISub.Stub.asInterface(ServiceManager.getService("isub"))
            when (action) {
                "subscriptions" -> {
                    val values =
                        try {
                            sub.getActiveSubscriptionInfoList(null, null, true)
                        } catch (_: NoSuchMethodError) {
                            sub.javaClass
                                .getMethod("getActiveSubscriptionInfoList", String::class.java, String::class.java)
                                .invoke(sub, null, null) as? List<SubscriptionInfo>
                        }
                    val parcel = Parcel.obtain()
                    try {
                        parcel.writeTypedList(values.orEmpty())
                        printParcel(parcel)
                    } finally {
                        parcel.recycle()
                    }
                }
                "read" -> {
                    val values =
                        try {
                            loader.getConfigForSubIdWithFeature(id, loader.defaultCarrierServicePackageName, "")
                        } catch (_: NoSuchMethodError) {
                            loader.getConfigForSubId(id, loader.defaultCarrierServicePackageName)
                        }
                    val parcel = Parcel.obtain()
                    try {
                        parcel.writePersistableBundle(values)
                        printParcel(parcel)
                    } finally {
                        parcel.recycle()
                    }
                }
                "write", "clear" -> {
                    val values = if (action == "clear") null else decode(args[2])
                    try {
                        loader.overrideConfig(id, values, true)
                    } catch (e: SecurityException) {
                        if (e.message?.contains("persistent=true") == true) {
                            loader.overrideConfig(id, values, false)
                        } else {
                            throw e
                        }
                    }
                    println("IMS_ROOT_OK")
                }
                "reset" -> {
                    val phone = ITelephony.Stub.asInterface(ServiceManager.getService("phone"))
                    phone.resetIms(sub.getSlotIndex(id))
                    println("IMS_ROOT_OK")
                }
                "registered" -> {
                    val phone = ITelephony.Stub.asInterface(ServiceManager.getService("phone"))
                    println("IMS_ROOT_VALUE=" + phone.isImsRegistered(id))
                }
                else -> error("Unsupported root operation")
            }
        } catch (e: Throwable) {
            System.err.println("IMS root operation failed: " + (e.message ?: e.javaClass.simpleName))
            kotlin.system.exitProcess(1)
        }
    }

    private fun printParcel(parcel: Parcel) {
        println("IMS_ROOT_DATA=" + Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP))
    }

    private fun decode(encoded: String): PersistableBundle {
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            parcel.readPersistableBundle(RootCarrierClient::class.java.classLoader) ?: error("Empty write")
        } finally {
            parcel.recycle()
        }
    }
}
