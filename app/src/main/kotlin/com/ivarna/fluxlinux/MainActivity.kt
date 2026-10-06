package com.ivarna.fluxlinux

import android.Manifest
import com.ivarna.fluxlinux.core.gpu.DriverRelease
import com.ivarna.fluxlinux.core.gpu.GpuDriverInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.isGranted
import com.ivarna.fluxlinux.ui.components.BottomTab
import com.ivarna.fluxlinux.ui.components.GlassBottomNavigation
import com.ivarna.fluxlinux.ui.components.GlassScaffold
import com.ivarna.fluxlinux.ui.theme.FluxLinuxTheme
import com.ivarna.fluxlinux.core.utils.StateManager
import com.ivarna.fluxlinux.core.utils.ThemePreferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi

// Screen navigation enum
enum class Screen {
    ONBOARDING,
    PREREQUISITES,
    HOME,
    SETTINGS,
    SETTINGS_TERMINAL,
    SETTINGS_X11,
    SETTINGS_AUDIO,
    SETTINGS_CHROOT,
    SETTINGS_CHROOT_DETAIL,
    SETTINGS_PROOT,
    SETTINGS_PROOT_DETAIL,
    SETTINGS_LEGACY_TERMUX,
    TROUBLESHOOTING,
    ROOT_ACCESS,
    INSTALL_WIZARD,
    DISTRO_SETTINGS,
    TERMINAL
}

class MainActivity : ComponentActivity() {
    
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleScriptCallback(intent)
        if (intent.getStringExtra("EXTRA_TARGET_PAGE") == "terminal") {
            // FGS notification tap → jump to in-app terminal (bottom-nav tab)
            setIntent(intent)
            lifecycleScope.launch { }
            currentScreenRef?.value = Screen.HOME
            currentTabRef?.value = BottomTab.TERMINAL
        } else if (intent.getStringExtra("EXTRA_TARGET_PAGE") == "home" || intent.getStringExtra("target_page") == "home") {
            setIntent(intent)
            currentScreenRef?.value = Screen.HOME
            currentTabRef?.value = BottomTab.HOME
        }
    }

    /** Screen state holder set by onCreate (used by FGS notification tap). */
    private var currentScreenRef: kotlinx.coroutines.flow.MutableStateFlow<Screen>? = null

    /** Bottom-tab holder so FGS / external intents can select Terminal. */
    private var currentTabRef: kotlinx.coroutines.flow.MutableStateFlow<BottomTab>? = null

    private fun handleScriptCallback(intent: android.content.Intent) {
        android.util.Log.d("FluxLinux", "handleScriptCallback called with action: ${intent.action}, data: ${intent.data}")
        // Handle Deep Link: fluxlinux://callback?result=success&name=setup_termux
        if (intent.action == android.content.Intent.ACTION_VIEW && intent.data?.scheme == "fluxlinux") {
            val uri = intent.data
            val result = uri?.getQueryParameter("result")
            val scriptName = uri?.getQueryParameter("name") ?: "unknown"
            val installedComponents = uri?.getQueryParameter("components") // Legacy
            
            android.util.Log.d("FluxLinux", "Deep Link received: result=$result, scriptName=$scriptName")
            
            if (scriptName.startsWith("legacy_termux_")) {
                com.ivarna.fluxlinux.core.legacy.LegacyTermuxCallbacks.handle(this, result, scriptName, uri)
                consumeCallbackIntent()
                return
            }

            if (result == "success") {
                 // Check Queue first
                 val queueManager = com.ivarna.fluxlinux.core.utils.InstallationQueueManager
                 val currentTask = queueManager.currentTask
                 
                 // If the callback matches the current task, proceed queue.
                 // Strict match: the script's callback name must equal the current task's id.
                 // The previous `|| scriptName == "base_install"` fallback was redundant
                 // (base install tasks have id="base_install") AND allowed the base-install
                 // callback to be attributed to a stale/different task (e.g. a debian
                 // component task left over from a previous screen), which would set state
                 // for the wrong distro.
                 if (currentTask != null && scriptName == currentTask.id) {
                     android.widget.Toast.makeText(this, "Task '${currentTask.name}' Complete. Proceeding...", android.widget.Toast.LENGTH_SHORT).show()
                     
                     if (currentTask.type == com.ivarna.fluxlinux.core.utils.TaskType.BASE_INSTALL) {
                         // Mark Distro Installed on Base Success
                         // We assume we know the distro ID from state or we just mark currently selected?
                         // Ideally we pass distro ID in callback, or use 'selectedDistro' variable if available?
                         // MainActivity is recreated? No, usually singleTop/SingleTask. 'selectedDistro' state might receive it.
                         // But for safety, we rely on 'distro_install_ID' naming convention if possible, but our base script has generic name.
                         // Let's rely on 'base_install' result means 'selectedDistro' (which should be saved/restored if activity died).
                         // Actually, 'selectedDistro' is inside setContent scope.
                         // We need a class-level property or StateManager access.
                         // For now, let's assume 'selectedDistro' is unavailable here and we rely on user manually refreshing or existing state?
                         // NO, we MUST update StateManager.
                         // FIX: Let's extract Distro ID from the Task if possible.
                         // But Task struct doesn't have distroId yet.
                         // We'll trust the processNextInstallTask logic to handle the flow.
                         // Marking distro installed:
                         // We can iterate Distros and match ID? Or just skip marking if ID not known?
                         // The Base Script DOES NOT include ID in "name=base_install". 
                         // However, InstallQueueManager is singleton. We can store context there?
                         // Let's just process queue. The user will see "Installed" in UI eventually.
                     }
                     
                     // Mark Component as Installed in StateManager
                     val distroId = currentTask.distroId
                     if (currentTask.type == com.ivarna.fluxlinux.core.utils.TaskType.COMPONENT) {
                         if (currentTask.isUninstall) {
                             // Uninstall completion: clear the "installed" flag
                             StateManager.setComponentInstalled(this, distroId, currentTask.id, false)
                             android.widget.Toast.makeText(this, "${currentTask.name.replace("Uninstall ", "")} Uninstalled 🗑️", android.widget.Toast.LENGTH_LONG).show()
                         } else {
                             StateManager.setComponentInstalled(this, distroId, currentTask.id, true)
                         }
                     }
                     // Force State Update
                     StateManager.triggerRefresh()
                 } else {
                     // Legacy / Standalone handling
                     if (scriptName.startsWith("distro_install_")) {
                         val distroId = scriptName.removePrefix("distro_install_")
                         StateManager.setDistroInstalled(this, distroId, true)
                         android.widget.Toast.makeText(this, "$distroId Installed! ✅", android.widget.Toast.LENGTH_LONG).show()
                     } else if (scriptName.startsWith("distro_uninstall_")) {
                         val distroId = scriptName.removePrefix("distro_uninstall_")
                         com.ivarna.fluxlinux.core.terminal.TerminalLauncher
                             .refreshInstalledAfterUninstall(this, distroId)
                         android.widget.Toast.makeText(this, "$distroId Uninstalled! 🗑️", android.widget.Toast.LENGTH_LONG).show()
                     } else {
                         // Generic Script
                         StateManager.setScriptStatus(this, scriptName, true)
                         android.widget.Toast.makeText(this, "Script '$scriptName' details saved.", android.widget.Toast.LENGTH_SHORT).show()
                     }
                 }
                 
                 // Process Next
                 processNextInstallTask()
                 
            } else {
                 // Component failed
                 android.widget.Toast.makeText(this, "Task '$scriptName' failed! ❌", android.widget.Toast.LENGTH_LONG).show()
                 com.ivarna.fluxlinux.core.utils.InstallationQueueManager.clear() // Stop queue on failure
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not — FGS still works, but FGS notification visibility
           improves on Android 13+ when POST_NOTIFICATIONS is granted. */ }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Component chain after a successful base install (M1): runs the wizard's
     * selected components sequentially in the parent distro's terminal component.
     * Each step only advances after the previous session exits 0 (B3 gate).
     */
    private fun runComponentChain(
        distro: com.ivarna.fluxlinux.core.data.Distro,
        components: List<com.ivarna.fluxlinux.core.data.DistroComponent>,
        theme: String,
        gpu: String
    ) {
        if (components.isEmpty()) return
        runComponentStep(distro, components, 0, theme, gpu)
    }

    private fun isCustomizationComponent(component: com.ivarna.fluxlinux.core.data.DistroComponent): Boolean =
        component.id == "customization" || component.id == "kde_customization" ||
            component.scriptName.contains("setup_customization")

    /**
     * Load a component script and prepend flux_gpu_common.sh for hw_accel.
     * [extraEnv] is already resolved (no FLUX_GPU=auto).
     */
    private fun loadGuestComponentScript(
        ctx: android.content.Context,
        component: com.ivarna.fluxlinux.core.data.DistroComponent,
        extraEnv: Map<String, String>
    ): String? {
        val scriptManager = com.ivarna.fluxlinux.core.data.ScriptManager(ctx)
        val envBlock = extraEnv.entries.joinToString("\n") {
            "export ${it.key}=\"${it.value}\""
        }
        val base = scriptManager.getScriptContent(component.scriptName)
        val common = if (component.id == "hw_accel") {
            runCatching {
                scriptManager.getScriptContent("common/setup/flux_gpu_common.sh")
            }.getOrDefault("")
        } else {
            ""
        }
        return buildString {
            if (extraEnv.isNotEmpty()) append(envBlock).append("\n\n")
            if (common.isNotBlank()) append(common).append("\n\n")
            append(base)
        }
    }

    /**
     * Host-stage theme/icons + Oh My Zsh into the proot rootfs before guest
     * customization runs. Distro Settings used to skip this (only onboarding
     * did), so guest hung on missing git / corrupt OMZ rm. Merges skip flags
     * into [extraEnv]. Safe to call off the main thread.
     *
     * [distroId] selects the proot container (`debian`, `alpine`, …). Defaults
     * to debian only when the id is unknown — never stage Alpine assets into
     * the Debian tree (or vice versa).
     */
    private fun stageCustomizationHostEnv(
        activity: android.content.Context,
        extraEnv: Map<String, String>,
        distroId: String = "debian"
    ): Map<String, String> {
        val theme = extraEnv["FLUX_THEME"] ?: "dark"
        val merged = extraEnv.toMutableMap()
        val profile = com.ivarna.fluxlinux.core.install.DistroInstallProfile.forId(distroId)
        // Chroot cards stage into the chroot guest /tmp (root copy) and let the
        // guest extract. Never target the sibling proot container, and never
        // set FLUX_SKIP_THEME_ICONS for a chroot — the guest must extract.
        if (profile?.method == "chroot") {
            val chrootPath = profile.chrootPath
                ?: com.ivarna.fluxlinux.core.root.ChrootPaths.CHROOT_PATH
            try {
                com.ivarna.fluxlinux.core.install.ProotXfceAssetInstaller.installToChroot(
                    activity, theme, chrootPath
                ) { line ->
                    android.util.Log.i("FluxLinux", "Chroot theme ($chrootPath): $line")
                }
            } catch (e: Exception) {
                android.util.Log.w("FluxLinux", "Chroot theme stage failed", e)
            }
            // OMZ + pokemon are guest-side for pure chroot (no host git).
            // Do not set FLUX_SKIP_OMZ / FLUX_SKIP_POKEMON — script defaults try.
            return merged
        }
        val prootName = profile?.prootName?.takeIf { it.isNotBlank() }
            ?: distroId.removeSuffix("_chroot").ifBlank { "debian" }
        try {
            val themeOk = com.ivarna.fluxlinux.core.install.ProotXfceAssetInstaller.install(
                activity, theme, prootName
            ) { line ->
                android.util.Log.i("FluxLinux", "Host theme ($prootName): $line")
            }
            if (themeOk) merged["FLUX_SKIP_THEME_ICONS"] = "1"
        } catch (e: Exception) {
            android.util.Log.w("FluxLinux", "Host theme stage failed", e)
        }
        try {
            val omzOk = com.ivarna.fluxlinux.core.install.ProotZshBootstrap.install(
                activity, prootName
            ) { line ->
                android.util.Log.i("FluxLinux", "Host OMZ ($prootName): $line")
            }
            // Only skip guest OMZ when *this* rootfs actually has oh-my-zsh.sh
            if (omzOk) merged["FLUX_SKIP_OMZ"] = "1"
        } catch (e: Exception) {
            android.util.Log.w("FluxLinux", "Host OMZ stage failed", e)
        }
        // Try pokemon on Distro Settings re-run (60s guest clone; skip only if caller set 1).
        if (!merged.containsKey("FLUX_SKIP_POKEMON")) {
            merged["FLUX_SKIP_POKEMON"] = "0"
        }
        return merged
    }

    private fun runComponentStep(
        distro: com.ivarna.fluxlinux.core.data.Distro,
        components: List<com.ivarna.fluxlinux.core.data.DistroComponent>,
        index: Int,
        theme: String,
        gpu: String
    ) {
        if (index >= components.size) return
        val component = components[index]
        val baseEnv = if (component.id == "hw_accel") {
            val det = com.ivarna.fluxlinux.core.terminal.GpuAccelDetector.detect()
            com.ivarna.fluxlinux.core.terminal.GpuAccelDetector.persist(this, det)
            val mode = com.ivarna.fluxlinux.core.terminal.GpuAccelDetector.resolveFluxGpu(gpu)
            mapOf("FLUX_GPU" to mode, "FLUX_GPU_VENDOR" to det.vendorHint)
        } else {
            mapOf("FLUX_THEME" to theme)
        }
        fun openWith(extraEnv: Map<String, String>) {
            val scriptContent = try {
                loadGuestComponentScript(this, component, extraEnv)
            } catch (e: Exception) {
                android.util.Log.e("FluxLinux", "Failed to load ${component.scriptName}", e)
                null
            }
            if (scriptContent == null) {
                runComponentStep(distro, components, index + 1, theme, gpu)
                return
            }
            val opened = com.ivarna.fluxlinux.core.terminal.FluxTerminalSessionManager.openComponentSession(
                this,
                distro = distro,
                scriptContent = scriptContent,
                title = component.name,
                extraEnv = extraEnv,
                isUninstall = false,
                onFinished = {
                    com.ivarna.fluxlinux.core.utils.StateManager.setComponentInstalled(
                        this, distro.id, component.id, true
                    )
                    if (component.id == "hw_accel") refreshDriverMarker(distro)
                    com.ivarna.fluxlinux.core.utils.StateManager.triggerRefresh()
                    runComponentStep(distro, components, index + 1, theme, gpu)
                }
            )
            if (!opened) {
                android.widget.Toast.makeText(
                    this, "Max tabs reached — ${component.name} skipped (retry in Distro Settings)",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                runComponentStep(distro, components, index + 1, theme, gpu)
            }
        }
        if (isCustomizationComponent(component)) {
            Thread {
                val env = stageCustomizationHostEnv(this, baseEnv, distro.id)
                runOnUiThread { openWith(env) }
            }.start()
        } else if (component.id == "hw_accel" && baseEnv["FLUX_GPU"] in listOf("turnip", "panvk")) {
            Thread {
                val env = baseEnv + GpuDriverInstaller.guestEnv(this, distro.id)
                runOnUiThread {
                    if (GpuDriverInstaller.pendingDistro(this) != null) armDriverResume()
                    openWith(env)
                }
            }.start()
        } else {
            openWith(baseEnv)
        }
    }

    /**
     * Embedded component install/uninstall: run the component script inside the
     * guest via the parent distro's terminal component (proot session or chroot
     * SSOT helper). Marks state when the session finishes.
     */
    private fun runEmbeddedComponent(
        activity: MainActivity,
        distro: com.ivarna.fluxlinux.core.data.Distro,
        component: com.ivarna.fluxlinux.core.data.DistroComponent,
        extraEnv: Map<String, String>,
        isUninstall: Boolean,
        onOpenTerminalScreen: () -> Unit = {}
    ) {
        com.ivarna.fluxlinux.core.terminal.TerminalLauncher.prepareHost(activity) { ok ->
            if (!ok) {
                android.widget.Toast.makeText(
                    activity, "Host bootstrap not ready — check Settings",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return@prepareHost
            }
            fun openWith(env: Map<String, String>) {
                val scriptContent = try {
                    loadGuestComponentScript(activity, component, env)
                } catch (e: Exception) {
                    android.util.Log.e("FluxLinux", "Failed to load ${component.scriptName}", e)
                    null
                }
                if (scriptContent == null) return
                val title = (if (isUninstall) "Uninstall " else "Install ") + component.name
                val opened = com.ivarna.fluxlinux.core.terminal.FluxTerminalSessionManager.openComponentSession(
                    activity,
                    distro = distro,
                    scriptContent = scriptContent,
                    title = title,
                    extraEnv = env,
                    isUninstall = isUninstall,
                    onFinished = {
                        com.ivarna.fluxlinux.core.utils.StateManager.setComponentInstalled(
                            activity, distro.id, component.id, !isUninstall
                        )
                        if (!isUninstall && component.id == "hw_accel") refreshDriverMarker(distro)
                        com.ivarna.fluxlinux.core.utils.StateManager.triggerRefresh()
                    }
                )
                if (opened) {
                    onOpenTerminalScreen()
                }
            }
            if (!isUninstall && component.id == "hw_accel") {
                // Adreno: pick the stable Turnip release (network) off the main thread.
                Thread {
                    val merged = extraEnv.toMutableMap()
                    val raw = extraEnv["FLUX_GPU"]
                    val det = com.ivarna.fluxlinux.core.terminal.GpuAccelDetector.detect()
                    com.ivarna.fluxlinux.core.terminal.GpuAccelDetector.persist(activity, det)
                    merged["FLUX_GPU"] =
                        com.ivarna.fluxlinux.core.terminal.GpuAccelDetector.resolveFluxGpu(raw)
                    if (!merged.containsKey("FLUX_GPU_VENDOR")) {
                        merged["FLUX_GPU_VENDOR"] = det.vendorHint
                    }
                    if (merged["FLUX_GPU"] in listOf("turnip", "panvk")) {
                        merged.putAll(GpuDriverInstaller.guestEnv(activity, distro.id))
                    }
                    activity.runOnUiThread {
                        if (GpuDriverInstaller.pendingDistro(activity) != null) armDriverResume()
                        openWith(merged)
                    }
                }.start()
                return@prepareHost
            }
            val resolvedEnv = extraEnv
            if (!isUninstall && isCustomizationComponent(component)) {
                android.widget.Toast.makeText(
                    activity, "Preparing themes & Oh My Zsh on host…",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                Thread {
                    val env = stageCustomizationHostEnv(activity, extraEnv, distro.id)
                    activity.runOnUiThread { openWith(env) }
                }.start()
            } else {
                openWith(resolvedEnv)
            }
        }
    }

    /**
     * Legacy queue runner: the clipboard→Termux flow was replaced by in-app
     * terminal sessions. Kept as a no-op that clears any stale queue state.
     */
    private fun processNextInstallTask() {
        com.ivarna.fluxlinux.core.utils.InstallationQueueManager.clear()
    }

    private fun consumeCallbackIntent() {
        setIntent(android.content.Intent(this, MainActivity::class.java).apply { action = android.content.Intent.ACTION_MAIN })
    }

    private fun dispatchLegacyTermuxCallback(intent: android.content.Intent?) {
        if (intent?.action == android.content.Intent.ACTION_VIEW && intent.data?.scheme == "fluxlinux") {
            val uri = intent.data
            val scriptName = uri?.getQueryParameter("name") ?: return
            if (scriptName.startsWith("legacy_termux_")) {
                val result = uri.getQueryParameter("result")
                com.ivarna.fluxlinux.core.legacy.LegacyTermuxCallbacks.handle(this, result, scriptName, uri)
                consumeCallbackIntent()
            }
        }
    }

    /** Update installed-driver prefs from the guest marker (off main thread). */
    private fun refreshDriverMarker(distro: com.ivarna.fluxlinux.core.data.Distro) {
        val p = com.ivarna.fluxlinux.core.install.DistroInstallProfile.forId(distro.id) ?: return
        Thread {
            GpuDriverInstaller.refreshInstalled(this, distro.id, p.prootName, p.method == "chroot")
        }.start()
    }

    /** Offline install pending: wait for internet (app open), then resume it. */
    private fun armDriverResume() {
        val id = GpuDriverInstaller.pendingDistro(this) ?: return
        if (DriverRelease.online(this)) return
        android.widget.Toast.makeText(
            this,
            "No internet — GPU driver not installed. Linux will use software rendering until you retry (Distro Settings > Hardware Acceleration > Retry). Waiting for internet…",
            android.widget.Toast.LENGTH_LONG
        ).show()
        DriverRelease.armResume(this) {
            runOnUiThread {
                val d = com.ivarna.fluxlinux.core.data.DistroRepository.supportedDistros
                    .find { it.id == id } ?: return@runOnUiThread
                val c = d.components.find { it.id == "hw_accel" } ?: return@runOnUiThread
                runEmbeddedComponent(this, d, c, mapOf("FLUX_GPU" to "auto"), false)
            }
        }
    }

    override fun onDestroy() {
        GpuDriverInstaller.onPending = null
        DriverRelease.disarm(this)
        super.onDestroy()
    }

    @OptIn(ExperimentalPermissionsApi::class, ExperimentalHazeMaterialsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GpuDriverInstaller.onPending = { runOnUiThread { armDriverResume() } }
        armDriverResume()
        if (savedInstanceState == null) {
            dispatchLegacyTermuxCallback(intent)
        }
        requestNotificationPermissionIfNeeded()
        setContent {
            // Force Permanent Dark Mode
            val currentThemeMode = com.ivarna.fluxlinux.core.utils.ThemeMode.DARK
            
            FluxLinuxTheme(themeMode = currentThemeMode) {
                val onboardingComplete = StateManager.isOnboardingComplete(this@MainActivity)
                val showPhantomPrompt by com.ivarna.fluxlinux.core.system.PhantomProcessFixer.promptVisible.collectAsState()
                if (showPhantomPrompt) {
                    com.ivarna.fluxlinux.ui.components.PhantomProcessDialog(warning = true) {
                        com.ivarna.fluxlinux.core.system.PhantomProcessFixer.promptVisible.value = false
                    }
                }

                // Permission State (Lifted for Settings and Home access)
                val permissionState = rememberPermissionState(
                    permission = "com.termux.permission.RUN_COMMAND"
                )

                // Navigation state
                var currentScreen by remember { 
                    mutableStateOf(if (onboardingComplete) Screen.HOME else Screen.ONBOARDING) 
                }
                currentScreenRef = remember { kotlinx.coroutines.flow.MutableStateFlow(currentScreen) }
                LaunchedEffect(currentScreen) { currentScreenRef?.value = currentScreen }
                
                var currentTab by remember { mutableStateOf(BottomTab.HOME) }
                currentTabRef = remember { kotlinx.coroutines.flow.MutableStateFlow(currentTab) }
                LaunchedEffect(currentTab) { currentTabRef?.value = currentTab }
                // FGS / external intent may flip tab via currentTabRef
                val externalTab by (currentTabRef ?: kotlinx.coroutines.flow.MutableStateFlow(BottomTab.HOME))
                    .collectAsState()
                LaunchedEffect(externalTab) {
                    if (externalTab != currentTab) currentTab = externalTab
                }
                val externalScreen by (currentScreenRef
                    ?: kotlinx.coroutines.flow.MutableStateFlow(Screen.HOME)).collectAsState()
                LaunchedEffect(externalScreen) {
                    if (externalScreen != currentScreen) currentScreen = externalScreen
                }

                /** Open the Terminal bottom-nav page (termux-lib style). */
                val openTerminalTab: () -> Unit = {
                    currentScreen = Screen.HOME
                    currentTab = BottomTab.TERMINAL
                }

                BackHandler {
                    if (currentScreen == Screen.SETTINGS_CHROOT_DETAIL) {
                        currentScreen = Screen.SETTINGS_CHROOT
                    } else if (currentScreen == Screen.SETTINGS_PROOT_DETAIL) {
                        currentScreen = Screen.SETTINGS_PROOT
                    } else if (currentScreen == Screen.SETTINGS_TERMINAL
                        || currentScreen == Screen.SETTINGS_X11
                        || currentScreen == Screen.SETTINGS_AUDIO
                        || currentScreen == Screen.SETTINGS_CHROOT
                        || currentScreen == Screen.SETTINGS_PROOT
                        || currentScreen == Screen.SETTINGS_LEGACY_TERMUX
                        || currentScreen == Screen.TROUBLESHOOTING
                        || currentScreen == Screen.ROOT_ACCESS) {
                        currentScreen = Screen.SETTINGS
                    } else if (currentScreen == Screen.SETTINGS
                        || currentScreen == Screen.DISTRO_SETTINGS
                        || currentScreen == Screen.INSTALL_WIZARD) {
                        currentScreen = Screen.HOME
                        currentTab = BottomTab.HOME
                    } else if (currentScreen == Screen.ONBOARDING || currentScreen == Screen.PREREQUISITES) {
                        finish()
                    } else if (currentScreen == Screen.HOME && currentTab != BottomTab.HOME) {
                        if (currentTab == BottomTab.TERMINAL) {
                            com.ivarna.fluxlinux.ui.terminal.hideIme(window.decorView)
                        }
                        currentTab = BottomTab.HOME
                    } else {
                        finish()
                    }
                }
                
                // Selected Distro for Wizard/Settings
                var selectedDistro by remember { mutableStateOf<com.ivarna.fluxlinux.core.data.Distro?>(null) }
                // Selected target id for Chroot/PRoot storage detail
                var storageTargetId by remember { mutableStateOf<String?>(null) }
                
                // Refresh key to force UI update on resume
                // Collected from StateManager for remote triggers too
                val refreshKey by com.ivarna.fluxlinux.core.utils.StateManager.refreshTrigger.collectAsState()
                
                // ALSO react to Lifecycle
                var lifecycleRefreshKey by remember { mutableStateOf(0) }
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            lifecycleRefreshKey++
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                
                // Helpers for service/activity
                val onStartServiceStub: (android.content.Intent) -> Unit = { intent ->
                    try { startService(intent) } catch (e: Exception) { android.util.Log.e("FluxLinux", "StartService failed", e) }
                }
                val onStartActivityStub: (android.content.Intent) -> Unit = { intent ->
                    try { startActivity(intent) } catch (e: Exception) { android.util.Log.e("FluxLinux", "StartActivity failed", e) }
                }
                
                // Navigation Callbacks
                val onNavigateToInstall: (com.ivarna.fluxlinux.core.data.Distro) -> Unit = { distro ->
                    selectedDistro = distro
                    currentScreen = Screen.INSTALL_WIZARD
                }
                val onNavigateToDistroSettings: (com.ivarna.fluxlinux.core.data.Distro) -> Unit = { distro ->
                    selectedDistro = distro
                    currentScreen = Screen.DISTRO_SETTINGS
                }

                // ── In-app terminal actions (termux-flux-terminal / chroot-root-shell) ──
                val onOpenTerminal: (String, Boolean) -> Unit = { distroId, root ->
                    val method = try {
                        com.ivarna.fluxlinux.core.data.terminalComponentFor(distroId).method
                    } catch (_: Exception) {
                        "proot"
                    }
                    val type = if (root) "shell-root" else "shell"
                    val label = com.ivarna.fluxlinux.core.install.DistroInstallProfile
                        .forId(distroId)?.displayName
                        ?: distroId
                    val title = when (method) {
                        "chroot" -> if (root) "$label Root Shell" else "$label Shell"
                        else -> if (root) "$label Shell (root)" else "$label Shell"
                    }
                    com.ivarna.fluxlinux.core.terminal.FluxTerminalSessionManager.openSessionAfterHost(
                        this@MainActivity,
                        type = type,
                        title = title,
                        method = method,
                        distroId = distroId,
                        onResult = { result ->
                            if (result == com.ivarna.fluxlinux.core.terminal.FluxTerminalSessionManager.SessionOpenResult.OPENED) {
                                openTerminalTab()
                            } else {
                                android.widget.Toast.makeText(
                                    this@MainActivity,
                                    "Host bootstrap not ready — check Settings",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    )
                }

                val startEmbeddedInstall: (com.ivarna.fluxlinux.core.data.Distro, String?, () -> Unit) -> Unit =
                    { distro, setupB64, onBaseInstalled ->
                        com.ivarna.fluxlinux.core.terminal.TerminalLauncher.prepareHost(
                            this@MainActivity,
                            onDone = { ok ->
                                if (!ok) {
                                    android.widget.Toast.makeText(
                                        this@MainActivity,
                                        "Host bootstrap failed to extract",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                    return@prepareHost
                                }
                                val opened = com.ivarna.fluxlinux.core.terminal.FluxTerminalSessionManager
                                    .openInstallSession(
                                        this@MainActivity,
                                        distro,
                                        setupB64,
                                        onFinished = {
                                            // B3: fires only when the install session exited 0
                                            com.ivarna.fluxlinux.core.utils.StateManager.setDistroInstalled(
                                                this@MainActivity, distro.id, true
                                            )
                                            com.ivarna.fluxlinux.core.utils.StateManager.triggerRefresh()
                                            onBaseInstalled()
                                        }
                                    )
                                if (opened) {
                                    openTerminalTab()
                                }
                            }
                        )
                    }

                
                @Composable
                fun MainScreenContent(
                    tab: BottomTab,
                    hazeState: HazeState
                ) {
                    when (tab) {
                        BottomTab.HOME -> {
                            com.ivarna.fluxlinux.ui.screens.HomeScreen(
                                permissionState = permissionState,
                                hazeState = hazeState,
                                scriptRefreshTrigger = refreshKey + lifecycleRefreshKey,
                                onStartService = onStartServiceStub,
                                onStartActivity = onStartActivityStub,
                                // Pass navigation callbacks
                                onNavigateToInstall = onNavigateToInstall,
                                onNavigateToSettings = onNavigateToDistroSettings,
                                onOpenTerminal = onOpenTerminal,
                                onShowTerminal = openTerminalTab
                            )
                        }
                        BottomTab.DISTROS -> {
                            com.ivarna.fluxlinux.ui.screens.DistroScreen(
                                permissionState = permissionState,
                                hazeState = hazeState,
                                onStartService = onStartServiceStub,
                                onStartActivity = onStartActivityStub,
                                onNavigateToInstall = onNavigateToInstall
                            )
                        }
                        BottomTab.TERMINAL -> {
                            com.ivarna.fluxlinux.ui.screens.TerminalScreen(
                                onBack = { currentTab = BottomTab.HOME },
                                embeddedInBottomNav = false
                            )
                        }
                    }
                }

                // Helper for Top Bar
                @Composable
                fun TopBar(
                    hazeState: HazeState,
                    onSettingsClick: () -> Unit,
                    onTerminalClick: () -> Unit,
                    onOpenDisplay: () -> Unit,
                    displayLive: Boolean,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .hazeChild(
                                state = hazeState,
                                style = HazeStyle(
                                    backgroundColor = MaterialTheme.colorScheme.background.copy(alpha = 0.7f),
                                    blurRadius = 20.dp,
                                    tint = null
                                )
                            )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .windowInsetsPadding(WindowInsets.statusBars),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_logo),
                                    contentDescription = "Logo",
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "FluxLinux",
                                    color = MaterialTheme.colorScheme.onBackground,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (!displayLive && com.ivarna.fluxlinux.core.terminal.TerminalLauncher.isBootstrapExtracted(LocalContext.current)) {
                                   Text(
                                       text = "Host: embedded",
                                       style = MaterialTheme.typography.labelSmall,
                                       color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                                       modifier = Modifier.padding(end = 4.dp)
                                   )
                                }

                                IconButton(onClick = onOpenDisplay) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.DesktopWindows,
                                            contentDescription = if (displayLive) "Open X11 display — desktop running" else "Open X11 display",
                                            tint = MaterialTheme.colorScheme.onBackground
                                        )
                                        if (displayLive) {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .align(Alignment.TopEnd)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFF69F0AE))
                                            )
                                        }
                                    }
                                }

                                IconButton(onClick = onTerminalClick) {
                                    Icon(
                                        imageVector = Icons.Default.Terminal,
                                        contentDescription = "Terminal",
                                        tint = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                                IconButton(onClick = onSettingsClick) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = "Settings",
                                        tint = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                            }
                        }
                    }
                }
                
                // Show appropriate screen based on state
                when (currentScreen) {
                    Screen.ONBOARDING -> {
                        com.ivarna.fluxlinux.ui.onboarding.OnboardingFlowScreen(
                            onFinished = {
                                StateManager.setOnboardingComplete(this@MainActivity, true)
                                currentScreen = Screen.HOME
                                currentTab = BottomTab.HOME
                            },
                            onOpenTerminal = {
                                StateManager.setOnboardingComplete(this@MainActivity, true)
                                currentScreen = Screen.HOME
                                currentTab = BottomTab.TERMINAL
                            },
                            onStartDesktop = { distroId ->
                                StateManager.setOnboardingComplete(this@MainActivity, true)
                                currentScreen = Screen.HOME
                                currentTab = BottomTab.HOME
                                // GUI flags owned by DesktopLauncher
                                com.ivarna.fluxlinux.core.desktop.DesktopLauncher.start(
                                    this@MainActivity, distroId
                                )
                            }
                        )
                    }
                    Screen.HOME -> {
                        val hazeState = remember { HazeState() }
                        val showTopBar = currentTab != BottomTab.TERMINAL
                        val desktopUi by com.ivarna.fluxlinux.core.desktop.DesktopLauncher.uiState.collectAsState()
                        val stateRefresh by com.ivarna.fluxlinux.core.utils.StateManager.refreshTrigger.collectAsState()
                        val session = remember(desktopUi, stateRefresh) {
                            com.ivarna.fluxlinux.core.desktop.DesktopSessionQuery.current(this@MainActivity, desktopUi)
                        }

                        LaunchedEffect(currentTab) {
                            if (currentTab != BottomTab.TERMINAL) {
                                com.ivarna.fluxlinux.ui.terminal.hideIme(window.decorView)
                            }
                        }

                        GlassScaffold(
                            hazeState = hazeState,
                            blurContent = currentTab == BottomTab.HOME,
                            topBar = {
                                if (showTopBar) {
                                    TopBar(
                                        hazeState = hazeState,
                                        onSettingsClick = { currentScreen = Screen.SETTINGS },
                                        onTerminalClick = { currentTab = BottomTab.TERMINAL },
                                        onOpenDisplay = {
                                            com.ivarna.fluxlinux.core.desktop.DesktopLauncher.reopenDisplay(this@MainActivity)
                                        },
                                        displayLive = session != null
                                    )
                                }
                            },
                            bottomBar = {
                                if (currentTab != BottomTab.TERMINAL) {
                                    GlassBottomNavigation(
                                        selectedTab = currentTab,
                                        onTabSelected = { currentTab = it },
                                        hazeState = hazeState
                                    )
                                }
                            }
                        ) {
                            MainScreenContent(
                                tab = currentTab,
                                hazeState = hazeState
                            )
                        }
                    }
                    Screen.SETTINGS -> {
                        com.ivarna.fluxlinux.ui.screens.SettingsScreen(
                            onBack = { currentScreen = Screen.HOME },
                            permissionState = permissionState,
                            onStartService = onStartServiceStub,
                            onStartActivity = onStartActivityStub,
                            onNavigateToOnboarding = {
                                StateManager.setOnboardingComplete(this@MainActivity, false)
                                currentScreen = Screen.ONBOARDING
                            },
                            onNavigateToTroubleshooting = { currentScreen = Screen.TROUBLESHOOTING },
                            onNavigateToRootCheck = { currentScreen = Screen.ROOT_ACCESS },
                            onNavigateToTerminalSettings = {
                                currentScreen = Screen.SETTINGS_TERMINAL
                            },
                            onNavigateToX11Settings = {
                                currentScreen = Screen.SETTINGS_X11
                            },
                            onNavigateToAudioSettings = {
                                currentScreen = Screen.SETTINGS_AUDIO
                            },
                            onNavigateToChrootSettings = {
                                currentScreen = Screen.SETTINGS_CHROOT
                            },
                            onNavigateToProotSettings = {
                                currentScreen = Screen.SETTINGS_PROOT
                            },
                            onNavigateToLegacyTermuxSettings = {
                                currentScreen = Screen.SETTINGS_LEGACY_TERMUX
                            }
                        )
                    }
                    Screen.SETTINGS_TERMINAL -> {
                        com.ivarna.fluxlinux.ui.screens.TerminalSettingsScreen(
                            onBack = { currentScreen = Screen.SETTINGS }
                        )
                    }
                    Screen.SETTINGS_X11 -> {
                        com.ivarna.fluxlinux.ui.screens.X11SettingsScreen(
                            onBack = { currentScreen = Screen.SETTINGS }
                        )
                    }
                    Screen.SETTINGS_AUDIO -> {
                        com.ivarna.fluxlinux.ui.screens.AudioSettingsScreen(
                            onBack = { currentScreen = Screen.SETTINGS }
                        )
                    }
                    Screen.SETTINGS_CHROOT -> {
                        com.ivarna.fluxlinux.ui.screens.ChrootStorageListScreen(
                            onBack = { currentScreen = Screen.SETTINGS },
                            onSelectDistro = { id ->
                                storageTargetId = id
                                currentScreen = Screen.SETTINGS_CHROOT_DETAIL
                            },
                            onNavigateToDistros = {
                                currentScreen = Screen.HOME
                                currentTab = BottomTab.DISTROS
                            }
                        )
                    }
                    Screen.SETTINGS_CHROOT_DETAIL -> {
                        val targetId = storageTargetId
                        if (targetId == null) {
                            LaunchedEffect(Unit) {
                                currentScreen = Screen.SETTINGS_CHROOT
                            }
                        } else {
                            com.ivarna.fluxlinux.ui.screens.ChrootStorageDetailScreen(
                                distroId = targetId,
                                onBack = { currentScreen = Screen.SETTINGS_CHROOT },
                                onNavigateToInstall = {
                                    selectedDistro = com.ivarna.fluxlinux.core.data.DistroRepository
                                        .supportedDistros
                                        .firstOrNull { it.id == targetId }
                                    currentScreen = if (selectedDistro != null) {
                                        Screen.INSTALL_WIZARD
                                    } else {
                                        Screen.SETTINGS_CHROOT
                                    }
                                }
                            )
                        }
                    }
                    Screen.SETTINGS_PROOT -> {
                        com.ivarna.fluxlinux.ui.screens.ProotStorageListScreen(
                            onBack = { currentScreen = Screen.SETTINGS },
                            onSelectDistro = { id ->
                                storageTargetId = id
                                currentScreen = Screen.SETTINGS_PROOT_DETAIL
                            },
                            onNavigateToDistros = {
                                currentScreen = Screen.HOME
                                currentTab = BottomTab.DISTROS
                            }
                        )
                    }
                    Screen.SETTINGS_PROOT_DETAIL -> {
                        val targetId = storageTargetId
                        if (targetId == null) {
                            LaunchedEffect(Unit) {
                                currentScreen = Screen.SETTINGS_PROOT
                            }
                        } else {
                            com.ivarna.fluxlinux.ui.screens.ProotStorageDetailScreen(
                                distroId = targetId,
                                onBack = { currentScreen = Screen.SETTINGS_PROOT },
                                onNavigateToInstall = {
                                    selectedDistro = com.ivarna.fluxlinux.core.data.DistroRepository
                                        .supportedDistros
                                        .firstOrNull { it.id == targetId }
                                    currentScreen = if (selectedDistro != null) {
                                        Screen.INSTALL_WIZARD
                                    } else {
                                        Screen.SETTINGS_PROOT
                                    }
                                }
                            )
                        }
                    }
                    Screen.SETTINGS_LEGACY_TERMUX -> {
                        com.ivarna.fluxlinux.ui.screens.LegacyTermuxSettingsScreen(
                            onBack = { currentScreen = Screen.SETTINGS },
                            permissionState = permissionState
                        )
                    }
                    Screen.TROUBLESHOOTING -> {
                        com.ivarna.fluxlinux.ui.screens.TroubleshootingScreen(
                            onBack = { currentScreen = Screen.SETTINGS }
                        )
                    }
                    Screen.TERMINAL -> {
                        // Legacy full-screen route — redirect to bottom-nav Terminal tab.
                        LaunchedEffect(Unit) {
                            currentScreen = Screen.HOME
                            currentTab = BottomTab.TERMINAL
                        }
                    }
                    Screen.PREREQUISITES -> { currentScreen = Screen.HOME }
                    Screen.ROOT_ACCESS -> {
                        com.ivarna.fluxlinux.ui.screens.RootAccessScreen(
                            onBack = { currentScreen = Screen.SETTINGS },
                            onEnableChroot = {
                                android.widget.Toast.makeText(this@MainActivity, "Chroot Mode Enabled", android.widget.Toast.LENGTH_SHORT).show()
                                currentScreen = Screen.SETTINGS
                            }
                        )
                    }
                    Screen.INSTALL_WIZARD -> {
                         val hazeState = remember { HazeState() }
                         if (selectedDistro != null) {
                             // Shared runner with onboarding: rootfs + XFCE + customization
                             // for both proot and chroot (no feature modules).
                             com.ivarna.fluxlinux.ui.screens.InstallConfigScreen(
                                 distro = selectedDistro!!,
                                 onBack = { currentScreen = Screen.HOME },
                                 hazeState = hazeState,
                                 onInstallComplete = {
                                     currentScreen = Screen.HOME
                                     currentTab = BottomTab.HOME
                                 }
                             )
                         } else {
                             currentScreen = Screen.HOME
                         }
                    }
                    Screen.DISTRO_SETTINGS -> {
                         val hazeState = remember { HazeState() }
                         if (selectedDistro != null) {
                              com.ivarna.fluxlinux.ui.screens.DistroSettingsScreen(
                                  distro = selectedDistro!!,
                                  onBack = { currentScreen = Screen.HOME },
                                  hazeState = hazeState,
                                   onInstallComponent = { component, extraEnv ->
                                       // Embedded: run component script inside the guest via
                                       // the same terminal component as the parent distro.
                                       runEmbeddedComponent(
                                           this@MainActivity, selectedDistro!!, component, extraEnv,
                                           isUninstall = false, onOpenTerminalScreen = openTerminalTab
                                       )
                                   },
                                   onUninstallComponent = { component ->
                                       runEmbeddedComponent(
                                           this@MainActivity, selectedDistro!!, component, emptyMap(),
                                           isUninstall = true, onOpenTerminalScreen = openTerminalTab
                                       )
                                   },
                                   onReinstallDistro = {
                                       // Full base reinstall (rootfs + XFCE + customization)
                                       // via InstallConfig / OnboardingInstallRunner — not rootfs-only.
                                       currentScreen = Screen.INSTALL_WIZARD
                                   },
                                 onUninstallDistro = {
                                     // Embedded uninstall: proot → proot-distro remove in Flux
                                     // Terminal; chroot → uninstall_debian13_chroot.sh in Root Shell.
                                     val distro = selectedDistro ?: return@DistroSettingsScreen
                                     com.ivarna.fluxlinux.core.terminal.TerminalLauncher.prepareHost(
                                         this@MainActivity,
                                         onDone = { ok ->
                                             if (!ok) return@prepareHost
                                             val opened = com.ivarna.fluxlinux.core.terminal.FluxTerminalSessionManager
                                                 .openUninstallSession(this@MainActivity, distro)
                                             if (opened) {
                                                 openTerminalTab()
                                             }
                                         }
                                     )
                                 },
                                  onStartActivity = onStartActivityStub,
                                  onNavigateToStart = { /* Not used in Settings, but if needed */ },
                                  onLaunchXfce = {
                                      try {
                                          com.ivarna.fluxlinux.core.desktop.DesktopLauncher.start(
                                              this@MainActivity, selectedDistro!!.id
                                          )
                                      } catch (e: Exception) {
                                          android.util.Log.e("FluxLinux", "Launch XFCE4 failed", e)
                                      }
                                  },
                                  onStopXfce = {
                                      try {
                                          com.ivarna.fluxlinux.core.desktop.DesktopLauncher.stop(
                                              this@MainActivity, selectedDistro!!.id
                                          )
                                      } catch (e: Exception) {
                                          android.util.Log.e("FluxLinux", "Stop XFCE4 failed", e)
                                      }
                                  },
                                  onLaunchKde = {
                                      try {
                                          com.ivarna.fluxlinux.core.desktop.DesktopLauncher.start(
                                              this@MainActivity, selectedDistro!!.id, "kde"
                                          )
                                      } catch (e: Exception) {
                                          android.util.Log.e("FluxLinux", "Launch KDE failed", e)
                                      }
                                  },
                                  onStopKde = {
                                      try {
                                          com.ivarna.fluxlinux.core.desktop.DesktopLauncher.stop(
                                              this@MainActivity, selectedDistro!!.id
                                          )
                                      } catch (e: Exception) {
                                          android.util.Log.e("FluxLinux", "Stop KDE failed", e)
                                      }
                                  }
                              )

                         } else {
                             currentScreen = Screen.HOME
                         }
                    }
                }
            }
        }
    }
}
