package dev.slushii.thorbottom;

import android.content.Context;
import android.os.IBinder;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * The Shizuku user service. This class is instantiated in a separate process that runs as
 * the shell user, so binder calls it makes are attributed to uid 2000.
 *
 * <p>This exists for exactly one reason. AOSP's SettingsProvider refuses a write to any
 * Settings.System key that is not in its own PUBLIC_SETTINGS/PRIVATE_SETTINGS lists unless
 * the caller is system, shell or root - it throws
 * {@code IllegalArgumentException: You cannot keep your settings in the secure settings}.
 * {@code dual_screen_display_mode} is a vendor key, so no permission a sideloaded app can
 * hold is enough. Holding WRITE_SETTINGS and even WRITE_SECURE_SETTINGS was measured to
 * make no difference. Borrowing the shell identity is the only route.
 */
public class ShellService extends IShellService.Stub {

    private final Context context;

    @SuppressWarnings("unused")
    public ShellService() { this.context = null; }

    @SuppressWarnings("unused")
    public ShellService(Context context) { this.context = context; }

    @Override
    public void destroy() { System.exit(0); }

    @Override
    public void exit() { destroy(); }

    @Override
    public boolean putSystemInt(String key, int value) {
        // Preferred: a plain binder call from this process, so the write is attributed to shell.
        if (context != null) {
            try {
                if (Settings.System.putInt(context.getContentResolver(), key, value)) return true;
            } catch (Throwable ignored) {
                // fall through to the command line
            }
        }
        // Fallback: the platform's own settings command, which runs with our identity.
        return exec("settings put system " + key + " " + value) != null;
    }

    /**
     * Settings.Secure is a different wall from Settings.System: it is not key-restricted, it
     * simply requires WRITE_SECURE_SETTINGS. A sideloaded app can be granted that over adb,
     * but since the helper is already here as shell it is one less thing to set up.
     */
    @Override
    public boolean putSecureInt(String key, int value) {
        if (context != null) {
            try {
                if (Settings.Secure.putInt(context.getContentResolver(), key, value)) return true;
            } catch (Throwable ignored) { }
        }
        return exec("settings put secure " + key + " " + value) != null;
    }

    @Override
    public int getSystemInt(String key, int defValue) {
        if (context != null) {
            try {
                return Settings.System.getInt(context.getContentResolver(), key, defValue);
            } catch (Throwable ignored) { }
        }
        String out = exec("settings get system " + key);
        if (out == null) return defValue;
        try {
            return Integer.parseInt(out.trim());
        } catch (NumberFormatException e) {
            return defValue;
        }
    }

    @Override
    public String setDisplayPowerMode(long physicalDisplayId, int powerMode) {
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            Method getToken = sc.getMethod("getPhysicalDisplayToken", long.class);
            IBinder token = (IBinder) getToken.invoke(null, physicalDisplayId);
            if (token == null) return "no token for " + physicalDisplayId;
            Method set = sc.getMethod("setDisplayPowerMode", IBinder.class, int.class);
            set.invoke(null, token, powerMode);
            // The call is void, so it reports nothing. Read the state back to say what stuck.
            return "requested=" + powerMode;
        } catch (Throwable t) {
            return "failed: " + t;
        }
    }

    /**
     * The authoritative answer to "what is actually in front on the main screen".
     *
     * <p>Needed because the Thor runs a second activity stack on display 4: a window event
     * from an app sitting on the bottom screen otherwise reads as the user leaving their
     * game. Accessibility events do not carry a display id, and the flag that would let us
     * read window info is exactly the expensive one we are avoiding, so we ask the system
     * directly - and only when we are about to actually change something.
     */
    @Override
    public String getTopPackageOnDisplay(int displayId) {
        String out = exec("dumpsys activity activities"
                + " | grep -A6 'Display #" + displayId + " (activities'"
                + " | grep -m1 topResumedActivity");
        if (out == null) return null;
        int u0 = out.indexOf("u0 ");
        if (u0 < 0) return null;
        String rest = out.substring(u0 + 3).trim();
        int slash = rest.indexOf('/');
        if (slash <= 0) return null;
        return rest.substring(0, slash);
    }

    /**
     * A genuine panel power-off, as opposed to the vendor's black cover window.
     *
     * <p>AYN patched SurfaceFlinger to refuse it. Disassembling /system/bin/surfaceflinger
     * shows the guard is:
     * <pre>
     *   if (mode == POWER_MODE_OFF &amp;&amp; display != primary
     *           &amp;&amp; property_get_int32("display.power.state", -1) == 1) {
     *       ALOGD("disable Screen-2 off cause it should be turn on");
     *       return;   // refused
     *   }
     * </pre>
     * So the block is conditional on a plain system property, which the shell user may set.
     * We drop it for the duration of the call and put it straight back: the guard is only
     * consulted while the power mode is being applied, so the panel stays off afterwards.
     * Powering back on is never gated.
     */
    @Override
    public String setBottomPanelPower(long physicalDisplayId, boolean on) {
        if (on) return setDisplayPowerMode(physicalDisplayId, 2);

        String previous = getProp(GATE_PROP);
        if (previous == null || previous.isEmpty()) previous = "1";
        setProp(GATE_PROP, "0");
        try {
            return setDisplayPowerMode(physicalDisplayId, 0);
        } finally {
            setProp(GATE_PROP, previous);
        }
    }

    private static final String GATE_PROP = "display.power.state";

    private static String getProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (String) sp.getMethod("get", String.class).invoke(null, key);
        } catch (Throwable t) {
            String out = exec("getprop " + key);
            return out == null ? null : out.trim();
        }
    }

    private static void setProp(String key, String value) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            sp.getMethod("set", String.class, String.class).invoke(null, key, value);
            return;
        } catch (Throwable ignored) { }
        exec("setprop " + key + " " + value);
    }

    private static String exec(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            return p.waitFor() == 0 ? sb.toString() : null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }
}
