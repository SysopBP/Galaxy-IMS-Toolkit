package dev.bluehouse.enablevolte

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore

object DiagnosticExports {
    fun save(context: Context, name: String, text: String, mime: String = "text/plain"): android.net.Uri {
        check(Build.VERSION.SDK_INT >= 29) { "Download export requires Android 10 or newer" }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Download destination unavailable")
        try {
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                ?: error("Cannot write diagnostic")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
    }
}
