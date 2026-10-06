package com.ivarna.fluxlinux.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ivarna.fluxlinux.R
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.res.Configuration

import com.ivarna.fluxlinux.core.root.RootShell
import com.ivarna.fluxlinux.core.utils.StateManager
import com.ivarna.fluxlinux.core.utils.RootUtils
import com.ivarna.fluxlinux.core.utils.SystemInfoUtils
import com.ivarna.fluxlinux.core.utils.LogcatStreamer
import com.ivarna.fluxlinux.core.data.TermuxIntentFactory
import com.ivarna.fluxlinux.core.data.ScriptManager
import com.ivarna.fluxlinux.core.terminal.TerminalLauncher
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import android.widget.Toast
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay

enum class ApkStatus { NOT_INSTALLED, DOWNLOADING, DOWNLOADED, INSTALLED, CORRUPTED }

private fun isVersionOlderThan(current: String, minimum: String): Boolean {
    if (current == "Not Installed") return false
    val c = current.split(".").mapNotNull { it.toIntOrNull() }
    val m = minimum.split(".").mapNotNull { it.toIntOrNull() }
    if (c.isEmpty() || m.isEmpty()) return false
    for (i in 0 until maxOf(c.size, m.size)) {
        val cv = c.getOrElse(i) { 0 }
        val mv = m.getOrElse(i) { 0 }
        if (cv < mv) return true
        if (cv > mv) return false
    }
    return false
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PrerequisitesScreen(
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // Step tracking
    var currentStep by remember { mutableStateOf(1) }
    val totalSteps = 9 // Adjusted down for keyboard removal

    // With the embedded host there is no external Termux: skip the legacy
    // Termux-config (2) and RUN_COMMAND permission (3) steps automatically.
    LaunchedEffect(currentStep) {
        if (TerminalLauncher.isHostSetupDone(context) && (currentStep == 2 || currentStep == 3)) {
            if (currentStep == 2) StateManager.setConnectionFixed(context, true)
            currentStep += 1
        }
    }
    
    // Package states
    val termuxInstalled = remember { mutableStateOf(StateManager.isTermuxInstalled(context)) }
    val x11Installed = remember { mutableStateOf(StateManager.isTermuxX11Installed(context)) }
    

    
    // Configuration state
    var configDone by remember { mutableStateOf(false) }

    // Setup log viewer (shown on every onboarding step)
    var showLogs by remember { mutableStateOf(false) }
    
    // Permission state
    val permissionState = rememberPermissionState(
        permission = "com.termux.permission.RUN_COMMAND"
    )

    // Function to re-check status
    val checkStatus = {
        termuxInstalled.value = StateManager.isTermuxInstalled(context)
        x11Installed.value = StateManager.isTermuxX11Installed(context)
    }

    // Refresh when app resumes
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                checkStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    val config = LocalConfiguration.current
    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

    val visualStep = if (currentStep > 5) currentStep - 1 else currentStep

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
    ) {
        if (isLandscape) {
            PrerequisitesLandscapeLayout(
                currentStep = currentStep,
                visualStep = visualStep,
                totalSteps = totalSteps,
                termuxInstalled = termuxInstalled,
                x11Installed = x11Installed,
                configDone = configDone,
                permissionState = permissionState,
                onRefresh = checkStatus,
                onStepChange = { currentStep = it },
                onConfigDone = { configDone = it },
                onComplete = onComplete,
                showLogs = showLogs,
                onToggleLogs = { showLogs = !showLogs }
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(40.dp))

                Text(
                    text = "Prerequisites",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "FluxLinux requires these to function",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    fontSize = 14.sp
                )

                Spacer(modifier = Modifier.height(32.dp))

                StepIndicator(currentStep = visualStep, totalSteps = totalSteps)

                Spacer(modifier = Modifier.height(32.dp))

                PrerequisitesStepContent(
                    currentStep = currentStep,
                    termuxInstalled = termuxInstalled,
                    x11Installed = x11Installed,
                    configDone = configDone,
                    permissionState = permissionState,
                    onRefresh = checkStatus,
                    onStepChange = { currentStep = it },
                    onConfigDone = { configDone = it },
                    onComplete = onComplete
                )

                Spacer(modifier = Modifier.height(16.dp))

                SetupLogPanel(
                    expanded = showLogs,
                    onToggle = { showLogs = !showLogs }
                )
            }
        }
    }
}

/**
 * Collapsible live logcat viewer for onboarding/setup steps. Tails logcat for
 * the app's own UID and shows host-setup tags (TerminalLauncher, BootstrapInstaller…).
 */
@Composable
fun SetupLogPanel(
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var lines by remember { mutableStateOf(LogcatStreamer.snapshot()) }
    var autoScroll by remember { mutableStateOf(true) }

    LaunchedEffect(expanded) {
        if (!expanded) return@LaunchedEffect
        LogcatStreamer.start(scope)
        val unsubscribe = LogcatStreamer.subscribe { _ ->
            lines = LogcatStreamer.snapshot()
        }
        try {
            while (true) {
                delay(400)
                lines = LogcatStreamer.snapshot()
                if (autoScroll) {
                    runCatching { scrollState.scrollTo(scrollState.maxValue) }
                }
            }
        } finally {
            unsubscribe()
            LogcatStreamer.stop()
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onToggle() }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (expanded) "▾ Setup Logs (live)" else "▸ Setup Logs",
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = { LogcatStreamer.clear(); lines = emptyList() },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Clear logs",
                    tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
            if (expanded) {
                Text(
                    text = if (autoScroll) "autoscroll on" else "autoscroll off",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    modifier = Modifier.clickable { autoScroll = !autoScroll }
                )
            }
        }

        if (expanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0D0D0D))
                    .border(
                        1.dp,
                        androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    )
                    .verticalScroll(scrollState)
                    .padding(8.dp)
            ) {
                Text(
                    text = lines.takeLast(500).joinToString("\n").ifEmpty { "Waiting for host setup logs…" },
                    color = Color(0xFF9ECE6A),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun PrerequisitesLandscapeLayout(
    currentStep: Int,
    visualStep: Int,
    totalSteps: Int,
    termuxInstalled: MutableState<Boolean>,
    x11Installed: MutableState<Boolean>,
    configDone: Boolean,
    permissionState: com.google.accompanist.permissions.PermissionState,
    onRefresh: () -> Unit,
    onStepChange: (Int) -> Unit,
    onConfigDone: (Boolean) -> Unit,
    onComplete: () -> Unit,
    showLogs: Boolean,
    onToggleLogs: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(start = 32.dp, top = 24.dp, end = 24.dp, bottom = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(0.35f)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Prerequisites",
                color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "FluxLinux requires these to function",
                color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                fontSize = 14.sp
            )
        }

        Spacer(modifier = Modifier.width(24.dp))

        VerticalDivider(
            modifier = Modifier.fillMaxHeight(),
            thickness = 1.dp,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        )

        Spacer(modifier = Modifier.width(24.dp))

        Column(
            modifier = Modifier
                .weight(0.65f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(end = 8.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StepIndicator(currentStep = visualStep, totalSteps = totalSteps)

            Spacer(modifier = Modifier.height(24.dp))

            PrerequisitesStepContent(
                currentStep = currentStep,
                termuxInstalled = termuxInstalled,
                x11Installed = x11Installed,
                configDone = configDone,
                permissionState = permissionState,
                onRefresh = onRefresh,
                onStepChange = onStepChange,
                onConfigDone = onConfigDone,
                onComplete = onComplete
            )

            Spacer(modifier = Modifier.height(16.dp))

            SetupLogPanel(
                expanded = showLogs,
                onToggle = onToggleLogs
            )
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun PrerequisitesStepContent(
    currentStep: Int,
    termuxInstalled: MutableState<Boolean>,
    x11Installed: MutableState<Boolean>,
    configDone: Boolean,
    permissionState: com.google.accompanist.permissions.PermissionState,
    onRefresh: () -> Unit,
    onStepChange: (Int) -> Unit,
    onConfigDone: (Boolean) -> Unit,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    when (currentStep) {
        1 -> HostBootstrapStep(
            onRefresh = onRefresh,
            onContinue = {
                onStepChange(2)
            }
        )

        2 -> TermuxConfigurationStep(
            configDone = configDone,
            onConfigDone = onConfigDone,
            onContinue = {
                if (configDone) {
                    StateManager.setConnectionFixed(context, true)
                    onStepChange(3)
                }
            }
        )

        3 -> PermissionRequestStep(
            permissionState = permissionState,
            onContinue = { onStepChange(4) }
        )

        4 -> OverlayPermissionStep(
            onContinue = { onStepChange(5) }
        )

        5 -> PhantomProcessStep(
            onContinue = { onStepChange(6) }
        )

        6 -> BusyBoxInstallStep(
            onContinue = { onStepChange(7) }
        )

        7 -> EnvironmentSetupStep(
            onContinue = { onStepChange(8) }
        )

        8 -> SystemCheckStep(
            onContinue = { onStepChange(9) }
        )

        9 -> FinalInstructionsStep(
            onContinue = { onStepChange(10) }
        )

        10 -> HelpAndSupportStep(
            onComplete = onComplete
        )
    }
}

@Composable
fun HostBootstrapStep(
    onRefresh: () -> Unit,
    onContinue: () -> Unit
) {
    val context = LocalContext.current
    var bootstrapDone by remember { mutableStateOf(TerminalLauncher.isHostSetupDone(context)) }
    var initializing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var statusText by remember { mutableStateOf("") }

    fun startInit() {
        initializing = true
        statusText = "Extracting host bootstrap…"
        TerminalLauncher.prepareHost(
            context,
            progress = { done, total, phase ->
                statusText = phase
                if (total > 0) progress = (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            },
            onDone = { ok ->
                initializing = false
                bootstrapDone = TerminalLauncher.isHostSetupDone(context)
                statusText = if (bootstrapDone) "Host environment ready."
                else "Setup failed — see device logs (Settings → Host status)."
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Step 1: Initialize Environment",
                color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    tint = androidx.compose.material3.MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Embedded host card (ivarna: download bootstrap.tar from GitHub; zenithblue: APK asset)
        PrerequisiteItem(
            name = "FluxLinux Host (embedded)",
            isInstalled = bootstrapDone,
            isOutdated = false,
            version = if (bootstrapDone) "v1 (extracted)" else null,
            apkStatus = if (bootstrapDone) ApkStatus.INSTALLED else ApkStatus.NOT_INSTALLED,
            progress = progress,
            minVersionHint = null,
            onDownload = { startInit() },
            onInstallApk = { startInit() }
        )

        Spacer(modifier = Modifier.height(8.dp))

        val hostHint = if (com.ivarna.fluxlinux.core.install.HostBootstrap.downloadsFromRelease(context.packageName)) {
            "Initialize downloads the Linux host (~124 MiB) from GitHub when you tap the button. " +
                "That archive is not in the F-Droid APK. No separate Termux APK is required."
        } else {
            "The Linux host environment (Termux-class prefix + proot-distro) is bundled inside this app — no separate Termux APK is required for Debian installs."
        }
        Text(
            text = hostHint,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(32.dp))

        val ready = bootstrapDone && !initializing
        Button(
            onClick = {
                when {
                    initializing -> {}
                    bootstrapDone -> onContinue()
                    else -> startInit()
                }
            },
            enabled = !initializing,
            colors = ButtonDefaults.buttonColors(
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                disabledContainerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                when {
                    initializing -> "Initializing…"
                    bootstrapDone -> "Continue"
                    else -> "Initialize Environment"
                },
                color = if (ready) androidx.compose.material3.MaterialTheme.colorScheme.onPrimary else androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (statusText.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = statusText,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun TermuxConfigurationStep(
    configDone: Boolean,
    onConfigDone: (Boolean) -> Unit,
    onContinue: () -> Unit
) {
    val context = LocalContext.current
    val fixCommand = "mkdir -p ~/.termux && echo \"allow-external-apps = true\" >> ~/.termux/termux.properties && termux-reload-settings"
    
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Step 2: Configure Termux",
            color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Allow FluxLinux to communicate with Termux",
            color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground.copy(alpha=0.7f),
            fontSize = 14.sp
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Command Box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1E1E1E))
                .border(1.dp, androidx.compose.material3.MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Text(
                text = fixCommand,
                color = Color(0xFF50fa7b),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Copy & Open Button
        Button(
            onClick = {
                // Copy to clipboard
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Termux Fix", fixCommand)
                clipboard.setPrimaryClip(clip)
                
                // Open Termux
                val launchIntent = context.packageManager.getLaunchIntentForPackage("com.termux")
                if (launchIntent != null) {
                    context.startActivity(launchIntent)
                    android.widget.Toast.makeText(context, "Command copied! Paste in Termux", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    android.widget.Toast.makeText(context, "Termux not found!", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.secondary),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                "Copy & Open Termux",
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSecondary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
                // Checkbox
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = configDone,
                onCheckedChange = onConfigDone,
                colors = CheckboxDefaults.colors(
                    checkedColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    uncheckedColor = androidx.compose.material3.MaterialTheme.colorScheme.outline
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "I've pasted and run the command in Termux",
                color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
                fontSize = 14.sp
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Continue Button - Show only when configDone (tick box) is checked
        if (configDone) {
            Button(
                onClick = onContinue,
                colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    "Continue",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionRequestStep(
    permissionState: com.google.accompanist.permissions.PermissionState,
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Step 3: Grant Permission",
            color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f))
                .border(2.dp, androidx.compose.material3.MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Permission Lock",
                modifier = Modifier.size(56.dp),
                tint = androidx.compose.material3.MaterialTheme.colorScheme.secondary
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "FluxLinux needs permission to communicate with Termux",
            color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground.copy(alpha=0.7f),
            fontSize = 14.sp
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Permission status
        if (permissionState.status.isGranted) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Granted",
                    tint = androidx.compose.material3.MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    "Permission Granted ✓",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            // Request Permission Button
            Button(
                onClick = {
                    permissionState.launchPermissionRequest()
                },
                colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.tertiary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    "Grant Permission",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onTertiary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        val permissionGranted = permissionState.status.isGranted
        // Continue Button
        Button(
            onClick = onContinue,
            enabled = permissionGranted,
            colors = ButtonDefaults.buttonColors(
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                disabledContainerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                if (permissionGranted) "Continue" else "Grant Permission to Continue",
                color = if (permissionGranted) androidx.compose.material3.MaterialTheme.colorScheme.onPrimary else androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun PrerequisiteItem(
    name: String,
    isInstalled: Boolean,
    isOutdated: Boolean = false,
    version: String?,
    apkStatus: ApkStatus = ApkStatus.INSTALLED,
    progress: Float = 0f,
    minVersionHint: String? = null,
    onDownload: (() -> Unit)? = null,
    onInstallApk: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.3f))
            .padding(16.dp)
    ) {
        if (isInstalled) {
            if (isOutdated) {
                // Outdated version warning (Play Store Termux)
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Outdated",
                            tint = Color(0xFFFF9800),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "$name — Version Too Old",
                                color = Color(0xFFFF5252),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (version != null) {
                                Text(
                                    text = "Installed: v$version",
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                    fontSize = 12.sp
                                )
                            }
                            Text(
                                text = "Play Store version will not work. Install v0.118.3 from F-Droid or GitHub releases.",
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    if (onDownload != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onDownload,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Download v0.118.3 (GitHub)", color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Installed",
                        tint = androidx.compose.material3.MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "$name ✓",
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (version != null) {
                            Text(
                                text = version,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground.copy(alpha=0.7f),
                                fontSize = 12.sp
                            )
                        }
                        if (minVersionHint != null) {
                            Text(
                                text = "Play Store version will not work. Install v0.118.3 from F-Droid or GitHub releases.",
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground.copy(alpha=0.6f),
                                fontSize = 11.sp,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }
        } else {
            Column {
                Text(
                    text = name,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                when (apkStatus) {
                    ApkStatus.NOT_INSTALLED -> {
                        Text(
                            text = "Not Installed",
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (onDownload != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = onDownload,
                                colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Download", color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary, fontSize = 14.sp)
                            }
                        }
                    }
                    ApkStatus.DOWNLOADING -> {
                        LinearProgressIndicator(
                            progress = { if (progress < 0f) 0f else progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp)),
                            color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                            trackColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (progress < 0f) "Downloading..." else "%.0f%%".format(progress * 100),
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground.copy(alpha=0.7f),
                            fontSize = 12.sp
                        )
                    }
                    ApkStatus.DOWNLOADED -> {
                        Text(
                            text = "Ready to install",
                            color = androidx.compose.material3.MaterialTheme.colorScheme.secondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (onInstallApk != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = onInstallApk,
                                colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.tertiary),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Install", color = androidx.compose.material3.MaterialTheme.colorScheme.onTertiary, fontSize = 14.sp)
                            }
                        }
                    }
                    ApkStatus.INSTALLED -> {
                        Text(
                            text = "✓ Installed",
                            color = androidx.compose.material3.MaterialTheme.colorScheme.secondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    ApkStatus.CORRUPTED -> {
                        Text(
                            text = "Download Corrupted",
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (onDownload != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = onDownload,
                                colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.error),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Redownload", color = androidx.compose.material3.MaterialTheme.colorScheme.onError, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * T4: Critical alert shown at the top of the prerequisite list when the
 * installed Termux is too old. Renders above the Termux install-check card
 * so the requirement (uninstall Play Store Termux, install v0.118.3 from
 * GitHub) is unmissable.
 */
@Composable
fun TermuxVersionAlertBanner() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFFF5252).copy(alpha = 0.15f))
            .border(
                width = 1.dp,
                color = Color(0xFFFF5252).copy(alpha = 0.6f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Outdated Termux",
                tint = Color(0xFFFF5252),
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Termux version is too old",
                    color = Color(0xFFFF5252),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Play Store version will not work. Install v0.118.3 from F-Droid or GitHub releases.",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun StepIndicator(currentStep: Int, totalSteps: Int) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(totalSteps) { index ->
            val step = index + 1
            Box(
                modifier = Modifier
                    .size(if (step == currentStep) 12.dp else 8.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(
                        if (step <= currentStep) androidx.compose.material3.MaterialTheme.colorScheme.primary else androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
                    )
            )
            if (step < totalSteps) {
                Spacer(modifier = Modifier.width(8.dp))
            }
        }
    }
}

@Composable
fun OverlayPermissionStep(
    onContinue: () -> Unit
) {
    val context = LocalContext.current
    
    // Manual override (User confirmation)
    var manualOverride by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Step 4: Display Overlay",
            color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(16.dp))

         // Permission Info Card
         androidx.compose.material3.Card(
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, androidx.compose.material3.MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
         ) {
             Column(modifier = Modifier.padding(20.dp)) {
                 Text(
                    "⚠️ Critical Permission",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error, // Usage error for warning
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                 )
                 Spacer(modifier = Modifier.height(8.dp))
                 Text(
                    "To display the Linux desktop (X11) on your screen, Termux needs the 'Display over other apps' permission.",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp
                 )
             }
         }
         
         Spacer(modifier = Modifier.height(24.dp))
         
         if (manualOverride) {
             // Success State
             Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { manualOverride = false }
                    .background(androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Granted",
                    tint = androidx.compose.material3.MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    "Permission Granted ✓",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
         } else {
             // Action Buttons
             Row(
                 modifier = Modifier.fillMaxWidth(),
                 horizontalArrangement = Arrangement.spacedBy(12.dp)
             ) {
                 // Overlay Settings Button
                 Button(
                     onClick = { 
                         try {
                             val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                             intent.data = android.net.Uri.parse("package:com.termux")
                             context.startActivity(intent)
                         } catch (e: Exception) {
                              try {
                                 val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                 context.startActivity(intent)
                             } catch (e2: Exception) {
                                 Toast.makeText(context, "Could not open settings", Toast.LENGTH_SHORT).show()
                             }
                         }
                     },
                     colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primary),
                     modifier = Modifier.weight(1f),
                     shape = RoundedCornerShape(12.dp)
                 ) {
                     Text("Enable Overlay", fontSize = 13.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary, textAlign = TextAlign.Center, lineHeight = 16.sp)
                 }
                 
                 // App Info Button
                 Button(
                     onClick = { 
                         try {
                             val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                             intent.data = android.net.Uri.parse("package:com.termux")
                             context.startActivity(intent)
                         } catch (e: Exception) {
                             Toast.makeText(context, "Could not open App Info", Toast.LENGTH_SHORT).show()
                         }
                     },
                     colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.secondary),
                     modifier = Modifier.weight(1f),
                     shape = RoundedCornerShape(12.dp)
                 ) {
                     Text("App Info", fontSize = 13.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.onSecondary, textAlign = TextAlign.Center, lineHeight = 16.sp)
                 }
             }
             
             Spacer(modifier = Modifier.height(16.dp))

             // Help Link
             androidx.compose.material3.TextButton(
                 onClick = { 
                     val browserIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://support.google.com/android/answer/12623953?hl=en"))
                     context.startActivity(browserIntent)
                 }
             ) {
                 Text(
                     "How to allow restricted settings on Android devices",
                     color = androidx.compose.material3.MaterialTheme.colorScheme.secondary,
                     fontSize = 13.sp,
                     textAlign = TextAlign.Center,
                     textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                 )
             }
             
             Spacer(modifier = Modifier.height(16.dp))
             
             // Manual Override Checkbox
             Row(
                 verticalAlignment = Alignment.CenterVertically,
                 modifier = Modifier
                     .fillMaxWidth()
                     .clickable { manualOverride = !manualOverride }
                     .padding(8.dp)
             ) {
                 Checkbox(
                     checked = manualOverride,
                     onCheckedChange = { manualOverride = it },
                     colors = CheckboxDefaults.colors(
                         checkedColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                         uncheckedColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha=0.6f)
                     )
                 )
                 Text(
                     "I have enabled this manually",
                     fontSize = 14.sp,
                     color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                     modifier = Modifier.padding(start = 8.dp)
                 )
             }
         }
                  Spacer(modifier = Modifier.height(32.dp))
          
          // Continue Button
          if (manualOverride) {
              Button(
                 onClick = onContinue,
                 colors = ButtonDefaults.buttonColors(
                     containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
                 ),
                 modifier = Modifier
                     .fillMaxWidth()
                     .height(56.dp),
                 shape = RoundedCornerShape(12.dp)
             ) {
                 Text(
                     "Continue",
                     color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
                     fontSize = 18.sp,
                     fontWeight = FontWeight.Bold
                 )
             }
         }
    }
}

@Composable
fun PhantomProcessStep(
    onContinue: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var rootAvailable by remember { mutableStateOf<Boolean?>(null) }
    var fixApplied by remember { mutableStateOf(false) }
    var checkingRoot by remember { mutableStateOf(false) }
    var adbConfirmed by remember { mutableStateOf(false) }
    
    // Check root on init
    LaunchedEffect(Unit) {
        checkingRoot = true
        kotlinx.coroutines.delay(500) // fake delay for UX
        rootAvailable = RootUtils.isRootAvailable()
        checkingRoot = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
            Text(
                text = "Step 5: Process Killer Fix",
                color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Info Card
            androidx.compose.material3.Card(
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, androidx.compose.material3.MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "⚠️ Android 12+ Stability Issue",
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Android 12 and higher kill background processes aggressively (Phantom Process Killer). This causes Termux to crash unexpectedly.",
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            if (checkingRoot) {
                CircularProgressIndicator(color = androidx.compose.material3.MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Checking for Root access...", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha=0.6f))
            } else if (fixApplied) {
                // Success State
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer)
                        .padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Applied",
                        tint = androidx.compose.material3.MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "Fix Applied Successfully ✓",
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else if (rootAvailable == true) {
                // Root Available Action
                Text(
                    "Root Access Detected ✅",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        coroutineScope.launch {
                            val err = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                com.ivarna.fluxlinux.core.system.PhantomProcessFixer.applyRoot()
                            }
                            if (err == null) {
                               fixApplied = true
                            } else {
                               Toast.makeText(context, "Failed to apply fix: $err", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.tertiary),
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                     shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Apply Fix (Grant Root)", color = androidx.compose.material3.MaterialTheme.colorScheme.onTertiary)
                }
            } else {
                // No Root
                 Text(
                    "Root Access Not Detected ❌",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "We cannot apply the fix automatically. Please run these commands from your PC via ADB:",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha=0.7f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                
                // ADB Commands Box
                val commands = """
                    adb shell "/system/bin/device_config set_sync_disabled_for_tests persistent"
                    adb shell "/system/bin/device_config put activity_manager max_phantom_processes 2147483647"
                    adb shell "settings put global settings_enable_monitor_phantom_procs false"
                """.trimIndent()
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(1.dp, androidx.compose.material3.MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                        .clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("ADB Commands", commands)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Commands copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                ) {
                    Text(
                        text = commands,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.secondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                 // Re-check Button
                 androidx.compose.material3.OutlinedButton(
                     onClick = { 
                         checkingRoot = true
                         coroutineScope.launch {
                             kotlinx.coroutines.delay(500)
                             rootAvailable = RootUtils.isRootAvailable()
                             checkingRoot = false
                         }
                     },
                     modifier = Modifier.fillMaxWidth(),
                     colors = ButtonDefaults.outlinedButtonColors(
                         contentColor = androidx.compose.material3.MaterialTheme.colorScheme.secondary
                     ),
                     border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.material3.MaterialTheme.colorScheme.secondary)
                 ) {
                     Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                     Spacer(modifier = Modifier.width(8.dp))
                     Text("Check Root Again")
                 }
            } // Close else
        
        if (!fixApplied) {
            Spacer(modifier = Modifier.height(16.dp))
            // Checkbox for ADB confirmation
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { adbConfirmed = !adbConfirmed }
                    .padding(8.dp)
            ) {
                Checkbox(
                    checked = adbConfirmed,
                    onCheckedChange = { adbConfirmed = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                        uncheckedColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha=0.6f)
                    )
                )
                Text(
                    "I have run the ADB commands on my PC",
                    fontSize = 14.sp,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
        
        val showContinueButton = fixApplied || adbConfirmed
        if (showContinueButton) {
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onContinue,
                colors = ButtonDefaults.buttonColors(
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    "Continue",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun EnvironmentSetupStep(
    onContinue: () -> Unit
) {
    val context = LocalContext.current
    val scriptManager = remember { ScriptManager(context) }
    
    // State to track if scripts have been run
    // Embedded host gate (Pass 2): setup_termux marker, not external Termux.
    var setupInitiated by remember { mutableStateOf(com.ivarna.fluxlinux.core.terminal.TerminalLauncher.isHostSetupDone(context)) }
    var isSetupLoading by remember { mutableStateOf(false) }
    var tweaksInitiated by remember { mutableStateOf(false) }
    var tweaksCompleted by remember { mutableStateOf(StateManager.getScriptStatus(context, "termux_tweaks")) }
    
    // Poll for setup completion
    LaunchedEffect(isSetupLoading) {
        if (isSetupLoading) {
            while (isSetupLoading) {
                delay(2000) // Check every 2 seconds
                if (com.ivarna.fluxlinux.core.terminal.TerminalLauncher.isHostSetupDone(context)) {
                    isSetupLoading = false
                    setupInitiated = true
                    Toast.makeText(context, "Environment Initialized Successfully!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Poll for tweaks completion
    LaunchedEffect(tweaksInitiated) {
        if (tweaksInitiated && !tweaksCompleted) {
            while (!tweaksCompleted) {
                delay(2000) // Check every 2 seconds
                if (StateManager.getScriptStatus(context, "termux_tweaks")) {
                     tweaksCompleted = true
                     break
                }
            }
        }
    }
    
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Step 7: Environment Setup",
            color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Please run both scripts below to set up your environment.",
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "1. Initialize Environment: Extracts the embedded host + validates dependencies (no Termux APK needed).\n2. Apply Termux Tweaks: Configures the shell and visuals (Optional, legacy external-Termux).",
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Setup Button (embedded host — no external Termux)
        Button(
            onClick = {
                isSetupLoading = true
                com.ivarna.fluxlinux.core.terminal.TerminalLauncher.prepareHost(
                    context,
                    onDone = { ok ->
                        isSetupLoading = false
                        setupInitiated = com.ivarna.fluxlinux.core.terminal.TerminalLauncher.isHostSetupDone(context)
                        if (!ok) {
                            Toast.makeText(context, "Host setup failed — see Troubleshooting", Toast.LENGTH_LONG).show()
                        }
                    }
                )
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (setupInitiated) androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer else androidx.compose.material3.MaterialTheme.colorScheme.primary,
                contentColor = if (setupInitiated) androidx.compose.material3.MaterialTheme.colorScheme.secondary else androidx.compose.material3.MaterialTheme.colorScheme.onPrimary
            ),
            enabled = !isSetupLoading && !setupInitiated,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (isSetupLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.Black,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "Initializing embedded host…",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                } else if (setupInitiated) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Environment Initialized",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Text(
                        "1. Initialize Environment (Required)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Tweaks Button (Foreground)
        Button(
            onClick = {
                val script = scriptManager.getScriptContent("termux/termux_tweaks.sh")
                val intent = TermuxIntentFactory.buildRunCommandIntent(script, runInBackground = false)
                try {
                    context.startService(intent)
                    tweaksInitiated = true
                } catch (e: Exception) {
                    Toast.makeText(context, "Failed to start service", Toast.LENGTH_SHORT).show()
                }
            },
            enabled = setupInitiated && !tweaksCompleted,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (tweaksCompleted) androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer else androidx.compose.material3.MaterialTheme.colorScheme.tertiary,
                disabledContainerColor = if (tweaksCompleted) androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer else androidx.compose.material3.MaterialTheme.colorScheme.tertiary.copy(alpha=0.5f),
                disabledContentColor = if (tweaksCompleted) androidx.compose.material3.MaterialTheme.colorScheme.secondary else androidx.compose.material3.MaterialTheme.colorScheme.onTertiary.copy(alpha = 0.5f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                 if (tweaksCompleted) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Tweaks Applied",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "2. Apply Termux Tweaks (Optional)",
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onTertiary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (tweaksInitiated) {
                             Text(
                                "(Opened in Termux... Wait for completion)",
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onTertiary.copy(alpha=0.7f),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Continue Button
        Button(
            onClick = onContinue,
            // Enforce only core setup
            enabled = setupInitiated,  
            colors = ButtonDefaults.buttonColors(
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                disabledContainerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                if (setupInitiated) "Continue" else "Initialize Environment",
                color = if (setupInitiated) androidx.compose.material3.MaterialTheme.colorScheme.onPrimary else androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun FinalInstructionsStep(
    onContinue: () -> Unit
) {
    var acknowledgeTermuxRunning by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Step 9: Almost Done!",
            color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Warning Card
        androidx.compose.material3.Card(
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.errorContainer
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, androidx.compose.material3.MaterialTheme.colorScheme.error, RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⚠️", fontSize = 24.sp)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        "Important Note",
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "FluxLinux runs on top of Termux.",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "You must keep Termux running in the background. Do not swipe close Termux from your recent apps!",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Acknowledge Checkbox
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { acknowledgeTermuxRunning = !acknowledgeTermuxRunning }
                .padding(8.dp)
        ) {
            Checkbox(
                checked = acknowledgeTermuxRunning,
                onCheckedChange = { acknowledgeTermuxRunning = it },
                colors = CheckboxDefaults.colors(
                    checkedColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    uncheckedColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha=0.6f)
                )
            )
            Text(
                "I understand I must keep Termux running in the background",
                fontSize = 14.sp,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        
        if (acknowledgeTermuxRunning) {
            Spacer(modifier = Modifier.height(32.dp))
            
            // Complete Button
            Button(
                onClick = onContinue,
                colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    "Complete Setup",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun SystemCheckStep(
    onContinue: () -> Unit
) {
    val context = LocalContext.current
    val memoryInfo = remember { SystemInfoUtils.getMemoryInfo(context) }
    
    Column(
        modifier = Modifier
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
            Text(
                text = "Step 8: System Check",
                color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "Checking your system resources",
                color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground.copy(alpha=0.7f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // RAM Info Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.3f))
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "💾",
                        fontSize = 32.sp,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                    Column {
                        Text(
                            text = "System RAM",
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "%.2f GB".format(memoryInfo.totalRamGB),
                            color = androidx.compose.material3.MaterialTheme.colorScheme.secondary,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                // RAM Info/Warning based on amount
                when {
                    memoryInfo.totalRamGB >= 7f -> {
                        // Good RAM
                        Spacer(modifier = Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer)
                                .padding(12.dp)
                        ) {
                            Column {
                                Text(
                                    text = "✓ Good RAM",
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.secondary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Your RAM is sufficient. For optimal performance, 12GB RAM would be great!",
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground.copy(alpha=0.8f),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                    memoryInfo.totalRamGB < 6f -> {
                        // Critical low RAM
                        Spacer(modifier = Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(androidx.compose.material3.MaterialTheme.colorScheme.errorContainer)
                                .padding(12.dp)
                        ) {
                            Column {
                                Text(
                                    text = "🚨 CRITICAL: Low RAM",
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Minimum required RAM: 8GB\nYour system will experience severe performance issues and instability.",
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground.copy(alpha=0.8f),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // SWAP Info Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(androidx.compose.material3.MaterialTheme.colorScheme.surface.copy(alpha = 0.3f))
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "🔄",
                        fontSize = 32.sp,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                    Column {
                        Text(
                            text = "System SWAP",
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "%.2f GB".format(memoryInfo.totalSwapGB),
                            color = if (memoryInfo.totalSwapGB <= 7.9f) androidx.compose.material3.MaterialTheme.colorScheme.error else androidx.compose.material3.MaterialTheme.colorScheme.secondary,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                // SWAP Critical Warning
                if (memoryInfo.totalSwapGB <= 7.9f) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(androidx.compose.material3.MaterialTheme.colorScheme.errorContainer)
                            .padding(12.dp)
                    ) {
                        Column {
                            Text(
                                text = "🚨 CRITICAL: Low SWAP",
                                color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Minimum required SWAP: 8GB\nWithout sufficient SWAP, your Linux environment will be UNSTABLE and may crash.",
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground.copy(alpha=0.8f),
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    try {
                                        val intent = android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                                        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                        context.startActivity(intent)
                                        Toast.makeText(context, "Enable more SWAP in system settings", Toast.LENGTH_LONG).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Could not open settings", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.error),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Go to Settings", color = Color.White, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        Spacer(modifier = Modifier.height(32.dp))
        
        // Next Button
        Button(
            onClick = onContinue,
            colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                "Continue",
                color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}




@Composable
fun BusyBoxInstallStep(
    onContinue: () -> Unit
) {
    val context = LocalContext.current
    val isRooted = remember { RootUtils.isRootAvailable() }
    var probing by remember { mutableStateOf(true) }
    var resolvedPath by remember { mutableStateOf<String?>(null) }
    var ackOptionalModule by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            RootShell.ensureBusyBoxResolver(context.applicationContext)
        }
        RootShell.probeBusyBox { path ->
            resolvedPath = path
            probing = false
        }
    }

    val found = !resolvedPath.isNullOrEmpty()

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Step 6: BusyBox",
            color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        when {
            probing -> {
                Text(
                    "Looking for BusyBox…",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                )
            }
            found -> {
                Text(
                    "Using the BusyBox that came with your root manager. You do not need to flash a separate module.",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    resolvedPath.orEmpty(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            isRooted -> {
                androidx.compose.material3.Card(
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, androidx.compose.material3.MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("📦", fontSize = 24.sp)
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    "BusyBox NDK",
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                                Text(
                                    "Optional fallback if chroot setup cannot find a manager BusyBox",
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            "If install fails later, flash BusyBox NDK in Magisk / KernelSU / APatch.",
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            "Credit: osm0sis",
                            color = androidx.compose.material3.MaterialTheme.colorScheme.secondary,
                            fontSize = 12.sp
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                val url = "https://xdaforums.com/attachments/update-busybox-installer-v1-36-1-all-signed-zip.6000117/"
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                context.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primary)
                        ) {
                            Text("Download Module", color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { ackOptionalModule = !ackOptionalModule }
                        .padding(8.dp)
                ) {
                    Checkbox(
                        checked = ackOptionalModule,
                        onCheckedChange = { ackOptionalModule = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                            uncheckedColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    )
                    Text(
                        "I will install BusyBox NDK only if chroot setup fails",
                        fontSize = 14.sp,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f))
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = androidx.compose.material3.MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            "Not rooted? You can safely skip this step.",
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        if (!probing) {
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onContinue,
                colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    "Continue",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun HelpAndSupportStep(
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Step 10: Help & Support",
            color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Check out our documentation or join the community",
            color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground.copy(alpha=0.7f),
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // FluxLinux Setup Link
        androidx.compose.material3.Card(
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().clickable {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/abhay-byte/fluxlinux/blob/main/docs/tutorial/setup_fluxlinux.md"))
                context.startActivity(intent)
            }
        ) {
            Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(painter = painterResource(id = R.drawable.logo), contentDescription = null, modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text("FluxLinux Setup", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Step-by-Step Installation", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Debian PRoot Link
        androidx.compose.material3.Card(
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().clickable {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/abhay-byte/fluxlinux/blob/main/docs/tutorial/setup_debian_proot.md"))
                context.startActivity(intent)
            }
        ) {
            Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(painter = painterResource(id = R.drawable.distro_debian), contentDescription = null, modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text("Debian PRoot", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Rootless Installation Guide", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Debian Chroot Link
        androidx.compose.material3.Card(
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().clickable {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/abhay-byte/fluxlinux/blob/main/docs/tutorial/setup_debian_chroot.md"))
                context.startActivity(intent)
            }
        ) {
            Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(painter = painterResource(id = R.drawable.distro_debian), contentDescription = null, modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text("Debian Chroot", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Rooted Installation Guide", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Discord Link
        androidx.compose.material3.Card(
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = Color(0xFF5865F2)
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().clickable {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.gg/tag9kXAs2x"))
                context.startActivity(intent)
            }
        ) {
            Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painter = painterResource(id = R.drawable.ic_discord), contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text("Join our Discord", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("Get help and share setups", color = Color.White.copy(alpha=0.8f), fontSize = 14.sp)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = onComplete,
            colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Complete Setup", color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}
