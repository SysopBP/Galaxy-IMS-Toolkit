package dev.bluehouse.enablevolte

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock

/** Only this app and Samsung's installed IMS process can exchange observer events. */
class ObserverProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle {
        val c = requireNotNull(context)
        val uid = Binder.getCallingUid()
        val packages = c.packageManager.getPackagesForUid(uid).orEmpty()
        check(uid == c.applicationInfo.uid || packages.contains("com.sec.imsservice")) { "Observer caller rejected" }
        val prefs = ObserverState.prefs(c)
        val enabled = prefs.getBoolean("enabled", false)
        if (method == "config") return Bundle().apply { putBoolean("enabled", enabled) }
        if (!enabled) return Bundle()
        val data = extras ?: return Bundle()
        if (method == "heartbeat") {
            val state = data.getString("state").orEmpty()
            require(
                state in
                    setOf("Hook loaded", "Observer active", "Unsupported firmware signatures", "Observer error", "Waiting for IMS classes"),
            )
            prefs
                .edit()
                .putLong(
                    "heartbeat",
                    SystemClock.elapsedRealtime(),
                ).putString("state", state)
                .putInt(
                    "boot",
                    android.provider.Settings.Global
                        .getInt(c.contentResolver, "boot_count", -1),
                ).apply()
        } else if (method == "registration") {
            val slot = data.getInt("slot", -1)
            require(slot in 0..1)
            prefs
                .edit()
                .putLong("event_$slot", SystemClock.elapsedRealtime())
                .apply { if (data.containsKey("volte")) putBoolean("volte_$slot", data.getBoolean("volte")) }
                .putBoolean("rcs_$slot", data.getBoolean("rcs"))
                .apply {
                    if (data.containsKey("vowifi")) putBoolean("vowifi_$slot", data.getBoolean("vowifi"))
                }.apply()
        }
        return Bundle()
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
