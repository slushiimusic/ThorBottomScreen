package dev.slushii.thorbottom;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

/**
 * Wraps the AYN Thor's own dual-screen switch.
 *
 * <p>The vendor app {@code com.odin.dualscreen.assistant} (which runs as
 * {@code android.uid.system}) keeps its screen mode in
 * {@code Settings.System/dual_screen_display_mode}, and its {@code TccServices} registers a
 * {@link android.database.ContentObserver} on that key. Writing the key is therefore the
 * entire switch: the vendor service reacts exactly as if the user had used the on-device
 * menu, and the vendor's own menu ({@code ScreenModeControlPanel}) observes the same key,
 * so it stays in sync with whatever we do.
 *
 * <p>Verified by inspection and on-device test on Thor_V1.0.0.377 (Android 13):
 * <ul>
 *   <li>mode 0 - both screens on ({@code ScreenUtil.openSecondaryScreen})</li>
 *   <li>mode 1 - top only ({@code ScreenUtil.closeSecondaryScreen}: the vendor adds its
 *       {@code primaryScreenTopLayout} cover window on display 4 and sets the system
 *       property {@code sys.temp.disabled.touch} to {@code "0,1"} to kill touch there)</li>
 *   <li>mode 2 - bottom only (covers the main screen instead)</li>
 * </ul>
 *
 * <p>Reading the key is unrestricted. Writing it is not: see {@link ShellService}.
 */
public final class ScreenModeController {

    private static final String TAG = "ThorBottomScreen";

    public static final String KEY_MODE = "dual_screen_display_mode";
    public static final String KEY_FOCUS_LOCK = "screen_focus_lock";
    public static final String KEY_SYS_KEY_FOCUS_LOCK = "enable_system_key_focus_lock";

    public static final int MODE_DUAL = 0;
    public static final int MODE_TOP_ONLY = 1;
    public static final int MODE_BOTTOM_ONLY = 2;

    /** The bottom screen's physical display id on the Thor. */
    public static final long BOTTOM_DISPLAY_PHYSICAL_ID = 4630946482288158084L;

    private ScreenModeController() {}

    /** We can only write through the shell helper. */
    public static boolean canWrite() {
        return ShizukuManager.isReady();
    }

    public static int getMode(Context ctx) {
        return Settings.System.getInt(ctx.getContentResolver(), KEY_MODE, MODE_DUAL);
    }

    private static int getInt(Context ctx, String key, int def) {
        return Settings.System.getInt(ctx.getContentResolver(), key, def);
    }

    /**
     * Switch to top-screen-only, mirroring what the vendor menu does for that choice.
     *
     * <p>The vendor's own handler saves the pre-existing focus-lock settings before
     * overwriting them, so we do the same into our own prefs and put them back in
     * {@link #applyDual}.
     */
    public static boolean applyTopOnly(Context ctx, Prefs prefs) {
        if (!canWrite()) return false;
        try {
            // Only capture the originals if we are not already holding a set. If a previous
            // cycle was cut short (service killed, app reinstalled) the live values are our
            // own, and overwriting the saved originals with them would lose them for good.
            if (getMode(ctx) == MODE_DUAL && !prefs.hasSavedFocusLockState()) {
                prefs.saveFocusLockState(
                        getInt(ctx, KEY_FOCUS_LOCK, 0),
                        getInt(ctx, KEY_SYS_KEY_FOCUS_LOCK, 0));
            }
            if (prefs.matchVendorFocusLock()) {
                ShizukuManager.putSystemInt(KEY_FOCUS_LOCK, 1);
                ShizukuManager.putSystemInt(KEY_SYS_KEY_FOCUS_LOCK, 1);
            }
            boolean ok = ShizukuManager.putSystemInt(KEY_MODE, MODE_TOP_ONLY);
            if (ok && prefs.deepOff() && ShizukuManager.setBottomPanelPower(false)) {
                prefs.setPanelPoweredOff(true);
            }
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "applyTopOnly failed", t);
            return false;
        }
    }

    /** Back to both screens, restoring the focus-lock settings we found before. */
    public static boolean applyDual(Context ctx, Prefs prefs) {
        if (!canWrite()) return false;
        try {
            // Bring the panel back before uncovering it, so nothing is shown on a dead screen.
            if (prefs.panelPoweredOff()) {
                ShizukuManager.setBottomPanelPower(true);
                prefs.setPanelPoweredOff(false);
            }
            boolean ok = ShizukuManager.putSystemInt(KEY_MODE, MODE_DUAL);
            if (prefs.matchVendorFocusLock() && prefs.hasSavedFocusLockState()) {
                ShizukuManager.putSystemInt(KEY_FOCUS_LOCK, prefs.savedFocusLock());
                ShizukuManager.putSystemInt(KEY_SYS_KEY_FOCUS_LOCK, prefs.savedSysKeyFocusLock());
                prefs.clearSavedFocusLockState();
            }
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "applyDual failed", t);
            return false;
        }
    }

    /** True if this firmware exposes the vendor key at all. */
    public static boolean isSupported(Context ctx) {
        return Settings.System.getString(ctx.getContentResolver(), KEY_MODE) != null;
    }
}
