# v2.0.1

## What's Changed

### Features
- Terminal starts faster; PRoot sessions launch directly (no proot-distro Python), chroot prep runs off the UI thread
- Shared storage access in guests (all-files access) and a new onboarding page (#48, #29)
- Disable the Android phantom-process killer via Shizuku, root or adb (#41)
- Auto-install Mesa Turnip on Adreno and PanVK on MediaTek Mali v10+ (with fallback and retry)
- KDE runs on the built-in X11 server; compositing and effects off by default; opt-in zink GL on Turnip/PanVK
- One-time notice that v1.x Termux distros were not removed on upgrade (#37)

### Bug Fixes
- Crash on foreground-service notifications (monochrome small icon) (#42, #46)
- Chromium/Electron typing in X11 (#45)
- Host env and paths refresh after an app update and on app start
- Desktop waits for the X socket; stale running-desktop state is cleared
- KDE on PanVK no longer exhausts RAM
- UI contrast in dark/light themes, unified top bars, correct GPU tag in the distro picker

### Build / F-Droid
- All native code (host packages, loader.apk, guest helpers, terminal-emulator, libXlorie) is built from source without Docker
- All native libraries are 16 KB page aligned
- Reproducible release: the APK is built from the tagged commit with the F-Droid recipe (`com.ivarna.fluxlinux.yml`) and signed with the release key

## Verification
- Version 2.0.1 (versionCode 13): `app/build.gradle.kts`, `com.ivarna.fluxlinux.yml`, `fastlane/.../changelogs/13.txt`.
- GitHub asset `app-release.apk` (F-Droid `Binaries:`) is byte-identical to the F-Droid build apart from the signature (`apksigcopier compare`).
