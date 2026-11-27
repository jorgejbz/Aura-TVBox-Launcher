package com.jajua.aura;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads launchable apps from PackageManager. Runs off the main thread. */
final class AppLoader {
    /** System components on some firmwares whose launcher icons only duplicate the "Archivos" tile. */
    private static final java.util.Set<String> HIDDEN = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.android.documentsui", "com.android.providers.downloads.ui"));

    /**
     * Cards never draw bigger than this (a 4K favourite card is about 820 px wide). Some apps ship one huge
     * icon (Xuper Crystal Clear: 1024 px in hdpi only, which Android scales to ~2700 px = 30 MB for xxxhdpi);
     * keeping those whole ran Aura out of memory when the app list reloaded after an install.
     */
    private static final int MAX_ICON_PX = 512, MAX_BANNER_PX = 960;

    private AppLoader() {}

    /** Scales a too-large bitmap down; anything else is returned as is. */
    private static Drawable shrink(Context c, Drawable d, int max) {
        if (!(d instanceof android.graphics.drawable.BitmapDrawable)) return d;
        Bitmap b = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
        if (b == null || (b.getWidth() <= max && b.getHeight() <= max)) return d;
        float k = (float) max / Math.max(b.getWidth(), b.getHeight());
        try {
            Bitmap small = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)),
                    Math.max(1, Math.round(b.getHeight() * k)), true);
            return new android.graphics.drawable.BitmapDrawable(c.getResources(), small);
        } catch (OutOfMemoryError e) {
            return d;
        }
    }

    static List<Entry> load(Context context) {
        PackageManager pm = context.getPackageManager();
        String self = context.getPackageName();

        // TV-optimised activities win over phone ones for the same package.
        Map<String, ResolveInfo> byPackage = new LinkedHashMap<>();
        Map<String, String> category = new LinkedHashMap<>();
        for (String cat : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(self) || HIDDEN.contains(pkg) || byPackage.containsKey(pkg)) continue;
                byPackage.put(pkg, ri);
                category.put(pkg, cat);
            }
        }
        // With the TV settings present, the full-screen phone settings never show (SettingsRedirect).
        if (byPackage.containsKey(SettingsRedirect.TV)) byPackage.remove("com.android.settings");

        List<Entry> apps = new ArrayList<>();
        for (Map.Entry<String, ResolveInfo> e : byPackage.entrySet()) {
            ResolveInfo ri = e.getValue();
            ActivityInfo ai = ri.activityInfo;
            ApplicationInfo app = ai.applicationInfo;
            Intent launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(category.get(e.getKey()))
                    .setClassName(ai.packageName, ai.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            boolean system = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    && (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
            Entry entry = new Entry(ai.packageName, ai.packageName, ri.loadLabel(pm), null, launch, !system);
            entry.banner = shrink(context, loadBanner(pm, ai), MAX_BANNER_PX);
            entry.icon = shrink(context, loadIcon(pm, ri), MAX_ICON_PX);
            if (entry.banner == null && PLAY_STORE.equals(ai.packageName)) {
                entry.banner = playStoreBanner(context, entry.icon);
            }
            entry.bannerFit = needsFit(entry.banner);
            entry.tint = dominantColor(entry.banner != null ? entry.banner : entry.icon);
            apps.add(entry);
        }

        Collator collator = Collator.getInstance();
        collator.setStrength(Collator.PRIMARY);
        Collections.sort(apps, (a, b) -> collator.compare(a.label.toString(), b.label.toString()));
        return apps;
    }

    private static final String PLAY_STORE = "com.android.vending";

    /**
     * True when filling the 16:9 card would cut the banner: a logo drawn on transparency, or a shape far
     * from 16:9. Those are shown whole instead (see Card).
     */
    static boolean needsFit(Drawable d) {
        if (d == null) return false;
        int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
        if (w <= 0 || h <= 0) return false;
        float ratio = (float) w / h;
        if (Math.abs(ratio - 16f / 9f) > 0.2f) return true;
        if (d instanceof android.graphics.drawable.BitmapDrawable) {
            Bitmap b = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
            if (b != null && b.hasAlpha()) {
                // Transparent corners: a free-standing logo, not full-bleed artwork.
                int[][] corners = {{0, 0}, {b.getWidth() - 1, 0}, {0, b.getHeight() - 1}, {b.getWidth() - 1, b.getHeight() - 1}};
                for (int[] p : corners) if (Color.alpha(b.getPixel(p[0], p[1])) < 128) return true;
            }
        }
        return false;
    }

    /**
     * The phone Play Store has no TV banner, so its card was a small icon on a coloured square. This
     * draws one in the style of YouTube's: an off-white card with the app's own icon and "Google Play".
     */
    private static Drawable playStoreBanner(Context c, Drawable icon) {
        if (icon == null) return null;
        try {
            int w = 640, h = 360;
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            cv.drawColor(0xFFF3F3F3);
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFF5F6368);
            p.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            p.setTextSize(62);
            String text = "Google Play";
            int iconSize = 176, gap = 14;
            float textW = p.measureText(text);
            int left = Math.round((w - (iconSize + gap + textW)) / 2f);
            int top = (h - iconSize) / 2;
            Drawable ic = icon.getConstantState() != null ? icon.getConstantState().newDrawable().mutate() : icon;
            ic.setBounds(left, top, left + iconSize, top + iconSize);
            ic.draw(cv);
            android.graphics.Paint.FontMetrics fm = p.getFontMetrics();
            float baseline = h / 2f - (fm.ascent + fm.descent) / 2f;
            cv.drawText(text, left + iconSize + gap, baseline, p);
            return new android.graphics.drawable.BitmapDrawable(c.getResources(), b);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private static Drawable loadBanner(PackageManager pm, ActivityInfo ai) {
        try {
            Drawable d = ai.loadBanner(pm);
            if (d == null) d = ai.applicationInfo.loadBanner(pm);
            return d;
        } catch (RuntimeException | OutOfMemoryError ex) {
            return null;
        }
    }

    /** Prefers a high-density icon so cards stay sharp on a 1080p/4K TV. */
    private static Drawable loadIcon(PackageManager pm, ResolveInfo ri) {
        try {
            int res = ri.getIconResource();
            if (res != 0) {
                Resources r = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                Drawable d = r.getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null);
                if (d != null) return d;
            }
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
            // Fall through to the default loader.
        } catch (OutOfMemoryError tooBig) {
            // A huge icon blown up for xxxhdpi: the screen's own density is plenty.
        }
        try {
            return ri.loadIcon(pm);
        } catch (OutOfMemoryError tooBig) {
            return pm.getDefaultActivityIcon();
        }
    }

    /** Average of the saturated, opaque pixels: gives each card and the backdrop its colour. */
    static int dominantColor(Drawable d) {
        if (d == null) return Ui.ACCENT;
        final int size = 24;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
        android.graphics.Rect old = copy.copyBounds();
        copy.setBounds(0, 0, size, size);
        copy.draw(new Canvas(bmp));
        copy.setBounds(old);

        float[] hsv = new float[3];
        double r = 0, g = 0, b = 0, total = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int px = bmp.getPixel(x, y);
                if (Color.alpha(px) < 200) continue;
                Color.colorToHSV(px, hsv);
                double w = hsv[1] * hsv[2] + 0.04;
                r += Color.red(px) * w;
                g += Color.green(px) * w;
                b += Color.blue(px) * w;
                total += w;
            }
        }
        bmp.recycle();
        if (total == 0) return Ui.ACCENT;
        int c = Color.rgb((int) (r / total), (int) (g / total), (int) (b / total));
        // Lift very dark colours so the glow is still visible on black.
        Color.colorToHSV(c, hsv);
        hsv[2] = Math.max(hsv[2], 0.55f);
        return Color.HSVToColor(hsv);
    }
}
