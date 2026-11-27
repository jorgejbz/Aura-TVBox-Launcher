package com.jajua.aura;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Collections;

/**
 * Aura updates itself at night (from {@link AutoUpdateJob}, 03:00-05:59, same "Actualizar apps de
 * madrugada" switch), so customers always have the newest version without pressing anything. Only when
 * nothing would restart the box: Aura may install silently (privileged) and this box has no system
 * improvements left to install (those restart it, so they keep waiting for the person). Worker thread.
 */
final class AuraSelfUpdate {
    private static final String TAG = "AuraNocturna";

    private AuraSelfUpdate() {}

    @SuppressWarnings("deprecation")
    static void tryAtNight(Context c) {
        if (!Installer.quiet(c)) return;
        SystemUpgrade.Manifest m = SystemUpgrade.manifest();
        if (m != null && !SystemUpgrade.missing(c, m).isEmpty()) {
            Log.i(TAG, "system improvements pending: the update waits for the person");
            return;
        }
        File apk = new File(c.getCacheDir(), "Aura-nocturna.apk");
        try {
            JSONObject release = new JSONObject(new String(UpdateActivity.read(UpdateActivity.LATEST), "UTF-8"));
            String version = release.getString("tag_name").replaceFirst("^[vV]", "");
            PackageManager pm = c.getPackageManager();
            PackageInfo mine = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES);
            if (!UpdateActivity.isNewer(version, mine.versionName)) return;
            String url = UpdateActivity.assetUrl(release.getJSONArray("assets"));
            if (url == null) return;
            byte[] bytes = UpdateActivity.read(url);
            try (OutputStream out = new FileOutputStream(apk)) {
                out.write(bytes);
            }
            PackageInfo update = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
            if (update == null || !c.getPackageName().equals(update.packageName)
                    || update.versionCode <= mine.versionCode
                    || !UpdateActivity.sameSigner(update.signatures, mine.signatures)) {
                Log.w(TAG, "downloaded file is not a valid newer Aura");
                return;
            }
            Log.i(TAG, "installing Aura " + version);
            // This process ends when Android replaces it; the new Aura starts on the next screen use.
            Installer.installAndWait(c, Collections.singletonList(apk), c.getPackageName(), 15);
        } catch (Exception e) {
            Log.i(TAG, "no night update: " + e);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
        }
    }
}
