package dev.bluehouse.enablevolte

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object BackendStatus {
    fun describe(): String = try {
        if (RootBackend.needed()) "Standalone Root UID 0 • authorized • firmware writes verified per operation"
        else if (!Shizuku.pingBinder()) "Binder disconnected"
        else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) "Binder connected • app authorization required"
        else when (val uid = Shizuku.getUid()) {
            0 -> "Root UID 0 • authorized • firmware write capability checked per operation"
            1000 -> "System UID 1000 • authorized • write results verified by readback"
            2000 -> "Shell UID 2000 • authorized • carrier writes unavailable"
            else -> "UID $uid • authorized • unsupported carrier write route"
        }
    } catch (e: Exception) { "Backend unavailable: ${e.javaClass.simpleName}" }
}
