package com.jajua.aura;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The texts of "¿No funciona?" that the owner can change without an Aura update: avisos/no-funciona.json
 * in the GitHub repository (edit it there and every box shows it). Kept on the box, so the last version
 * shows without internet; anything the file leaves out falls back to the text built into Aura.
 *
 * <pre>
 * {
 *   "novedad": {"id": "2026-11-27", "titulo": "…", "texto": "…"},   // optional: a highlighted card on top
 *   "xuper_pasos": ["…", "…"],          // optional: the steps for Xuper Crystal Clear / Magis TV
 *   "xuper_importante": "…",            // optional: their "Importante" box
 *   "amigonly_texto": "…",              // optional: XuperAmigOnlyTV
 *   "amigonly_madrugada": "…",          // optional: its "En la madrugada" box
 *   "porque": ["…", "…"]                // optional: the "¿Por qué pasa?" paragraphs
 * }
 * </pre>
 * A new "novedad" id puts an amber dot on the "¿No funciona?" button until the page is opened.
 */
final class NoticeBoard {
    private static final String TAG = "AuraAvisos";
    static final String URL_DEFAULT =
            "https://raw.githubusercontent.com/jorgejbz/Aura-TVBox-Launcher/main/avisos/no-funciona.json";
    private static final String PREFS = "notice_board";
    private static final String JSON = "json", SEEN = "seen_id";

    private NoticeBoard() {}

    /** Downloads the file (worker thread); keeps the last good copy. True when it changed. */
    static boolean refresh(Context c, int timeoutMs) {
        HttpURLConnection h = null;
        try {
            // Tests only: `setprop persist.aura.avisos.url http://<pc>:<port>/no-funciona.json` (needs root adb).
            String test = SystemInfo.prop("persist.aura.avisos.url").trim();
            String url = test.startsWith("http") ? test : URL_DEFAULT + "?t=" + (System.currentTimeMillis() / 300000);
            h = (HttpURLConnection) new URL(url).openConnection();
            h.setConnectTimeout(timeoutMs);
            h.setReadTimeout(timeoutMs);
            h.setRequestProperty("User-Agent", "Aura-Launcher");
            if (h.getResponseCode() != HttpURLConnection.HTTP_OK) return false;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = h.getInputStream()) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) {
                    out.write(b, 0, n);
                    if (out.size() > 64 * 1024) return false;
                }
            }
            String body = out.toString("UTF-8");
            new JSONObject(body); // Only a valid file replaces the kept one.
            SharedPreferences p = prefs(c);
            if (body.equals(p.getString(JSON, null))) return false;
            p.edit().putString(JSON, body).apply();
            return true;
        } catch (IOException | JSONException | RuntimeException e) {
            Log.i(TAG, "no update: " + e);
            return false;
        } finally {
            if (h != null) h.disconnect();
        }
    }

    /** {@link #refresh} when the last try is older than maxAgeMs (boxes that stay on for days). */
    static boolean refreshIfStale(Context c, long maxAgeMs) {
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis(), last = p.getLong("last_try", 0);
        if (now - last >= 0 && now - last < maxAgeMs) return false;
        p.edit().putLong("last_try", now).apply();
        return refresh(c, 10000);
    }

    /** The kept file, or an empty object. */
    static JSONObject get(Context c) {
        try {
            return new JSONObject(prefs(c).getString(JSON, "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /**
     * The highlighted news, or null. With "horario": "12:00-19:00" it only exists between those hours
     * each day (in «¿No funciona?» and as a floating notice): gone at night, back the next day.
     */
    static JSONObject news(Context c) {
        JSONObject n = get(c).optJSONObject("novedad");
        if (n == null || n.optString("texto", "").trim().isEmpty()) return null;
        return inDailyHours(n.optString("horario", "")) ? n : null;
    }

    /** "HH:mm-HH:mm" in the box's time zone; empty or unreadable means all day. */
    private static boolean inDailyHours(String range) {
        if (range == null || !range.matches("\\s*\\d{1,2}:\\d{2}\\s*-\\s*\\d{1,2}:\\d{2}\\s*")) return true;
        String[] p = range.trim().split("\\s*-\\s*");
        int from = minutes(p[0]), to = minutes(p[1]);
        java.util.Calendar now = java.util.Calendar.getInstance();
        int m = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE);
        return from <= to ? m >= from && m < to : m >= from || m < to;
    }

    private static int minutes(String hhmm) {
        String[] p = hhmm.split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }

    // ------------------------------------------------------------ the night notice

    /**
     * "aviso_nocturno": {"activo": true, "horario": "19:00-08:00", "titulo": "…", "texto": "…"} — every
     * key optional. Without it (or on older Auras) the built-in night notice and hours stay as they are.
     */
    private static JSONObject night(Context c) {
        return get(c).optJSONObject("aviso_nocturno");
    }

    static boolean nightActive(Context c) {
        JSONObject n = night(c);
        return n == null || n.optBoolean("activo", true);
    }

    /** {from, to} hours of the night notice; the built-in ones if the file does not set them. */
    static int[] nightHours(Context c, int from, int to) {
        JSONObject n = night(c);
        String r = n == null ? "" : n.optString("horario", "");
        if (!r.matches("\\s*\\d{1,2}:\\d{2}\\s*-\\s*\\d{1,2}:\\d{2}\\s*")) return new int[] {from, to};
        String[] p = r.trim().split("\\s*-\\s*");
        return new int[] {minutes(p[0]) / 60, minutes(p[1]) / 60};
    }

    static String nightTitle(Context c, String builtIn) {
        JSONObject n = night(c);
        return n == null ? builtIn : text(n, "titulo", builtIn);
    }

    static String nightText(Context c, String builtIn) {
        JSONObject n = night(c);
        return n == null ? builtIn : text(n, "texto", builtIn);
    }

    /** The person left «No volver a mostrar» ticked: this news no longer floats (it stays in «¿No funciona?»). */
    static void hideFloating(Context c) {
        JSONObject n = get(c).optJSONObject("novedad");
        if (n != null) prefs(c).edit().putString("floating_off", n.optString("id", n.optString("texto"))).apply();
    }

    private static boolean floatingHidden(Context c, JSONObject n) {
        return n.optString("id", n.optString("texto")).equals(prefs(c).getString("floating_off", ""));
    }

    /**
     * The news is also a floating notice right now: "flotante": true, and the box's clock between "desde"
     * and "hasta" (both optional, "yyyy-MM-dd HH:mm" in the box's time zone). A box whose clock is not set
     * yet (no RTC: it starts in 1969 until the network gives the time) shows nothing.
     */
    static boolean floatingNow(Context c) {
        JSONObject n = news(c);
        if (n == null || !n.optBoolean("flotante", false) || floatingHidden(c, n)) return false;
        long now = System.currentTimeMillis();
        if (now < 1704067200000L) return false; // Before 2024: clock not set.
        long from = time(n.optString("desde", "")), to = time(n.optString("hasta", ""));
        return (from <= 0 || now >= from) && (to <= 0 || now < to);
    }

    private static long time(String s) {
        if (s == null || s.trim().isEmpty()) return 0;
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT);
            f.setLenient(false);
            return f.parse(s.trim()).getTime();
        } catch (java.text.ParseException e) {
            return 0;
        }
    }

    /** A news item the person has not opened yet (for the dot on the button). */
    static boolean unseenNews(Context c) {
        JSONObject n = news(c);
        return n != null && !n.optString("id", n.optString("texto")).equals(prefs(c).getString(SEEN, ""));
    }

    static void markSeen(Context c) {
        JSONObject n = news(c);
        if (n != null) prefs(c).edit().putString(SEEN, n.optString("id", n.optString("texto"))).apply();
    }

    /** A text from the file, or the built-in one. */
    static String text(JSONObject o, String key, String builtIn) {
        String s = o.optString(key, "").trim();
        return s.isEmpty() ? builtIn : s;
    }

    /** A list from the file, or the built-in one. */
    static String[] list(JSONObject o, String key, String... builtIn) {
        JSONArray a = o.optJSONArray(key);
        if (a == null || a.length() == 0) return builtIn;
        String[] out = new String[a.length()];
        for (int i = 0; i < a.length(); i++) out[i] = a.optString(i, "");
        return out;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
