package com.ivarna.fluxlinux.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ivarna.fluxlinux.MainActivity
import com.ivarna.fluxlinux.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground keep-alive during base desktop / onboarding install (proot + chroot).
 *
 * Long rootfs extract and package installs can take many minutes; without an FGS
 * Android may kill the process when the user leaves the app or under memory pressure.
 * Uses [ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC] (network/file install work).
 */
class BaseInstallService : Service() {

    override fun onCreate() {
        super.onCreate()
        running.set(true)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopInstallForeground()
                return START_NOT_STICKY
            }
            ACTION_UPDATE -> {
                if (!foregrounded) {
                    // Not yet in FG (race) — promote with this payload
                    enterForeground(
                        intent.getStringExtra(EXTRA_TITLE) ?: "Installing…",
                        intent.getStringExtra(EXTRA_TEXT) ?: "Base desktop setup",
                        intent.getIntExtra(EXTRA_PERCENT, 0)
                    )
                } else {
                    val title = intent.getStringExtra(EXTRA_TITLE) ?: "Installing…"
                    val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
                    val percent = intent.getIntExtra(EXTRA_PERCENT, 0)
                    notifyProgress(title, text, percent)
                }
                return START_STICKY
            }
            else -> {
                // ACTION_START or null
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Installing…"
                val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Base desktop setup"
                val percent = intent?.getIntExtra(EXTRA_PERCENT, 0) ?: 0
                enterForeground(title, text, percent)
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running.set(false)
        foregrounded = false
        super.onDestroy()
    }

    private fun enterForeground(title: String, text: String, percent: Int) {
        val notification = buildNotification(title, text, percent)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
            foregrounded = true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            try {
                startForeground(NOTIF_ID, notification)
                foregrounded = true
            } catch (e2: Exception) {
                Log.e(TAG, "fallback startForeground failed", e2)
            }
        }
    }

    private fun notifyProgress(title: String, text: String, percent: Int) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(title, text, percent))
    }

    private fun stopInstallForeground() {
        if (foregrounded) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregrounded = false
        }
        stopSelf()
    }

    private fun buildNotification(title: String, text: String, percent: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            NOTIF_ID,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val clamped = percent.coerceIn(0, 100)
        val body = if (text.isNotBlank()) text else "Keeping install alive…"
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_flux)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText("$clamped%")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .setProgress(100, clamped, clamped <= 0 && body.contains("Preparing", ignoreCase = true))
        return builder.build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Base desktop install",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description =
                "Shows while Debian rootfs / XFCE install is running so the system does not kill FluxLinux"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private var foregrounded = false

    companion object {
        private const val TAG = "BaseInstallService"
        const val CHANNEL_ID = "FluxLinuxBaseInstall_v1"
        private const val NOTIF_ID = 4712

        const val ACTION_START = "com.ivarna.fluxlinux.BASE_INSTALL_START"
        const val ACTION_UPDATE = "com.ivarna.fluxlinux.BASE_INSTALL_UPDATE"
        const val ACTION_STOP = "com.ivarna.fluxlinux.BASE_INSTALL_STOP"

        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_PERCENT = "percent"

        private val running = AtomicBoolean(false)

        fun isRunning(): Boolean = running.get()

        fun start(
            context: Context,
            title: String = "Installing…",
            text: String = "Base desktop setup",
            percent: Int = 0
        ) {
            val intent = Intent(context, BaseInstallService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_PERCENT, percent)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun update(context: Context, title: String, text: String, percent: Int) {
            if (!running.get()) return
            val intent = Intent(context, BaseInstallService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_PERCENT, percent)
            }
            // Service already running — plain startService is enough for updates
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "update failed: ${e.message}")
            }
        }

        fun stop(context: Context) {
            if (!running.get()) return
            try {
                val intent = Intent(context, BaseInstallService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "stop via action failed: ${e.message}")
                try {
                    context.stopService(Intent(context, BaseInstallService::class.java))
                } catch (_: Exception) {
                }
            }
        }
    }
}
