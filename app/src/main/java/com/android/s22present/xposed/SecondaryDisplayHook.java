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
 * Stops Android from powering the secondary (outer) panel on when it wakes with the lid open.
 * Waking powers the whole display group on, and S22Present would then have to power the outer
 * panel straight back off: a visible flash and an extra SPI power cycle. Only system_server's own
 * calls are affected; S22Present's root process controls the panel from its own process.
 */
public class SecondaryDisplayHook extends XposedModule {

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
    private static volatile IBinder secondaryToken;
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
            if (mode != POWER_MODE_OFF && token != null && isLidOpen() && token.equals(secondary())) {
                Log.i(TAG, "Lid open: kept secondary panel off (mode " + mode + ")");
                return null;
            }
            return chain.proceed();
        }

        private IBinder secondary() {
            if (secondaryToken == null) {
                try {
                    long[] ids = (long[]) surfaceControl.getMethod("getPhysicalDisplayIds").invoke(null);
                    if (ids != null && ids.length > 1) {
                        secondaryToken = (IBinder) surfaceControl
                            .getMethod("getPhysicalDisplayToken", long.class).invoke(null, ids[1]);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "Failed to get secondary display token", t);
                }
            }
            return secondaryToken;
        }
    }

    private static boolean isLidOpen() {
        try {
            return "1".equals(getProp.invoke(null, LID_OPEN_PROP, ""));
        } catch (Throwable t) {
            return false;
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
