// termux-app terminal-emulator v0.118.0 (GPLv3), vendored unmodified from
// https://github.com/termux/termux-app/tree/v0.118.0/terminal-emulator.
// Built here so libtermux.so is compiled from source with 16 KB page alignment
// (jitpack's prebuilt ships 4 KB LOAD alignment).
plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.terminal"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26
        externalNativeBuild {
            ndkBuild {
                cFlags += listOf("-std=c11", "-Wall", "-Wextra", "-Werror", "-Os", "-fno-stack-protector", "-Wl,--gc-sections")
            }
        }
        // Same as the app's host binaries and libXlorie: arm64-v8a only.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
