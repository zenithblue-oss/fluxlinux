# Plan: Build native `.so` files the way F-Droid’s builder does (local container)

| Field | Value |
| --- | --- |
| **Date** | 2026-08-17 (review pass same day, after in-tree Xlorie) |
| **Status** | **IMPLEMENTED** — `fdroid lint` green; `fdroid scanner --exit-code` 0 problems; `assembleIvarnaRelease` **SUCCESS** on `buildserver-trixie` (`com.ivarna.fluxlinux_12.apk`, 32 MiB unsigned). Post-build `Binaries:` fetch 404s until the GitHub `v2.0.0` APK is uploaded. |
| **Flavor** | Ivarna (`com.ivarna.fluxlinux`) is what F-Droid builds |
| **Related** | [v2.0.0-fdroid-release-plan.md](../releases/v2.0.0-fdroid-release-plan.md), [termux-native-packages-dual-appid.md](./termux-native-packages-dual-appid.md), [rootfs-github-release-no-apk-bloat.md](./rootfs-github-release-no-apk-bloat.md) |
| **Scope lock** | Review + update this plan (and the in-repo recipe `scanignore` so it matches scanner rules). Do **not** pull images, compile, or produce APK/test output. |

This is the local-repro map for “those `.so` files must be built,” plus a pass/fail review against F-Droid’s **Inclusion Policy** and the **`fdroid build` runner**.

> **Superseded 2026-10-06.** The old §0 verdict and sections 0.1, 2, 3.2, 3.4 and 5 below describe the
> 2.0.0 "prebuilt + scanignore" approach that F-Droid rejected (MR 46098,
> `scandelete: [app, native]`). The current design is §0 directly below.

## §0. Current design (2.0.1+): every native file built from source on F-Droid

F-Droid cannot run Docker, so termux-packages is built **on the buildserver host**.
Only distro rootfs archives (and optional guest GPU / SDK archives) are
downloaded at runtime, after opt-in; the host bootstrap is
built and bundled in the APK.

| Output | Source | Built by (recipe `build:`, after the scanner) |
| --- | --- | --- |
| `libbash/libproot/libloader/libloader32/libpulseaudio/libpactl.so`, `pulse-runtime/*.so`, `assets/bootstrap.tar` | `native/termux-packages` submodule (termux/termux-packages `ebe0dcc`) + `native/patches/<pkg>/` | `NO_DOCKER=1 scripts/build_packages_for_appid.sh com.ivarna.fluxlinux --list termux-lib-ssot`, then `scripts/assemble_bootstrap.py`; Gradle `packageHostAssetsIvarna` stages them |
| `assets/loader.apk` | `native/loader/Loader.java` | `scripts/build_loader_apk.sh` (javac + d8) |
| `bwrap-proot-shim`, `libevp_md2.so` (guest, aarch64 glibc) | `bwrap-proot-shim.c`, `native/guest/libevp_md2.c` | `scripts/build_guest_helpers.sh` (`gcc-aarch64-linux-gnu`) |
| `libXlorie.so` | `termux-x11/src/main/cpp` | Gradle `:termux-x11` (NDK r29) |
| `libtermux.so` | `terminal-emulator/src/main/jni` | Gradle `:terminal-emulator` (NDK r29) |

Host requirements (see `sudo:` in `com.ivarna.fluxlinux.yml`): Debian build
tools derived from termux `scripts/setup-ubuntu.sh` (no i386, no apt.llvm.org),
`fuse-overlayfs` + `/dev/fuse` (termux mounts the NDK toolchain with it), and a
writable `/data/data/com.ivarna.fluxlinux` owned by the build user. termux
`build-package.sh` downloads each package's upstream source tarball (sha256-pinned),
so the `build:` step needs network.

Trixie vs. termux's Ubuntu image (handled in the recipe / `NO_DOCKER` branch):
JDK 17 comes from bookworm (`termux-am` hardcodes it; AGP 7.4 D8 crashes on JDK 21
classes); host clang is Debian's (termux wants `clang-21`); `xcb-proto` cleanup
uses `TERMUX_PYTHON_VERSION` (Debian python is 3.13); `webrtc-audio-processing`
is fetched from Debian's identical orig tarball (freedesktop.org returns HTTP 418
to curl); `TAR_OPTIONS=--no-same-owner` for root-in-userns test chroots.
Local repro (Debian trixie chroot, clean prefix, warm source cache): 2382 s for
the `build:` step + ~330 s Gradle.

Prebuilts still tracked in git (`native/output/*.deb`, `native/bootstrap/*/jniLibs`,
`pulse-runtime`, `loader.apk`, guest helpers) are for local dev only; the recipe
`scandelete`s / `rm -rf`s them and rebuilds.

---

## 0. Review verdict (will they pass?)

**Policy: yes, with documented prebuilts.** Nothing here is Play Services, a tracker, or a mystery closed blob. Host W^X bins stay in the APK because targetSdk 36 cannot `exec` `$PREFIX/bin/*`; they are still FLOSS, built in `ghcr.io/termux/package-builder`, cited, and `scanignore`d — the same model as Termux’s own F-Droid recipe (prefix not compiled on `buildserver-trixie`).

**Runner: not yet.** `libXlorie.so` is the only ELF F-Droid should compile, and that path is now real. The job still dies at `--refresh-scanner` if `scanignore` lists paths the scanner never flags (**unused / non-exist path = error**). The previous list would fail on that rule. Corrected in `com.ivarna.fluxlinux.yml` (this pass). Docker lint/scanner still **not run**.

Xlorie CMake + 16 submodules are **in the working tree, not on `origin/main`**. A tag of current `main` would still ship the old prebuilt `libXlorie.so`. The runner can only compile Xlorie from a commit that contains `termux-x11/src/main/cpp/` + `.gitmodules` + `submodules: yes`.

### 0.1 Per-`.so` matrix

Scanner rule used below is `fdroidserver` **master** `scanner.py` (fetched 2026-08-17): flag `.so` / `.a` / `.aar` / `.jar` / `.apk` / `.gz` / `.tgz` / `.zip` / `.wasm` / extensionless-binary. **Not flagged:** `.deb`, `.tar`, `.tar.xz`. `.apk` is **always deleted** from the tree (does **not** go through `scanignore`). After the walk, every `scanignore` entry that was not used, or that `glob` did not find, is a **fatal error**.

| ELF | In Ivarna APK? | Built where | Policy | `buildserver-trixie` runner |
|---|---|---|---|---|
| `libXlorie.so` | yes (`lib/arm64-v8a/`) | **This repo**, `:termux-x11` NDK `29.0.14206865` + CMake over vendored `lorie/` + 16 submodules | **Pass** — compiled from FLOSS source on their builder | **Pass once committed** — `submodules: yes`, `sudo` `bison` `patch`, Gradle fetches NDK 29. Local `:termux-x11:assembleRelease` already linked arm64 (~3.5 MiB stripped, `CmdEntryPoint` + `LorieView` JNI). **Not** proven on the F-Droid image. |
| `libtermux.so` | yes | JitPack `com.github.termux:termux-app:v0.118.0` (GPLv3) | **Pass** — trusted Maven + FLOSS | **Pass** — Gradle resolves it |
| `libandroidx.graphics.path.so` | yes | AndroidX / `maven.google.com` | **Pass** | **Pass** |
| `libbash.so` `libproot.so` `libloader.so` `libloader32.so` `libpulseaudio.so` `libpactl.so` | yes (`lib/arm64-v8a/`, W^X) | Termux docker `ghcr.io/termux/package-builder` → tracked `native/bootstrap/*/jniLibs/` | **Pass** — FLOSS, rebuild script cited, not feasible in this image | **Pass if `scanignore: native/bootstrap/`** — copied, not compiled. Do **not** ask F-Droid to rebuild termux-packages. |
| `pulse-runtime/lib{soxr,soxr-lsr,FLAC,mp3lame,android-execinfo}.so` | yes (`assets/`) | Same Termux debs, staged by `package_host_assets.sh` | **Pass** — LGPL/BSD, same citation | **Pass if those two flavor dirs are `scanignore`d** (scanner **does** flag `.so`) |
| `libevp_md2.so` | yes (`assets/scripts/opensuse/…`) | Guest sudo/preload helper | **Weak pass** — needs a real source/license sentence in the MR; do not put on the NDK path | **Pass** — exact path `scanignore`d |
| Prebuilt `termux-x11/.../jniLibs/*/libXlorie.so` | **gone** | was a vendored blob | n/a | n/a — `git rm`’d; no `scanignore` needed |

Not `.so`, but they decide whether the **job** reaches Gradle:

| Blob | Scanner? | Policy | What to do |
|---|---|---|---|
| 392 `native/output/**/*.deb` | **Not flagged** (`.deb` is not in `scanner.py`) | Cite in the MR; do not rebuild on F-Droid | **Do not `scanignore` this dir** — unused path = fail |
| `app/src/main/assets/loader.apk` | **Always deleted** (`.apk` bypasses `scanignore`) | FLOSS `shell-loader` | **Do not `scanignore` it** (unused). Ivarna runtime still gets a copy from the GitHub `rootfs` `bootstrap_*.tar`. APK `assets/loader.apk` will be missing on an F-Droid build unless we later stage it from a non-`.apk` name after scan. Reproducible hash vs GitHub APK is already at risk. |
| `app/src/main/assets/xfce4/**/*.tar.xz` | **Not flagged** | Papirus/Vimix/Space, GPLv3 | **Do not `scanignore`** this dir — unused |
| `assets/xfce4/**` (`.tar.gz` + `.zip`) | Flagged | same | `scanignore: assets/xfce4/` |
| `assets/wallpaper/wallpaper.zip` | Flagged | project asset | `scanignore: assets/wallpaper/` |
| Distro `assets/rootfs/*` | not in tree | TetheredNet + consent | **Not in the clone F-Droid builds** — see §8 |

### 0.2 Two containers — do not mix them

| Image | Role |
|---|---|
| `registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie` | What the fdroiddata **`fdroid build` job** uses (`FROM debian:trixie`, SDK at `/opt/android-sdk`, user `vagrant`). This is the one to run locally. |
| `ghcr.io/termux/package-builder` | What **we** already use (`scripts/run-docker.sh`) to compile bash / proot / Pulse. F-Droid **never** runs this. |

Fork CI lint jobs use plain `debian:trixie-slim`. The popular `docker-executable-fdroidserver` image has **no full SDK** — lint only.

---

## 1. What F-Droid’s GitLab actually runs

Source of truth: [`fdroid/fdroiddata` `.gitlab-ci.yml` on `master`](https://gitlab.com/fdroid/fdroiddata/-/blob/master/.gitlab-ci.yml) (fetched 2026-08-17).

### 1.1 Jobs that fire on an app-metadata MR

| Job | Image | What it does | Native `.so`? |
|---|---|---|---|
| `fdroid lint` | `debian:trixie-slim` | `fdroid lint $CHANGED` | No |
| `fdroid rewritemeta` | `debian:trixie-slim` | Formatting | No |
| `checkupdates` | `debian:trixie-slim` | Auto fields | No |
| `schema validation` | `python` | JSON schema | No |
| **`fdroid build`** | **`registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie`** | Real compile + scanner | **Yes** |
| `check apk` | `debian:trixie-slim` | Needs `fdroid build` artifacts | DEX / signing-block scan of the APK (not a re-walk of source `.so`) |
| `check source code` | `debian:trixie-slim` | Fastlane | No |

The comment on `fdroid build` is explicit:

> This job should be as close as possible to the production buildserver.  
> The docker image is created using the same provisioning as the production buildserver, via the `docker` job in `fdroid/fdroidserver`.

That is the container to reproduce locally.

### 1.2 How that image is produced

[`fdroidserver` `buildserver/Dockerfile`](https://gitlab.com/fdroid/fdroidserver/-/blob/master/buildserver/Dockerfile):

```dockerfile
FROM debian:trixie
# user `vagrant` (password vagrant), NOPASSWD sudo
# provision-android-sdk  →  ANDROID_HOME=/opt/android-sdk
# provision-android-ndk  →  /opt/android-sdk/ndk  (gradle may install extra NDKs)
# provision-gradle
# /etc/profile.d/bsenv.sh
```

CI tag mapping ([`fdroidserver` `.gitlab-ci.yml` `docker:` job](https://gitlab.com/fdroid/fdroidserver/-/blob/master/.gitlab-ci.yml)):

- `registry.gitlab.com/fdroid/fdroidserver:buildserver`
- **`registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie`** ← same image, extra tag  
  Registry listing: ~674 MiB compressed for `buildserver-trixie`.

`provision-android-ndk` installs cached NDK zips, then makes `$NDK_BASE` group-writable so Gradle/`sdkmanager` can install **NDK `29.0.14206865`** when `:termux-x11` asks. After each app the job does `rm -rf $ANDROID_HOME/ndk`.

`--on-server` runs the recipe `sudo:` block as `vagrant`, then **uninstalls sudo**. `bison` / `patch` stay installed for CMake.

### 1.3 Exact `fdroid build` invocation (CI)

```text
fdroid fetchsrclibs $build --verbose
(unset CI; fdroid build --verbose --test --refresh-scanner --on-server --no-tarball $build)
```

Order that matters for us:

1. Clone the pinned `commit:`
2. `submodules: yes` → `git submodule update --init --recursive`
3. `sudo:` (`binutils bison patch` + `assemble_bootstrap.py`)
4. **`--refresh-scanner`** (this is the first hard fail)
5. `gradle assembleIvarnaRelease` (NDK 29 + CMake Xlorie + package host jniLibs)

Java is forced to **OpenJDK 21**.

### 1.4 The *other* F-Droid docker people find first (wrong for APK compile)

`registry.gitlab.com/fdroid/docker-executable-fdroidserver:master` — **no full Android SDK**. Lint only.

---

## 2. Scanner mechanics (why the old recipe fails)

From [`fdroidserver/scanner.py`](https://gitlab.com/fdroid/fdroidserver/-/blob/master/fdroidserver/scanner.py) + [`common.getpaths_map`](https://gitlab.com/fdroid/fdroidserver/-/blob/master/fdroidserver/common.py):

- `scanignore` paths are **`glob.glob`’d**, then a file is ignored if `relpath.startswith(expanded_path)`.
- A glob like `app/src/*/assets/pulse-runtime/` **does** expand (both flavors exist).
- After the walk: **unused** `scanignore` and **non-exist** `scanignore` each increment the problem count → job red.
- `.apk` files call `removeproblem()` **without** `toignore()` — `loader.apk` is deleted and a `scanignore` on it is unused.

Correct `scanignore` for 2.0.0 / 12 (now in `com.ivarna.fluxlinux.yml`):

```yaml
scanignore:
  - native/bootstrap/
  - app/src/ivarna/assets/pulse-runtime/
  - app/src/zenithblue/assets/pulse-runtime/
  - app/src/main/assets/scripts/opensuse/common/libevp_md2.so
  - app/src/main/assets/scripts/common/setup/bwrap-proot-shim
  - assets/xfce4/
  - assets/wallpaper/
```

**Not listed on purpose:** `native/output/` (392 `.deb`s, not flagged), `app/src/main/assets/loader.apk` (auto-deleted), `app/src/main/assets/xfce4/` (only `.tar.xz`), `termux-x11/**` (no prebuilt `.so` left).

---

## 3. How each FluxLinux `.so` is built

### 3.1 Inventory (Ivarna APK + git)

**In the release APK** (`app-ivarna-release.apk`, arm64 only):

| Path in APK | Origin | Built how today |
|---|---|---|
| `lib/arm64-v8a/libXlorie.so` | vendored native tree | **Built here** — `:termux-x11` NDK+CMake over `src/main/cpp/` (§3.3) |
| `lib/arm64-v8a/libbash.so` | Termux `bash` | `assemble_bootstrap.py` rename of `usr/bin/bash` (ET_DYN) |
| `lib/arm64-v8a/libproot.so` | Termux `proot` | `usr/bin/proot` |
| `lib/arm64-v8a/libloader.so` | Termux `proot` | `usr/libexec/proot/loader` |
| `lib/arm64-v8a/libloader32.so` | Termux `proot` | `usr/libexec/proot/loader32` |
| `lib/arm64-v8a/libpulseaudio.so` | Termux `pulseaudio` | `usr/bin/pulseaudio` |
| `lib/arm64-v8a/libpactl.so` | Termux `pulseaudio` | `usr/bin/pactl` |
| `lib/arm64-v8a/libtermux.so` | JitPack `termux-app` | Built by that project |
| `lib/arm64-v8a/libandroidx.graphics.path.so` | AndroidX | Maven |
| `assets/pulse-runtime/libsoxr.so` etc. | Termux libs | `package_host_assets.sh` from `usr/lib/` |
| `assets/loader.apk` | termux-x11 `shell-loader` | Prebuilt 2.3 KiB — F-Droid scanner will strip the **source** copy |
| `assets/scripts/opensuse/common/libevp_md2.so` | openSUSE helper | Prebuilt |

**Tracked in git, not all in the Ivarna APK:** 392 `native/output/**/*.deb`, zenithblue twins of jniLibs + pulse-runtime. The scanner sees **the git tree**, not just the APK.

Ivarna Gradle does **not** require `native/bootstrap/…/bootstrap.tar` (that tarball is downloaded from the GitHub `rootfs` release). It **does** require the six W^X jniLibs. `packageHostAssetsIvarna` only fail-closes on those six files.

### 3.2 Host W^X binaries + pulse overlay (Termux docker)

Already implemented. **Not** F-Droid’s container.

```text
native/termux-packages  →  symlink to a termux-packages clone
scripts/set_termux_package_name.sh com.ivarna.fluxlinux
scripts/build_packages_for_appid.sh com.ivarna.fluxlinux --list termux-lib-ssot
# inside: ./scripts/run-docker.sh ./build-package.sh -a aarch64 <pkg>
# image:  ghcr.io/termux/package-builder
scripts/assemble_bootstrap.py --package-name com.ivarna.fluxlinux \
  --list native/package-lists/termux-lib-ssot.txt --mode full
scripts/package_host_assets.sh com.ivarna.fluxlinux
```

W^X still requires the host ET_DYN copies **inside the APK** `lib/arm64-v8a/`. You cannot exec them from `$PREFIX` on targetSdk 36, and you cannot drop new `.so`s into `nativeLibraryDir` at runtime. So they must be *in* the APK; they just should not be *compiled* on F-Droid.

`sudo:` may run `assemble_bootstrap.py` to re-extract tracked `.deb`s into `native/bootstrap/` (reproducible tar + jniLibs). That is copy/extract, not a compile.

### 3.3 `libXlorie.so` — built from source (repo-side, 2026-08-17)

Upstream [termux/termux-x11](https://github.com/termux/termux-x11):

- Native tree: `lorie/src/main/cpp/CMakeLists.txt`.
- NDK pin: `lorie/version.gradle` → **`29.0.14206865`**.
- Submodules are **required**.

This repo now (working tree):

1. Vendored `termux-x11/src/main/cpp/` from upstream `lorie/src/main/cpp` at `cdf6a01e` (`CMakeLists.txt`, `lorie/`, `patches/`, `recipes/`).
2. **16 gitlinks** in `.gitmodules` (`ignore = dirty`): `bzip2`, `libepoxy`, `libfontenc`, `libtirpc`, `libx11`, `libxau`, `libxcvt`, `libxdmcp`, `libxfont`, `libxkbfile`, `libxshmfence`, `libxtrans`, `pixman`, `xkbcomp`, `xorgproto`, `xserver`.
3. `termux-x11/build.gradle.kts`: `ndkVersion = "29.0.14206865"`, `externalNativeBuild.cmake.path = src/main/cpp/CMakeLists.txt`, `abiFilters = [arm64-v8a]`.
4. Prebuilt `termux-x11/src/main/jniLibs/*/libXlorie.so` **removed**. No leftover ELF under `cpp/` (only Gradle `build/` / `.cxx/`, both gitignored).
5. Recipe: `submodules: yes`, `sudo` installs `bison` + `patch` (CMake `find_package(BISON)` + `patch -p1` in `CMakeLists.txt`). Python3 is already on the image.

Local verify (prior session, not re-run here): `:termux-x11:assembleRelease` ~500 objects, arm64 `libXlorie.so`, NDK r29, 3.5 MiB stripped, expected JNI exports.

**Still open for the runner:** first configure on `buildserver-trixie` (extra Debian bits CMake might want: none expected beyond bison/patch/python3 + SDK cmake/ninja). Job time for 500 objects + NDK fetch should fit a normal `fdroid build` budget.

`loader.apk` stays a 2.3 KiB prebuilt. Building `:shell-loader` on F-Droid is optional later; it is not an `.so`.

### 3.4 `libevp_md2.so`

Guest-only. Keep `scanignore` + a source/license line in the MR. Do not put this on the F-Droid NDK path.

---

## 4. What “must be built” means, strictly

Inclusion Policy (Free Software + Build Transparency):

- Prefer source compilation or Debian `main`.
- Prebuilt FLOSS is allowed when a rebuild is not feasible, from a trusted Maven repo **or** documented + `scanignore`.
- The **scanner** is the enforcement: ELF in the source tree that this build did not produce = fail, unless ignored. Unused `scanignore` also fails.

| Reviewer wording | Actual requirement |
|---|---|
| “`.so` must be built” | Built **somewhere from FLOSS source**, and F-Droid’s **APK job** either compiles it or `scanignore`s a documented prebuilt. **`libXlorie.so` is compiled here.** Host W^X bins are compiled in Termux docker, not here. |
| “Build bash on F-Droid” | **Not** what Termux does. Not feasible in `buildserver-trixie`. |
| “Don’t check in mystery ELF” | Real. Host jniLibs + pulse overlay + theme zips fail CI without the **used** `scanignore` list in §2. |

Runtime downloads (rootfs, Mesa/Turnip, optional SDK) stay **TetheredNet** + the existing unchecked-consent checkbox. That is Inclusion § Security, not a native-`.so` reject. Kali/Parrot guests may still pick up **`NonFreeAdd`**.

---

## 5. Recommended path (superseded 2026-10-06 — see §0 at top)

The 2.0.0 plan below ("do not compile termux-packages inside `fdroid build`",
prebuilt `.deb`s + `scanignore`) failed review: the maintainer `scandelete`s every
prebuilt, so F-Droid **must** rebuild termux-packages. Current path is §0.

---

## 6. Local reproduction (documented, not run)

Need: Docker or Podman, ~20–40 GiB free. amd64.

```bash
docker pull registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie
# optional lint-only:
docker pull debian:trixie-slim

git clone https://gitlab.com/fdroid/fdroiddata.git
# metadata/com.ivarna.fluxlinux.yml  ← copy in-repo yml, pin commit: to a
# SHA that actually contains termux-x11/src/main/cpp + .gitmodules

docker run --rm -it --name flux-fdroid-bs \
  -e ANDROID_HOME=/opt/android-sdk \
  -v "$PWD/fdroiddata":/home/vagrant/fdroiddata \
  registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie \
  bash

# inside:
source /etc/profile.d/bsenv.sh
apt-get update && apt-get dist-upgrade
apt-get install -y sudo openjdk-21-jdk-headless
update-alternatives --set java /usr/lib/jvm/java-21-openjdk-amd64/bin/java
cd /home/vagrant/fdroiddata
fdroid lint com.ivarna.fluxlinux
fdroid rewritemeta com.ivarna.fluxlinux
fdroid readmeta
# cheapest proof the blob list is complete:
fdroid scanner --verbose com.ivarna.fluxlinux
# then the real job:
fdroid build --verbose --test --refresh-scanner --on-server --no-tarball \
  com.ivarna.fluxlinux:12
```

`--on-server` expects `sudo -u vagrant`. Skip it and you get a laptop build, not what reviewers run.

Lint-only (fork CI):

```bash
docker run --rm -it -v "$PWD/fdroiddata":/repo -w /repo debian:trixie-slim bash
apt-get update && apt-get install -y fdroidserver git curl
fdroid lint com.ivarna.fluxlinux
```

Do not treat a green `assembleIvarnaRelease` on the laptop as a green F-Droid build. The scanner is a different gate.

---

## 7. What was verified on `buildserver-trixie` (2026-08-17)

| Gate | Result |
|---|---|
| `fdroid lint com.ivarna.fluxlinux` | **Pass** |
| `fdroid scanner --exit-code …:12` | **0 problems** |
| `fdroid build --test --refresh-scanner --on-server --no-tarball …:12` | **`assembleIvarnaRelease` SUCCESS** (3m 39s Gradle after assemble). APK `tmp/com.ivarna.fluxlinux_12.apk` (32 MiB unsigned) contains compiled `libXlorie.so` + host W^X bins + restored `assets/loader.apk`. |
| `Binaries:` download of `v2.0.0/app-release.apk` | **404** — expected until the GitHub release asset exists. Not a compile failure. |

Recipe that built: `com.ivarna.fluxlinux.yml` (`commit: 63f022a`, `submodules: true`, `gradle: [ivarna]`, `prebuild` assemble, `sudo` `binutils bison patch gcc libc6-dev xz-utils zstd bzip2`).

Not done: fdroiddata MR, `v2.0.0` tag / `app-release.apk` upload, history rewrite (§8).

---

## 8. Rootfs: already off GitHub git, keep local + release

Asked: if any distro rootfs is in a git commit, remove it from GitHub only; keep the files locally because they live on the GitHub **release**.

**Current tip (`HEAD` / `origin/main`): no rootfs tarball is tracked.** `.gitignore` has `assets/rootfs/` and `app/src/*/assets/rootfs/`. Local disk still has all 12 archives (~720 MiB) under `assets/rootfs/` — leave them.

The GitHub release tag [`rootfs`](https://github.com/abhay-byte/fluxlinux/releases/tag/rootfs) already has the 12 distro archives + `bootstrap_com.ivarna.fluxlinux.tar` + `bootstrap_com.zenithblue.fluxlinux.tar` + `sha256sums.txt`. That is the only place F-Droid / the app should fetch them.

**History only:** commit `8d813e6` added `assets/rootfs/debian_13_rootfs.tar.xz` (~81 MiB). `fde4f67` (`feat: rootfs on-demand…`) **deleted it from the tree**. The blob remains in git objects. F-Droid clones the **pinned tag SHA**, which does not contain the file, so the scanner never sees it.

No `git rm` is possible on the tip (already gone). Removing the blob from GitHub’s object store would mean `git filter-repo` + force-push of `main` — not done. Say so if you want that later.

---

## 9. Sources (fetched 2026-08-17)

- https://gitlab.com/fdroid/fdroiddata/-/blob/master/.gitlab-ci.yml  
  (`image: registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie`, job `fdroid build`)
- https://gitlab.com/fdroid/fdroidserver/-/blob/master/buildserver/Dockerfile (`FROM debian:trixie`)
- https://gitlab.com/fdroid/fdroidserver/-/blob/master/.gitlab-ci.yml (`docker:` tags `:buildserver` and `:buildserver-trixie`)
- https://gitlab.com/fdroid/fdroidserver/-/blob/master/fdroidserver/scanner.py (`.so` / unused `scanignore` / `.apk` always deleted)
- https://gitlab.com/fdroid/fdroidserver/-/blob/master/fdroidserver/common.py (`getpaths_map` = `glob.glob`)
- https://f-droid.org/en/docs/Build_Metadata_Reference/#scanignore (`relpath` prefix after glob)
- https://gitlab.com/fdroid/docker-executable-fdroidserver (SDK-less helper)
- https://f-droid.org/en/docs/Installing_the_Server_and_Repo_Tools/
- https://github.com/termux/termux-packages `scripts/run-docker.sh` (`ghcr.io/termux/package-builder`)
- https://github.com/termux/termux-x11 `lorie/build.gradle`, `lorie/version.gradle` (NDK 29.0.14206865)
- This repo: `scripts/build_packages_for_appid.sh`, `scripts/assemble_bootstrap.py` `copy_to_jni_libs`, `scripts/package_host_assets.sh`, `termux-x11/build.gradle.kts`
