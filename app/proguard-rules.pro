# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep Compose and Material Icons
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# Keep Material Icons that are used (R8 should handle this automatically)
-keep class androidx.compose.material.icons.** { *; }

# OkHttp rules
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Keep data classes used with intents
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}

# Keep app code package (applicationId may be com.zenithblue.fluxlinux but
# Kotlin sources stay under com.ivarna.fluxlinux for both flavors).
-keep class com.ivarna.fluxlinux.** { *; }

# Embedded Termux:X11 (cloned module) — keep everything; CmdEntryPoint is
# launched by class name from app_process and activities via reflection.
-dontwarn com.termux.x11.**
-keep class com.termux.x11.** { *; }

# Shizuku: PhantomProcessFixer calls the private Shizuku.newProcess by reflection.
-keep class rikka.shizuku.Shizuku { private static rikka.shizuku.ShizukuRemoteProcess newProcess(java.lang.String[], java.lang.String[], java.lang.String); }
