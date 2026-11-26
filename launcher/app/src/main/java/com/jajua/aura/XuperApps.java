package com.jajua.aura;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.StatFs;

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
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * "Reinstalar Xuper's": the recommended Xuper and Xuper Crystal Clear, kept as fixed files in one GitHub
 * release of Aura's repository (tag "xupers", a pre-release so Aura's own update check never sees it):
 *
 *   https://github.com/jorgejbz/Aura-TVBox-Launcher/releases/download/xupers/Xuper.apk
 *   https://github.com/jorgejbz/Aura-TVBox-Launcher/releases/download/xupers/XuperCrystalClear.apk
 *   https://github.com/jorgejbz/Aura-TVBox-Launcher/releases/download/xupers/SHA256SUMS.txt
 *
 * These links never change; GitHub redirects them to a short-lived download address on each request.
 * SHA256SUMS.txt ("hash  name" per line, as sha256sum writes it) is required: a file whose hash is not
 * listed there is never installed. /system/etc/aura/defaults.properties can point elsewhere with
 * xupers.release=owner/repo/tag. Sends nothing about the box.
 */
final class XuperApps {
    static final String DEFAULT_SOURCE = "jorgejbz/Aura-TVBox-Launcher/xupers";
    static final String SUMS = "SHA256SUMS.txt";
    /** For tests on the bench only: `setprop debug.aura.xupers.base http://pc:port/` (needs root/shell). */
    private static final String TEST_BASE_PROP = "debug.aura.xupers.base";
    private static final long MAX_APK_BYTES = 200L * 1024 * 1024;
    private static final int TIMEOUT_MS = 20000;

    /** One app this screen can bring back. The package name is fixed so the two files can never swap. */
    static final class Target {
        final String file;
        final String pkg;
        final int label;
        /**
         * The version the app itself shows when it differs from what Android reads in its manifest, or null.
         * Xuper Crystal Clear says 4.34.4 to Android but 4.99.2 inside the app: the real one is found by
         * looking for that exact string in the APK's code.
         */
        final String realVersion;
        /**
         * The version of the file in the release, as the app shows it: what "Reinstalar Xuper's" says it will
         * install and what the usage guide recommends. Keep it equal to the APK uploaded to the release.
         */
        final String recommended;

        Target(String file, String pkg, int label, String realVersion, String recommended) {
            this.file = file;
            this.pkg = pkg;
            this.label = label;
            this.realVersion = realVersion;
            this.recommended = recommended;
        }
    }

    static final String CRYSTAL_REAL_VERSION = "4.99.2";
    static final String XUPER_VERSION = "4.34.5";

    static final Target[] TARGETS = {
            new Target("XuperCrystalClear.apk", "com.xuper.crystalclear", R.string.xuper_crystal,
                    CRYSTAL_REAL_VERSION, CRYSTAL_REAL_VERSION),
            new Target("Xuper.apk", "com.android.mgstv", R.string.xuper_magis, null, XUPER_VERSION),
    };

    static Target target(String pkg) {
        for (Target t : TARGETS) if (t.pkg.equals(pkg)) return t;
        return null;
    }

    /** Why a downloaded file cannot be used; the message is a string resource for the user. */
    static final class Problem extends Exception {
        final int message;

        Problem(int message, String detail) {
            super(detail);
            this.message = message;
        }
    }

    /** What the downloaded APK is, compared with what the box has. */
    static final class Check {
        final File apk;
        final String versionName;
        final long versionCode;
        /** Null when the app is not installed. */
        final String installedName;
        final long installedCode;
        final boolean sameSigner;

        Check(File apk, String versionName, long versionCode, String installedName, long installedCode,
                boolean sameSigner) {
            this.apk = apk;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.installedName = installedName;
            this.installedCode = installedCode;
            this.sameSigner = sameSigner;
        }

        boolean installed() {
            return installedName != null;
        }

        boolean downgrade() {
            return installed() && installedCode > versionCode;
        }
    }

    interface Progress {
        void at(int percent);
    }

    private XuperApps() {}

    /** Where the files are: "https://github.com/owner/repo/releases/download/tag/". */
    static String base() {
        String test = SystemInfo.prop(TEST_BASE_PROP);
        if (test != null && !test.isEmpty()) return test.endsWith("/") ? test : test + "/";
        String src = Defaults.xupersSource();
        if (src == null || src.isEmpty()) src = DEFAULT_SOURCE;
        String[] p = src.split("/");
        if (p.length != 3) p = DEFAULT_SOURCE.split("/");
        return "https://github.com/" + p[0] + "/" + p[1] + "/releases/download/" + p[2] + "/";
    }

    /**
     * The installed version as the app shows it, or null when not installed. May read the APK (about a
     * second for Crystal Clear), so call it off the main thread; the answer is remembered until the app changes.
     */
    static String installedVersion(Context c, Target t) {
        PackageInfo pi;
        try {
            pi = c.getPackageManager().getPackageInfo(t.pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        String name = pi.versionName == null ? "" : pi.versionName;
        if (t.realVersion == null || pi.applicationInfo == null) return name;
        android.content.SharedPreferences p = c.getSharedPreferences("xuper_versions", Context.MODE_PRIVATE);
        String stamp = pi.lastUpdateTime + "|" + versionCode(pi) + "|";
        String cached = p.getString(t.pkg, "");
        if (cached.startsWith(stamp)) return cached.substring(stamp.length());
        String shown = shownVersion(t, new File(pi.applicationInfo.sourceDir), name);
        p.edit().putString(t.pkg, stamp + shown).apply();
        return shown;
    }

    /** {@code name} (Android's), or the target's real version when the APK's code contains it. */
    static String shownVersion(Target t, File apk, String name) {
        if (t.realVersion == null) return name;
        return dexHasString(apk, t.realVersion) ? t.realVersion : name;
    }

    /**
     * True when one of the APK's classes*.dex has exactly {@code value} as a string constant. In a dex file
     * every string is stored as its length, the characters and a 0 byte, so that whole sequence is searched.
     */
    static boolean dexHasString(File apk, String value) {
        byte[] text;
        try {
            text = value.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return false;
        }
        if (text.length > 127) return false;
        byte[] pattern = new byte[text.length + 2];
        pattern[0] = (byte) text.length;
        System.arraycopy(text, 0, pattern, 1, text.length);
        pattern[pattern.length - 1] = 0;
        try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.getName().matches("classes[0-9]*[.]dex")) continue;
                try (InputStream in = new BufferedInputStream(zip.getInputStream(e), 65536)) {
                    if (contains(in, pattern)) return true;
                }
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    /** Streaming search (the pattern has no repeated prefix worth a smarter algorithm). */
    private static boolean contains(InputStream in, byte[] pattern) throws IOException {
        byte[] buf = new byte[65536 + pattern.length];
        int keep = 0, n;
        while ((n = in.read(buf, keep, buf.length - keep)) > 0) {
            int end = keep + n;
            outer:
            for (int i = 0; i + pattern.length <= end; i++) {
                for (int j = 0; j < pattern.length; j++) if (buf[i + j] != pattern[j]) continue outer;
                return true;
            }
            keep = Math.min(pattern.length - 1, end);
            System.arraycopy(buf, end - keep, buf, 0, keep);
        }
        return false;
    }

    static File dir(Context c) {
        return new File(c.getCacheDir(), "xupers");
    }

    /** Removes every downloaded or half-downloaded file. */
    static void cleanup(Context c) {
        File[] files = dir(c).listFiles();
        if (files != null) for (File f : files) f.delete();
    }

    /**
     * Downloads the target's APK and checks it completely before anything is installed or removed.
     * Worker thread; an interrupt cancels it. Throws {@link Problem} with a message for the user.
     */
    static Check download(Context c, Target t, Progress progress) throws Problem {
        cleanup(c);
        File dir = dir(c);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new Problem(R.string.xr_err_storage, "no dir");
        String base = base();

        // 1. The published hash first: without it the file is not trusted.
        String expected;
        try {
            expected = expectedHash(new String(fetch(base + SUMS, null, null), "UTF-8"), t.file);
        } catch (FileNotFoundException e) {
            throw new Problem(R.string.xr_err_missing, base + SUMS);
        } catch (IOException e) {
            throw network(e);
        }
        if (expected == null) throw new Problem(R.string.xr_err_missing, t.file + " not in " + SUMS);

        // 2. The APK, hashed while it arrives; a partial file never keeps the final name.
        File apk = new File(dir, t.file);
        File part = new File(dir, t.file + ".part");
        String actual;
        try (OutputStream out = new FileOutputStream(part)) {
            actual = hex(fetch(base + t.file, out, progress));
        } catch (FileNotFoundException e) {
            part.delete();
            throw new Problem(R.string.xr_err_missing, base + t.file);
        } catch (IOException e) {
            part.delete();
            throw network(e);
        }
        if (part.length() == 0) {
            part.delete();
            throw new Problem(R.string.xr_err_corrupt, "empty");
        }
        if (!expected.equalsIgnoreCase(actual)) {
            part.delete();
            throw new Problem(R.string.xr_err_corrupt, "sha256 " + actual + " != " + expected);
        }
        if (!part.renameTo(apk)) {
            part.delete();
            throw new Problem(R.string.xr_err_storage, "rename");
        }

        // 3. It is the right app, it runs on this box, and how it relates to the installed one.
        try {
            if (Thread.currentThread().isInterrupted()) throw new Problem(R.string.xr_cancelled, "cancelled");
            return inspect(c, t, apk);
        } catch (Problem p) {
            apk.delete();
            throw p;
        }
    }

    @SuppressWarnings("deprecation")
    private static Check inspect(Context c, Target t, File apk) throws Problem {
        PackageManager pm = c.getPackageManager();
        PackageInfo info = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
        // The hash already proved the file is the published one: if Android cannot read it, it was made for a
        // newer Android than this box has (older package managers reject the newer format outright).
        if (info == null || info.applicationInfo == null) throw new Problem(R.string.xr_err_device, "unreadable apk");
        if (!t.pkg.equals(info.packageName)) {
            throw new Problem(R.string.xr_err_wrong_app, info.packageName + " != " + t.pkg);
        }
        if (Build.VERSION.SDK_INT >= 24 && info.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT) {
            throw new Problem(R.string.xr_err_device, "minSdk " + info.applicationInfo.minSdkVersion);
        }
        if (!abiFits(apk)) throw new Problem(R.string.xr_err_device, "abi");

        String installedName = null;
        long installedCode = 0;
        boolean sameSigner = true;
        try {
            PackageInfo have = pm.getPackageInfo(t.pkg, PackageManager.GET_SIGNATURES);
            installedName = have.versionName == null ? "" : have.versionName;
            installedCode = versionCode(have);
            sameSigner = signer(have.signatures).equals(signer(info.signatures));
        } catch (PackageManager.NameNotFoundException e) {
            // Not installed: a plain install.
        }
        if (installedName != null) installedName = installedVersion(c, t);
        return new Check(apk, shownVersion(t, apk, info.versionName == null ? "" : info.versionName),
                versionCode(info), installedName, installedCode, sameSigner);
    }

    /** True when the APK has no native code, or has it for one of this box's processors. */
    private static boolean abiFits(File apk) {
        Set<String> abis = new HashSet<>();
        try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.startsWith("lib/") && n.endsWith(".so")) {
                    String[] p = n.split("/");
                    if (p.length >= 3) abis.add(p[1]);
                }
            }
        } catch (IOException e) {
            return false;
        }
        if (abis.isEmpty()) return true;
        String[] supported = Build.VERSION.SDK_INT >= 21 ? Build.SUPPORTED_ABIS
                : new String[] {Build.CPU_ABI, Build.CPU_ABI2};
        for (String a : supported) if (a != null && abis.contains(a)) return true;
        return false;
    }

    /** Free space for the download plus Android's own copy while installing. */
    static boolean enoughSpace(Context c) {
        try {
            long free = new StatFs(c.getCacheDir().getPath()).getAvailableBytes();
            return free > 3L * 60 * 1024 * 1024;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** The hash listed for {@code name}, from "hash  name" or "hash *name" lines. */
    static String expectedHash(String sums, String name) {
        for (String line : sums.split("\r?\n")) {
            String[] p = line.trim().split("\\s+", 2);
            if (p.length != 2) continue;
            String file = p[1].startsWith("*") ? p[1].substring(1) : p[1];
            if (file.equals(name) && p[0].matches("(?i)[0-9a-f]{64}")) return p[0].toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static Problem network(IOException e) {
        if ("cancelled".equals(e.getMessage())) return new Problem(R.string.xr_cancelled, "cancelled");
        return new Problem(R.string.xr_err_network, String.valueOf(e));
    }

    /**
     * GETs {@code url} (following GitHub's redirect). With a sink, streams into it and returns the SHA-256;
     * without one, returns the (small) body. Fails if the transfer stops before Content-Length.
     */
    private static byte[] fetch(String url, OutputStream sink, Progress progress) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            // GitHub rejects requests without a User-Agent; this one says nothing about the device.
            c.setRequestProperty("User-Agent", "Aura-Launcher");
            int code = c.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) throw new FileNotFoundException(url);
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
            long limit = sink == null ? 64 * 1024 : MAX_APK_BYTES;
            long length = c.getContentLength();
            if (length > limit) throw new IOException("too large");
            OutputStream out = sink != null ? sink : new ByteArrayOutputStream();
            long total = 0;
            int shown = -2;
            try (InputStream in = new BufferedInputStream(c.getInputStream())) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("cancelled");
                    total += n;
                    if (total > limit) throw new IOException("too large");
                    out.write(buffer, 0, n);
                    md.update(buffer, 0, n);
                    int percent = length > 0 ? (int) Math.min(100, total * 100 / length) : -1;
                    if (progress != null && percent != shown) {
                        shown = percent;
                        progress.at(percent);
                    }
                }
            }
            if (Thread.currentThread().isInterrupted()) throw new IOException("cancelled");
            if (length > 0 && total != length) throw new IOException("incomplete " + total + "/" + length);
            return sink == null ? ((ByteArrayOutputStream) out).toByteArray() : md.digest();
        } finally {
            c.disconnect();
        }
    }

    @SuppressWarnings("deprecation")
    static long versionCode(PackageInfo pi) {
        return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
    }

    private static String signer(Signature[] signatures) {
        if (signatures == null || signatures.length == 0) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Signature s : signatures) md.update(s.toByteArray());
            return hex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }
}
