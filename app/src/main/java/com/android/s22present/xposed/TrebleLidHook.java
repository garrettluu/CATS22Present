package com.android.s22present.xposed;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Disables TrebleApp's built-in Cat S22 Flip lid handling, which sleeps/wakes Android and cuts the
 * secondary panel's power on every hall uevent with no rate limit (wedging the panel's SPI bus).
 * S22Present's root ScreenService handles the lid instead.
 */
public class TrebleLidHook extends XposedModule {

    private static final String TAG = "S22Present.TrebleLidHook";
    private static final String TREBLE_PACKAGE = "me.phh.treble.app";
    // Read by ScreenService's DisplayController (root) from TrebleApp's device-protected files dir:
    // /data/user_de/0/me.phh.treble.app/files/s22present_lid_hook, containing the boot_id.
    private static final String MARKER_FILE = "s22present_lid_hook";

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!TREBLE_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        try {
            Class<?> lidClass = Class.forName(
                "me.phh.treble.app.Lid", false, param.getDefaultClassLoader());
            Method cat = lidClass.getDeclaredMethod("cat", Context.class);
            hook(cat).intercept(new SkipCatHooker());
            log(Log.INFO, TAG, "Hooked Lid.cat");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook TrebleApp Lid.cat", t);
        }
    }

    private static class SkipCatHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) {
            Log.i(TAG, "Suppressed TrebleApp Cat S22 lid observer");
            Object arg = chain.getArg(0);
            if (arg instanceof Context) {
                try {
                    File dir = ((Context) arg).createDeviceProtectedStorageContext().getFilesDir();
                    try (FileWriter w = new FileWriter(new File(dir, MARKER_FILE))) {
                        w.write(readBootId());
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "Failed to write hook marker", t);
                }
            }
            return null;
        }
    }

    private static String readBootId() throws Exception {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/sys/kernel/random/boot_id"))) {
            return r.readLine().trim();
        }
    }
}
