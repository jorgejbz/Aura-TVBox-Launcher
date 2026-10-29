package com.jajua.aura;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Ordered list of favourite packages. Until the user edits it, known media apps are suggested. */
final class Favorites {
    private static final String PREFS = "aura";
    private static final String KEY = "favorites";

    private static final List<String> DEFAULTS = Arrays.asList(
            "com.stremio.one",
            "com.android.mgstv",
            "com.xuper.crystalclear",
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus",
            "com.wbd.stream",
            "com.spotify.tv.android",
            "com.plexapp.android",
            "org.xbmc.kodi",
            "org.videolan.vlc");

    private Favorites() {}

    /** Only packages that are installed right now, so the row never shows a dead card. */
    static List<String> get(Context c, Set<String> installed) {
        String saved = prefs(c).getString(KEY, null);
        List<String> baked = Defaults.favorites();
        List<String> source = saved == null ? (baked != null ? baked : DEFAULTS)
                : saved.isEmpty() ? new ArrayList<String>() : Arrays.asList(saved.split(","));
        List<String> out = new ArrayList<>();
        for (String pkg : source) {
            if (installed.contains(pkg) && !out.contains(pkg)) out.add(pkg);
        }
        return out;
    }

    static void set(Context c, List<String> packages) {
        prefs(c).edit().putString(KEY, TextUtils.join(",", packages)).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
