package dev.slushii.thorbottom;

import android.graphics.drawable.Drawable;

final class AppEntry {
    final String pkg;
    final String label;
    final Drawable icon;
    final boolean likelyEmulator;

    AppEntry(String pkg, String label, Drawable icon, boolean likelyEmulator) {
        this.pkg = pkg;
        this.label = label;
        this.icon = icon;
        this.likelyEmulator = likelyEmulator;
    }
}
