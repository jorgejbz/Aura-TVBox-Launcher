package com.jajua.aura;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.File;
import java.util.List;

/**
 * Apps every box should have: if one is missing, Aura installs it by itself from the owner's app
 * folder (the same files as "Apps nuevas"), without asking, like the nightly updates. Only when Aura
 * can install without Android's confirmation screen (a privileged system app), so nothing ever pops up.
 *
 * Once a box has had the app, it is never put back: if the person uninstalls it on purpose, that stays.
 * Without internet or with GitHub down it simply tries again on the next start.
 */
final class EssentialApps {
    private static final String TAG = "AuraEsenciales";
    /** XuperAmigOnlyTV since 1.4.5. */
    static final String[] PACKAGES = {Favorites.AMIGONLY};
    private static final String PREFS = "essential_apps";

    private EssentialApps() {}

    /** Worker thread. */
    static void ensure(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (String pkg : PACKAGES) {
            if (installed(c, pkg)) {
                p.edit().putBoolean(pkg, true).apply(); // The box has (had) it: never forced again.
                continue;
            }
            if (p.getBoolean(pkg, false)) continue; // Uninstalled on purpose after having it.
            if (AppsCatalog.source() == null || !Installer.quiet(c)) return;
            List<AppsCatalog.Offer> offers;
            try {
                offers = AppsCatalog.check(c);
            } catch (Exception e) {
                Log.i(TAG, "no catalog: " + e);
                return; // No network or GitHub down: next start.
            }
            for (AppsCatalog.Offer o : offers) {
                if (!pkg.equals(o.pkg) || o.update) continue;
                try {
                    List<File> apks = AppsCatalog.fetch(c, o);
                    Installer.installAndWait(c, apks, o.pkg, 15);
                    Log.i(TAG, pkg + " installed: " + installed(c, pkg));
                } catch (Exception e) {
                    Log.w(TAG, "install " + pkg, e);
                } finally {
                    AppsCatalog.discard(c, o);
                }
            }
            if (installed(c, pkg)) p.edit().putBoolean(pkg, true).apply();
        }
    }

    private static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
