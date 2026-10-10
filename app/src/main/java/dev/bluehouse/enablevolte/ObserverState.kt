package dev.bluehouse.enablevolte

import android.content.Context
import android.os.SystemClock

object ObserverState {
    fun prefs(context: Context) = context.getSharedPreferences("ims_observer", 0)

    fun fresh(context: Context): Boolean {
        val prefs = prefs(context)
        if (prefs.getInt("boot", -2) !=
            android.provider.Settings.Global
                .getInt(context.contentResolver, "boot_count", -1)
        ) {
            return false
        }
        val seen = prefs.getLong("heartbeat", -1)
        val age = SystemClock.elapsedRealtime() - seen
        return seen >= 0 && age in 0..90_000
    }

    fun summary(context: Context): String {
        val prefs = prefs(context)
        if (!prefs.getBoolean("enabled", false)) return "Observer disabled"
        if (!fresh(context)) return "No recent hook handshake • enable module, scope Samsung IMS, then restart device"
        return prefs.getString("state", "Hook loaded") ?: "Hook loaded"
    }

    fun serviceSummary(
        context: Context,
        service: String,
    ): String? {
        if (!prefs(context).getBoolean("enabled", false) || !fresh(context)) return null
        val key =
            when (service) {
                "VoLTE" -> "volte"
                "VoWiFi" -> "vowifi"
                "RCS" -> "rcs"
                else -> return null
            }
        val values =
            (0..1).mapNotNull { slot ->
                val prefs = prefs(context)
                val seen = prefs.getLong("event_$slot", -1)
                val age = SystemClock.elapsedRealtime() - seen
                if (seen < 0 || age !in 0..90_000 || !prefs.contains("${key}_$slot")) {
                    null
                } else {
                    "SIM ${slot + 1}: " + if (prefs.getBoolean("${key}_$slot", false)) "Registered" else "Not registered"
                }
            }
        return values.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }
}
