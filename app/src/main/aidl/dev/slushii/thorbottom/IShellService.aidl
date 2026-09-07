package dev.slushii.thorbottom;

/**
 * Runs inside the Shizuku-spawned helper process, which carries the shell user's identity.
 * That identity is the whole point: SettingsProvider only lets system/shell/root write a
 * vendor-defined Settings.System key such as dual_screen_display_mode.
 */
interface IShellService {
    void destroy() = 16777114;
    void exit() = 1;
    boolean putSystemInt(String key, int value) = 2;
    int getSystemInt(String key, int defValue) = 3;
    /** Compositor-level panel power. Returns a short status string for diagnostics. */
    String setDisplayPowerMode(long physicalDisplayId, int powerMode) = 4;
    /** Settings.Secure needs WRITE_SECURE_SETTINGS, which the shell user has and we do not. */
    boolean putSecureInt(String key, int value) = 7;
    /** Genuinely powers the bottom panel down or back up. See ShellService for the gate. */
    String setBottomPanelPower(long physicalDisplayId, boolean on) = 6;
    /** Package of the top resumed activity on a given display, or null. */
    String getTopPackageOnDisplay(int displayId) = 5;
}
