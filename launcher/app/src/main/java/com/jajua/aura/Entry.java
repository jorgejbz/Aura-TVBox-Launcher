package com.jajua.aura;

import android.content.Intent;
import android.graphics.drawable.Drawable;

/** Something the home screen can show and open: an installed app or a system shortcut. */
final class Entry {
    /** Package name for apps, "tile:<name>" for system shortcuts. */
    final String key;
    /** Null for shortcuts that are not a single app. */
    final String packageName;
    final CharSequence label;
    final CharSequence subtitle;
    final Intent intent;
    /** True for apps the user installed (they can be uninstalled normally). */
    final boolean removable;

    Drawable banner;
    Drawable icon;
    int tint = Ui.ACCENT;

    Entry(String key, String packageName, CharSequence label, CharSequence subtitle,
            Intent intent, boolean removable) {
        this.key = key;
        this.packageName = packageName;
        this.label = label;
        this.subtitle = subtitle;
        this.intent = intent;
        this.removable = removable;
    }

    boolean isApp() {
        return packageName != null && !key.startsWith("tile:");
    }
}
