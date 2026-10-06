package com.ivarna.fluxlinux.core.gpu

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Generic GitHub-release driver picker, shared by GPU drivers (Turnip now,
 * PanVK later): stable-tag filter, newest pick, sha256 from the API digest,
 * pinned fallback, connectivity check + resume-when-online. The guest script
 * downloads and verifies; this only decides what to download.
 */
object DriverRelease {

    data class Pkg(val version: String, val url: String, val sha256: String)

    private val SHA = Regex("^[0-9a-f]{64}$")

    /**
     * Newest non-prerelease, non-draft release whose tag matches [tagRe]
     * (group 1 = version) and that has an asset accepted by [assetOk] with a
     * sha256 digest. Null when the JSON is bad or nothing qualifies.
     */
    fun latest(
        json: String?, tagRe: Regex, allowPre: Boolean = false, assetOk: (String) -> Boolean
    ): Pkg? {
        val arr = try { JSONArray(json ?: return null) } catch (_: Exception) { return null }
        var best: Pkg? = null
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            if ((r.optBoolean("prerelease") && !allowPre) || r.optBoolean("draft")) continue
            val ver = tagRe.matchEntire(r.optString("tag_name"))?.groupValues?.get(1) ?: continue
            val assets = r.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a = assets.optJSONObject(j) ?: continue
                val sha = a.optString("digest").removePrefix("sha256:").lowercase()
                val url = a.optString("browser_download_url")
                if (!assetOk(a.optString("name")) || !SHA.matches(sha) || url.isEmpty()) continue
                if (best == null || isNewer(ver, best.version)) best = Pkg(ver, url, sha)
            }
        }
        return best
    }

    /** Download order: latest first, pinned fallback second (deduped). */
    fun candidates(latest: Pkg?, pin: Pkg?): List<Pkg> =
        listOfNotNull(latest, pin).distinctBy { it.url }

    /** Numeric-segment compare: 26.3.0-devel-20260824 > 26.2.0-devel-20260709. */
    fun isNewer(a: String, b: String): Boolean {
        val x = a.split(Regex("\\D+")).mapNotNull { it.toLongOrNull() }
        val y = b.split(Regex("\\D+")).mapNotNull { it.toLongOrNull() }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d > 0
        }
        return false
    }

    fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Blocking GET; null on any failure (rate limit, timeout, offline). */
    fun fetch(url: String): String? = try {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            if (responseCode == 200) inputStream.bufferedReader().use { it.readText() } else null
        }
    } catch (_: Exception) {
        null
    }

    // ── resume when the network returns (app open only, no background job) ──

    /** True while a callback waits for internet. UI shows "Waiting for internet…". */
    val waiting = MutableStateFlow(false)
    private var cb: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    fun armResume(ctx: Context, onOnline: () -> Unit) {
        if (cb != null) return
        val app = ctx.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        val c = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                disarm(app)
                onOnline()
            }
        }
        try {
            cm.registerNetworkCallback(req, c)
            cb = c
            waiting.value = true
        } catch (_: Exception) {
            // no ACCESS_NETWORK_STATE: Retry button still works
        }
    }

    @Synchronized
    fun disarm(ctx: Context) {
        val c = cb ?: return
        cb = null
        waiting.value = false
        try {
            ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(c)
        } catch (_: Exception) {
        }
    }
}
