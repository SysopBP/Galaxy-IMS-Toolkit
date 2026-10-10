package dev.bluehouse.enablevolte

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.os.PersistableBundle
import android.telephony.SubscriptionInfo
import android.util.Base64
import rikka.shizuku.Shizuku

object RootBackend {
    val ready = kotlinx.coroutines.flow.MutableStateFlow(false)
    @Volatile var available = false
        private set
    private var cachedSub = -1
    private var cachedAt = 0L
    private var cachedConfig: PersistableBundle? = null

    fun probe(): Boolean {
        available = runCatching { RootCommands.run("id -u", 5, 4096).requireSuccess().trim() == "0" }.getOrDefault(false)
        ready.value = available
        return available
    }
    fun needed(): Boolean = available && !runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            (Shizuku.getUid() == 0 || Shizuku.getUid() == 1000)
    }.getOrDefault(false)
    private fun run(context: Context, action: String, subId: Int = -1, data: String = ""): String {
        check(available) { "Root not authorized for this app" }
        val apk = context.applicationInfo.sourceDir.replace("'", "'\''")
        return RootCommands.run("CLASSPATH='$apk' /system/bin/app_process /system/bin " +
            "dev.bluehouse.enablevolte.RootCarrierClient $action $subId $data", 20, 4 * 1024 * 1024).requireSuccess()
    }
    private fun parcel(output: String): Parcel {
        val encoded = output.lines().firstOrNull { it.startsWith("IMS_ROOT_DATA=") }
            ?.substringAfter("=") ?: error("Root worker returned no data")
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        return Parcel.obtain().apply { unmarshall(bytes, 0, bytes.size); setDataPosition(0) }
    }
    fun subscriptions(context: Context): List<SubscriptionInfo> {
        val parcel = parcel(run(context, "subscriptions"))
        return try { parcel.createTypedArrayList(SubscriptionInfo.CREATOR).orEmpty() } finally { parcel.recycle() }
    }
    @Synchronized fun read(context: Context, id: Int): PersistableBundle {
        if (cachedSub == id && android.os.SystemClock.elapsedRealtime() - cachedAt < 1000)
            cachedConfig?.let { return PersistableBundle(it) }
        val parcel = parcel(run(context, "read", id))
        return try {
            val result = parcel.readPersistableBundle(javaClass.classLoader) ?: error("Carrier config unavailable")
            cachedSub = id; cachedAt = android.os.SystemClock.elapsedRealtime(); cachedConfig = result
            PersistableBundle(result)
        } finally { parcel.recycle() }
    }
    @Synchronized fun write(context: Context, id: Int, values: Bundle?) {
        val encoded = if (values == null) "" else {
            val parcel = Parcel.obtain()
            try {
                parcel.writePersistableBundle(toPersistableBundle(values))
                Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP)
            } finally { parcel.recycle() }
        }
        run(context, if (values == null) "clear" else "write", id, encoded)
        cachedConfig = null
    }
    fun reset(context: Context, id: Int) { run(context, "reset", id) }
    fun registered(context: Context, id: Int): Boolean =
        run(context, "registered", id).lines().firstOrNull { it.startsWith("IMS_ROOT_VALUE=") }
            ?.substringAfter("=")?.toBooleanStrictOrNull() ?: error("Registration unknown")
}
