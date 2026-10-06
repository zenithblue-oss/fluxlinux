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
import com.ivarna.fluxlinux.MainActivity
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.core.utils.InstallationQueueManager
import com.ivarna.fluxlinux.core.utils.LocalInstallServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that hosts [LocalInstallServer] for the duration of an
 * installation. Android is free to kill background processes; a foreground
 * service with a persistent notification gets a much higher priority and
 * survives the activity being backgrounded or trimmed.
 *
 * Lifecycle:
 *  - Historically started by MainActivity to serve install scripts over HTTP to
 *    external Termux (`curl localhost:PORT/install`).
 *  - Pass 2 (embedded terminal plan): NO LONGER started for debian* product
 *    installs — installs run in-app via FluxTerminalSessionManager. Kept only
 *    as legacy scaffolding for old flows; do not start for new install paths.
 *
 * Port discovery: [activePort] is a process-wide StateFlow. The activity
 * awaits on it after [start] returns to build the curl command for Termux.
 */
@Deprecated("Legacy clipboard→Termux install bridge; embedded terminal installs run in-app (see docs/plans/embedded-terminal-bootstrap-proot-chroot.md §2.4)")
class InstallServerService : Service() {

    companion object {
        private const val TAG = "InstallServerService"
        const val ACTION_START = "com.ivarna.fluxlinux.START_INSTALL_SERVER"
        const val ACTION_STOP = "com.ivarna.fluxlinux.STOP_INSTALL_SERVER"
        const val EXTRA_SCRIPT = "extra_script"

        private const val CHANNEL_ID = "fluxlinux_install_server"
        private const val NOTIF_ID = 4711
        private val FOREGROUND_TYPE_DATA_SYNC =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else 0

        // Idle window for the script-bearing path (BASE_INSTALL / reinstall).
        // Mirrors the pre-T3 behaviour: keep the LocalInstallServer alive
        // for 5 minutes so the user can re-run curl if the first attempt
        // fails, then self-stop.
        private const val SCRIPT_BEARING_IDLE_MS = 5 * 60 * 1000L

        // Port the bound LocalInstallServer is listening on, or null if none.
        // Process-scoped: activity and service share the same process, so
        // the activity can await the value here.
        private val _activePort = MutableStateFlow<Int?>(null)
        val activePort: StateFlow<Int?> = _activePort.asStateFlow()

        // Latched true between onCreate and onDestroy. Used by [stop] to
        // decide between stopService and startService(ACTION_STOP) — the
        // latter would spin up a fresh service just to immediately stop it,
        // which can leave a phantom notification and trip stopForeground
        // on a service that was never put in the foreground.
        private val running = AtomicBoolean(false)

        fun isRunning(): Boolean = running.get()

        /**
         * Start the foreground service. If [script] is non-empty, the service
         * will also start a [LocalInstallServer] hosting it.
         */
        fun start(context: Context, script: String = "") {
            val intent = Intent(context, InstallServerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SCRIPT, script)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Suspend until [activePort] is non-null or [timeoutMs] elapses.
         * Returns the bound port, or null on timeout.
         */
        suspend fun awaitPort(timeoutMs: Long = 5_000L): Int? {
            val current = _activePort.value
            if (current != null) return current
            return withTimeoutOrNull(timeoutMs) {
                _activePort.first { it != null }
            }
        }

        fun stop(context: Context) {
            if (!running.get()) return
            context.stopService(Intent(context, InstallServerService::class.java))
        }
    }

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + supervisor)
    private var server: LocalInstallServer? = null
    private var stateJob: Job? = null
    private var scriptBearing = false
    private var foregrounded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running.set(true)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.d(TAG, "ACTION_STOP received")
                doStop()
                return START_NOT_STICKY
            }
            else -> {
                val script = intent?.getStringExtra(EXTRA_SCRIPT) ?: ""
                startInForeground()
                if (script.isNotEmpty()) {
                    scriptBearing = true
                    startLocalServer(script)
                    armScriptBearingIdleTimer()
                }
                observeInstallState()
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val notification = buildNotification("Preparing install…", "Serving install script to Termux")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, FOREGROUND_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        foregrounded = true
    }

    private fun startLocalServer(script: String) {
        // If a server is already running, replace its script (handles
        // multiple startService calls during the same install).
        val existing = server
        if (existing != null) {
            existing.stop()
            server = null
            _activePort.value = null
        }
        val local = LocalInstallServer()
        server = local
        scope.launch(Dispatchers.IO) {
            try {
                val port = local.start(script)
                _activePort.value = port
            } catch (e: Exception) {
                Log.e(TAG, "LocalInstallServer.start failed", e)
                _activePort.value = null
            }
        }
    }

    private fun armScriptBearingIdleTimer() {
        // Mirrors the pre-T3 `delay(300_000L); server.stop()` safety net.
        // Cancelled in doStop() if the install completes or the user cancels
        // before the timer fires.
        scope.launch {
            delay(SCRIPT_BEARING_IDLE_MS)
            if (scriptBearing) {
                Log.d(TAG, "Script-bearing idle timer fired, stopping FGS")
                doStop()
            }
        }
    }

    private fun observeInstallState() {
        stateJob?.cancel()
        stateJob = scope.launch {
            var hasSeenBusy = false
            InstallationQueueManager.installState.collectLatest { state ->
                if (state.isInstalling) {
                    hasSeenBusy = true
                    scriptBearing = false
                }

                val title = if (state.isInstalling) {
                    if (state.currentTaskName.isNotEmpty())
                        "Installing: ${state.currentTaskName}"
                    else
                        "Install in progress"
                } else if (state.cancelledByUser) {
                    "Install cancelled"
                } else {
                    "Install finished"
                }
                val body = when {
                    state.cancelledByUser -> "Cancelled by user"
                    state.isInstalling -> "Progress ${state.progressCurrent}/${state.progressTotal}"
                    else -> "Done"
                }
                updateNotification(title, body)
                // Only self-stop after we've observed a busy→idle transition.
                // The script-bearing idle timer handles the BASE_INSTALL path
                // where the queue is never enqueued.
                if (hasSeenBusy && !state.isInstalling) {
                    doStop()
                }
            }
        }
    }

    private fun updateNotification(title: String, body: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(title, body))
    }

    private fun buildNotification(title: String, body: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_flux)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Install server",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Shows the active install — keeps FluxLinux alive during the install"
            setShowBadge(true)
        }
        nm.createNotificationChannel(channel)
    }

    private fun doStop() {
        stateJob?.cancel()
        stateJob = null
        try { server?.stop() } catch (e: Exception) { Log.w(TAG, "server.stop failed", e) }
        server = null
        _activePort.value = null
        if (foregrounded) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregrounded = false
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        running.set(false)
        try { server?.stop() } catch (e: Exception) { Log.w(TAG, "server.stop failed in onDestroy", e) }
        server = null
        _activePort.value = null
        stateJob?.cancel()
        scope.cancel()
    }
}
