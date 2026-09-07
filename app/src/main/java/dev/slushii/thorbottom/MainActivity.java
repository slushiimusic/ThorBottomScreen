package dev.slushii.thorbottom;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    /** Substrings that usually mean "emulator", used only to pre-tick a suggestion. */
    private static final List<String> EMULATOR_HINTS = Arrays.asList(
            "aethersx2", "nethersx2", "pcsx", "duckstation", "citra", "azahar", "lime3ds",
            "panda3ds", "citron", "yuzu", "sudachi", "eden", "ryujinx", "skyline", "melonds",
            "drastic", "ppsspp", "dolphin", "redream", "flycast", "retroarch", "mupen",
            "vita3k", "winlator", "mame", "epsxe", "daijishou", "emulator", "emu");

    private Prefs prefs;
    private AppListAdapter adapter;
    private View header;

    private TextView statusLine, permWriteState, permA11yState, permOverlayState;
    private View setupBox;
    private Switch swEnabled, swRestore, swOverride, swFocus, swDeep, swRotation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = new Prefs(this);
        adapter = new AppListAdapter(this, prefs);

        ListView list = findViewById(R.id.app_list);
        header = LayoutInflater.from(this).inflate(R.layout.header_main, list, false);
        list.addHeaderView(header, null, false);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                int index = position - 1; // header occupies position 0
                if (index >= 0 && index < adapter.getCount()) {
                    adapter.toggle(adapter.getItem(index));
                }
            }
        });

        statusLine = header.findViewById(R.id.status_line);
        setupBox = header.findViewById(R.id.setup_box);
        permWriteState = header.findViewById(R.id.perm_write_state);
        permA11yState = header.findViewById(R.id.perm_a11y_state);
        permOverlayState = header.findViewById(R.id.perm_overlay_state);

        header.findViewById(R.id.btn_overlay).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivitySafely(i, new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            }
        });

        header.findViewById(R.id.btn_write_settings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!ShizukuManager.isRunning()) {
                    openShizukuApp();
                } else if (!ShizukuManager.hasPermission()) {
                    ShizukuManager.requestPermission();
                } else {
                    ShizukuManager.bind(MainActivity.this);
                    refreshStatus();
                }
            }
        });

        try {
            Shizuku.addRequestPermissionResultListener(
                    new Shizuku.OnRequestPermissionResultListener() {
                        @Override
                        public void onRequestPermissionResult(int requestCode, int grantResult) {
                            ShizukuManager.bind(MainActivity.this);
                            refreshStatus();
                        }
                    });
        } catch (Throwable ignored) { }
        header.findViewById(R.id.btn_accessibility).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivitySafely(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), null);
            }
        });
        header.findViewById(R.id.btn_select_emulators).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { selectLikelyEmulators(); }
        });

        swEnabled = header.findViewById(R.id.sw_enabled);
        swRestore = header.findViewById(R.id.sw_restore);
        swOverride = header.findViewById(R.id.sw_override);
        swFocus = header.findViewById(R.id.sw_focus);
        swDeep = header.findViewById(R.id.sw_deep);
        swRotation = header.findViewById(R.id.sw_rotation);

        swEnabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean c) { prefs.setEnabled(c); }
        });
        swRestore.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean c) { prefs.setRestoreOnExit(c); }
        });
        swOverride.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean c) { prefs.setRespectManualOverride(c); }
        });
        swFocus.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean c) { prefs.setMatchVendorFocusLock(c); }
        });
        swDeep.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean c) { prefs.setDeepOff(c); }
        });
        swRotation.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean c) {
                prefs.setHideRotationSuggestions(c);
                if (!RotationSuggestions.apply(MainActivity.this, prefs)) {
                    Toast.makeText(MainActivity.this,
                            "Connect Shizuku first - that setting needs the shell helper",
                            Toast.LENGTH_SHORT).show();
                }
                refreshStatus();
            }
        });

        ((EditText) header.findViewById(R.id.search)).addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                adapter.applyFilter(s.toString());
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        swEnabled.setChecked(prefs.enabled());
        swRestore.setChecked(prefs.restoreOnExit());
        swOverride.setChecked(prefs.respectManualOverride());
        swFocus.setChecked(prefs.matchVendorFocusLock());
        swDeep.setChecked(prefs.deepOff());
        swRotation.setChecked(prefs.hideRotationSuggestions());
        ShizukuManager.bind(this);
        refreshStatus();
        // Reload rather than just redraw: an emulator installed since this screen was last
        // opened would otherwise never appear, because the process is long-lived.
        loadApps();
    }

    private void refreshStatus() {
        boolean supported = ScreenModeController.isSupported(this);
        boolean shizukuUp = ShizukuManager.isRunning();
        boolean shizukuOk = ShizukuManager.hasPermission();
        boolean canWrite = ShizukuManager.isReady();
        boolean canCover = ScreenCover.canDraw(this);
        boolean a11y = isAccessibilityEnabled();

        if (!shizukuUp) {
            permWriteState.setText("Shizuku: not running. Start it, then come back. "
                    + "Android refuses this screen-mode write to any non-system app, so the "
                    + "shell identity Shizuku provides is the only way in.");
        } else if (!shizukuOk) {
            permWriteState.setText("Shizuku: running, but this app is not authorised yet.");
        } else if (!canWrite) {
            permWriteState.setText("Shizuku: authorised. Connecting helper...");
        } else {
            permWriteState.setText("Shizuku: connected");
        }
        permA11yState.setText(a11y
                ? "Accessibility service: on"
                : "Accessibility service: off - required to notice which app is in front");
        permOverlayState.setText(canCover
                ? "Display over other apps: granted (works without Shizuku)"
                : "Display over other apps: not granted");
        setupBox.setVisibility((canWrite || canCover) && a11y ? View.GONE : View.VISIBLE);

        String modeText;
        switch (ScreenModeController.getMode(this)) {
            case ScreenModeController.MODE_TOP_ONLY: modeText = "top screen only"; break;
            case ScreenModeController.MODE_BOTTOM_ONLY: modeText = "bottom screen only"; break;
            default: modeText = "dual screen"; break;
        }

        if (!supported) {
            statusLine.setText("This firmware does not expose the dual-screen setting. "
                    + "This app only works on AYN dual-screen handhelds.");
        } else if (canWrite && a11y) {
            statusLine.setText("Ready via Shizuku. Currently: " + modeText
                    + ". " + prefs.packages().size() + " app(s) selected.");
        } else if (canCover && a11y) {
            statusLine.setText("Ready without Shizuku, using our own cover. "
                    + prefs.packages().size() + " app(s) selected. Deep off needs Shizuku.");
        } else {
            statusLine.setText("Currently: " + modeText + ". Finish setup below.");
        }
    }

    private boolean isAccessibilityEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (flat == null || flat.isEmpty()) return false;
        String full = getPackageName() + "/" + AppMonitorService.class.getName();
        String shortForm = getPackageName() + "/." + AppMonitorService.class.getSimpleName();
        for (String part : flat.split(":")) {
            if (part.equalsIgnoreCase(full) || part.equalsIgnoreCase(shortForm)) return true;
        }
        return false;
    }

    private void openShizukuApp() {
        Intent i = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
        if (i != null) {
            startActivitySafely(i, null);
        } else {
            Toast.makeText(this, "Shizuku is not installed", Toast.LENGTH_LONG).show();
        }
    }

    private void startActivitySafely(Intent primary, Intent fallback) {
        try {
            startActivity(primary);
        } catch (Throwable t) {
            if (fallback != null) {
                try { startActivity(fallback); return; } catch (Throwable ignored) { }
            }
            Toast.makeText(this, "Could not open that settings screen", Toast.LENGTH_SHORT).show();
        }
    }

    private void selectLikelyEmulators() {
        int n = 0;
        for (AppEntry e : adapter.allApps()) {
            if (e.likelyEmulator && !prefs.isSelected(e.pkg)) {
                prefs.setSelected(e.pkg, true);
                n++;
            }
        }
        adapter.notifyDataSetChanged();
        refreshStatus();
        Toast.makeText(this, n == 0 ? "No new emulators found" : "Selected " + n + " app(s)",
                Toast.LENGTH_SHORT).show();
    }

    /** One-off, on a worker thread. Nothing here runs while the app is closed. */
    private void loadApps() {
        new Thread(new Runnable() {
            @Override public void run() {
                final PackageManager pm = getPackageManager();
                Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> infos = pm.queryIntentActivities(launcher, 0);
                final List<AppEntry> out = new ArrayList<>(infos.size());
                for (ResolveInfo ri : infos) {
                    String pkg = ri.activityInfo.packageName;
                    if (pkg.equals(getPackageName())) continue;
                    String label = String.valueOf(ri.loadLabel(pm));
                    out.add(new AppEntry(pkg, label, ri.loadIcon(pm), looksLikeEmulator(pkg, label)));
                }
                final Collator collator = Collator.getInstance();
                Collections.sort(out, new Comparator<AppEntry>() {
                    @Override public int compare(AppEntry a, AppEntry b) {
                        boolean sa = prefs.isSelected(a.pkg), sb = prefs.isSelected(b.pkg);
                        if (sa != sb) return sa ? -1 : 1;
                        return collator.compare(a.label, b.label);
                    }
                });
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        adapter.setApps(out);
                        refreshStatus();
                    }
                });
            }
        }).start();
    }

    private static boolean looksLikeEmulator(String pkg, String label) {
        String p = pkg.toLowerCase(Locale.US);
        String l = label.toLowerCase(Locale.US);
        for (String hint : EMULATOR_HINTS) {
            if (p.contains(hint) || l.contains(hint)) return true;
        }
        return false;
    }
}
