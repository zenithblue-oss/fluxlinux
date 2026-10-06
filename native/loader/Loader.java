package com.termux.x11;

/**
 * termux-x11 shell loader, run as
 *   CLASSPATH=$PREFIX/libexec/termux-x11/loader.apk app_process / com.termux.x11.Loader
 * Loads com.termux.x11.CmdEntryPoint from the app APK in $TERMUX_X11_APK_PATH.
 * Built into loader.apk (classes.dex only) by scripts/build_loader_apk.sh.
 */
public class Loader {
    public static void main(String[] args) {
        String apk = System.getenv("TERMUX_X11_APK_PATH");
        if (apk == null || apk.isEmpty()) {
            System.err.println("Error: TERMUX_X11_APK_PATH environment variable is not set!");
            System.exit(1);
        }
        System.out.println("Loader: Loading classes from " + apk);
        try {
            // Reflection keeps the build free of android.jar (plain javac + d8).
            ClassLoader cl = (ClassLoader) Class.forName("dalvik.system.PathClassLoader")
                .getConstructor(String.class, ClassLoader.class)
                .newInstance(apk, ClassLoader.getSystemClassLoader());
            cl.loadClass("com.termux.x11.CmdEntryPoint")
                .getMethod("main", String[].class)
                .invoke(null, (Object) args);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
