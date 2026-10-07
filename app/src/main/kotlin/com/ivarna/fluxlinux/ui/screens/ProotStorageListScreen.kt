package com.ivarna.fluxlinux.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ivarna.fluxlinux.core.chroot.GuestStorageCatalog
import com.ivarna.fluxlinux.core.data.DistroRepository
import com.ivarna.fluxlinux.core.proot.ProotInfoStore
import com.ivarna.fluxlinux.core.proot.ProotSettingsModel
import com.ivarna.fluxlinux.core.terminal.TerminalLauncher
import com.ivarna.fluxlinux.core.utils.StateManager
import com.ivarna.fluxlinux.ui.components.GlassSettingCard
import com.ivarna.fluxlinux.ui.screens.storage.StatusBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PRoot storage list screen: displays all PRoot containers and total container size.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProotStorageListScreen(
    onBack: () -> Unit,
    onSelectDistro: (distroId: String) -> Unit,
    onNavigateToDistros: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val stateRefresh by StateManager.refreshTrigger.collectAsState()
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

    val catalogDistros = remember { GuestStorageCatalog.installableProots() }
    val sortedCatalog = remember(catalogDistros) {
        DistroRepository.sortForDistroPage(catalogDistros)
    }

    val liveSizes = remember { mutableStateMapOf<String, Long?>() }
    val installedSet = remember { mutableStateMapOf<String, Boolean>() }
    var liveMeasuring by remember { mutableStateOf(false) }

    // Phase 1 cache read
    val initialRows = remember(lifecycleRefreshKey, stateRefresh) {
        sortedCatalog.filter { d ->
            TerminalLauncher.isDistroInstalledOnFs(context, d.id) ||
                ProotInfoStore.cachedInstalled(context, d.id)
        }
    }

    LaunchedEffect(initialRows) {
        initialRows.forEach { d ->
            installedSet[d.id] = true
            if (!liveSizes.containsKey(d.id)) {
                liveSizes[d.id] = ProotInfoStore.cachedBytes(context, d.id)
            }
        }
    }

    fun runSequentialProbe() {
        if (liveMeasuring) return
        liveMeasuring = true
        scope.launch {
            val results = withContext(Dispatchers.IO) {
                val ids = sortedCatalog.map { it.id }
                ProotSettingsModel.refreshInstalledSizes(context, ids)
            }
            installedSet.clear()
            results.forEach { (id, sizeUi) ->
                if (sizeUi.installed) {
                    installedSet[id] = true
                    liveSizes[id] = sizeUi.bytes
                } else {
                    installedSet[id] = false
                    liveSizes.remove(id)
                }
            }
            liveMeasuring = false
        }
    }

    LaunchedEffect(lifecycleRefreshKey, stateRefresh) {
        runSequentialProbe()
    }

    val installedDistros = sortedCatalog.filter { d ->
        installedSet[d.id] == true || (installedSet[d.id] == null && initialRows.any { it.id == d.id })
    }

    val totalBytes: Long? = run {
        val measured = installedDistros.mapNotNull { d -> liveSizes[d.id] ?: ProotInfoStore.cachedBytes(context, d.id) }
        if (measured.isEmpty()) null else measured.sum()
    }

    val accent = MaterialTheme.colorScheme.secondary
    val body = MaterialTheme.colorScheme.onBackground
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "PRoot",
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
                "// Userspace Linux — inside app storage",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = muted
            )

            // Info banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp)
            ) {
                Text(
                    "PRoot lives in app storage (container + rootfs) and is removed if you uninstall the app.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Universal row (always present)
            GlassSettingCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectDistro(GuestStorageCatalog.ALL_PROOT_ID) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.size(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "All containers",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = body
                        )
                        Spacer(Modifier.height(4.dp))
                        val (sizeVal, sizeUnit) = ProotInfoStore.formatStorageBytes(totalBytes)
                        val sizeStr = if (sizeVal == "—") "—" else "$sizeVal $sizeUnit"
                        Text(
                            "${installedDistros.size} installed · $sizeStr",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = muted
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "View all",
                        tint = muted
                    )
                }
            }

            // Installed rows or empty state
            if (installedDistros.isNotEmpty()) {
                Text(
                    "Installed Containers",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )

                installedDistros.forEach { distro ->
                    val path = GuestStorageCatalog.prootContainerPath(context, distro.id) ?: ""
                    val bytes = liveSizes[distro.id] ?: ProotInfoStore.cachedBytes(context, distro.id)
                    val (v, u) = ProotInfoStore.formatStorageBytes(bytes)
                    val sizeStr = if (v == "—") "—" else "$v $u"

                    GlassSettingCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectDistro(distro.id) }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        distro.name,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = body
                                    )
                                    StatusBadge(text = "INSTALLED", ok = true)
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    sizeStr,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                    color = accent
                                )
                                Text(
                                    path,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = muted
                                )
                            }
                            Spacer(Modifier.size(8.dp))
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = "View detail",
                                tint = muted
                            )
                        }
                    }
                }
            } else {
                GlassSettingCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "No PRoot container installed",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = body
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Install a userspace Linux distribution from Distros.",
                            fontSize = 12.sp,
                            color = muted,
                            lineHeight = 16.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = onNavigateToDistros,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accent,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            ),
                            modifier = Modifier.fillMaxWidth().height(44.dp)
                        ) {
                            Text("Install from Distros", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Refresh sizes button
            Button(
                onClick = { runSequentialProbe() },
                enabled = !liveMeasuring,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                    disabledContainerColor = com.ivarna.fluxlinux.ui.theme.FluxSwitchUncheckedTrack,
                    disabledContentColor = com.ivarna.fluxlinux.ui.theme.FluxBodyMuted
                ),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                if (liveMeasuring) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = com.ivarna.fluxlinux.ui.theme.FluxBodyMuted
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text("Refresh sizes", fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
