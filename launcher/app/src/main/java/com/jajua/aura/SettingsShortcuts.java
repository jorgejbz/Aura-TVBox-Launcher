package com.jajua.aura;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.File;

/**
 * Turns Aura's settings shortcuts ({@link SettingsRedirect}, {@link PermissionsPanel}) on only where they
 * help: boxes that have both the phone and the TV settings (Rockchip), with Aura in /system at 1.4.2 or
 * newer, so Android honours their priority. Anywhere else (a HiSilicon with only the TV settings, or a
 * box whose /system Aura is older) they would tie with the TV settings and Android would ask
 * "Abrir con…" for every settings screen, as 1.4.5 did on the HiSilicon box: there they are switched
 * off and the TV settings answer directly, as before.
 */
final class SettingsShortcuts {
    private static final String PHONE = "com.android.settings";

    private SettingsShortcuts() {}

    /** On every start (and after the system improvements): cheap, only writes when something changes. */
    static void sync(Context c) {
        boolean on = useful(c);
        set(c, SettingsRedirect.class, on);
        set(c, PermissionsPanel.class, on);
    }

    static boolean enabled(Context c) {
        int s = c.getPackageManager().getComponentEnabledSetting(new ComponentName(c, SettingsRedirect.class));
        return s != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    /** Aura's own "Información de la app" and the like: through the redirect only while it is on. */
    static Intent route(Context c, Intent settingsIntent) {
        return enabled(c) ? settingsIntent.setClass(c, SettingsRedirect.class) : settingsIntent;
    }

    private static boolean useful(Context c) {
        return installed(c, PHONE) && installed(c, SettingsRedirect.TV) && systemAuraCode(c) >= SystemUpgrade.SYSTEM_AURA_MIN;
    }

    @SuppressWarnings("deprecation")
    private static long systemAuraCode(Context c) {
        File sys = new File("/system/priv-app/Aura/Aura.apk");
        if (!sys.exists()) return 0;
        PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(sys.getPath(), 0);
        return pi == null ? 0 : pi.versionCode;
    }

    private static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static void set(Context c, Class<?> cls, boolean on) {
        PackageManager pm = c.getPackageManager();
        ComponentName cn = new ComponentName(c, cls);
        int want = on ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        if (pm.getComponentEnabledSetting(cn) == want) return;
        pm.setComponentEnabledSetting(cn, want, PackageManager.DONT_KILL_APP);
    }
}
