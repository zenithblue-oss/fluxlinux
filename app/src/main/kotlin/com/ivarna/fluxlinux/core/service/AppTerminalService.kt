package com.ivarna.fluxlinux.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ivarna.fluxlinux.MainActivity
import com.ivarna.fluxlinux.R

/**
 * Foreground service keeping in-app terminal sessions alive while the app is
 * backgrounded. Mirrors termux-lib `AppTerminalService` (specialUse subtype).
 */
class AppTerminalService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = intent?.getIntExtra("SESSION_COUNT", 0) ?: 0
        if (count <= 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = createNotification(count)
        try {
            startAsForeground(notification)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            try {
                startForeground(NOTIF_ID, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "fallback startForeground failed", e2)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createNotification(count: Int): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("EXTRA_TARGET_PAGE", "terminal")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, NOTIF_ID, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FluxLinux — Terminal")
            .setContentText(
                if (count == 1) "1 terminal session running"
                else "$count terminal sessions running"
            )
            .setSmallIcon(R.drawable.ic_stat_flux)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FluxLinux Terminal Sessions",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows while in-app terminal sessions stay alive in the background"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "AppTerminalService"
        // New id: IMPORTANCE is immutable once a channel exists on device
        const val CHANNEL_ID = "FluxLinuxAppTerminalServiceChannel_v2"
        private const val NOTIF_ID = 1001
    }
}
