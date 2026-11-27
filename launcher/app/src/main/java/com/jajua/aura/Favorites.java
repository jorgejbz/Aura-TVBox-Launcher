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
    /** XuperAmigOnlyTV: a default favourite since 1.4.3, in YouTube's place. */
    static final String AMIGONLY = "com.xuper.amigonly.tv.new";
    private static final String YOUTUBE = "com.google.android.youtube.tv";

    private static final List<String> DEFAULTS = Arrays.asList(
            "com.stremio.one",
            "com.android.mgstv",
            "com.xuper.crystalclear",
            AMIGONLY,
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
        if (baked != null && baked.contains(YOUTUBE) && !baked.contains(AMIGONLY)) {
            // Boxes prepared before 1.4.3 have YouTube in their factory list: XuperAmigOnlyTV takes its
            // place. Only while the person never edited the row (then "saved" is used instead).
            baked = new ArrayList<>(baked);
            baked.set(baked.indexOf(YOUTUBE), AMIGONLY);
        }
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
