package dev.slushii.thorbottom;

import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * SystemUI's rotation-suggestion button - the little rotate icon that appears in the corner
 * when the device's physical orientation disagrees with the locked one.
 *
 * <p>It lives in {@code Settings.Secure/show_rotation_suggestions}. Unlike the vendor screen
 * key, Secure is not key-restricted; it just needs WRITE_SECURE_SETTINGS. The shell helper
 * already has that, so this rides along on the same connection.
 *
 * <p>The key is normally unset, which AOSP reads as enabled, so a missing value means "on".
 * Whatever it was before we first turned it off is remembered and put back if the option is
 * switched off again.
 */
public final class RotationSuggestions {

    public static final String KEY = "show_rotation_suggestions";
    public static final int ENABLED = 1;
    public static final int DISABLED = 0;

    private RotationSuggestions() {}

    public static int current(Context ctx) {
        return Settings.Secure.getInt(ctx.getContentResolver(), KEY, ENABLED);
    }

    public static boolean isHidden(Context ctx) {
        return current(ctx) == DISABLED;
    }

    /**
     * Bring the device in line with the preference. Idempotent, so it is safe to call again
     * whenever the helper reconnects - which is how the setting survives a reboot, since
     * Shizuku has to be restarted anyway and something may have reset the key meanwhile.
     */
    /**
     * True if this app itself holds WRITE_SECURE_SETTINGS, which can be granted once over adb
     * and then survives reboots - unlike Shizuku, which must be restarted every boot.
     */
    public static boolean canWriteDirectly(Context ctx) {
        return ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean isAvailable(Context ctx) {
        return canWriteDirectly(ctx) || ShizukuManager.isReady();
    }

    private static boolean put(Context ctx, int value) {
        if (canWriteDirectly(ctx)) {
            try {
                return Settings.Secure.putInt(ctx.getContentResolver(), KEY, value);
            } catch (Throwable ignored) { }
        }
        return ShizukuManager.putSecureInt(KEY, value);
    }

    public static boolean apply(Context ctx, Prefs prefs) {
        if (!isAvailable(ctx)) return false;

        if (prefs.hideRotationSuggestions()) {
            if (!prefs.hasSavedRotationSuggestionState()) {
                prefs.saveRotationSuggestionState(current(ctx));
            }
            if (current(ctx) == DISABLED) return true;
            return put(ctx, DISABLED);
        }

        if (prefs.hasSavedRotationSuggestionState()) {
            int previous = prefs.savedRotationSuggestions();
            prefs.clearSavedRotationSuggestionState();
            if (current(ctx) != previous) return put(ctx, previous);
        }
        return true;
    }
}
