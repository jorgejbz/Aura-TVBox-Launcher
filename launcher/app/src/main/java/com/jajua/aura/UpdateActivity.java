package com.jajua.aura;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Checks GitHub for a newer Aura, only when the user asks ("Actualizaciones del sistema" in the
 * system settings, or Aura > Sistema). Sends nothing about the device. The APK must have Aura's
 * package name, a higher version and the same signing key, and "Descargar e instalar" is the only confirmation when Aura is a privileged system app.
 */
public final class UpdateActivity extends Activity {
    private static final String LATEST =
            "https://api.github.com/repos/jorgejbz/Aura-TVBox-Launcher/releases/latest";
    private static final String ASSET = "Aura.apk";
    private static final int MAX_APK_BYTES = 20 * 1024 * 1024;
    private static final int TIMEOUT_MS = 15000;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private AlertDialog dialog;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        status(getString(R.string.update_title), getString(R.string.update_checking), false);
        worker.execute(this::check);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        if (dialog != null) dialog.dismiss();
        super.onDestroy();
    }

    private void check() {
        try {
            JSONObject release = new JSONObject(new String(read(LATEST), "UTF-8"));
            String version = release.getString("tag_name").replaceFirst("^[vV]", "");
            String current = installed().versionName;
            if (!isNewer(version, current)) {
                ui(() -> upToDate(current));
                return;
            }
            String url = assetUrl(release.getJSONArray("assets"));
            if (url == null) throw new IOException("release without " + ASSET);
            ui(() -> offer(version, current, url, release.optString("body", "")));
        } catch (IOException | JSONException | PackageManager.NameNotFoundException e) {
            ui(() -> status(getString(R.string.update_title), getString(R.string.update_error), true));
        }
    }

    /** Aura is current: offer to look for new apps in the online folder too. */
    private void upToDate(String current) {
        close();
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(R.string.update_title)
                .setMessage(getString(R.string.update_none, current))
                .setPositiveButton(R.string.info_close, (d, w) -> finish())
                .setOnCancelListener(d -> finish());
        if (AppsCatalog.source() != null) {
            b.setNeutralButton(R.string.apps_check, (d, w) -> {
                startActivity(new Intent(this, AppsActivity.class).putExtra(AppsActivity.EXTRA_MANUAL, true));
                finish();
            });
        }
        dialog = b.show();
    }

    private static String assetUrl(JSONArray assets) throws JSONException {
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (ASSET.equals(a.getString("name"))) return a.getString("browser_download_url");
        }
        return null;
    }

    /** Numeric, part by part: 1.10 is newer than 1.9. An unreadable part counts as 0. */
    static boolean isNewer(String candidate, String current) {
        String[] a = candidate.split("\\."), b = current.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? number(a[i]) : 0, y = i < b.length ? number(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int number(String part) {
        String digits = part.replaceAll("[^0-9].*$", "");
        try {
            return digits.isEmpty() ? 0 : Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void offer(String version, String current, String url, String notes) {
        String text = getString(R.string.update_available, version, current);
        String plain = plainNotes(notes);
        if (!plain.isEmpty()) text += "\n\n" + plain;
        close();
        dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.update_title)
                .setMessage(text)
                .setPositiveButton(R.string.update_install, (d, w) -> {
                    status(getString(R.string.update_title), getString(R.string.update_downloading), false);
                    worker.execute(() -> download(url));
                })
                .setNegativeButton(R.string.update_later, (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
    }

    /** Release notes are GitHub Markdown; the TV dialog shows plain text, without fenced code. */
    private static String plainNotes(String markdown) {
        String s = markdown.replace("\r", "")
                .replaceAll("(?s)```.*?```", "")
                .replaceAll("(?m)^#+\\s*", "")
                .replaceAll("\\*\\*|`", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return s.length() > 700 ? s.substring(0, 700).trim() + "…" : s;
    }

    private void download(String url) {
        File apk = new File(getCacheDir(), "Aura-update.apk");
        try {
            byte[] bytes = read(url);
            try (OutputStream out = new FileOutputStream(apk)) {
                out.write(bytes);
            }
            if (!isValidUpdate(apk)) {
                apk.delete();
                ui(() -> status(getString(R.string.update_title), getString(R.string.update_invalid), true));
                return;
            }
            ui(() -> status(getString(R.string.update_title), getString(R.string.update_installing), false));
            // "Descargar e instalar" is the confirmation: as a system app Aura replaces itself without a
            // second screen (and this process ends). Otherwise Android shows its own confirmation.
            Installer.install(this, apk, getPackageName(), ok -> ui(() -> {
                if (ok) finish();
                else status(getString(R.string.update_title), getString(R.string.update_error), true);
            }));
        } catch (IOException | PackageManager.NameNotFoundException e) {
            apk.delete();
            ui(() -> status(getString(R.string.update_title), getString(R.string.update_error), true));
        }
    }

    /** Fails early with a clear message; Android repeats the signature check when installing. */
    @SuppressWarnings("deprecation")
    private boolean isValidUpdate(File apk) throws PackageManager.NameNotFoundException {
        PackageManager pm = getPackageManager();
        PackageInfo update = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
        if (update == null || !getPackageName().equals(update.packageName)) return false;
        PackageInfo mine = pm.getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES);
        return update.versionCode > mine.versionCode && sameSigner(update.signatures, mine.signatures);
    }

    private static boolean sameSigner(Signature[] a, Signature[] b) {
        return a != null && b != null && Arrays.equals(a, b);
    }


    private static byte[] read(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            // GitHub rejects requests without a User-Agent; this one says nothing about the device.
            c.setRequestProperty("User-Agent", "Aura-Launcher");
            c.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream");
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + c.getResponseCode());
            }
            try (InputStream in = new BufferedInputStream(c.getInputStream())) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    if (out.size() + n > MAX_APK_BYTES) throw new IOException("too large");
                    out.write(buffer, 0, n);
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private PackageInfo installed() throws PackageManager.NameNotFoundException {
        return getPackageManager().getPackageInfo(getPackageName(), 0);
    }

    private void status(String title, String message, boolean closable) {
        close();
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(title).setMessage(message);
        if (closable) b.setPositiveButton(R.string.info_close, (d, w) -> finish());
        b.setOnCancelListener(d -> finish());
        dialog = b.show();
    }

    private void close() {
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }

    private void ui(Runnable r) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) r.run();
        });
    }
}
