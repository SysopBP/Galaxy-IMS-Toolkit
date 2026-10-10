package dev.bluehouse.enablevolte

import android.content.Context
import android.os.Build
import android.os.Parcel
import android.os.PersistableBundle
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** A previous effective config, not the modem's provisioning database. */
object CarrierBackup {
    private fun file(context: Context, subId: Int) = File(context.filesDir, "carrier_previous_$subId.json")
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    fun save(context: Context, subId: Int, values: PersistableBundle) {
        val parcel = Parcel.obtain()
        val bytes = try { parcel.writePersistableBundle(values); parcel.marshall() } finally { parcel.recycle() }
        val record = JSONObject().put("schema", 1).put("device", Build.FINGERPRINT)
            .put("subscriptionId", subId).put("timestamp", System.currentTimeMillis())
            .put("sha256", hash(bytes))
            .put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
        val target = file(context, subId)
        val atomic = android.util.AtomicFile(target)
        val output = atomic.startWrite()
        try {
            output.write(record.toString().toByteArray())
            atomic.finishWrite(output)
        } catch (e: Exception) { atomic.failWrite(output); throw e }
    }

    fun export(context: Context, subId: Int): android.net.Uri {
        load(context, subId)
        return DiagnosticExports.save(context, "Galaxy_IMS_Carrier_Snapshot_$subId.json",
            file(context, subId).readText(), "application/json")
    }

    fun import(context: Context, subId: Int, text: String): PersistableBundle {
        require(text.length <= 4 * 1024 * 1024) { "Snapshot too large" }
        return decode(context, subId, text)
    }

    fun load(context: Context, subId: Int): PersistableBundle =
        decode(context, subId, file(context, subId).readText())

    private fun decode(context: Context, subId: Int, text: String): PersistableBundle {
        val record = JSONObject(text)
        require(record.getInt("schema") == 1 && record.getInt("subscriptionId") == subId &&
            record.getString("device") == Build.FINGERPRINT) { "Snapshot belongs to another SIM, firmware or schema" }
        val bytes = android.util.Base64.decode(record.getString("data"), android.util.Base64.DEFAULT)
        require(hash(bytes) == record.getString("sha256")) { "Snapshot checksum failed" }
        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(bytes, 0, bytes.size); parcel.setDataPosition(0)
            parcel.readPersistableBundle(javaClass.classLoader)
                ?: error("Snapshot empty")
        } finally { parcel.recycle() }
    }
}
