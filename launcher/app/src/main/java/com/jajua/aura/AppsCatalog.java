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
import java.io.FileInputStream;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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

    private static final String PENDING = "pending";
    private static final String AURA_LATEST = "aura_latest";
    private static final String AURA_RELEASE =
            "https://api.github.com/repos/jorgejbz/Aura-TVBox-Launcher/releases/latest";

    /** Download progress for the screen: what is being fetched and how far (0-100, or -1 if unknown). */
    interface Progress {
        void at(String what, int percent);
    }

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
        /** A split app: a .zip/.apks/.xapk holding base.apk and its split APKs. */
        final boolean bundle;

        Offer(long assetId, String key, String url, String pkg, String label, String versionName,
              long versionCode, boolean update, boolean bundle) {
            this.assetId = assetId;
            this.key = key;
            this.url = url;
            this.pkg = pkg;
            this.label = label;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.update = update;
            this.bundle = bundle;
        }

        File file(Context c) {
            return new File(dir(c), fileName(assetId, bundle));
        }
    }

    private AppsCatalog() {}

    static String fileName(long assetId, boolean bundle) {
        return "app-" + assetId + (bundle ? ".zip" : ".apk");
    }

    /** Split apps are uploaded as one archive with all their APKs inside. */
    static boolean isBundle(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".apks") || n.endsWith(".xapk") || n.endsWith(".zip");
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
        return check(c, null);
    }

    static List<Offer> check(Context c, Progress progress) throws IOException, JSONException {
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
            boolean bundle = isBundle(name);
            if (!(bundle || name.toLowerCase(Locale.ROOT).endsWith(".apk")) || a.optLong("size") > MAX_APK_BYTES) {
                continue;
            }
            long id = a.getLong("id");
            String key = id + ":" + a.optString("updated_at", "");
            String url = a.getString("browser_download_url");
            metaKeys.add(META + key);
            File f = new File(dir, fileName(id, bundle));

            String meta = prefs.getString(META + key, null);
            if (meta == null) {
                download(url, f, progress, name);
                meta = describe(c, f, bundle);
                if ("-".equals(meta)) f.delete();
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
            offers.add(new Offer(id, key, url, pkg, m[3], m[2], code, update, bundle));
            keepFiles.add(f.getName());
        }

        // Forget files and notes about APKs that are gone from the folder or not worth offering.
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) if (!keepFiles.contains(f.getName())) deleteTree(f);
        SharedPreferences.Editor e = prefs.edit();
        for (String k : prefs.getAll().keySet()) if (k.startsWith(META) && !metaKeys.contains(k)) e.remove(k);
        e.apply();

        Collections.sort(offers, (x, y) -> x.label.compareToIgnoreCase(y.label));
        last = Collections.unmodifiableList(offers);
        Set<String> pending = new HashSet<>();
        for (Offer o : offers) pending.add(o.pkg + ":" + o.versionCode);
        prefs.edit().putStringSet(PENDING, pending).apply();
        return last;
    }

    /**
     * The APKs ready to install (base first): downloads again if the cache was cleared, unpacks a split
     * app keeping only the parts this box can use, and re-checks the app. {@link #discard} cleans up.
     */
    static List<File> fetch(Context c, Offer o) throws IOException {
        return fetch(c, o, null);
    }

    static List<File> fetch(Context c, Offer o, Progress progress) throws IOException {
        File f = o.file(c);
        if (!f.isFile()) {
            File dir = dir(c);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no cache dir");
            download(o.url, f, progress, o.label);
        }
        List<File> apks = o.bundle ? choose(unpack(f, work(f))) : Collections.singletonList(f);
        String[] m = apks == null ? new String[0] : describeApk(c.getPackageManager(), apks.get(0)).split("\t", -1);
        if (m.length < 5 || !m[0].equals(o.pkg) || Long.parseLong(m[1]) != o.versionCode) {
            discard(c, o);
            throw new IOException("the file changed on GitHub");
        }
        return apks;
    }

    /** Deletes what {@link #fetch} left in the cache for this offer. */
    static void discard(Context c, Offer o) {
        File f = o.file(c);
        deleteTree(work(f));
        f.delete();
    }

    private static String describe(Context c, File f, boolean bundle) throws IOException {
        if (!bundle) return describeApk(c.getPackageManager(), f);
        File work = work(f);
        try {
            List<File> apks = choose(unpack(f, work));
            // No base APK, or only processor parts for other boxes (e.g. arm64 on a 32-bit box).
            return apks == null ? "-" : describeApk(c.getPackageManager(), apks.get(0));
        } finally {
            deleteTree(work);
        }
    }

    private static File work(File archive) {
        return new File(archive.getPath() + ".d");
    }

    /** Every .apk inside the archive, by file name only (paths inside the archive are ignored). */
    private static List<File> unpack(File archive, File work) throws IOException {
        deleteTree(work);
        if (!work.mkdirs()) throw new IOException("no work dir");
        List<File> out = new ArrayList<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry e;
            byte[] buffer = new byte[65536];
            while ((e = zip.getNextEntry()) != null) {
                String name = new File(e.getName()).getName();
                if (e.isDirectory() || !name.toLowerCase(Locale.ROOT).endsWith(".apk")) continue;
                File to = new File(work, out.size() + "-" + name.replaceAll("[^A-Za-z0-9._-]", "_"));
                try (OutputStream o = new FileOutputStream(to)) {
                    int n;
                    while ((n = zip.read(buffer)) > 0) {
                        total += n;
                        if (total > 2 * MAX_APK_BYTES) throw new IOException("archive too large");
                        o.write(buffer, 0, n);
                    }
                }
                out.add(to);
            }
        }
        return out;
    }

    private static final String[] ABIS = {"arm64_v8a", "armeabi_v7a", "x86_64", "x86", "armeabi", "mips64", "mips"};

    /** The processor a split is for, as Android names it ("armeabi-v7a"), or null for other splits. */
    private static String abiOf(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT).replace('-', '_');
        if (!n.contains("config.") && !n.contains("split")) return null;
        for (String abi : ABIS) {
            if (n.contains("." + abi + ".") || n.endsWith("." + abi + ".apk") || n.contains("_" + abi + ".apk")) {
                return abi.replace('_', '-');
            }
        }
        return null;
    }

    /**
     * Base APK first, then every language/density split and the one processor split that fits this box
     * best. Null when there is no base or no processor split fits.
     */
    @SuppressWarnings("deprecation")
    private static List<File> choose(List<File> apks) {
        File base = null;
        for (File f : apks) if (f.getName().replaceFirst("^\\d+-", "").equalsIgnoreCase("base.apk")) base = f;
        if (base == null) {
            // No "base.apk": the largest APK that is not named as a split.
            for (File f : apks) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (n.contains("split") || n.contains("config.")) continue;
                if (base == null || f.length() > base.length()) base = f;
            }
        }
        if (base == null) return null;
        List<File> out = new ArrayList<>();
        out.add(base);
        Map<String, File> byAbi = new HashMap<>();
        for (File f : apks) {
            if (f == base) continue;
            String abi = abiOf(f.getName());
            if (abi == null) out.add(f);
            else byAbi.put(abi, f);
        }
        if (!byAbi.isEmpty()) {
            String[] supported = android.os.Build.VERSION.SDK_INT >= 21 ? android.os.Build.SUPPORTED_ABIS
                    : new String[] {android.os.Build.CPU_ABI, android.os.Build.CPU_ABI2};
            File pick = null;
            for (String abi : supported) {
                if (abi != null && byAbi.containsKey(abi)) {
                    pick = byAbi.get(abi);
                    break;
                }
            }
            if (pick == null) return null;
            out.add(pick);
        }
        return out;
    }

    private static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        f.delete();
    }

    @SuppressWarnings("deprecation")
    private static String describeApk(PackageManager pm, File f) {
        PackageInfo pi = pm.getPackageArchiveInfo(f.getPath(), PackageManager.GET_SIGNATURES);
        if (pi == null || pi.applicationInfo == null) {
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

    private static void download(String url, File to, Progress progress, String what) throws IOException {
        File part = new File(to.getPath() + ".part");
        try (OutputStream out = new FileOutputStream(part)) {
            read(url, out, progress, what);
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
        return read(url, sink, null, null);
    }

    private static byte[] read(String url, OutputStream sink, Progress progress, String what) throws IOException {
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
            long length = c.getContentLength();
            int shown = -2;
            try (InputStream in = new BufferedInputStream(c.getInputStream())) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("cancelled");
                    total += n;
                    if (total > limit) throw new IOException("too large");
                    out.write(buffer, 0, n);
                    int percent = length > 0 ? (int) Math.min(100, total * 100 / length) : -1;
                    if (progress != null && percent != shown) {
                        shown = percent;
                        progress.at(what, percent);
                    }
                }
            }
            return sink == null ? ((ByteArrayOutputStream) out).toByteArray() : null;
        } finally {
            c.disconnect();
        }
    }

    /** Asks GitHub for the newest published Aura and remembers it for the badge; worker thread. */
    static void checkAura(Context c) throws IOException, JSONException {
        JSONObject release = new JSONObject(new String(read(AURA_RELEASE, null), "UTF-8"));
        String tag = release.getString("tag_name").replaceFirst("^[vV]", "");
        prefs(c).edit().putString(AURA_LATEST, tag).apply();
    }

    /** A newer Aura was published (as of the last check). */
    static boolean auraNewer(Context c) {
        String latest = prefs(c).getString(AURA_LATEST, "");
        try {
            String mine = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return !latest.isEmpty() && UpdateActivity.isNewer(latest, mine);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * What the "Actualizar" shortcut counts: a newer Aura plus the apps from the last check that are still
     * not installed at that version. Reads no network, so it can run on every return home.
     */
    @SuppressWarnings("deprecation")
    static int pendingCount(Context c) {
        PackageManager pm = c.getPackageManager();
        int n = auraNewer(c) ? 1 : 0;
        for (String item : prefs(c).getStringSet(PENDING, Collections.<String>emptySet())) {
            int colon = item.lastIndexOf(':');
            if (colon < 0) continue;
            try {
                long code = Long.parseLong(item.substring(colon + 1));
                if (versionCode(pm.getPackageInfo(item.substring(0, colon), 0)) >= code) continue;
            } catch (PackageManager.NameNotFoundException | NumberFormatException e) {
                // Not installed yet: still pending.
            }
            n++;
        }
        return n;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
