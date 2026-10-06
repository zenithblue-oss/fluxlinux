#!/usr/bin/env python3
"""Assemble bootstrap.tar + jniLibs from custom-prefix .debs.

Usage:
  ./scripts/assemble_bootstrap.py --package-name com.ivarna.fluxlinux
  ./scripts/assemble_bootstrap.py --package-name com.zenithblue.fluxlinux \\
      --deb-dir native/output/com.zenithblue.fluxlinux \\
      --list native/package-lists/bootstrap-host.txt
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent

# Default package set (mirrors termux-lib assemble_bootstrap.py)
DEFAULT_PACKAGES = [
    "bash",
    "termux-exec",
    "coreutils",
    "findutils",
    "grep",
    "sed",
    "psmisc",
    "procps",
    "curl",
    "ca-certificates",
    "tar",
    "xz-utils",
    "python",
    "termux-am",
    "termux-tools",
    "proot",
    "proot-distro",
    "pulseaudio",
    "xkeyboard-config",
    "libandroid-support",
    "readline",
    "ncurses",
    "libtalloc",
    "libcurl",
    "openssl",
    "libnghttp2",
    "libssh2",
    "zlib",
    "libidn2",
    "libunistring",
    "libiconv",
    "libunbound",
    "libnettle",
    "libgmp",
    "liblzma",
    "libc++",
    "libandroid-shmem",
    "libsndfile",
    "libvorbis",
    "libogg",
    "flac",
    "libflac",
    "libsoxr",
    "libandroid-execinfo",
    "libmp3lame",
    "libopus",
    "speexdsp",
    "dbus",
    "libexpat",
    "libltdl",
    "libcap",
    "libcap-ng",
    "libevent",
    "glib",
    "pcre2",
    "libffi",
    "libsqlite",
    "libbz2",
    "gdbm",
    "libandroid-selinux",
    "libandroid-glob",
    "libacl",
    "libx11",
    "libxau",
    "libxcb",
    "libxdmcp",
]


def load_list(path: Path | None) -> list[str]:
    if path is None:
        return list(DEFAULT_PACKAGES)
    pkgs: list[str] = []
    for line in path.read_text().splitlines():
        line = line.split("#", 1)[0].strip()
        if line:
            pkgs.append(line)
    return pkgs


def clean_and_prepare(extract_dir: Path) -> None:
    if extract_dir.exists():
        print(f"[*] Removing existing {extract_dir}...")
        shutil.rmtree(extract_dir)
    extract_dir.mkdir(parents=True)


def find_deb(deb_dir: Path, pkg: str) -> Path | None:
    matches = sorted(deb_dir.glob(f"{pkg}_*.deb"))
    # Prefer non-static / main package: exact name prefix before first _
    for m in matches:
        name = m.name
        # skip static variants when looking for base pkg (e.g. libgmp-static)
        if name.startswith(f"{pkg}_"):
            return m
    return None


def extract_debs(deb_dir: Path, extract_dir: Path, packages: list[str]) -> list[str]:
    missing: list[str] = []
    selected: list[Path] = []
    for pkg in packages:
        match = find_deb(deb_dir, pkg)
        if match:
            selected.append(match)
        else:
            print(f"[!] Warning: package {pkg} not found in {deb_dir}")
            missing.append(pkg)

    print(f"[*] Extracting {len(selected)} packages...")
    for deb in selected:
        print(f"  Unpacking {deb.name}...")
        temp = extract_dir / "temp_deb"
        if temp.exists():
            shutil.rmtree(temp)
        temp.mkdir(parents=True)
        subprocess.run(["ar", "x", str(deb.resolve())], cwd=temp, check=True)
        data = next((f for f in temp.iterdir() if f.name.startswith("data.tar")), None)
        if data is None:
            raise RuntimeError(f"no data.tar* in {deb}")
        subprocess.run(
            ["tar", "-xf", str(data), "-C", str(extract_dir.resolve())],
            check=True,
        )
        shutil.rmtree(temp)
    return missing


def add_loader(extract_dir: Path, target_prefix: str, loader_src: Path) -> None:
    if not loader_src.is_file():
        print(f"[!] Warning: loader APK missing at {loader_src} — skip")
        return
    dest_dir = extract_dir / target_prefix / "usr/libexec/termux-x11"
    dest_dir.mkdir(parents=True, exist_ok=True)
    dest = dest_dir / "loader.apk"
    print(f"[*] Copying loader.apk → {dest}")
    shutil.copy2(loader_src, dest)


def verify_bootstrap(
    extract_dir: Path,
    target_prefix: str,
    require_loader: bool,
    mode: str = "full",
) -> None:
    print(f"[*] Verifying critical files in bootstrap (mode={mode})...")
    # Terminal + proot (nativecode host shell path)
    required = [
        "usr/bin/bash",
        "usr/bin/python",
        "usr/bin/proot",
        "usr/bin/proot-distro",
        "usr/bin/pkill",
        "usr/libexec/proot/loader",
    ]
    if mode == "full":
        required.extend(
            [
                "usr/bin/pulseaudio",
                "usr/lib/libsoxr.so",
                "usr/lib/libandroid-execinfo.so",
                "usr/lib/libFLAC.so",
                "usr/lib/libmp3lame.so",
                "usr/lib/pulseaudio/modules/module-aaudio-sink.so",
                "usr/lib/pulseaudio/modules/module-sles-sink.so",
                "usr/lib/pulseaudio/modules/module-native-protocol-tcp.so",
            ]
        )
    if require_loader:
        required.append("usr/libexec/termux-x11/loader.apk")

    base = extract_dir / target_prefix
    ok = True
    for rel in required:
        full = base / rel
        if full.exists():
            print(f"  [OK] {rel}")
        else:
            print(f"  [MISSING] {rel}")
            ok = False

    if mode == "full":
        xkb = base / "usr/share/X11/xkb"
        xkb2 = base / "usr/share/xkeyboard-config-2"
        if xkb.exists() or xkb2.exists():
            print("  [OK] XKB config present")
        else:
            print("  [MISSING] XKB config")
            ok = False
    else:
        print("  [SKIP] XKB/pulse (terminal-proot mode)")

    if not ok:
        raise SystemExit("Verification failed. Missing required bootstrap files.")


def create_tarball(extract_dir: Path, target_prefix: str, tar_path: Path) -> None:
    tar_path.parent.mkdir(parents=True, exist_ok=True)
    files_dir = extract_dir / target_prefix
    print(f"[*] Packaging bootstrap into {tar_path}...")
    if tar_path.exists():
        tar_path.unlink()
    # F-Droid reproducible publish: pin sort order, mtime, uid/gid so the APK
    # hash does not change between machines (docs/plans/fdroid-buildserver-native-so.md).
    source_date_epoch = os.environ.get("SOURCE_DATE_EPOCH", "")
    mtime = source_date_epoch if source_date_epoch.isdigit() else "0"
    subprocess.run(
        [
            "tar",
            "--sort=name",
            f"--mtime=@{mtime}",
            "--owner=0",
            "--group=0",
            "--numeric-owner",
            "-cf",
            str(tar_path),
            "usr",
        ],
        cwd=files_dir,
        check=True,
    )
    size_mb = tar_path.stat().st_size / (1024 * 1024)
    print(f"[*] Tarball created: {size_mb:.2f} MB")


def copy_to_jni_libs(extract_dir: Path, target_prefix: str, jni_dir: Path) -> None:
    jni_dir.mkdir(parents=True, exist_ok=True)
    mapping = {
        "usr/bin/proot": "libproot.so",
        "usr/bin/bash": "libbash.so",
        "usr/libexec/proot/loader": "libloader.so",
        "usr/libexec/proot/loader32": "libloader32.so",
        # W^X: app uid cannot exec $PREFIX/bin/pulseaudio (EACCES).
        "usr/bin/pulseaudio": "libpulseaudio.so",
        "usr/bin/pactl": "libpactl.so",
    }
    print(f"[*] Copying critical binaries to {jni_dir}...")
    base = extract_dir / target_prefix
    for src_rel, dest_name in mapping.items():
        src = base / src_rel
        dest = jni_dir / dest_name
        if src.is_file():
            print(f"  {src_rel} → {dest_name}")
            shutil.copy2(src, dest)
            os.chmod(dest, 0o755)
        else:
            print(f"  [WARN] missing {src_rel}")


def assert_no_wrong_prefix(extract_dir: Path, package_name: str) -> None:
    """Ensure archive root is for this package, not another FluxLinux id / termux."""
    expected = extract_dir / f"data/data/{package_name}/files"
    if not expected.is_dir():
        # list what we got
        data = extract_dir / "data" / "data"
        found = []
        if data.is_dir():
            found = [p.name for p in data.iterdir()]
        raise SystemExit(
            f"Expected extract root {expected} missing. Found packages under data/data: {found}"
        )


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--package-name",
        required=True,
        help="Android applicationId (e.g. com.ivarna.fluxlinux)",
    )
    ap.add_argument(
        "--deb-dir",
        type=Path,
        default=None,
        help="Directory of .deb files (default: native/output/<package-name>)",
    )
    ap.add_argument(
        "--list",
        type=Path,
        default=None,
        help="Package list file (default: built-in DEFAULT_PACKAGES)",
    )
    ap.add_argument(
        "--out-tar",
        type=Path,
        default=None,
        help="Output bootstrap.tar path",
    )
    ap.add_argument(
        "--jni-dir",
        type=Path,
        default=None,
        help="Output jniLibs/arm64-v8a directory",
    )
    ap.add_argument(
        "--extract-dir",
        type=Path,
        default=None,
        help="Working extract directory (default: native/bootstrap/<pkg>/root)",
    )
    ap.add_argument(
        "--loader-apk",
        type=Path,
        default=ROOT / "app/src/main/assets/loader.apk",
        help="Path to loader.apk (default: app/src/main/assets/loader.apk, "
        "built by scripts/build_loader_apk.sh)",
    )
    ap.add_argument(
        "--allow-missing",
        action="store_true",
        help="Do not fail if some listed packages are missing from deb-dir",
    )
    ap.add_argument(
        "--skip-loader-check",
        action="store_true",
        help="Do not require loader.apk in verification",
    )
    ap.add_argument(
        "--mode",
        choices=("full", "terminal-proot"),
        default="full",
        help="full = host+GUI (pulse/xkb); terminal-proot = shell+proot only",
    )
    args = ap.parse_args()

    package_name = args.package_name
    deb_dir = (args.deb_dir or ROOT / "native/output" / package_name).resolve()
    out_tar = (
        args.out_tar
        or ROOT / "native/bootstrap" / package_name / "bootstrap.tar"
    ).resolve()
    jni_dir = (
        args.jni_dir
        or ROOT / "native/bootstrap" / package_name / "jniLibs" / "arm64-v8a"
    ).resolve()
    extract_dir = (
        args.extract_dir or ROOT / "native/bootstrap" / package_name / "root"
    ).resolve()
    target_prefix = f"data/data/{package_name}/files"

    if not deb_dir.is_dir():
        print(f"error: deb dir not found: {deb_dir}", file=sys.stderr)
        sys.exit(1)

    packages = load_list(args.list.resolve() if args.list else None)
    print(f"[*] package-name: {package_name}")
    print(f"[*] deb-dir:      {deb_dir}")
    print(f"[*] packages:     {len(packages)}")
    print(f"[*] extract-dir:  {extract_dir}")
    print(f"[*] out-tar:      {out_tar}")
    print(f"[*] jni-dir:      {jni_dir}")

    clean_and_prepare(extract_dir)
    missing = extract_debs(deb_dir, extract_dir, packages)
    if missing and not args.allow_missing:
        print(f"[!] Missing packages: {', '.join(missing)}", file=sys.stderr)
        print("    Re-run with --allow-missing to assemble a partial bootstrap.", file=sys.stderr)
        sys.exit(1)

    assert_no_wrong_prefix(extract_dir, package_name)
    if args.mode == "full" or not args.skip_loader_check:
        add_loader(extract_dir, target_prefix, args.loader_apk.resolve())
    elif args.loader_apk.resolve().is_file():
        add_loader(extract_dir, target_prefix, args.loader_apk.resolve())
    verify_bootstrap(
        extract_dir,
        target_prefix,
        require_loader=(args.mode == "full" and not args.skip_loader_check),
        mode=args.mode,
    )
    copy_to_jni_libs(extract_dir, target_prefix, jni_dir)
    create_tarball(extract_dir, target_prefix, out_tar)
    print("[*] Bootstrap assembly completed successfully!")


if __name__ == "__main__":
    main()
