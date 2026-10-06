package com.ivarna.fluxlinux.core.system

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/** All-files access (MANAGE_EXTERNAL_STORAGE): declared only by the ivarna flavor, Android 11+. */
object SharedStorageAccess {
    fun declared(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && runCatching {
            context.packageManager.getPackageInfo(
                context.packageName, PackageManager.GET_PERMISSIONS
            ).requestedPermissions?.contains(Manifest.permission.MANAGE_EXTERNAL_STORAGE) == true
        }.getOrDefault(false)

    fun granted(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    fun openSettings(context: Context) {
        val pkg = Uri.parse("package:${context.packageName}")
        runCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg))
        }.onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }
}
