# Ubuntu 26.04 in FluxLinux

| | |
|---|---|
| Cards | `ubuntu` (proot) · `ubuntu_chroot` (`/data/local/tmp/chrootUbuntu`) |
| Rootfs | `ubuntu_26.04_rootfs.tar.xz` (Ubuntu 26.04 LTS Resolute arm64 base) |
| Package manager | `apt-get` |
| Desktop | XFCE4 + Flux theme/icons/font + Mesa/VirGL |
| User | `flux` / `flux`, NOPASSWD sudo |
| Repos | `http://ports.ubuntu.com/ubuntu-ports` only (never `archive.ubuntu.com`) |

Guest scripts: `setup_ubuntu_family.sh`, `setup_office_ubuntu.sh`, shared `setup_customization_xfce.sh`, `setup_hw_accel_guest.sh`.  
Chroot host: shared `setup_guest_chroot.sh` + `start_guest_gui.sh`.

Family rewrites DEB822 `ubuntu.sources` URIs from amd64 archive/security to Ubuntu Ports. Targeted XFCE (no `ubuntu-desktop`). `xz-utils` installed for guest theme extract.

## Office Suite component

`setup_office_ubuntu.sh` installs LibreOffice, a PDF viewer and Xournal++. Ubuntu-specific handling versus the Debian stack:

- Enables the `universe` component in place (DEB822 `ubuntu.sources` or legacy `sources.list`) — `thunderbird`, `xournalpp`, `npm` and `fonts-noto` are not in `main`. Never appends a new archive or PPA entry.
- Skips `thunderbird` when the archive package is a snap transitional deb (24.04+), since `snapd` cannot run under proot.
- Installs `papers` (GNOME's renamed Evince) rather than `evince`: on 26.04 `papers` is the stable release in `main` (50.x) while `evince` is an alpha in `universe` (49~alpha). `evince` stays as the fallback for older bases, and a missing PDF viewer is non-fatal because LibreOffice opens PDFs.

See [docs/plan/ubuntu-kali-parrot-arch.md](../plan/ubuntu-kali-parrot-arch.md).
