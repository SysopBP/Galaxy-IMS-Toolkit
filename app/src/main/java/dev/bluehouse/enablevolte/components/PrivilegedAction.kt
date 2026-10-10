package dev.bluehouse.enablevolte.components

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.bluehouse.enablevolte.CarrierWrites
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Errors stay visible and saving always ends, including a lost Binder. */
@Composable
fun rememberPrivilegedAction(): ((() -> Unit) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    return { action ->
        if (!busy) {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { CarrierWrites.atomic(action) }
                } catch (e: Exception) {
                    Toast.makeText(context, e.message ?: "Operation failed", Toast.LENGTH_LONG).show()
                } finally {
                    busy = false
                }
            }
        }
    }
}
