package dev.slushii.thorbottom;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import rikka.shizuku.Shizuku;

/**
 * The whole runtime of this app.
 *
 * <p>Cost model, which is the point of the exercise:
 * <ul>
 *   <li>No polling, no timers, no alarms, no WorkManager, no foreground service, no wake
 *       locks, no overlay, nothing drawn. The process sits idle at 0% CPU.</li>
 *   <li>The system wakes us only when a window actually changes, and we then do nothing but
 *       compare a string against a small set.</li>
 *   <li>Only when that comparison says the screen should actually flip do we spend anything:
 *       one query for the real top app, then one settings write. Both are rare - a handful
 *       of times per gaming session.</li>
 * </ul>
 */
public class AppMonitorService extends AccessibilityService {

    private static final String TAG = "ThorBottomScreen";

    /** The Thor's main display. Display 4 is the bottom screen and has its own activity stack. */
    private static final int MAIN_DISPLAY = 0;

    /**
     * Packages whose windows must never be read as "the user switched app".
     *
     * <p>The vendor entries are the important ones: the dual-screen menu raised by the
     * hardware button is itself a window owned by {@code com.odin.dualscreen.assistant}
     * (it is added to the bottom display at window type 2021). Without this, opening that
     * menu from inside an emulator would look like leaving the emulator, and we would
     * restore dual mode underneath the user while they stood in the menu choosing something.
     */
    private static final String VENDOR_DUAL_SCREEN = "com.odin.dualscreen.assistant";

    private static final Set<String> ALWAYS_IGNORE = new HashSet<>(Arrays.asList(
            "android",
            "com.android.systemui",
            VENDOR_DUAL_SCREEN,
            "com.odin.gameassistant",
            "com.odin.settings",
            "moe.shizuku.privileged.api"));

    private Prefs prefs;
    private String imePackage;

    /** Best guess at the package on top, from the last usable accessibility event. */
    private String currentPkg;

    /**
     * Non-null when the user overrode us by hand (hardware button / vendor menu) while this
     * package was in the foreground. We stay out of the way until they leave that app.
     */
    private String overrideForPkg;

    /** The value we are about to write ourselves, so our own write is not read as an override. */
    private int expectSelfWrite = Integer.MIN_VALUE;

    private ContentObserver modeObserver;
    private BroadcastReceiver screenOnReceiver;
    private SharedPreferences.OnSharedPreferenceChangeListener prefsListener;

    /**
     * Set while reacting to the user editing the app list. Taking an app OUT of the list is an
     * explicit statement that it should not be turning the screen off, so it restores even when
     * "turn the bottom screen back on when you leave" is off, which otherwise pins the screen
     * off until the hardware button is used.
     */
    private boolean forceRestore;

    /**
     * When we switch the vendor mode, the vendor adds or removes its own cover window on the
     * bottom screen, and that fires a window event from its package a millisecond or two
     * later. That is our own doing, not the user opening the menu, so it is ignored for a
     * moment afterwards. A human pressing the button is always seconds away, never
     * milliseconds.
     */
    private static final long VENDOR_ECHO_WINDOW_MS = 3000L;
    private long lastApplyUptimeMs = Long.MIN_VALUE;

    /** How long DisplayManager's wake sequence needs before a panel power-off will stick. */
    private static final long WAKE_SETTLE_MS = 1500L;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        prefs = new Prefs(this);
        imePackage = resolveImePackage();

        // Bind the shell helper now, and again whenever Shizuku itself comes up later
        // (on a non-rooted device it has to be restarted after every reboot).
        ShizukuManager.setOnReadyCallback(new Runnable() {
            @Override public void run() {
                RotationSuggestions.apply(AppMonitorService.this, prefs);
                evaluate();
            }
        });
        try {
            Shizuku.addBinderReceivedListenerSticky(new Shizuku.OnBinderReceivedListener() {
                @Override public void onBinderReceived() { ShizukuManager.bind(AppMonitorService.this); }
            });
        } catch (Throwable t) {
            Log.w(TAG, "could not listen for Shizuku", t);
        }
        ShizukuManager.bind(this);

        modeObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, Uri uri) { onModeChangedExternally(); }
        };
        getContentResolver().registerContentObserver(
                Settings.System.getUriFor(ScreenModeController.KEY_MODE), false, modeObserver);

        // Waking the device resets the panel's power state, so re-apply if we are still
        // sitting in a selected app. Registered dynamically; it costs nothing when idle.
        screenOnReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (prefs == null || !prefs.deepOff()) return;
                prefs.setPanelPoweredOff(false);
                // DisplayManager finishes its own wake sequence after this broadcast and
                // re-asserts power on both panels, so re-applying immediately gets undone.
                // One delayed shot, not a poll.
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() { evaluateAfterWake(); }
                }, WAKE_SETTLE_MS);
            }
        };
        registerReceiver(screenOnReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));

        // React the moment the user edits the list or the master switch, instead of waiting
        // for the next app switch. Same process as the UI, so this fires immediately.
        prefsListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
                if (!"packages".equals(key) && !"enabled".equals(key)) return;
                forceRestore = true;
                try {
                    if ("enabled".equals(key) && !prefs.enabled()) {
                        applyRestore();
                    } else {
                        evaluate();
                    }
                } finally {
                    forceRestore = false;
                }
            }
        };
        prefs.raw().registerOnSharedPreferenceChangeListener(prefsListener);

        // Also apply here, not only from the helper-ready callback: if the app was granted
        // WRITE_SECURE_SETTINGS over adb it can do this itself and there is no helper to wait for.
        RotationSuggestions.apply(this, prefs);

        Log.i(TAG, "connected; shizuku=" + ShizukuManager.hasPermission()
                + " enabled=" + prefs.enabled() + " selected=" + prefs.packages());
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence raw = event.getPackageName();
        if (raw == null) return;
        String pkg = raw.toString();

        if (VENDOR_DUAL_SCREEN.equals(pkg)) {
            if (SystemClock.uptimeMillis() - lastApplyUptimeMs < VENDOR_ECHO_WINDOW_MS) {
                Log.d(TAG, "ignoring vendor echo (our own cover), class=" + event.getClassName());
                return;
            }
            Log.d(TAG, "vendor window class=" + event.getClassName());
            // The dual-screen menu is opening. It is drawn on the bottom screen, so if we
            // have that panel genuinely powered down the user would be pressing the button
            // and getting nothing. Bring it back and let them choose; whatever they pick
            // arrives as an external write and we stand down.
            if (prefs != null && prefs.panelPoweredOff()) {
                Log.i(TAG, "vendor menu opening - restoring panel power");
                if (ShizukuManager.setBottomPanelPower(true)) prefs.setPanelPoweredOff(false);
            }
            // Our own cover sits above the vendor's windows, so it would hide the very menu
            // the user just asked for. Take it down; the next app switch puts it back.
            if (ScreenCover.isShowing()) {
                Log.i(TAG, "vendor menu opening - lifting cover");
                ScreenCover.hide();
            }
            return;
        }
        // Our own windows, including the cover we just put on the bottom screen, otherwise
        // read as "the user switched to Thor Bottom Screen" and undo the very change we made.
        if (pkg.equals(getPackageName())) return;
        if (ALWAYS_IGNORE.contains(pkg)) return;
        if (pkg.equals(imePackage)) return;
        if (pkg.equals(currentPkg)) return;

        currentPkg = pkg;
        clearOverrideIfLeft(pkg);
        evaluate();
    }

    private void clearOverrideIfLeft(String pkg) {
        // Leaving the app the user overrode in ends the override: automation resumes.
        if (overrideForPkg != null && !overrideForPkg.equals(pkg)) overrideForPkg = null;
    }

    /**
     * After a wake, the vendor mode may still be top-only while the panel is back on, which
     * {@link #wouldChange} would read as "nothing to do". Re-apply the panel power directly.
     */
    private void evaluateAfterWake() {
        if (prefs == null || !prefs.enabled() || !ScreenModeController.canWrite()) return;
        if (overrideForPkg != null && prefs.respectManualOverride()) return;
        String top = ShizukuManager.getTopPackageOnDisplay(MAIN_DISPLAY);
        if (top == null) return;
        currentPkg = top;
        if (prefs.isSelected(top)
                && ScreenModeController.getMode(this) == ScreenModeController.MODE_TOP_ONLY) {
            if (ShizukuManager.setBottomPanelPower(false)) prefs.setPanelPoweredOff(true);
        } else {
            evaluate();
        }
    }

    /** Would acting on this package actually change the screen mode? */
    private boolean wouldChange(String pkg, int mode) {
        if (!usingShizuku()) {
            // Cover backend: the only state that matters is whether our window is up.
            return prefs.isSelected(pkg) != ScreenCover.isShowing();
        }
        if (prefs.isSelected(pkg)) {
            if (mode == ScreenModeController.MODE_DUAL) return true;
            // The mode can already be right while the panel is still lit - after our own
            // service restarts, or after a wake. Deep off still has work to do.
            return prefs.deepOff()
                    && mode == ScreenModeController.MODE_TOP_ONLY
                    && !prefs.panelPoweredOff();
        }
        // A powered-down panel is never left behind, whatever the restore preference says.
        // "Leave it off when I leave" means the vendor's cover, not a dead panel that only a
        // reboot brings back.
        if (prefs.panelPoweredOff()) return true;
        // Otherwise only ever undo what we did. A top-only mode the user chose is left alone.
        return mode == ScreenModeController.MODE_TOP_ONLY
                && prefs.weSetIt() && (prefs.restoreOnExit() || forceRestore);
    }

    private void evaluate() {
        if (prefs == null || !prefs.enabled()) return;
        if (!ScreenModeController.canWrite()) {
            // Keep trying for the helper - it gives the better behaviour - but fall back to
            // our own cover window if the user has granted "Display over other apps".
            ShizukuManager.bind(this);
            if (!ScreenCover.canDraw(this)) return;
        }
        if (currentPkg == null) {
            // No window event seen yet - the service just started, or the device booted
            // straight into a game. Ask who is in front rather than waiting for a switch.
            currentPkg = usingShizuku()
                    ? ShizukuManager.getTopPackageOnDisplay(MAIN_DISPLAY) : null;
            if (currentPkg == null) return;
        }
        if (overrideForPkg != null && prefs.respectManualOverride()) return;

        int mode = ScreenModeController.getMode(this);
        if (!wouldChange(currentPkg, mode)) return;

        // We are about to flip the screen, so it is worth one query to confirm what is
        // genuinely in front on the MAIN display. The Thor runs a second activity stack on
        // display 4, and an event from an app sitting down there must not be mistaken for
        // the user leaving their game.
        // Without the helper there is no way to ask which display a package is on, so the
        // event's own package has to be trusted. See the README for what that costs.
        String top = usingShizuku() ? ShizukuManager.getTopPackageOnDisplay(MAIN_DISPLAY) : null;
        if (top != null && !top.equals(currentPkg)) {
            Log.d(TAG, "event said " + currentPkg + " but display 0 holds " + top);
            currentPkg = top;
            clearOverrideIfLeft(top);
            if (overrideForPkg != null && prefs.respectManualOverride()) return;
            if (!wouldChange(currentPkg, mode)) return;
        }

        boolean shouldDisable = prefs.isSelected(currentPkg);
        lastApplyUptimeMs = SystemClock.uptimeMillis();
        Log.d(TAG, "applying: pkg=" + currentPkg + " disable=" + shouldDisable
                + " mode=" + mode + " backend=" + (usingShizuku() ? "vendor" : "cover"));

        if (shouldDisable) applyDisable(); else applyRestore();
    }

    /** True when the shell helper is up, so we can drive the vendor's own switch. */
    private boolean usingShizuku() { return ShizukuManager.isReady(); }

    private void applyDisable() {
        if (usingShizuku()) {
            expectSelfWrite = ScreenModeController.MODE_TOP_ONLY;
            if (ScreenModeController.applyTopOnly(this, prefs)) {
                prefs.setWeSetIt(true);
            } else {
                expectSelfWrite = Integer.MIN_VALUE;
            }
            return;
        }
        // No helper: put up our own cover, which is what the vendor does anyway.
        if (ScreenCover.show(this)) prefs.setWeSetIt(true);
    }

    private void applyRestore() {
        ScreenCover.hide();
        if (usingShizuku()) {
            expectSelfWrite = ScreenModeController.MODE_DUAL;
            if (ScreenModeController.applyDual(this, prefs)) {
                prefs.setWeSetIt(false);
            } else {
                expectSelfWrite = Integer.MIN_VALUE;
            }
            return;
        }
        prefs.setWeSetIt(false);
    }

    /**
     * The mode key changed. If it was not our own write, the user did it - via the hardware
     * button's menu or the vendor settings - and their choice wins until they leave this app.
     */
    private void onModeChangedExternally() {
        int now = ScreenModeController.getMode(this);
        if (now == expectSelfWrite) {
            expectSelfWrite = Integer.MIN_VALUE;
            return;
        }
        expectSelfWrite = Integer.MIN_VALUE;
        overrideForPkg = currentPkg;
        if (prefs != null) {
            // Standing down is not enough. If we had the panel genuinely powered down, the
            // user's choice would land on a dead screen: they pick "dual screen", the vendor
            // sets the mode, and the bottom panel stays dark with nothing left to turn it
            // back on. Always give the panel back before yielding.
            if (prefs.panelPoweredOff()) {
                Log.i(TAG, "manual override with panel powered down, restoring panel power");
                if (ShizukuManager.setBottomPanelPower(true)) prefs.setPanelPoweredOff(false);
            }
            prefs.setWeSetIt(false);
        }
        Log.i(TAG, "manual override (mode=" + now + "), standing down for " + currentPkg);
    }

    private String resolveImePackage() {
        String ime = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (TextUtils.isEmpty(ime)) return null;
        int slash = ime.indexOf('/');
        return slash > 0 ? ime.substring(0, slash) : ime;
    }

    @Override
    public void onInterrupt() { }

    @Override
    public boolean onUnbind(Intent intent) {
        // Do not strand the user with a covered bottom screen if they turn the service off.
        ScreenCover.hide();
        if (prefs != null && prefs.panelPoweredOff()) {
            ShizukuManager.setBottomPanelPower(true);
            prefs.setPanelPoweredOff(false);
        }
        if (prefs != null && prefs.weSetIt() && prefs.restoreOnExit()) {
            ScreenModeController.applyDual(this, prefs);
            prefs.setWeSetIt(false);
        }
        cleanup();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    private void cleanup() {
        if (prefsListener != null && prefs != null) {
            try { prefs.raw().unregisterOnSharedPreferenceChangeListener(prefsListener); } catch (Throwable ignored) { }
            prefsListener = null;
        }
        if (screenOnReceiver != null) {
            try { unregisterReceiver(screenOnReceiver); } catch (Throwable ignored) { }
            screenOnReceiver = null;
        }
        if (modeObserver != null) {
            try { getContentResolver().unregisterContentObserver(modeObserver); } catch (Throwable ignored) { }
            modeObserver = null;
        }
    }
}
