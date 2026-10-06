package com.ivarna.fluxlinux.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.core.system.PhantomProcessFixer
import com.ivarna.fluxlinux.core.system.PhantomProcessFixer.State
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Status + Root / Shizuku / PC (adb) fixes. [warning] adds the intro text and "Don't show again". */
@Composable
fun PhantomProcessDialog(warning: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(PhantomProcessFixer.state(context)) }
    var msg by remember { mutableStateOf<String?>(null) }

    fun done(err: String?) {
        state = PhantomProcessFixer.state(context)
        msg = err?.let { "Failed: $it" } ?: "Applied. Restart the terminal/desktop."
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Phantom process killer") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Android 12+ limits the background child processes each app may run. " +
                        "Without this fix your desktop or terminal can be killed after a few seconds (exit 137).",
                    fontSize = 14.sp
                )
                Text(
                    "Status: " + when {
                        !PhantomProcessFixer.applicable() -> "Not needed (Android 11 or lower)"
                        state == State.DISABLED -> "Disabled (fixed)"
                        state == State.ENABLED -> "Enabled (desktop may be killed)"
                        else -> "Unknown"
                    },
                    fontSize = 14.sp
                )
                msg?.let { Text(it, fontSize = 13.sp) }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        msg = "Requesting root..."
                        scope.launch {
                            val err = withContext(Dispatchers.IO) { PhantomProcessFixer.applyRoot() }
                            done(err)
                        }
                    }
                ) { Text("Fix with Root") }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        fun run() = scope.launch {
                            val err = withContext(Dispatchers.IO) { PhantomProcessFixer.applyShizuku() }
                            done(err)
                        }
                        when {
                            !PhantomProcessFixer.shizukuRunning() ->
                                msg = "Shizuku is not running. Install and start Shizuku, then retry."
                            PhantomProcessFixer.shizukuGranted() -> run()
                            else -> PhantomProcessFixer.requestShizuku { ok ->
                                if (ok) run() else msg = "Shizuku permission denied."
                            }
                        }
                    }
                ) { Text("Fix with Shizuku") }
                Text("Or from a PC over adb:", fontSize = 13.sp)
                Text(PhantomProcessFixer.adbCommands, fontSize = 11.sp)
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("ADB Commands", PhantomProcessFixer.adbCommands))
                        Toast.makeText(context, "Commands copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                ) { Text("Copy adb commands") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = if (warning) {
            {
                TextButton(onClick = {
                    PhantomProcessFixer.hideWarningForever(context)
                    onDismiss()
                }) { Text("Don't show again") }
            }
        } else null
    )
}
