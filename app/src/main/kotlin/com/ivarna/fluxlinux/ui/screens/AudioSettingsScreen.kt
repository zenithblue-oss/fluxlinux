package com.ivarna.fluxlinux.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.core.terminal.PulseHost
import com.ivarna.fluxlinux.ui.components.GlassSettingCard
import com.ivarna.fluxlinux.ui.theme.fluxMutedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Host PulseAudio status / start / restart / logs.
 * Guests stay clients (`PULSE_SERVER=tcp:127.0.0.1`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<PulseHost.Status?>(null) }
    var busy by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    var showLogs by remember { mutableStateOf(false) }

    fun loadStatus(startIfStopped: Boolean = false) {
        if (busy) return
        busy = true
        scope.launch {
            val (s, log) = withContext(Dispatchers.IO) {
                var q = PulseHost.query(context)
                // prepareHost no longer starts Pulse; this screen does it on open.
                if (startIfStopped && !q.running) {
                    PulseHost.ensureStarted(context)
                    q = PulseHost.query(context)
                }
                q to PulseHost.readLog(context)
            }
            status = s
            logText = log
            busy = false
        }
    }

    fun runAction(
        label: String,
        toastOf: ((String) -> String)? = null,
        block: (Context) -> String
    ) {
        if (busy) return
        busy = true
        scope.launch {
            val (s, log, toast) = withContext(Dispatchers.IO) {
                val out = block(context)
                Triple(
                    PulseHost.query(context),
                    PulseHost.readLog(context),
                    toastOf?.invoke(out) ?: label
                )
            }
            status = s
            logText = log
            showLogs = true
            busy = false
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) { loadStatus(startIfStopped = true) }

    val running = status?.running == true
    val healthy = status?.healthy == true
    val statusColor = when {
        status == null -> fluxMutedText()
        healthy -> Color(0xFF69F0AE)
        running -> MaterialTheme.colorScheme.secondary
        else -> Color(0xFFFF8A80)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Audio",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Host PulseAudio in the FluxLinux prefix. Guests connect with PULSE_SERVER=tcp:127.0.0.1. Stopping the desktop does not stop this service.",
                fontSize = 13.sp,
                color = fluxMutedText()
            )

            GlassSettingCard {
                Column(Modifier.padding(20.dp).fillMaxWidth()) {
                    Text(
                        "Host server",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (busy && status == null) "Checking…" else (status?.label ?: "Checking…"),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        status?.detail ?: "Querying pulseaudio --check and pactl info…",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { loadStatus() },
                            enabled = !busy,
                            modifier = Modifier.weight(1f).height(48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            Text("Refresh", fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = {
                                if (running) {
                                    runAction("Pulse restarted") { PulseHost.restart(it) }
                                } else {
                                    runAction("Pulse started") { PulseHost.start(it) }
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.weight(1f).height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            )
                        ) {
                            Text(
                                if (running) "Restart" else "Start",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            GlassSettingCard {
                Column(Modifier.padding(20.dp).fillMaxWidth()) {
                    Text(
                        "Logs",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Supervisor and status output from this app (start_pulse_host.sh).",
                        fontSize = 13.sp,
                        color = fluxMutedText()
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showLogs = !showLogs },
                            modifier = Modifier.weight(1f).height(48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            Text(if (showLogs) "Hide logs" else "View logs", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = {
                                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as ClipboardManager
                                clip.setPrimaryClip(
                                    ClipData.newPlainText("Pulse log", logText.ifBlank { "(empty)" })
                                )
                                Toast.makeText(context, "Log copied", Toast.LENGTH_SHORT).show()
                            },
                            enabled = logText.isNotBlank(),
                            modifier = Modifier.weight(1f).height(48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            Text("Copy", fontWeight = FontWeight.Bold)
                        }
                    }
                    if (showLogs) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = logText.ifBlank { "(no log yet — tap Refresh or Start)" },
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFE8EAED),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 160.dp, max = 360.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(4.dp)
                        )
                    }
                }
            }

            GlassSettingCard {
                Column(Modifier.padding(20.dp).fillMaxWidth()) {
                    Text(
                        "Guest clients",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Writes PULSE_SERVER=tcp:127.0.0.1 and installs pactl in already-installed PRoots. Chroots need root. Does not start a guest Pulse daemon.",
                        fontSize = 13.sp,
                        color = fluxMutedText()
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            runAction(
                                "Guest audio repaired",
                                PulseHost::repairToast
                            ) { PulseHost.repairGuests(it) }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Text("Repair guests", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
