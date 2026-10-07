package com.ivarna.fluxlinux.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.core.data.Distro
import com.ivarna.fluxlinux.core.data.DistroRepository
import com.ivarna.fluxlinux.core.install.HostBootstrap
import com.ivarna.fluxlinux.core.install.OnboardingInstallRunner
import com.ivarna.fluxlinux.core.root.RootShell
import com.ivarna.fluxlinux.core.system.SharedStorageAccess
import com.ivarna.fluxlinux.core.terminal.TerminalLauncher
import com.ivarna.fluxlinux.ui.components.CompactDistroCard
import com.ivarna.fluxlinux.ui.components.MethodChip
import com.ivarna.fluxlinux.ui.components.MethodTab
import com.ivarna.fluxlinux.ui.components.MethodTabs
import com.ivarna.fluxlinux.ui.components.isChrootCard
import com.ivarna.fluxlinux.ui.install.InstallProgressPanel
import com.ivarna.fluxlinux.ui.theme.BrandCream
import com.ivarna.fluxlinux.ui.theme.FluxAccentCyan
import com.ivarna.fluxlinux.ui.theme.FluxAccentMagenta
import com.ivarna.fluxlinux.ui.theme.FluxDarkGrey
import com.ivarna.fluxlinux.ui.theme.FluxDarkSurface
import com.ivarna.fluxlinux.ui.theme.FluxBodyMuted
import com.ivarna.fluxlinux.ui.theme.FluxHairline
import com.ivarna.fluxlinux.ui.theme.FluxSwitchUncheckedTrack
import com.ivarna.fluxlinux.ui.theme.fluxMutedText

private enum class OnboardStep { Welcome, Consent, HostSetup, DistroPick, Options, Storage, Running, Done }

/**
 * Redesigned full first-run onboarding:
 * Welcome → Disclosure/Consent → Host Setup → Distro Selection → Options → Progress → Complete.
 * Installs rootfs + XFCE + customization with unified glassmorphic styling and responsive layouts.
 */
@Composable
fun OnboardingFlowScreen(
    onFinished: () -> Unit,
    onOpenTerminal: () -> Unit = {},
    onStartDesktop: (distroId: String) -> Unit = {}
) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(OnboardStep.Welcome) }
    var selectedDistroId by remember { mutableStateOf("debian") }
    var theme by remember { mutableStateOf("dark") }

    var percent by remember { mutableIntStateOf(0) }
    var phaseLabel by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf("") }
    var logText by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showLog by remember { mutableStateOf(true) }

    val runner = remember { OnboardingInstallRunner(context.applicationContext) }
    DisposableEffect(Unit) {
        onDispose { runner.cancel() }
    }

    fun startInstall() {
        step = OnboardStep.Running
        percent = 0
        phaseLabel = "Starting…"
        detail = ""
        logText = ""
        failed = false
        errorMessage = null
        runner.start(selectedDistroId, theme) { p ->
            percent = p.overallPercent
            phaseLabel = p.phaseLabel
            detail = p.detail
            p.logLine?.let { line ->
                logText = (logText + line + "\n").takeLast(12_000)
            }
            if (p.finished) {
                if (p.failed) {
                    failed = true
                    errorMessage = p.errorMessage
                } else {
                    step = OnboardStep.Done
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FluxDarkSurface)
            .systemBarsPadding()
    ) {
        when (step) {
            OnboardStep.Welcome -> WelcomePage(onNext = { step = OnboardStep.Consent })
            OnboardStep.Consent -> ConsentPage(
                onBack = { step = OnboardStep.Welcome },
                onNext = { step = OnboardStep.HostSetup }
            )
            OnboardStep.HostSetup -> HostSetupPage(
                onBack = { step = OnboardStep.Consent },
                onNext = { step = OnboardStep.DistroPick }
            )
            OnboardStep.DistroPick -> DistroPickPage(
                selectedId = selectedDistroId,
                onSelect = { selectedDistroId = it },
                onBack = { step = OnboardStep.HostSetup },
                onNext = { step = OnboardStep.Options }
            )
            OnboardStep.Options -> OptionsPage(
                distroId = selectedDistroId,
                theme = theme,
                onTheme = { theme = it },
                onBack = { step = OnboardStep.DistroPick },
                onInstall = {
                    if (SharedStorageAccess.declared(context) && !SharedStorageAccess.granted()) {
                        step = OnboardStep.Storage
                    } else {
                        startInstall()
                    }
                }
            )
            OnboardStep.Storage -> StoragePage(
                onBack = { step = OnboardStep.Options },
                onNext = { startInstall() }
            )
            OnboardStep.Running -> InstallProgressPanel(
                percent = percent,
                phaseLabel = phaseLabel,
                detail = detail,
                logText = logText,
                failed = failed,
                errorMessage = errorMessage,
                showLog = showLog,
                onToggleLog = { showLog = !showLog },
                onRetry = { startInstall() },
                onBack = {
                    runner.cancel()
                    step = OnboardStep.Options
                }
            )
            OnboardStep.Done -> DonePage(
                distroId = selectedDistroId,
                onHome = onFinished,
                onTerminal = onOpenTerminal,
                onDesktop = { onStartDesktop(selectedDistroId) }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. WELCOME PAGE
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun WelcomePage(onNext: () -> Unit) {
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Hero Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Glowing Logo Container
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                FluxAccentMagenta.copy(alpha = 0.25f),
                                FluxAccentCyan.copy(alpha = 0.25f)
                            )
                        )
                    )
                    .border(1.dp, FluxHairline, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_logo),
                    contentDescription = "FluxLinux Logo",
                    modifier = Modifier.size(44.dp)
                )
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = "FluxLinux",
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                color = colors.onBackground,
                letterSpacing = 0.5.sp
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = "Full Linux desktop on Android — no Termux app required",
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                color = colors.onBackground.copy(alpha = 0.72f),
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        // Scrollable Features & Illustration Area
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Feature 1: PRoot & Chroot
            ModernFeatureCard(
                icon = Icons.Filled.Storage,
                iconTint = FluxAccentMagenta,
                title = "PRoot & Chroot",
                description = "No root required with PRoot mode, or run with full native speed via Chroot on rooted devices."
            )

            // Feature 2: Desktop & Themes
            ModernFeatureCard(
                icon = Icons.Filled.DesktopWindows,
                iconTint = FluxAccentCyan,
                title = "XFCE4 Desktop",
                description = "Pre-configured desktop experience with Flux glass themes, icons, wallpapers, and fonts."
            )

            // Feature 3: Integrated Terminal & X11
            ModernFeatureCard(
                icon = Icons.Filled.Terminal,
                iconTint = BrandCream,
                title = "Embedded Terminal & X11",
                description = "Built-in hardware accelerated X11 display and multi-tab terminal with Oh My Zsh integration."
            )

            Spacer(Modifier.height(4.dp))

            // Responsive Illustration Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 130.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface.copy(alpha = 0.45f))
                    .border(1.dp, FluxHairline, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.onboarding_bg_1),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Transparent,
                                    FluxDarkSurface.copy(alpha = 0.65f)
                                )
                            )
                        )
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Get Started CTA
        FluxPrimaryButton(
            text = "Get Started",
            icon = Icons.Filled.ChevronRight,
            onClick = onNext
        )
    }
}

@Composable
private fun ModernFeatureCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    description: String
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface.copy(alpha = 0.72f))
            .border(1.dp, FluxHairline, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconTint.copy(alpha = 0.15f))
                .border(1.dp, iconTint.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                color = colors.onSurface.copy(alpha = 0.68f),
                fontSize = 12.5.sp,
                lineHeight = 16.sp
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. CONSENT PAGE (GITHUB DOWNLOAD NOTICE)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ConsentPage(onBack: () -> Unit, onNext: () -> Unit) {
    var downloadConsent by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        // Step Navigation Bar
        StepHeader(
            currentStep = 1,
            totalSteps = 5,
            title = "External Downloads",
            onBack = onBack
        )

        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            // Notice Header Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface.copy(alpha = 0.72f))
                    .border(1.dp, FluxHairline, RoundedCornerShape(16.dp))
                    .padding(18.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(FluxAccentMagenta.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CloudDownload,
                                contentDescription = null,
                                tint = FluxAccentMagenta,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "F-Droid & Package Notice",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = "The F-Droid APK is intentionally lightweight and does not bundle large Linux OS archives. To provide a complete Linux environment, FluxLinux downloads the required system components directly from GitHub Releases upon continuation:",
                        color = colors.onSurface.copy(alpha = 0.75f),
                        fontSize = 13.5.sp,
                        lineHeight = 19.sp
                    )

                    Spacer(Modifier.height(14.dp))

                    // Breakdown chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DownloadItemBadge(
                            title = "Host Prefix",
                            size = "~124 MiB",
                            modifier = Modifier.weight(1f)
                        )
                        DownloadItemBadge(
                            title = "Distro Rootfs",
                            size = "~80-150 MiB",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Interactive Consent Card
            FluxConsentCheckboxCard(
                consented = downloadConsent,
                onConsentChange = { downloadConsent = it }
            )
        }

        Spacer(Modifier.height(16.dp))

        // Navigation Action Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FluxGhostButton(
                text = "Back",
                onClick = onBack,
                modifier = Modifier.weight(0.7f)
            )

            FluxPrimaryButton(
                text = "Continue",
                enabled = downloadConsent,
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                onClick = onNext,
                modifier = Modifier.weight(1.3f)
            )
        }
    }
}

@Composable
private fun DownloadItemBadge(
    title: String,
    size: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(FluxDarkSurface.copy(alpha = 0.8f))
            .border(1.dp, FluxHairline, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = size,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = FluxAccentCyan
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. HOST SETUP PAGE
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HostSetupPage(onBack: () -> Unit, onNext: () -> Unit) {
    val context = LocalContext.current
    var bootstrapDone by remember { mutableStateOf(TerminalLauncher.isHostSetupDone(context)) }
    var initializing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var statusText by remember { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme

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
                else "Setup failed — check device storage."
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        StepHeader(
            currentStep = 2,
            totalSteps = 5,
            title = "Host Environment",
            onBack = onBack
        )

        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Initialize the local embedded Linux prefix, PulseAudio server, and terminal toolchain before selecting your distribution.",
                color = colors.onBackground.copy(alpha = 0.72f),
                fontSize = 14.sp,
                lineHeight = 19.sp
            )

            Spacer(Modifier.height(16.dp))

            // Dedicated Host Status Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface.copy(alpha = 0.75f))
                    .border(1.dp, FluxHairline, RoundedCornerShape(16.dp))
                    .padding(18.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (bootstrapDone) Color(0xFF69F0AE).copy(alpha = 0.15f)
                                        else FluxAccentMagenta.copy(alpha = 0.15f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (bootstrapDone) Icons.Filled.CheckCircle else Icons.Filled.Memory,
                                    contentDescription = null,
                                    tint = if (bootstrapDone) Color(0xFF69F0AE) else FluxAccentMagenta,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Embedded Host Prefix",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.onSurface
                                )
                                Text(
                                    text = if (bootstrapDone) "Initialized & Ready" else "Requires Setup",
                                    fontSize = 12.sp,
                                    color = if (bootstrapDone) Color(0xFF69F0AE) else colors.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }

                        MethodChip(
                            label = if (bootstrapDone) "READY" else if (initializing) "EXTRACTING" else "PENDING",
                            color = if (bootstrapDone) Color(0xFF69F0AE) else if (initializing) FluxAccentMagenta else Color(0xFFFFA000)
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    if (initializing) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = statusText.ifBlank { "Extracting bootstrap…" },
                                    fontSize = 12.sp,
                                    color = colors.onSurface.copy(alpha = 0.75f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${(progress * 100).toInt()}%",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = FluxAccentMagenta
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = FluxAccentMagenta,
                                trackColor = colors.surfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    } else if (!bootstrapDone) {
                        Text(
                            text = "Tap below to extract the core userland files and start the embedded runtime.",
                            fontSize = 12.5.sp,
                            color = colors.onSurface.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { startInit() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = FluxAccentMagenta.copy(alpha = 0.20f),
                                contentColor = Color.White
                            ),
                            border = BorderStroke(1.dp, FluxAccentMagenta.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Initialize Host", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    } else {
                        Text(
                            text = "Host environment is extracted and operational. PulseAudio and internal session services are active.",
                            fontSize = 12.5.sp,
                            color = colors.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Navigation Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FluxGhostButton(
                text = "Back",
                onClick = onBack,
                modifier = Modifier.weight(0.7f)
            )

            FluxPrimaryButton(
                text = if (bootstrapDone) "Continue" else "Initialize",
                enabled = !initializing,
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                onClick = {
                    if (bootstrapDone) onNext() else startInit()
                },
                modifier = Modifier.weight(1.3f)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. DISTRO SELECTION PAGE
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DistroPickPage(
    selectedId: String,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    var methodTab by remember { mutableStateOf(MethodTab.PROOT) }
    var rootAvailable by remember { mutableStateOf(false) }
    var probingRoot by remember { mutableStateOf(false) }
    var showRootHint by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    fun applyTab(tab: MethodTab) {
        methodTab = tab
        val next = DistroRepository.installableVariant(selectedId, chroot = tab == MethodTab.CHROOT)
        if (next != null && next.id != selectedId) onSelect(next.id)
    }

    fun probeRoot(openChrootIfGranted: Boolean) {
        probingRoot = true
        RootShell.probeRootAvailable(forceClearCache = openChrootIfGranted) { ok ->
            rootAvailable = ok
            probingRoot = false
            if (ok) {
                showRootHint = false
                if (openChrootIfGranted) applyTab(MethodTab.CHROOT)
            } else if (openChrootIfGranted) {
                showRootHint = true
                if (methodTab == MethodTab.CHROOT) applyTab(MethodTab.PROOT)
            }
        }
    }

    LaunchedEffect(Unit) { probeRoot(openChrootIfGranted = false) }

    val forTab = DistroRepository.supportedDistros.filter { distro ->
        if (methodTab == MethodTab.CHROOT) distro.chrootSupported else distro.prootSupported
    }
    val installable = forTab.filter { !it.comingSoon }
    val comingSoon = forTab.filter { it.comingSoon }.sortedBy { it.name }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        StepHeader(
            currentStep = 3,
            totalSteps = 5,
            title = "Choose Distribution",
            onBack = onBack
        )

        Spacer(Modifier.height(14.dp))

        // PRoot vs Chroot Mode Tabs
        MethodTabs(
            selected = methodTab,
            onSelected = { tab ->
                if (tab == MethodTab.CHROOT && !rootAvailable) {
                    showRootHint = true
                    probeRoot(openChrootIfGranted = true)
                } else {
                    applyTab(tab)
                }
            },
            chrootLabel = "Chroot (Rooted)",
            chrootEnabled = true,
            horizontalPadding = 0.dp
        )

        // Root Warning / Notice Card
        if (showRootHint && !rootAvailable) {
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.error.copy(alpha = 0.12f))
                    .border(1.dp, colors.error.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = colors.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (probingRoot) "Checking superuser access…"
                        else "Grant superuser to FluxLinux in Magisk/KernelSU, then tap Chroot again.",
                        color = colors.error,
                        fontSize = 12.5.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Distro Card List
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Available Distributions",
                fontWeight = FontWeight.Bold,
                color = colors.onBackground,
                fontSize = 14.sp
            )

            installable.forEach { distro ->
                ModernSelectableDistroCard(
                    distro = distro,
                    selected = selectedId == distro.id,
                    onClick = { onSelect(distro.id) }
                )
            }

            if (comingSoon.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Coming Soon",
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onBackground.copy(alpha = 0.6f),
                    fontSize = 13.sp
                )
                comingSoon.forEach { distro ->
                    CompactDistroCard(distro = distro)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Navigation Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FluxGhostButton(
                text = "Back",
                onClick = onBack,
                modifier = Modifier.weight(0.7f)
            )

            FluxPrimaryButton(
                text = "Continue",
                enabled = installable.any { it.id == selectedId },
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                onClick = onNext,
                modifier = Modifier.weight(1.3f)
            )
        }
    }
}

@Composable
private fun ModernSelectableDistroCard(
    distro: Distro,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val borderColor by animateColorAsState(
        if (selected) FluxAccentMagenta else FluxHairline,
        label = "border"
    )
    val bgColor = if (selected) FluxAccentMagenta.copy(alpha = 0.12f)
    else colors.surface.copy(alpha = 0.72f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .border(if (selected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = FluxAccentMagenta,
                unselectedColor = colors.onSurface.copy(alpha = 0.4f)
            )
        )

        Spacer(Modifier.width(8.dp))

        if (distro.iconRes != null) {
            Image(
                painter = painterResource(id = distro.iconRes),
                contentDescription = distro.name,
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
            Spacer(Modifier.width(12.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = distro.name,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    fontSize = 15.5.sp
                )
                MethodChip(
                    label = if (distro.chrootSupported) "CHROOT" else "PROOT",
                    color = if (distro.chrootSupported) Color(0xFFE040FB) else Color(0xFF00B8D4)
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = distro.description,
                color = colors.onSurface.copy(alpha = 0.68f),
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 5. OPTIONS PAGE
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun OptionsPage(
    distroId: String,
    theme: String,
    onTheme: (String) -> Unit,
    onBack: () -> Unit,
    onInstall: () -> Unit
) {
    val distro = DistroRepository.supportedDistros.find { it.id == distroId }
    var downloadConsent by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        StepHeader(
            currentStep = 4,
            totalSteps = 5,
            title = "Desktop Options",
            onBack = onBack
        )

        Spacer(Modifier.height(14.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Selected Distro Overview Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface.copy(alpha = 0.72f))
                    .border(1.dp, FluxHairline, RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (distro?.iconRes != null) {
                        Image(
                            painter = painterResource(id = distro.iconRes),
                            contentDescription = null,
                            modifier = Modifier.size(38.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = distro?.name ?: distroId,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = colors.onSurface
                        )
                        Text(
                            text = "Base XFCE4 desktop with Flux customizations",
                            fontSize = 12.sp,
                            color = colors.onSurface.copy(alpha = 0.65f)
                        )
                    }
                }
            }

            // Appearance Theme Selection
            Text(
                text = "Appearance Theme",
                fontWeight = FontWeight.Bold,
                color = colors.onBackground,
                fontSize = 14.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ThemeOptionCard(
                    title = "Dark Theme",
                    desc = "Sleek default",
                    isDark = true,
                    selected = theme == "dark",
                    onSelect = { onTheme("dark") },
                    modifier = Modifier.weight(1f)
                )
                ThemeOptionCard(
                    title = "Light Theme",
                    desc = "Bright workspace",
                    isDark = false,
                    selected = theme == "light",
                    onSelect = { onTheme("light") },
                    modifier = Modifier.weight(1f)
                )
            }

            // Package Manifest Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface.copy(alpha = 0.75f))
                    .border(1.dp, FluxHairline, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Layers,
                            contentDescription = null,
                            tint = FluxAccentCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Package Manifest Included",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    val items = listOf(
                        when {
                            distroId.startsWith("alpine") -> "Alpine 3.24 minirootfs"
                            distroId.startsWith("fedora") -> "Fedora 44 container rootfs"
                            distroId.startsWith("void") -> "Void Linux (glibc aarch64) rootfs"
                            distroId.startsWith("opensuse") -> "openSUSE Tumbleweed rootfs"
                            else -> "Debian 13 rootfs"
                        },
                        "XFCE4 lightweight desktop environment",
                        if (distroId.startsWith("fedora") || distroId.startsWith("void") || distroId.startsWith("opensuse"))
                            "Mesa / VirGL 3D hardware acceleration" else null,
                        "Flux themes, wallpapers, and Nerd fonts",
                        "Embedded X11 display server + Zsh terminal"
                    ).filterNotNull()

                    items.forEach { item ->
                        Row(
                            modifier = Modifier.padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(FluxAccentMagenta)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = item,
                                fontSize = 12.5.sp,
                                color = colors.onSurface.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            if (distroId.contains("chroot")) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.error.copy(alpha = 0.12f))
                        .border(1.dp, colors.error.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = "Rooted path: Magisk, KernelSU, or APatch superuser is required for Chroot guest execution.",
                        color = colors.error,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            // Download Consent Checkbox Card
            FluxConsentCheckboxCard(
                consented = downloadConsent,
                onConsentChange = { downloadConsent = it }
            )
        }

        Spacer(Modifier.height(16.dp))

        // Navigation Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FluxGhostButton(
                text = "Back",
                onClick = onBack,
                modifier = Modifier.weight(0.7f)
            )

            FluxPrimaryButton(
                text = "Install Now",
                enabled = downloadConsent,
                icon = Icons.Filled.Download,
                onClick = onInstall,
                modifier = Modifier.weight(1.3f)
            )
        }
    }
}

@Composable
private fun ThemeOptionCard(
    title: String,
    desc: String,
    isDark: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val borderColor = if (selected) FluxAccentMagenta else FluxHairline

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) FluxAccentMagenta.copy(alpha = 0.12f) else colors.surface.copy(alpha = 0.72f))
            .border(if (selected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onSelect)
            .padding(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Swatch preview circle
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(if (isDark) Color(0xFF1E1E24) else Color(0xFFE8EAF6))
                        .border(1.dp, FluxHairline, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = if (isDark) FluxAccentMagenta else Color(0xFF1E1E24),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                RadioButton(
                    selected = selected,
                    onClick = onSelect,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = FluxAccentMagenta,
                        unselectedColor = colors.onSurface.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = colors.onSurface
            )
            Text(
                text = desc,
                fontSize = 11.sp,
                color = colors.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 6. DONE / COMPLETION PAGE
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DonePage(
    distroId: String,
    onHome: () -> Unit,
    onTerminal: () -> Unit,
    onDesktop: () -> Unit
) {
    val distro = DistroRepository.supportedDistros.find { it.id == distroId }
    val displayName = distro?.name?.removeSuffix(" (Rooted)") ?: distroId
    val isChroot = distro?.isChrootCard() == true
    val methodColor = if (isChroot) Color(0xFFE040FB) else Color(0xFF00B8D4)
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Celebration Badge
        Box(contentAlignment = Alignment.BottomEnd) {
            Box(
                modifier = Modifier
                    .size(108.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                colors.surface.copy(alpha = 0.85f),
                                colors.surfaceVariant.copy(alpha = 0.6f)
                            )
                        )
                    )
                    .border(1.5.dp, FluxHairline, RoundedCornerShape(32.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (distro?.iconRes != null) {
                    Image(
                        painter = painterResource(id = distro.iconRes),
                        contentDescription = displayName,
                        modifier = Modifier.size(68.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF69F0AE),
                        modifier = Modifier.size(52.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(FluxDarkSurface)
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF69F0AE)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = FluxDarkSurface,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "You're Ready!",
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            color = colors.onBackground
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "$displayName base desktop is installed. Launch your graphical session or open an interactive shell.",
            textAlign = TextAlign.Center,
            color = colors.onBackground.copy(alpha = 0.72f),
            fontSize = 14.5.sp,
            lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        Spacer(Modifier.height(14.dp))

        MethodChip(
            label = if (isChroot) "CHROOT GUEST" else "PROOT GUEST",
            color = methodColor
        )

        Spacer(Modifier.height(32.dp))

        // Primary Action: Launch Desktop
        Button(
            onClick = onDesktop,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = BrandCream,
                contentColor = FluxDarkGrey
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Filled.DesktopWindows, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Start Desktop", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        Spacer(Modifier.height(10.dp))

        // Secondary Action: Open Terminal
        Button(
            onClick = onTerminal,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = FluxAccentMagenta.copy(alpha = 0.20f),
                contentColor = Color.White
            ),
            border = BorderStroke(1.dp, FluxAccentMagenta.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Filled.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("Open Terminal", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }

        Spacer(Modifier.height(10.dp))

        // Tertiary Action: Go to Dashboard
        Button(
            onClick = onHome,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.surface.copy(alpha = 0.6f),
                contentColor = colors.onSurface
            ),
            border = BorderStroke(1.dp, FluxHairline),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Filled.Home, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Go to Dashboard", fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// STORAGE ACCESS PAGE (ivarna flavor, Android 11+, only while not yet granted)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StoragePage(onBack: () -> Unit, onNext: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var resumeKey by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumeKey++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val granted = remember(resumeKey) { SharedStorageAccess.granted() }
    val success = Color(0xFF69F0AE)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        StepHeader(currentStep = 5, totalSteps = 5, title = "Access your files", onBack = onBack)

        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background((if (granted) success else FluxAccentMagenta).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.FolderShared,
                    contentDescription = "Shared storage",
                    tint = if (granted) success else FluxAccentMagenta,
                    modifier = Modifier.size(44.dp)
                )
            }

            Text(
                text = "Your Linux distros can read and write your phone's storage (/sdcard: Downloads, Documents, USB drives), so you can open and save files from Linux apps.",
                color = colors.onSurface.copy(alpha = 0.75f),
                fontSize = 13.5.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface.copy(alpha = 0.72f))
                    .border(1.dp, FluxHairline, RoundedCornerShape(16.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                StorageBenefit(Icons.Filled.FolderOpen, "Open files from Linux apps")
                StorageBenefit(Icons.Filled.Save, "Save to Downloads and Documents")
                StorageBenefit(Icons.Filled.Usb, "Use USB drives and SD cards")
            }

            if (granted) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(success.copy(alpha = 0.12f))
                        .border(1.dp, success.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = success, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Access granted", color = success, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            } else {
                Text(
                    text = "You can change this later in Settings > Shared storage.",
                    color = colors.onSurface.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        FluxPrimaryButton(
            text = if (granted) "Continue" else "Grant access",
            icon = if (granted) Icons.AutoMirrored.Filled.ArrowForward else null,
            onClick = if (granted) onNext else { { SharedStorageAccess.openSettings(context) } }
        )
        if (!granted) {
            FluxGhostButton(text = "Skip for now", onClick = onNext, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun StorageBenefit(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = FluxAccentCyan, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// REUSABLE ONBOARDING UI COMPONENTS
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StepHeader(
    currentStep: Int,
    totalSteps: Int,
    title: String,
    onBack: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val progress = currentStep.toFloat() / totalSteps.toFloat()

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = BrandCream,
                    modifier = Modifier.size(20.dp)
                )
            }

            Text(
                text = "Step $currentStep of $totalSteps",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onBackground.copy(alpha = 0.55f)
            )
        }

        Spacer(Modifier.height(6.dp))

        // Step Progress Bar
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = FluxAccentMagenta,
            trackColor = colors.surfaceVariant.copy(alpha = 0.4f)
        )

        Spacer(Modifier.height(14.dp))

        Text(
            text = title,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = colors.onBackground
        )
    }
}

@Composable
private fun FluxConsentCheckboxCard(
    consented: Boolean,
    onConsentChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val downloadsHost = HostBootstrap.downloadsFromRelease(context.packageName)
    val body = if (downloadsHost) {
        "I understand this setup downloads Linux system images (host bootstrap and distro rootfs) from GitHub Releases. Those files are not bundled in the F-Droid APK."
    } else {
        "I understand this setup downloads the selected Linux distro rootfs from GitHub Releases. That archive is not in the base APK."
    }
    val colors = MaterialTheme.colorScheme
    val borderColor = if (consented) FluxAccentMagenta else FluxHairline

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (consented) FluxAccentMagenta.copy(alpha = 0.08f) else colors.surface.copy(alpha = 0.72f))
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable { onConsentChange(!consented) }
            .padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Checkbox(
            checked = consented,
            onCheckedChange = onConsentChange,
            colors = CheckboxDefaults.colors(
                checkedColor = FluxAccentMagenta,
                uncheckedColor = colors.onSurface.copy(alpha = 0.45f)
            )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = body,
            color = colors.onSurface.copy(alpha = 0.85f),
            fontSize = 12.5.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(top = 10.dp)
        )
    }
}

@Composable
private fun FluxPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = BrandCream,
            contentColor = FluxDarkGrey,
            disabledContainerColor = FluxSwitchUncheckedTrack,
            disabledContentColor = FluxBodyMuted
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        if (icon != null) {
            Spacer(Modifier.width(6.dp))
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun FluxGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = BrandCream)
    ) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

