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
    private AppLoader() {}

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
                if (pkg.equals(self) || byPackage.containsKey(pkg)) continue;
                byPackage.put(pkg, ri);
                category.put(pkg, cat);
            }
        }

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
            entry.banner = loadBanner(pm, ai);
            entry.icon = loadIcon(pm, ri);
            entry.tint = dominantColor(entry.banner != null ? entry.banner : entry.icon);
            apps.add(entry);
        }

        Collator collator = Collator.getInstance();
        collator.setStrength(Collator.PRIMARY);
        Collections.sort(apps, (a, b) -> collator.compare(a.label.toString(), b.label.toString()));
        return apps;
    }

    private static Drawable loadBanner(PackageManager pm, ActivityInfo ai) {
        try {
            Drawable d = ai.loadBanner(pm);
            if (d == null) d = ai.applicationInfo.loadBanner(pm);
            return d;
        } catch (RuntimeException ex) {
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
        }
        return ri.loadIcon(pm);
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
