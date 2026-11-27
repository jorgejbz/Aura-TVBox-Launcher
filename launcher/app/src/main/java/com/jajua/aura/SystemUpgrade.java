package com.jajua.aura;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * "Mejoras del sistema": brings a box prepared earlier up to what a newly prepared one has, from
 * Aura's update alone (no PC): the TV settings and keyboard in Aura's style (bind copies), Android's
 * accent colour (overlay), the 1080p menu (resource partition + density), the newest preinstall.sh,
 * and this Aura in /system (so its privileged permissions and settings shortcuts work).
 *
 * The files and their expected hashes come from the GitHub pre-release "sistema-rk28" (sistema.json),
 * only for the firmware it names. Each file is written only when what the box has now is one of the
 * versions it was made for (md5 listed in "accept"); anything else is left alone. Root comes from the
 * box's own adbd (root: + remount:, like "adb root" and "adb remount" from a PC). The partition is
 * backed up first, read back after writing, and restored at once if it does not match.
 * Worker thread only.
 */
final class SystemUpgrade {
    private static final String TAG = "AuraMejoras";
    /** The firmware the "sistema-rk28" files are made for (ro.build.display.id). */
    static final String FIRMWARE = "TVBOX-20260608-RK28";
    private static final String GITHUB = "https://github.com/jorgejbz/Aura-TVBox-Launcher/releases/download/sistema-rk28/";
    /** Tests only: `setprop persist.aura.sistema.url http://<pc>:<port>/` serves the files from a PC (needs root adb). */
    static final String BASE = baseUrl();

    private static String baseUrl() {
        String test = SystemInfo.prop("persist.aura.sistema.url");
        return test.startsWith("http") ? (test.endsWith("/") ? test : test + "/") : GITHUB;
    }
    private static final int TIMEOUT_MS = 20000;
    private static final long MAX_FILE = 64L * 1024 * 1024;
    private static final String PREFS = "system_upgrade";
    /** Set when the upgrade opened ADB: closed again on the next start (the reboot cuts the session). */
    static final String CLOSE_ADB = "close_adb_after_boot";

    interface Progress {
        void step(String text);
    }

    static final class Item {
        final String id, file, sha256, md5, target, orig, partition, stop;
        final boolean gz;
        final List<String> accept = new ArrayList<>();

        Item(JSONObject o) throws JSONException {
            id = o.getString("id");
            file = o.optString("file", null);
            sha256 = o.optString("sha256", null);
            md5 = o.optString("md5", null);
            target = o.optString("target", null);
            orig = o.optString("orig", null);
            partition = o.optString("partition", null);
            stop = o.optString("stop", null);
            gz = o.optBoolean("gz", false);
            JSONArray a = o.optJSONArray("accept");
            if (a != null) for (int i = 0; i < a.length(); i++) accept.add(a.getString(i));
        }
    }

    static final class Manifest {
        final String firmware;
        final String density;
        final List<Item> items = new ArrayList<>();

        Manifest(JSONObject o) throws JSONException {
            firmware = o.getString("firmware");
            density = o.optString("density", null);
            JSONArray a = o.getJSONArray("items");
            for (int i = 0; i < a.length(); i++) items.add(new Item(a.getJSONObject(i)));
        }
    }

    private SystemUpgrade() {}

    // ------------------------------------------------------------ what is missing (no root needed)

    /** The manifest for this box, or null if there is none or it is for another firmware. */
    static Manifest manifest() {
        try {
            byte[] body = fetch(BASE + "sistema.json", null, null);
            Manifest m = new Manifest(new JSONObject(new String(body, "UTF-8")));
            return m.firmware.equals(Build.DISPLAY) ? m : null;
        } catch (IOException | JSONException e) {
            Log.i(TAG, "no manifest: " + e);
            return null;
        }
    }

    /** Short names of what this box still lacks; empty when it is up to date. */
    static List<String> missing(Context c, Manifest m) {
        List<String> out = new ArrayList<>();
        for (Item it : m.items) {
            if (it.partition != null) {
                if (!is1080(c)) out.add(it.id);
                continue;
            }
            if (it.target == null) continue;
            File t = new File(it.target);
            String now = t.exists() ? md5(t) : (it.orig != null && new File(it.orig).exists() ? md5(new File(it.orig)) : "absent");
            if (!it.md5.equals(now) && it.accept.contains(now)) out.add(it.id);
        }
        if (!auraInSystemCurrent(c)) out.add("aura");
        return out;
    }

    /** The UI draws at 1920x1080 or more (the framebuffer, not the HDMI mode). */
    static boolean is1080(Context c) {
        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        return Math.max(dm.widthPixels, dm.heightPixels) >= 1920;
    }

    /**
     * The oldest Aura that may stay in /system: the first one declaring the privileged permissions and
     * settings shortcuts this Aura relies on (1.4.2). Raise it only when a version adds new ones, or every
     * Aura update would restart the box just to copy itself.
     */
    static final int SYSTEM_AURA_MIN = 16;

    @SuppressWarnings("deprecation")
    static boolean auraInSystemCurrent(Context c) {
        File sys = new File("/system/priv-app/Aura/Aura.apk");
        if (!sys.exists()) return true;
        android.content.pm.PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(sys.getPath(), 0);
        return pi != null && pi.versionCode >= SYSTEM_AURA_MIN;
    }

    // ------------------------------------------------------------ applying it (root)

    /**
     * Downloads, checks and writes what is missing, then reboots the box. Returns a message for the
     * person when something could not be done (the box is left as it was for that part), or null
     * after asking for the reboot.
     */
    static String apply(Context c, Manifest m, Progress p) {
        File dir = new File(c.getCacheDir(), "sistema");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        List<String> todo = missing(c, m);
        // The screen size can mislead (a warm reboot keeps the last mode): the partition itself is
        // always checked as root, and written only if it is the stock image.
        for (Item it : m.items) if (it.partition != null && !todo.contains(it.id)) todo.add(it.id);
        List<String> problems = new ArrayList<>();

        // 1. Every file first, fully checked, before touching anything.
        for (Item it : m.items) {
            if (it.file == null || !todo.contains(it.id)) continue;
            p.step(c.getString(R.string.up_downloading, label(c, it.id)));
            File out = new File(dir, it.id + ".bin");
            try {
                download(it, out);
            } catch (IOException e) {
                Log.w(TAG, "download " + it.id, e);
                return c.getString(R.string.up_err_download);
            }
        }

        // 2. Root through this box's own adbd.
        p.step(c.getString(R.string.up_root));
        boolean[] openedBox = {false};
        int port;
        try {
            port = StaleSessions.adbPort(c, openedBox);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            port = 0;
        }
        boolean opened = openedBox[0];
        // The reboot cuts the session before ADB can be closed: the next start closes it (afterBoot).
        if (opened) c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(CLOSE_ADB, true).commit();
        if (port <= 0 || !becomeRoot(port)) {
            if (opened) closeAdbNow(c);
            return c.getString(R.string.up_err_root);
        }
        if (systemWritable(port) == null) {
            if (opened) closeAdbNow(c);
            return c.getString(R.string.up_err_root);
        }

        // 3. Files, one by one, each only if the box still has a version it was made for.
        // Density 240 only goes with the 1080p partition (written now or already there).
        boolean hasPartition = false, screen1080 = false;
        for (Item it : m.items) hasPartition |= it.partition != null;
        if (!hasPartition) screen1080 = is1080(c);
        for (Item it : m.items) {
            if (!todo.contains(it.id)) continue;
            p.step(c.getString(R.string.up_installing, label(c, it.id)));
            File src = new File(dir, it.id + ".bin");
            String err = it.partition != null ? writePartition(port, it, src) : writeFile(port, it, src);
            if (err != null) {
                Log.w(TAG, it.id + ": " + err);
                problems.add(label(c, it.id));
            } else if (it.partition != null) {
                screen1080 = true;
            }
        }
        // The 1080p menu needs the matching density, or everything would look 1.5 times smaller.
        if (screen1080 && m.density != null) {
            sh(port, "grep -q '^ro.sf.lcd_density=160$' /system/build.prop && "
                    + "sed -i 's/^ro.sf.lcd_density=160$/ro.sf.lcd_density=" + m.density + "/' /system/build.prop; true");
        }

        // 4. This Aura in /system, as aura-a-sistema.ps1 does.
        if (todo.contains("aura")) {
            p.step(c.getString(R.string.up_installing, label(c, "aura")));
            String own = c.getApplicationInfo().sourceDir;
            String want = md5(new File(own));
            String out = sh(port, "cp '" + own + "' /system/priv-app/Aura/Aura.apk.new && chown root:root /system/priv-app/Aura/Aura.apk.new"
                    + " && chmod 644 /system/priv-app/Aura/Aura.apk.new && chcon u:object_r:system_file:s0 /system/priv-app/Aura/Aura.apk.new"
                    + " && mv /system/priv-app/Aura/Aura.apk.new /system/priv-app/Aura/Aura.apk; sync; md5sum /system/priv-app/Aura/Aura.apk");
            if (out == null || !out.startsWith(want)) problems.add(label(c, "aura"));
        }

        deleteAll(dir);
        if (!problems.isEmpty()) {
            sh(port, "sync");
            if (opened) closeAdbNow(c);
            return c.getString(R.string.up_err_partial, android.text.TextUtils.join(", ", problems));
        }
        p.step(c.getString(R.string.up_rebooting));
        sleep(5000); // Long enough to read that it is about to restart.
        sh(port, "sync; reboot");
        return null;
    }

    private static String writeFile(int port, Item it, File src) {
        String base = "if [ -e '" + it.target + "' ]; then echo '" + it.target + "'; "
                + (it.orig != null ? "elif [ -e '" + it.orig + "' ]; then echo '" + it.orig + "'; " : "")
                + "fi";
        String basePath = trim(sh(port, base));
        String now = basePath.isEmpty() ? "absent" : first(sh(port, "md5sum '" + basePath + "'"));
        if (it.md5.equals(now)) return null;
        if (!it.accept.contains(now)) return "unexpected version " + now;
        if (it.stop != null) sh(port, "am force-stop " + it.stop);
        String dir = it.target.substring(0, it.target.lastIndexOf('/'));
        // An existing file may be the source of an active bind mount: overwrite its contents in place.
        String cmd = "mkdir -p '" + dir + "' && chmod 755 '" + dir + "' && chcon u:object_r:system_file:s0 '" + dir + "'; "
                + "if [ -e '" + it.target + "' ]; then cat '" + src + "' > '" + it.target + "'; "
                + "else cp '" + src + "' '" + it.target + ".new' && chmod 644 '" + it.target + ".new' && "
                + "chcon u:object_r:system_file:s0 '" + it.target + ".new' && mv '" + it.target + ".new' '" + it.target + "'; fi; "
                + "sync; md5sum '" + it.target + "'";
        String out = sh(port, cmd);
        return out != null && out.startsWith(it.md5) ? null : "written file does not match";
    }

    /** Backup, write, read back; on any mismatch the backup goes back before returning. */
    private static String writePartition(int port, Item it, File src) {
        String dev = trim(sh(port, "for f in /sys/block/mmcblk0/mmcblk0p*/uevent; do grep -q '^PARTNAME=" + it.partition
                + "$' $f && echo /dev/block/$(basename $(dirname $f)); done"));
        if (!dev.startsWith("/dev/block/mmcblk0p")) return "no partition " + it.partition;
        String now = first(sh(port, "md5sum " + dev));
        if (it.md5.equals(now)) return null;
        if (!it.accept.contains(now)) return "unexpected partition " + now;
        String backup = "/data/local/tmp/aura-" + it.partition + "-antes.img";
        String saved = first(sh(port, "dd if=" + dev + " of=" + backup + " bs=1048576 2>/dev/null; sync; md5sum " + backup));
        if (!now.equals(saved)) return "backup failed";
        String written = first(sh(port, "dd if='" + src + "' of=" + dev + " bs=1048576 2>/dev/null; sync; "
                + "echo 3 > /proc/sys/vm/drop_caches; md5sum " + dev));
        if (it.md5.equals(written)) return null;
        String back = first(sh(port, "dd if=" + backup + " of=" + dev + " bs=1048576 2>/dev/null; sync; "
                + "echo 3 > /proc/sys/vm/drop_caches; md5sum " + dev));
        return now.equals(back) ? "write did not verify, restored" : "WRITE AND RESTORE FAILED (" + back + ")";
    }

    // ------------------------------------------------------------ /system in writing, and Aura's copy there

    /**
     * Mounts /system read-write and returns its mount point ("/system", or "/" on system-as-root boxes),
     * or null. Rockchip only accepts adbd's "remount:"; the HiSilicon (Android 9, /dev/root) refuses it and
     * needs its block device set rw and a manual remount, like aura-a-sistema.ps1.
     */
    static String systemWritable(int port) {
        String mnt = trim(sh(port, "grep -q ' /system ' /proc/mounts && echo /system || echo /"));
        if (mnt.isEmpty()) return null;
        String rwCheck = "grep ' " + mnt + " ' /proc/mounts | grep -q '[ ,]rw[ ,]' && echo rw";
        if ("rw".equals(trim(sh(port, rwCheck)))) return mnt;
        svc(port, "remount:");
        if ("rw".equals(trim(sh(port, rwCheck)))) return mnt;
        sh(port, "D=$(grep ' " + mnt + " ' /proc/mounts | cut -d' ' -f1); "
                + "if [ \"$D\" = /dev/root ] || [ ! -e \"$D\" ]; then D=$(readlink -f /dev/block/by-name/system 2>/dev/null"
                + " || ls /dev/block/platform/*/by-name/system 2>/dev/null); fi; "
                + "blockdev --setrw $D; mount -o remount,rw " + mnt);
        if ("rw".equals(trim(sh(port, rwCheck)))) return mnt;
        Log.w(TAG, "could not mount " + mnt + " read-write");
        return null;
    }

    /**
     * Keeps the Aura in /system the same as the one running (an update lives in /data): that copy is what a
     * factory reset brings back, so after an update every box, Rockchip or HiSilicon, keeps the newest Aura
     * even after "Formatear". No restart: Android picks the copy up on the next normal start. Only on boxes
     * that already have Aura in /system. Worker thread; true when the copy matches.
     */
    static boolean syncAuraCopy(Context c) {
        File sys = new File("/system/priv-app/Aura/Aura.apk");
        String own = c.getApplicationInfo().sourceDir;
        if (!sys.exists() || own == null || own.startsWith("/system/")) return true;
        String want = md5(new File(own));
        if (want.equals(md5(sys))) return true;
        if (SystemUpgradeActivity.running()) return false; // The improvements copy it themselves.
        boolean[] openedBox = {false};
        int port;
        try {
            port = StaleSessions.adbPort(c, openedBox);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        boolean ok = false;
        try {
            if (port <= 0) return false;
            boolean wasRoot = "0".equals(trim(sh(port, "id -u")));
            if (!becomeRoot(port)) return false;
            String mnt = systemWritable(port);
            if (mnt == null) return false;
            String out = sh(port, "cp '" + own + "' /system/priv-app/Aura/Aura.apk.new && chown root:root /system/priv-app/Aura/Aura.apk.new"
                    + " && chmod 644 /system/priv-app/Aura/Aura.apk.new && chcon u:object_r:system_file:s0 /system/priv-app/Aura/Aura.apk.new"
                    + " && mv /system/priv-app/Aura/Aura.apk.new /system/priv-app/Aura/Aura.apk; rm -f /system/priv-app/Aura/Aura.apk.new;"
                    + " sync; md5sum /system/priv-app/Aura/Aura.apk");
            ok = out != null && out.startsWith(want);
            Log.i(TAG, "Aura in /system " + (ok ? "updated" : "NOT updated: " + out));
            // Read-only again (as after a restart); a busy mount just stays rw until the next start.
            sh(port, "mount -o remount,ro " + mnt + " 2>/dev/null; true");
            if (!wasRoot) svc(port, "unroot:"); // Leave adbd as it was.
            return ok;
        } finally {
            if (openedBox[0]) SystemInfo.setAdbNetwork(c, false);
        }
    }

    // ------------------------------------------------------------ adbd

    private static boolean becomeRoot(int port) {
        String id = trim(sh(port, "id -u"));
        if ("0".equals(id)) return true;
        svc(port, "root:"); // adbd restarts as root and drops this connection.
        for (int i = 0; i < 30; i++) {
            sleep(1000);
            int p = SystemInfo.adbNetworkPort();
            if ("0".equals(trim(sh(p > 0 ? p : port, "id -u")))) return true;
        }
        return false;
    }

    private static String sh(int port, String cmd) {
        return svc(port, "shell:" + cmd);
    }

    /** One adbd service; adbd serves one connection at a time, so a busy moment is retried. */
    private static String svc(int port, String service) {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                return StaleSessions.AdbLocal.service(port, service, 120000);
            } catch (IOException e) {
                if (service.equals("root:")) return "";
                sleep(2000);
            }
        }
        return null;
    }

    static void closeAdbNow(Context c) {
        SystemInfo.setAdbNetwork(c, false);
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(CLOSE_ADB).apply();
    }

    /**
     * True once, on the first start of a version that was just installed as an update (from the
     * "Actualizar" screen of this or an older Aura): the update and the system improvements are one step
     * for the person, so the improvements follow without a second question. A fresh install, a PC
     * install long ago or any later start returns false.
     */
    static boolean justUpdated(Context c) {
        SharedPreferences prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            String mine = pi.versionName;
            String ran = prefs.getString("ran_version", null);
            prefs.edit().putString("ran_version", mine).commit();
            if (mine.equals(ran)) return false;
            if (ran != null) return true;
            // Updated from an Aura that did not record its version (1.4.1 or older): a recent update only.
            return pi.lastUpdateTime > pi.firstInstallTime
                    && System.currentTimeMillis() - pi.lastUpdateTime < 15 * 60 * 1000L;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** On start: if the upgrade had to open ADB, close it again now that the box rebooted. */
    static void afterBoot(Context c) {
        SharedPreferences prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(CLOSE_ADB, false)) closeAdbNow(c);
    }

    // ------------------------------------------------------------ files

    static String label(Context c, String id) {
        switch (id) {
            case "tvsettings": return c.getString(R.string.up_i_settings);
            case "latinime": return c.getString(R.string.up_i_keyboard);
            case "tema": return c.getString(R.string.up_i_colour);
            case "resource": return c.getString(R.string.up_i_1080);
            case "preinstall": return c.getString(R.string.up_i_preinstall);
            case "aura": return c.getString(R.string.up_i_aura);
            default: return id;
        }
    }

    private static void download(Item it, File out) throws IOException {
        File tmp = new File(out.getPath() + ".part");
        try (OutputStream os = new FileOutputStream(tmp)) {
            byte[] sha = fetch(BASE + it.file, os, it.sha256);
            if (sha == null) throw new IOException("bad sha256 " + it.file);
        }
        if (it.gz) {
            File plain = new File(out.getPath() + ".plain");
            try (InputStream in = new GZIPInputStream(new BufferedInputStream(new FileInputStream(tmp)));
                 OutputStream os = new FileOutputStream(plain)) {
                byte[] b = new byte[65536];
                int n;
                long total = 0;
                while ((n = in.read(b)) > 0) {
                    total += n;
                    if (total > MAX_FILE) throw new IOException("too large");
                    os.write(b, 0, n);
                }
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            tmp = plain;
        }
        if (!it.md5.equals(md5(tmp))) throw new IOException("bad md5 " + it.file);
        if (!tmp.renameTo(out)) throw new IOException("rename");
        //noinspection ResultOfMethodCallIgnored
        out.setReadable(true, false);
    }

    /** Body (small, sink null) or streams into sink and returns the SHA-256 if it matches `sha`, else null. */
    private static byte[] fetch(String url, OutputStream sink, String sha) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("User-Agent", "Aura-Launcher");
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code + " " + url);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            OutputStream out = sink != null ? sink : new ByteArrayOutputStream();
            long total = 0, limit = sink == null ? 256 * 1024 : MAX_FILE;
            try (InputStream in = new BufferedInputStream(c.getInputStream())) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) {
                    total += n;
                    if (total > limit) throw new IOException("too large");
                    out.write(b, 0, n);
                    md.update(b, 0, n);
                }
            }
            if (sink == null) return ((ByteArrayOutputStream) out).toByteArray();
            return hex(md.digest()).equals(sha) ? new byte[] {1} : null;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        } finally {
            c.disconnect();
        }
    }

    static String md5(File f) {
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
            return hex(md.digest());
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            return "unreadable";
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte x : bytes) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String first(String s) {
        String t = trim(s);
        int sp = t.indexOf(' ');
        return sp > 0 ? t.substring(0, sp) : t;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteAll(File dir) {
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }
}
