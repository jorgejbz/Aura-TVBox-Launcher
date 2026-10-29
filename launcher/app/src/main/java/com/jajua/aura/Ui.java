package com.jajua.aura;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * Resolution-independent sizing. Everything is designed on a 960 x 540 canvas and
 * scaled to the real screen, so the layout looks the same on 720p, 1080p and 4K
 * regardless of the density the box firmware reports.
 */
final class Ui {
    static final int TEXT = 0xFFF5F5F7;
    static final int ACCENT = 0xFF8E9BFF;
    static final int BG = 0xFF07080B;

    /** Horizontal / vertical TV safe area (about 5%), so nothing is cut by overscan. */
    static final float SAFE_X = 48f;
    static final float SAFE_Y = 26f;

    static Typeface regular;
    static Typeface medium;
    static Typeface light;

    private static float unit = 1f;

    private Ui() {}

    static void init(Context context) {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int w = Math.max(dm.widthPixels, dm.heightPixels);
        int h = Math.min(dm.widthPixels, dm.heightPixels);
        unit = Math.min(w / 960f, h / 540f);
        regular = Typeface.create("sans-serif", Typeface.NORMAL);
        medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    }

    static int u(float v) {
        return Math.round(v * unit);
    }

    static float uf(float v) {
        return v * unit;
    }

    static TextView text(Context c, float size, Typeface face, float alpha) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, uf(size));
        t.setTypeface(face);
        t.setTextColor(TEXT);
        t.setAlpha(alpha);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    static int blend(int from, int to, float amount) {
        float inv = 1f - amount;
        return Color.rgb(
                Math.round(Color.red(from) * inv + Color.red(to) * amount),
                Math.round(Color.green(from) * inv + Color.green(to) * amount),
                Math.round(Color.blue(from) * inv + Color.blue(to) * amount));
    }

    static int withAlpha(int color, float alpha) {
        return (Math.round(alpha * 255) << 24) | (color & 0x00FFFFFF);
    }
}
