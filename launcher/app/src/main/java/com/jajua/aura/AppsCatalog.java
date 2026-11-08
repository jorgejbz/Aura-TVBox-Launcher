package com.jajua.aura;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The owner's app folder online: the APKs attached to a GitHub release (by default the "apps" release
 * of Aura's repository; /system/etc/aura/defaults.properties can set apps.release=owner/repo/tag, or
 * "off"). Offers the apps this box lacks and newer versions of the ones it has.
 *
 * GitHub does not say which app an APK holds, so each new or changed file is downloaded once and read
 * with the package manager; the result is remembered by asset id and date. Sends nothing about the box.
 */
final class AppsCatalog {
    static final String DEFAULT_SOURCE = "jorgejbz/Aura-TVBox-Launcher/apps";
    private static final long CHECK_EVERY_MS = 24L * 60 * 60 * 1000;
    private static final long MAX_APK_BYTES = 300L * 1024 * 1024;
    private static final int TIMEOUT_MS = 20000;
    private static final String PREFS = "apps_catalog";
    private static final String LAST_CHECK = "last_check";
    private static final String SEEN = "seen";
    private static final String META = "meta.";

    /** Offers from the latest check, so the dialog opened right after it does not ask GitHub again. */
    static volatile List<Offer> last;

    static final class Offer {
        final long assetId;
        final String key;
        final String url;
        final String pkg;
        final String label;
        final String versionName;
        final long versionCode;
        final boolean update;

        Offer(long assetId, String key, String url, String pkg, String label, String versionName,
              long versionCode, boolean update) {
            this.assetId = assetId;
            this.key = key;
            this.url = url;
            this.pkg = pkg;
            this.label = label;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.update = update;
        }

        File file(Context c) {
            return new File(dir(c), fileName(assetId));
        }
    }

    private AppsCatalog() {}

    static String fileName(long assetId) {
        return "app-" + assetId + ".apk";
    }

    static File dir(Context c) {
        return new File(c.getCacheDir(), "apps");
    }

    /** "owner/repo/tag", or null when the box has the check turned off. */
    static String source() {
        String s = Defaults.appsSource();
        if (s == null || s.isEmpty()) return DEFAULT_SOURCE;
        return "off".equalsIgnoreCase(s) ? null : s;
    }

    static boolean due(Context c) {
        if (source() == null) return false;
        long lastCheck = prefs(c).getLong(LAST_CHECK, 0);
        long now = System.currentTimeMillis();
        return now - lastCheck > CHECK_EVERY_MS || now < lastCheck;
    }

    /** True when an offer has not been shown yet, so the automatic notice does not repeat itself. */
    static boolean hasUnseen(Context c, List<Offer> offers) {
        Set<String> seen = prefs(c).getStringSet(SEEN, Collections.<String>emptySet());
        for (Offer o : offers) if (!seen.contains(o.key)) return true;
        return false;
    }

    static void markSeen(Context c, List<Offer> offers) {
        Set<String> seen = new HashSet<>();
        for (Offer o : offers) seen.add(o.key);
        prefs(c).edit().putStringSet(SEEN, seen).apply();
    }

    /** Asks GitHub and returns what this box can install; runs on a worker thread. */
    static List<Offer> check(Context c) throws IOException, JSONException {
        SharedPreferences prefs = prefs(c);
        prefs.edit().putLong(LAST_CHECK, System.currentTimeMillis()).apply();
        String src = source();
        String[] part = src == null ? new String[0] : src.split("/");
        if (part.length != 3) {
            last = Collections.emptyList();
            return last;
        }
        JSONObject release;
        try {
            release = new JSONObject(new String(read(
                    "https://api.github.com/repos/" + part[0] + "/" + part[1] + "/releases/tags/" + part[2],
                    null), "UTF-8"));
        } catch (FileNotFoundException e) {
            // No folder yet (the release does not exist): nothing to offer, not a connection problem.
            last = Collections.emptyList();
            return last;
        }
        JSONArray assets = release.getJSONArray("assets");
        PackageManager pm = c.getPackageManager();
        File dir = dir(c);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no cache dir");

        List<Offer> offers = new ArrayList<>();
        Set<String> metaKeys = new HashSet<>();
        Set<String> keepFiles = new HashSet<>();
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            String name = a.optString("name", "");
            if (!name.toLowerCase(Locale.ROOT).endsWith(".apk") || a.optLong("size") > MAX_APK_BYTES) continue;
            long id = a.getLong("id");
            String key = id + ":" + a.optString("updated_at", "");
            String url = a.getString("browser_download_url");
            metaKeys.add(META + key);
            File f = new File(dir, fileName(id));

            String meta = prefs.getString(META + key, null);
            if (meta == null) {
                download(url, f);
                meta = describe(pm, f);
                prefs.edit().putString(META + key, meta).apply();
            }
            // pkg \t versionCode \t versionName \t label \t signer; "-" when the file is not an app.
            String[] m = meta.split("\t", -1);
            if (m.length < 5) continue;
            String pkg = m[0];
            long code = Long.parseLong(m[1]);
            boolean update;
            try {
                PackageInfo have = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
                // Android refuses an update signed by someone else: offering it would only fail later.
                if (versionCode(have) >= code || !m[4].equals(signer(have.signatures))) {
                    continue;
                }
                update = true;
            } catch (PackageManager.NameNotFoundException e) {
                update = false;
            }
            if (pkg.equals(c.getPackageName())) continue; // Aura updates itself from its own release.
            offers.add(new Offer(id, key, url, pkg, m[3], m[2], code, update));
            keepFiles.add(f.getName());
        }

        // Forget files and notes about APKs that are gone from the folder or not worth offering.
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) if (!keepFiles.contains(f.getName())) f.delete();
        SharedPreferences.Editor e = prefs.edit();
        for (String k : prefs.getAll().keySet()) if (k.startsWith(META) && !metaKeys.contains(k)) e.remove(k);
        e.apply();

        Collections.sort(offers, (x, y) -> x.label.compareToIgnoreCase(y.label));
        last = Collections.unmodifiableList(offers);
        return last;
    }

    /** The APK ready to install: downloads it again if the cache was cleared, and re-checks it. */
    static File fetch(Context c, Offer o) throws IOException {
        File f = o.file(c);
        if (!f.isFile()) {
            File dir = dir(c);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no cache dir");
            download(o.url, f);
        }
        String[] m = describe(c.getPackageManager(), f).split("\t", -1);
        if (m.length < 5 || !m[0].equals(o.pkg) || Long.parseLong(m[1]) != o.versionCode) {
            f.delete();
            throw new IOException("the file changed on GitHub");
        }
        return f;
    }

    @SuppressWarnings("deprecation")
    private static String describe(PackageManager pm, File f) {
        PackageInfo pi = pm.getPackageArchiveInfo(f.getPath(), PackageManager.GET_SIGNATURES);
        if (pi == null || pi.applicationInfo == null) {
            f.delete();
            return "-";
        }
        // Without these the label of an uninstalled APK cannot be read.
        pi.applicationInfo.sourceDir = f.getPath();
        pi.applicationInfo.publicSourceDir = f.getPath();
        String label = clean(String.valueOf(pi.applicationInfo.loadLabel(pm)));
        if (label.isEmpty()) label = pi.packageName;
        return pi.packageName + "\t" + versionCode(pi) + "\t" + clean(pi.versionName) + "\t" + label + "\t"
                + signer(pi.signatures);
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').trim();
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo pi) {
        return android.os.Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
    }

    private static String signer(Signature[] signatures) {
        if (signatures == null || signatures.length == 0) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Signature s : signatures) md.update(s.toByteArray());
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    private static void download(String url, File to) throws IOException {
        File part = new File(to.getPath() + ".part");
        try (OutputStream out = new FileOutputStream(part)) {
            read(url, out);
        } catch (IOException e) {
            part.delete();
            throw e;
        }
        if (!part.renameTo(to)) {
            part.delete();
            throw new IOException("rename failed");
        }
    }

    /** Body into {@code sink}, or returned when {@code sink} is null (small JSON answers only). */
    private static byte[] read(String url, OutputStream sink) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            // GitHub rejects requests without a User-Agent; this one says nothing about the device.
            c.setRequestProperty("User-Agent", "Aura-Launcher");
            c.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream");
            if (c.getResponseCode() == HttpURLConnection.HTTP_NOT_FOUND) throw new FileNotFoundException(url);
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + c.getResponseCode());
            }
            long limit = sink == null ? 4L * 1024 * 1024 : MAX_APK_BYTES;
            OutputStream out = sink != null ? sink : new ByteArrayOutputStream();
            long total = 0;
            try (InputStream in = new BufferedInputStream(c.getInputStream())) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("cancelled");
                    total += n;
                    if (total > limit) throw new IOException("too large");
                    out.write(buffer, 0, n);
                }
            }
            return sink == null ? ((ByteArrayOutputStream) out).toByteArray() : null;
        } finally {
            c.disconnect();
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
