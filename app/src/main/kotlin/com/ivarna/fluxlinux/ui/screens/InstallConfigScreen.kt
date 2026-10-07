package com.ivarna.fluxlinux.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.core.data.Distro
import com.ivarna.fluxlinux.core.install.DistroInstallProfile
import com.ivarna.fluxlinux.core.install.OnboardingInstallRunner
import com.ivarna.fluxlinux.ui.components.GlassScaffold
import com.ivarna.fluxlinux.ui.install.InstallProgressPanel
import com.ivarna.fluxlinux.ui.install.InstallThemePickRow
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * Thin install wizard: XFCE + customization only (no feature modules).
 * Uses [OnboardingInstallRunner] for both proot and chroot.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun InstallConfigScreen(
    distro: Distro,
    onBack: () -> Unit,
    onInstallComplete: () -> Unit,
    hazeState: HazeState
) {
    val context = LocalContext.current
    val appCtx = context.applicationContext
    var selectedTheme by remember { mutableStateOf("dark") }
    var phase by remember { mutableStateOf(InstallPhase.OPTIONS) }
    var percent by remember { mutableIntStateOf(0) }
    var phaseLabel by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf("") }
    var logText by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showLog by remember { mutableStateOf(true) }
    val runner = remember { OnboardingInstallRunner(appCtx) }

    fun startInstall() {
        // Generation cancel inside runner; single instance avoids parallel jobs
        failed = false
        errorMessage = null
        percent = 0
        phaseLabel = "Starting…"
        detail = ""
        logText = ""
        phase = InstallPhase.RUNNING
        runner.start(distro.id, selectedTheme) { progress ->
            percent = progress.overallPercent
            phaseLabel = progress.phaseLabel
            detail = progress.detail
            progress.logLine?.let { line ->
                logText = (logText + line + "\n").takeLast(12_000)
            }
            if (progress.failed) {
                failed = true
                errorMessage = progress.errorMessage ?: progress.detail
            }
            if (progress.finished && !progress.failed) {
                phase = InstallPhase.DONE
                Toast.makeText(context, "Base desktop installed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { runner.cancel() }
    }

    GlassScaffold(
        hazeState = hazeState,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        when (phase) {
                            InstallPhase.OPTIONS -> "Install ${distro.name}"
                            InstallPhase.RUNNING -> if (failed) "Install failed" else "Installing…"
                            InstallPhase.DONE -> "Install complete"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (phase == InstallPhase.RUNNING && !failed) {
                                runner.cancel()
                            }
                            onBack()
                        }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
                modifier = Modifier.hazeChild(
                    state = hazeState,
                    shape = androidx.compose.ui.graphics.RectangleShape,
                    style = HazeMaterials.thin()
                )
            )
        },
        bottomBar = {
            if (phase == InstallPhase.OPTIONS) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Button(
                        onClick = { startInstall() },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            // Cream secondary = readable CTA (dark primary blends into background)
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        ),
                        shape = RoundedCornerShape(28.dp)
                    ) {
                        val cta = when {
                            distro.id.contains("chroot") -> "Install base desktop (Root)"
                            else -> "Install base desktop"
                        }
                        Text(cta, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }
    ) {
        when (phase) {
            InstallPhase.OPTIONS -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "Base desktop only",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold
                )
                val profile = DistroInstallProfile.forId(distro.id)
                val rootfsLine = when {
                    distro.id.startsWith("alpine") ->
                        "Installs Alpine 3.24 minirootfs, XFCE4 (apk), and Flux customization. "
                    distro.id.startsWith("fedora") ->
                        "Installs Fedora 44 rootfs, XFCE4 (dnf), Mesa, and Flux customization. "
                    distro.id.startsWith("void") ->
                        "Installs Void Linux rootfs, XFCE4 (xbps), Mesa, and Flux customization. "
                    distro.id.startsWith("opensuse") ->
                        "Installs openSUSE Tumbleweed rootfs, XFCE4 (zypper), Mesa, and Flux customization. "
                    else ->
                        "Installs ${profile?.displayName ?: distro.name} rootfs, XFCE4, and Flux customization. "
                }
                Text(
                    rootfsLine +
                        "App modules can be added later from Distro Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Appearance theme",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold
                )
                InstallThemePickRow(
                    id = "dark",
                    title = "Dark",
                    desc = "Sleek default",
                    selected = selectedTheme == "dark",
                    onSelect = { selectedTheme = "dark" }
                )
                InstallThemePickRow(
                    id = "light",
                    title = "Light",
                    desc = "Bright desktop",
                    selected = selectedTheme == "light",
                    onSelect = { selectedTheme = "light" }
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.90f))
                        .border(1.dp, com.ivarna.fluxlinux.ui.theme.FluxHairline, RoundedCornerShape(16.dp))
                        .padding(14.dp)
                ) {
                    Column {
                        Text(
                            "Includes",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        val bulletColor = com.ivarna.fluxlinux.ui.theme.fluxMutedText()
                        Text(
                            when {
                                distro.id.startsWith("alpine") ->
                                    "• Alpine 3.24 minirootfs (~4 MB + apk packages)"
                                distro.id.startsWith("fedora") ->
                                    "• Fedora 44 container rootfs"
                                distro.id.startsWith("void") ->
                                    "• Void Linux (glibc aarch64) rootfs"
                                distro.id.startsWith("opensuse") ->
                                    "• openSUSE Tumbleweed rootfs"
                                else ->
                                    "• ${profile?.displayName ?: distro.name} rootfs"
                            },
                            fontSize = 13.sp,
                            color = bulletColor
                        )
                        Text("• XFCE4 desktop", fontSize = 13.sp, color = bulletColor)
                        if (distro.id.startsWith("fedora") || distro.id.startsWith("void") ||
                            distro.id.startsWith("opensuse")
                        ) {
                            Text(
                                "• Mesa / VirGL hardware acceleration",
                                fontSize = 13.sp,
                                color = bulletColor
                            )
                        }
                        Text("• Flux theme / wallpapers / fonts", fontSize = 13.sp, color = bulletColor)
                        if (distro.id.contains("chroot")) {
                            Text("• Requires root (KernelSU / Magisk)", fontSize = 13.sp, color = bulletColor)
                        }
                    }
                }
                Spacer(Modifier.height(80.dp))
            }
            InstallPhase.RUNNING, InstallPhase.DONE -> InstallProgressPanel(
                percent = percent,
                phaseLabel = phaseLabel,
                detail = detail,
                logText = logText,
                failed = failed,
                errorMessage = errorMessage,
                showLog = showLog,
                onToggleLog = { showLog = !showLog },
                onRetry = { startInstall() },
                onDone = onInstallComplete,
                isDone = phase == InstallPhase.DONE
            )
        }
    }
}

private enum class InstallPhase { OPTIONS, RUNNING, DONE }
