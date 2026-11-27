package com.jajua.aura;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Small helpers for looking after the box: internet speed, freeing cache space, spotting repeated reboots (heat), and
 * the support links. Network and disk work must run off the main thread.
 */
final class BoxCare {
    private static final String TAG = "AuraCuidado";

    private BoxCare() {}

    // ---------------------------------------------------------------- support

    static final String TELEGRAM_USER = "jorge_jbz";
    static final String MSG_SUPPORT = "Hola, soy cliente de Aura TVBox y necesito soporte con mi TV Box.";
    static final String MSG_ADDONS = "Hola, soy cliente de Aura TVBox y necesito los addons de Stremio.";

    /** t.me link that opens the chat with {@code message} already written. */
    static String telegramLink(String message) {
        try {
            return "https://t.me/" + TELEGRAM_USER + "?text=" + URLEncoder.encode(message, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return "https://t.me/" + TELEGRAM_USER;
        }
    }

    // ---------------------------------------------------------------- speed

    interface SpeedProgress {
        void at(int percent);
    }

    /**
     * Megabits per second, or -1 without internet. Downloads for about 8 s from GitHub's CDN (the recommended Xuper
     * of the "xupers" release, so the test uses the same servers as Aura's downloads), again if a copy ends early.
     */
    static double measureSpeed(SpeedProgress progress) {
        final long windowMs = 8000, warmupMs = 1000;
        long start = SystemClock.elapsedRealtime(), countFrom = start + warmupMs, counted = 0;
        boolean any = false;
        byte[] buffer = new byte[65536];
        while (SystemClock.elapsedRealtime() - start < windowMs) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(XuperApps.base() + "Xuper.apk").openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("User-Agent", "Aura-Launcher");
                if (c.getResponseCode() != HttpURLConnection.HTTP_OK) return any ? result(counted, countFrom) : -1;
                try (InputStream in = c.getInputStream()) {
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        any = true;
                        long now = SystemClock.elapsedRealtime();
                        if (now >= countFrom) counted += n;
                        if (Thread.currentThread().isInterrupted()) return -1;
                        if (progress != null) progress.at((int) Math.min(100, (now - start) * 100 / windowMs));
                        if (now - start >= windowMs) break;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "speed: " + e);
                return any ? result(counted, countFrom) : -1;
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return result(counted, countFrom);
    }

    private static double result(long bytes, long countFrom) {
        long ms = Math.max(1, SystemClock.elapsedRealtime() - countFrom);
        return bytes * 8.0 / 1000.0 / ms; // bits per ms = kbit/s; /1000 -> Mbit/s
    }

    /** True when the internet answers (HTTP 204 from Google's connectivity check, or GitHub as a fallback). */
    static boolean internetReachable() {
        for (String u : new String[] {"https://www.google.com/generate_204", "https://github.com/"}) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(u).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.setRequestMethod("HEAD");
                c.setRequestProperty("User-Agent", "Aura-Launcher");
                int code = c.getResponseCode();
                if (code > 0 && code < 500) return true;
            } catch (Exception ignored) {
                // Try the next one.
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- HDMI resolution

    private static final String HDMI = "/sys/class/display/HDMI/";

    /** The HDMI output mode now ("1920x1080p-60"), or null on boxes without this interface. */
    static String hdmiMode() {
        return readLine(HDMI + "mode");
    }

    /**
     * The best mode the TV and the box agree on: most pixels, then progressive, then refresh rate (the firmware
     * lists only what the TV's EDID and the chip both support). Null when the list cannot be read.
     */
    static String bestHdmiMode() {
        String all = readAll(HDMI + "modes");
        if (all == null) return null;
        String best = null;
        long bestScore = -1;
        for (String m : all.split("\\s+")) {
            java.util.regex.Matcher x = java.util.regex.Pattern.compile("(\\d+)x(\\d+)([pi])-(\\d+)").matcher(m);
            if (!x.matches()) continue; // "auto" and anything unexpected
            long score = Long.parseLong(x.group(1)) * Long.parseLong(x.group(2)) * 1000
                    + ("p".equals(x.group(3)) ? 500 : 0) + Long.parseLong(x.group(4));
            if (score > bestScore) {
                bestScore = score;
                best = m;
            }
        }
        return best;
    }

    /**
     * Puts the HDMI output on the best mode the TV accepts (e.g. 4K on a 4K TV, 1080p on a Full HD one). The firmware
     * keeps a fixed mode from the factory, so a better TV would otherwise stay at 1080p. Root, via the box's own adbd.
     * Worker thread. Returns the mode set, or null when nothing changed.
     */
    static String ensureBestHdmi(Context c) {
        String now = hdmiMode(), best = bestHdmiMode();
        if (now == null || best == null || best.equals(now.trim())) return null;
        Log.i(TAG, "HDMI " + now + " -> " + best);
        StaleSessions.runCommand(c, "su 0 sh -c 'echo " + best + " > " + HDMI + "mode'");
        String after = hdmiMode();
        return after != null && after.trim().equals(best) ? best : null;
    }

    private static String readLine(String path) {
        String s = readAll(path);
        return s == null ? null : s.trim();
    }

    private static String readAll(String path) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(path)) {
            byte[] b = new byte[4096];
            int n = in.read(b);
            return n <= 0 ? null : new String(b, 0, n, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- space

    static long freeBytes() {
        try {
            return new StatFs(android.os.Environment.getDataDirectory().getPath()).getAvailableBytes();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * Clears every app's cache (the apps rebuild it as needed; no data, sessions or settings are touched). Returns the bytes freed. Uses the system call when Aura may, otherwise the box's own
     * adbd as root, like {@link StaleSessions}.
     */
    static long freeSpace(Context c) {
        long before = freeBytes();
        boolean done = false;
        if (c.getPackageManager().checkPermission("android.permission.CLEAR_APP_CACHE", c.getPackageName())
                == PackageManager.PERMISSION_GRANTED) {
            try {
                Class<?> observer = Class.forName("android.content.pm.IPackageDataObserver");
                PackageManager pm = c.getPackageManager();
                pm.getClass().getMethod("freeStorageAndNotify", String.class, long.class, observer)
                        .invoke(pm, null, Long.MAX_VALUE, null);
                done = true;
                Thread.sleep(4000); // The call returns at once; give it time to finish.
            } catch (Exception e) {
                Log.w(TAG, "freeStorage: " + e);
            }
        }
        if (!done) {
            StaleSessions.runCommand(c, "su 0 pm trim-caches 1000G");
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        // Aura's own downloads cache.
        deleteTree(new java.io.File(c.getCacheDir(), "xupers"));
        return Math.max(0, freeBytes() - before);
    }

    private static void deleteTree(java.io.File f) {
        java.io.File[] children = f.listFiles();
        if (children != null) for (java.io.File ch : children) deleteTree(ch);
        f.delete();
    }

    // ---------------------------------------------------------------- reboots (heat)

    private static final String PREFS = "box_care";
    private static final String BOOTS = "boots";
    private static final String BOOT_SEEN = "boot_seen";
    private static final String WARNED_AT = "heat_warned_at";
    /** Three switch-ons within 30 minutes look like heat restarts, not someone turning the box off and on. */
    private static final int REBOOTS = 3;
    private static final long WINDOW_MS = 30 * 60 * 1000L;

    /**
     * Notes this boot's time once the clock is right (these boxes start in 1969 until the network sets it) and
     * says whether the box has been restarting by itself. Blocks for up to ~10 minutes; worker thread.
     */
    static boolean recordBootAndCheckHeat(Context c) {
        int boot = bootCount(c);
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (boot >= 0 && p.getInt(BOOT_SEEN, -1) == boot) return false;
        for (int i = 0; i < 40 && Calendar.getInstance().get(Calendar.YEAR) < 2025; i++) {
            try {
                Thread.sleep(15000);
            } catch (InterruptedException e) {
                return false;
            }
        }
        if (Calendar.getInstance().get(Calendar.YEAR) < 2025) return false;
        long bootAt = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        List<Long> boots = new ArrayList<>();
        for (String s : p.getString(BOOTS, "").split(",")) {
            try {
                long t = Long.parseLong(s.trim());
                if (bootAt - t < 24 * 3600 * 1000L && t < bootAt - 30_000) boots.add(t);
            } catch (NumberFormatException ignored) {
                // Empty or damaged entry.
            }
        }
        boots.add(bootAt);
        while (boots.size() > 10) boots.remove(0);
        StringBuilder sb = new StringBuilder();
        for (long t : boots) sb.append(sb.length() > 0 ? "," : "").append(t);
        SharedPreferences.Editor e = p.edit().putString(BOOTS, sb.toString());
        if (boot >= 0) e.putInt(BOOT_SEEN, boot);
        e.apply();

        int recent = 0;
        for (long t : boots) if (bootAt - t <= WINDOW_MS) recent++;
        long warned = p.getLong(WARNED_AT, 0);
        if (recent >= REBOOTS && bootAt - warned > 2 * 3600 * 1000L) {
            p.edit().putLong(WARNED_AT, bootAt).apply();
            Log.i(TAG, recent + " starts in 30 min: heat warning");
            return true;
        }
        return false;
    }

    /**
     * Once per boot: true when the box was switched on inside [from, to) hours (e.g. 22-8, across midnight). Waits for
     * a real clock first (these boxes start in 1969 until the network sets the time). Worker thread.
     */
    static boolean waitForClockAndCheckDownHours(Context c, int from, int to) {
        int boot = bootCount(c);
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (boot >= 0 && p.getInt("night_seen", -1) == boot) return false;
        for (int i = 0; i < 40 && Calendar.getInstance().get(Calendar.YEAR) < 2025; i++) {
            try {
                Thread.sleep(15000);
            } catch (InterruptedException e) {
                return false;
            }
        }
        if (Calendar.getInstance().get(Calendar.YEAR) < 2025) return false;
        if (boot >= 0) p.edit().putInt("night_seen", boot).apply();
        return !nightHidden(c) && inHours(from, to);
    }

    /** Inside [from, to) hours right now, with a real clock (e.g. 19-8 across midnight). */
    static boolean inHours(int from, int to) {
        Calendar now = Calendar.getInstance();
        if (now.get(Calendar.YEAR) < 2025) return false;
        int h = now.get(Calendar.HOUR_OF_DAY);
        return from > to ? (h >= from || h < to) : (h >= from && h < to);
    }

    /** "Enterado, no volver a mostrar" on the night notice. */
    static boolean nightHidden(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("night_hidden", false);
    }

    static void setNightHidden(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("night_hidden", true).apply();
    }

    static int bootCount(Context c) {
        if (Build.VERSION.SDK_INT < 24) return -1;
        try {
            return Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT);
        } catch (Settings.SettingNotFoundException e) {
            return -1;
        }
    }
}
