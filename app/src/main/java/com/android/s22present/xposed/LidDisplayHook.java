package com.android.s22present.xposed;

import android.os.IBinder;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Waking Android powers the whole display group on. This keeps the panel the lid hides off:
 * the outer panel while the lid is open (avoiding a flash and an extra SPI power cycle), and the
 * main panel while it's closed (brief wakes for the outer screen). Only system_server's own calls
 * are affected; S22Present's root process controls the panels from its own process.
 */
public class LidDisplayHook extends XposedModule {

    private static final String TAG = "S22Present.DisplayHook";
    // Set by ScreenService's DisplayController before it wakes Android; "1" = open, "0" = closed.
    private static final String LID_OPEN_PROP = "sys.s22present.lid_open";
    // Read by DisplayController (via /proc/1/root); contains the boot_id the hook is active for.
    private static final String MARKER_PATH = "/data/system/s22present_display_hook";
    // Install diagnostics, since logcat has usually rotated past boot by the time anyone looks.
    private static final String STATUS_PATH = "/data/system/s22present_display_hook_status";
    // The only caller of SurfaceControl.setDisplayPowerMode in system_server's display code; may
    // have the call inlined by AOT compilation, so it's deoptimized once its classloader is known.
    private static final String CALLER_CLASS =
        "com.android.server.display.LocalDisplayAdapter$SurfaceControlProxy";
    private static final int POWER_MODE_OFF = 0;

    private static Method getProp;
    private static final IBinder[] displayTokens = new IBinder[2];
    private static volatile boolean markerWritten;
    private static boolean inSystemServer;
    private static boolean installed;
    private static boolean callerDeoptimized;
    private static final StringBuilder status = new StringBuilder();

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        inSystemServer = param.isSystemServer();
        if (inSystemServer) install("onModuleLoaded");
    }

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        inSystemServer = true;
        install("onSystemServerStarting");
        deoptimizeCaller(param.getClassLoader());
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!inSystemServer) return;
        install("onPackageReady");
        deoptimizeCaller(param.getClassLoader());
    }

    private synchronized void install(String via) {
        if (installed) return;
        installed = true;
        try {
            Class<?> surfaceControl = Class.forName("android.view.SurfaceControl");
            Method setPowerMode = surfaceControl.getDeclaredMethod(
                "setDisplayPowerMode", IBinder.class, int.class);
            getProp = Class.forName("android.os.SystemProperties")
                .getMethod("get", String.class, String.class);
            hook(setPowerMode).intercept(new SetPowerModeHooker(surfaceControl));
            record("hooked via " + via);
            log(Log.INFO, TAG, "Hooked SurfaceControl.setDisplayPowerMode via " + via);
        } catch (Throwable t) {
            record("hook failed via " + via + ": " + t);
            log(Log.ERROR, TAG, "Failed to hook SurfaceControl.setDisplayPowerMode", t);
        }
    }

    private synchronized void deoptimizeCaller(ClassLoader classLoader) {
        if (callerDeoptimized || classLoader == null) return;
        try {
            Class<?> proxy = Class.forName(CALLER_CLASS, false, classLoader);
            Method caller = proxy.getDeclaredMethod("setDisplayPowerMode", IBinder.class, int.class);
            callerDeoptimized = true;
            record("deoptimize caller: " + deoptimize(caller));
        } catch (ClassNotFoundException e) {
            // Not the system server classloader; a later callback will have it.
        } catch (Throwable t) {
            callerDeoptimized = true;
            record("deoptimize caller failed: " + t);
        }
    }

    private static synchronized void record(String line) {
        status.append(line).append('\n');
        try (FileWriter w = new FileWriter(STATUS_PATH)) {
            w.write(status.toString());
        } catch (Throwable ignored) {
            // /data may not be ready yet; the next record() rewrites the whole status.
        }
    }

    private static class SetPowerModeHooker implements XposedInterface.Hooker {
        private final Class<?> surfaceControl;

        SetPowerModeHooker(Class<?> surfaceControl) {
            this.surfaceControl = surfaceControl;
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            writeMarkerOnce();
            Object token = chain.getArg(0);
            int mode = (Integer) chain.getArg(1);
            if (mode != POWER_MODE_OFF && token != null) {
                String lid = lidOpenProp();
                if ("1".equals(lid) && token.equals(displayToken(1))) {
                    Log.i(TAG, "Lid open: kept secondary panel off (mode " + mode + ")");
                    return null;
                }
                if ("0".equals(lid) && token.equals(displayToken(0))) {
                    Log.i(TAG, "Lid closed: kept main panel off (mode " + mode + ")");
                    return null;
                }
            }
            return chain.proceed();
        }

        // 0 = main, 1 = secondary, matching Globals.token1 / Globals.token.
        private IBinder displayToken(int index) {
            if (displayTokens[index] == null) {
                try {
                    long[] ids = (long[]) surfaceControl.getMethod("getPhysicalDisplayIds").invoke(null);
                    if (ids != null && ids.length > index) {
                        displayTokens[index] = (IBinder) surfaceControl
                            .getMethod("getPhysicalDisplayToken", long.class).invoke(null, ids[index]);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "Failed to get display token " + index, t);
                }
            }
            return displayTokens[index];
        }
    }

    private static String lidOpenProp() {
        try {
            return (String) getProp.invoke(null, LID_OPEN_PROP, "");
        } catch (Throwable t) {
            return "";
        }
    }

    private static void writeMarkerOnce() {
        if (markerWritten) return;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/sys/kernel/random/boot_id"));
             FileWriter w = new FileWriter(MARKER_PATH)) {
            w.write(r.readLine().trim());
            markerWritten = true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to write hook marker", t);
        }
    }
}
