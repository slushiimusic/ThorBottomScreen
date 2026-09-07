package dev.slushii.thorbottom;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;

import rikka.shizuku.Shizuku;

/**
 * Owns the connection to the shell-identity helper. Binding happens once and is kept, so
 * switching screen mode later is a single binder call with no process spawn.
 */
public final class ShizukuManager {

    private static final String TAG = "ThorBottomScreen";
    public static final int REQUEST_CODE = 4321;

    private static volatile IShellService service;
    private static volatile boolean binding;
    private static volatile Runnable onReady;

    private ShizukuManager() {}

    /** Invoked once the helper is up, so a pending decision can be re-run. */
    public static void setOnReadyCallback(Runnable r) { onReady = r; }

    /** Shizuku itself installed and running. */
    public static boolean isRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hasPermission() {
        try {
            if (!isRunning()) return false;
            if (Shizuku.isPreV11()) return false;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void requestPermission() {
        try {
            Shizuku.requestPermission(REQUEST_CODE);
        } catch (Throwable t) {
            Log.w(TAG, "requestPermission failed", t);
        }
    }

    public static boolean isReady() { return service != null; }

    /** Idempotent. Safe to call from anywhere; returns immediately. */
    public static void bind(Context ctx) {
        if (service != null || binding) return;
        if (!hasPermission()) return;
        binding = true;
        try {
            Shizuku.UserServiceArgs args =
                    new Shizuku.UserServiceArgs(
                            new ComponentName(ctx.getPackageName(), ShellService.class.getName()))
                            .daemon(false)
                            .processNameSuffix("shell")
                            .debuggable(false)
                            .version(1);
            Shizuku.bindUserService(args, CONNECTION);
        } catch (Throwable t) {
            binding = false;
            Log.w(TAG, "bindUserService failed", t);
        }
    }

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            binding = false;
            service = (binder != null && binder.pingBinder())
                    ? IShellService.Stub.asInterface(binder) : null;
            Log.i(TAG, "shell helper connected=" + (service != null));
            Runnable r = onReady;
            if (service != null && r != null) r.run();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binding = false;
            service = null;
            Log.i(TAG, "shell helper disconnected");
        }
    };

    /** Write a Settings.System int with the shell identity. False if the helper is not up. */
    public static boolean putSystemInt(String key, int value) {
        IShellService s = service;
        if (s == null) return false;
        try {
            return s.putSystemInt(key, value);
        } catch (Throwable t) {
            Log.w(TAG, "putSystemInt failed", t);
            service = null;
            return false;
        }
    }

    public static boolean putSecureInt(String key, int value) {
        IShellService s = service;
        if (s == null) return false;
        try {
            return s.putSecureInt(key, value);
        } catch (Throwable t) {
            Log.w(TAG, "putSecureInt failed", t);
            service = null;
            return false;
        }
    }

    /** Null if the helper is not up or the answer could not be determined. */
    public static String getTopPackageOnDisplay(int displayId) {
        IShellService s = service;
        if (s == null) return null;
        try {
            return s.getTopPackageOnDisplay(displayId);
        } catch (Throwable t) {
            Log.w(TAG, "getTopPackageOnDisplay failed", t);
            return null;
        }
    }

    /** True if the request was delivered to the helper. */
    public static boolean setBottomPanelPower(boolean on) {
        IShellService s = service;
        if (s == null) return false;
        try {
            String r = s.setBottomPanelPower(ScreenModeController.BOTTOM_DISPLAY_PHYSICAL_ID, on);
            Log.i(TAG, "panel power " + (on ? "on" : "off") + " -> " + r);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "setBottomPanelPower failed", t);
            return false;
        }
    }

    public static String setDisplayPowerMode(long physicalDisplayId, int powerMode) {
        IShellService s = service;
        if (s == null) return "helper not connected";
        try {
            return s.setDisplayPowerMode(physicalDisplayId, powerMode);
        } catch (Throwable t) {
            return "failed: " + t;
        }
    }
}
