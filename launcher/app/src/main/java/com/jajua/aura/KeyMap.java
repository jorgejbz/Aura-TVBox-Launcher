package com.jajua.aura;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;

import java.util.Map;
import java.util.TreeMap;

/** Remote-control button -> app package assignments chosen by the user. */
final class KeyMap {
    private static final String PREFS = "keymap";

    /** Navigation and system keys are never remapped, so the remote can't be locked out. */
    static boolean isReserved(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_UNKNOWN:
                return true;
            default:
                return false;
        }
    }

    private KeyMap() {}

    private static final String IMPORTED = "_imported";

    /** First run (or after a factory reset): copy the buttons baked into the system image. */
    static void importDefaultsOnce(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getBoolean(IMPORTED, false)) return;
        SharedPreferences.Editor e = p.edit();
        for (Map.Entry<Integer, String> k : Defaults.keys().entrySet()) {
            if (!p.contains(String.valueOf(k.getKey()))) e.putString(String.valueOf(k.getKey()), k.getValue());
        }
        e.putBoolean(IMPORTED, true).apply();
    }

    /**
     * Turns the button service on once, when Aura is installed as a privileged system app and so
     * holds WRITE_SECURE_SETTINGS. If the user later switches it off, it stays off.
     */
    static void enableServiceOnce(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getBoolean("_service_init", false)) return;
        if (c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        if (!serviceEnabled(c)) {
            String me = new ComponentName(c, KeyMapService.class).flattenToString();
            String cur = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    TextUtils.isEmpty(cur) ? me : cur + ":" + me);
            Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        }
        p.edit().putBoolean("_service_init", true).apply();
    }

    static String get(Context c, int code) {
        return prefs(c).getString(String.valueOf(code), null);
    }

    static void set(Context c, int code, String pkg) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (pkg == null) e.remove(String.valueOf(code));
        else e.putString(String.valueOf(code), pkg);
        e.apply();
    }

    static Map<Integer, String> all(Context c) {
        Map<Integer, String> out = new TreeMap<>();
        for (Map.Entry<String, ?> e : prefs(c).getAll().entrySet()) {
            try {
                out.put(Integer.parseInt(e.getKey()), String.valueOf(e.getValue()));
            } catch (NumberFormatException ignored) {
                // Not a key entry.
            }
        }
        return out;
    }

    /** "PROG_RED (183)" — readable enough to tell buttons apart. */
    static String keyName(int code) {
        String s = KeyEvent.keyCodeToString(code);
        if (s.startsWith("KEYCODE_")) s = s.substring(8);
        return s.equals(String.valueOf(code)) ? "Botón " + code : s + " (" + code + ")";
    }

    static boolean serviceEnabled(Context c) {
        String enabled = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        String me = new ComponentName(c, KeyMapService.class).flattenToString();
        for (String s : enabled.split(":")) {
            if (s.equalsIgnoreCase(me)) return true;
        }
        return false;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
