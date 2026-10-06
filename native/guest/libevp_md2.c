/*
 * openSUSE guest stub: libldap (pulled by sudo) needs EVP_md2@OPENSSL_3.0.0,
 * which OpenSSL 3.5.3 dropped. MD2 is never used; returning NULL ("no such
 * digest") satisfies the loader. Installed via /etc/ld.so.preload by
 * setup_opensuse_family.sh. Built by scripts/build_guest_helpers.sh.
 */
const void *EVP_md2(void) { return 0; }
