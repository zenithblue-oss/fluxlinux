package com.ivarna.fluxlinux

import android.app.Application
import android.util.Log
import com.ivarna.fluxlinux.core.terminal.TermuxHostPaths

class FluxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // nativeLibraryDir moves on every app update; fix host env before ANY host script runs.
        runCatching { TermuxHostPaths.refreshHostEnvIfStale(filesDir, this) }
            .onFailure { Log.w("FluxApp", "host env refresh failed", it) }
    }
}
