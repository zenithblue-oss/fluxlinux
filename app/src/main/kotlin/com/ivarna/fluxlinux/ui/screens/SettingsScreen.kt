package com.ivarna.fluxlinux.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings as AndroidSettings
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.material.icons.filled.SdStorage
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.core.utils.ThemeMode
import com.ivarna.fluxlinux.ui.components.GlassSettingCard

private fun storageAccessDeclared(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && runCatching {
        context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_PERMISSIONS
        ).requestedPermissions?.contains(Manifest.permission.MANAGE_EXTERNAL_STORAGE) == true
    }.getOrDefault(false)

/**
 * Settings hub (nativecode-style): nav cards open detail pages.
 * Legacy Termux leftover manager on its own page; connection-fix / Prerequisites cards removed.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    permissionState: PermissionState,
    onStartService: (Intent) -> Unit,
    onStartActivity: (Intent) -> Unit,
    onNavigateToOnboarding: (() -> Unit)? = null,
    onNavigateToTroubleshooting: (() -> Unit)? = null,
    onNavigateToRootCheck: (() -> Unit)? = null,
    onNavigateToTerminalSettings: (() -> Unit)? = null,
    onNavigateToX11Settings: (() -> Unit)? = null,
    onNavigateToAudioSettings: (() -> Unit)? = null,
    onNavigateToChrootSettings: (() -> Unit)? = null,
    onNavigateToProotSettings: (() -> Unit)? = null,
    onNavigateToLegacyTermuxSettings: (() -> Unit)? = null,
    onThemeChanged: ((ThemeMode) -> Unit)? = null,
    currentTheme: ThemeMode = ThemeMode.SYSTEM
) {
    // Keep unused params so call sites stay stable (permission / theme hooks).
    @Suppress("UNUSED_PARAMETER")
    val _permissionState = permissionState
    @Suppress("UNUSED_PARAMETER")
    val _onStartService = onStartService
    @Suppress("UNUSED_PARAMETER")
    val _onStartActivity = onStartActivity
    @Suppress("UNUSED_PARAMETER")
    val _onThemeChanged = onThemeChanged
    @Suppress("UNUSED_PARAMETER")
    val _currentTheme = currentTheme
    @Suppress("UNUSED_PARAMETER")
    val _onNavigateToRootCheck = onNavigateToRootCheck

    val context = LocalContext.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Settings",
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
                "App",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
            )

            // F-Droid (ivarna) flavor only: its manifest declares the permission, Play's does not.
            if (storageAccessDeclared(context)) {
                var lifecycleKey by remember { mutableIntStateOf(0) }
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, e ->
                        if (e == Lifecycle.Event.ON_RESUME) lifecycleKey++
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                val granted = remember(lifecycleKey) { Environment.isExternalStorageManager() }
                SettingsNavCard(
                    icon = Icons.Default.SdStorage,
                    title = "Shared storage",
                    subtitle = if (granted) "Allowed: /sdcard and /storage (SD, USB OTG) are mounted in guests"
                    else "Not allowed: guests cannot see /sdcard or USB drives. Tap, then enable All files access",
                    onClick = {
                        val pkg = Uri.parse("package:${context.packageName}")
                        runCatching {
                            context.startActivity(
                                Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg)
                            )
                        }.onFailure {
                            context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                    }
                )
            }
            SettingsNavCard(
                icon = Icons.Default.Terminal,
                title = "Terminal",
                subtitle = "Font zoom, extra keys, and guest shell",
                onClick = { onNavigateToTerminalSettings?.invoke() }
            )
            SettingsNavCard(
                icon = Icons.Default.DisplaySettings,
                title = "X11 Display",
                subtitle = "Scale, fullscreen, input for embedded X11",
                onClick = { onNavigateToX11Settings?.invoke() }
            )
            SettingsNavCard(
                icon = Icons.Default.VolumeUp,
                title = "Audio",
                subtitle = "Host PulseAudio status, start, restart, logs",
                onClick = { onNavigateToAudioSettings?.invoke() }
            )
            SettingsNavCard(
                icon = Icons.Default.Storage,
                title = "Chroot",
                subtitle = "Installed roots, size, kill orphan processes",
                onClick = { onNavigateToChrootSettings?.invoke() }
            )
            SettingsNavCard(
                icon = Icons.Default.Folder,
                title = "PRoot",
                subtitle = "Installed containers and app-storage size",
                onClick = { onNavigateToProotSettings?.invoke() }
            )
            SettingsNavCard(
                icon = Icons.Default.History,
                title = "Legacy Termux",
                subtitle = "Leftover PRoot installs from FluxLinux ≤ v1.8.0 live in the Termux app",
                onClick = { onNavigateToLegacyTermuxSettings?.invoke() }
            )

            Text(
                "Help",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp)
            )

            GlassSettingCard {
                Column(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (onNavigateToOnboarding != null) {
                        // secondary = cream in dark theme — primary is near-black and vanishes on glass
                        Button(
                            onClick = { onNavigateToOnboarding() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            ),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("Show onboarding", fontWeight = FontWeight.Bold)
                        }
                    }
                    if (onNavigateToTroubleshooting != null) {
                        Button(
                            onClick = { onNavigateToTroubleshooting() },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.secondary
                            ),
                            border = BorderStroke(
                                1.5.dp,
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f)
                            ),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("Troubleshoot", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // About
            GlassSettingCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1A1A1A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_logo),
                            contentDescription = "FluxLinux Logo",
                            modifier = Modifier.size(48.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "FluxLinux",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        "v${com.ivarna.fluxlinux.BuildConfig.VERSION_NAME} • embedded terminal & X11",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            GlassSettingCard(
                onClick = { openUrl(context, "https://github.com/abhay-byte/fluxlinux#readme") }
            ) {
                Row(
                    Modifier.padding(20.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("📖", fontSize = 24.sp)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            "Read documentation",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            "Setup guides and tutorials",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            GlassSettingCard(
                onClick = { openUrl(context, "https://discord.gg/tag9kXAs2x") }
            ) {
                Row(
                    Modifier.padding(20.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF5865F2)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_discord),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            "Join our Discord",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            "Help, setups, and features",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            GlassSettingCard {
                Column(Modifier.padding(20.dp).fillMaxWidth()) {
                    Text(
                        "Special thanks",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    listOf(
                        "Termux Team" to "https://github.com/termux",
                        "PRoot Distro" to "https://github.com/termux/proot-distro",
                        "Termux:X11" to "https://github.com/termux/termux-x11"
                    ).forEach { (name, url) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { openUrl(context, url) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.Favorite,
                                null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(name, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }

            GlassSettingCard(onClick = { openUrl(context, "https://github.com/abhay-byte") }) {
                Column(Modifier.padding(20.dp).fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.me),
                                contentDescription = "Profile",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                "Abhay Raj",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                "@abhay-byte",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }

            GlassSettingCard(
                onClick = { openUrl(context, "https://github.com/abhay-byte/FluxLinux") }
            ) {
                Row(
                    Modifier.padding(20.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_star),
                        contentDescription = null,
                        tint = Color(0xFFFFD700),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Star this repository",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
private fun SettingsNavCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    // Dark theme primary is near-black (filled surfaces); use secondary (cream) for icons.
    val iconTint = MaterialTheme.colorScheme.secondary
    val titleColor = MaterialTheme.colorScheme.onBackground
    val subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant
    val chevronColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)

    GlassSettingCard(onClick = onClick) {
        Row(
            modifier = Modifier
                .padding(horizontal = 18.dp, vertical = 18.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = titleColor
                )
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = subtitleColor
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = chevronColor
            )
        }
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        Log.e("Settings", "Error opening URL", e)
    }
}
