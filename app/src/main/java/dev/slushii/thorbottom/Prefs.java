package dev.slushii.thorbottom;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Plain SharedPreferences. No database, no background work. */
public final class Prefs {

    private static final String FILE = "thor_bottom_screen";

    private static final String K_ENABLED = "enabled";
    private static final String K_PACKAGES = "packages";
    private static final String K_RESTORE_ON_EXIT = "restore_on_exit";
    private static final String K_RESPECT_OVERRIDE = "respect_manual_override";
    private static final String K_MATCH_VENDOR_FOCUS = "match_vendor_focus_lock";
    private static final String K_WE_SET_IT = "we_set_it";
    private static final String K_SAVED_FOCUS = "saved_focus_lock";
    private static final String K_SAVED_SYSKEY = "saved_syskey_focus_lock";
    private static final String K_HAS_SAVED = "has_saved_focus_state";
    private static final String K_DEEP_OFF = "deep_off";
    private static final String K_PANEL_OFF = "panel_powered_off";
    private static final String K_HIDE_ROT = "hide_rotation_suggestions";
    private static final String K_SAVED_ROT = "saved_rotation_suggestions";
    private static final String K_HAS_SAVED_ROT = "has_saved_rotation_suggestions";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public SharedPreferences raw() { return sp; }

    public boolean enabled() { return sp.getBoolean(K_ENABLED, true); }
    public void setEnabled(boolean v) { sp.edit().putBoolean(K_ENABLED, v).apply(); }

    public boolean restoreOnExit() { return sp.getBoolean(K_RESTORE_ON_EXIT, true); }
    public void setRestoreOnExit(boolean v) { sp.edit().putBoolean(K_RESTORE_ON_EXIT, v).apply(); }

    public boolean respectManualOverride() { return sp.getBoolean(K_RESPECT_OVERRIDE, true); }
    public void setRespectManualOverride(boolean v) { sp.edit().putBoolean(K_RESPECT_OVERRIDE, v).apply(); }

    public boolean matchVendorFocusLock() { return sp.getBoolean(K_MATCH_VENDOR_FOCUS, true); }
    public void setMatchVendorFocusLock(boolean v) { sp.edit().putBoolean(K_MATCH_VENDOR_FOCUS, v).apply(); }

    /** True while the bottom screen is off because we turned it off (not the user). */
    public boolean weSetIt() { return sp.getBoolean(K_WE_SET_IT, false); }
    public void setWeSetIt(boolean v) { sp.edit().putBoolean(K_WE_SET_IT, v).apply(); }

    /** Opt-in: also power the panel down, beyond the vendor's cover window. */
    public boolean deepOff() { return sp.getBoolean(K_DEEP_OFF, false); }
    public void setDeepOff(boolean v) { sp.edit().putBoolean(K_DEEP_OFF, v).apply(); }

    /** True while we have the panel genuinely powered down. */
    public boolean panelPoweredOff() { return sp.getBoolean(K_PANEL_OFF, false); }
    public void setPanelPoweredOff(boolean v) { sp.edit().putBoolean(K_PANEL_OFF, v).apply(); }

    /** Hide SystemUI's rotation-suggestion button. Applies device-wide, not per app. */
    public boolean hideRotationSuggestions() { return sp.getBoolean(K_HIDE_ROT, false); }
    public void setHideRotationSuggestions(boolean v) { sp.edit().putBoolean(K_HIDE_ROT, v).apply(); }

    void saveRotationSuggestionState(int value) {
        sp.edit().putInt(K_SAVED_ROT, value).putBoolean(K_HAS_SAVED_ROT, true).apply();
    }
    boolean hasSavedRotationSuggestionState() { return sp.getBoolean(K_HAS_SAVED_ROT, false); }
    int savedRotationSuggestions() { return sp.getInt(K_SAVED_ROT, 1); }
    void clearSavedRotationSuggestionState() { sp.edit().putBoolean(K_HAS_SAVED_ROT, false).apply(); }

    public Set<String> packages() {
        return new HashSet<>(sp.getStringSet(K_PACKAGES, Collections.<String>emptySet()));
    }

    public boolean isSelected(String pkg) {
        return pkg != null && sp.getStringSet(K_PACKAGES, Collections.<String>emptySet()).contains(pkg);
    }

    public void setSelected(String pkg, boolean selected) {
        Set<String> set = packages();
        if (selected) set.add(pkg); else set.remove(pkg);
        sp.edit().putStringSet(K_PACKAGES, set).apply();
    }

    void saveFocusLockState(int focusLock, int sysKeyFocusLock) {
        sp.edit()
          .putInt(K_SAVED_FOCUS, focusLock)
          .putInt(K_SAVED_SYSKEY, sysKeyFocusLock)
          .putBoolean(K_HAS_SAVED, true)
          .apply();
    }

    boolean hasSavedFocusLockState() { return sp.getBoolean(K_HAS_SAVED, false); }
    int savedFocusLock() { return sp.getInt(K_SAVED_FOCUS, 0); }
    int savedSysKeyFocusLock() { return sp.getInt(K_SAVED_SYSKEY, 0); }
    void clearSavedFocusLockState() { sp.edit().putBoolean(K_HAS_SAVED, false).apply(); }
}
