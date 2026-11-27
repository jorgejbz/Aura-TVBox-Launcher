package com.jajua.aura;

import android.app.Activity;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The four permission switches the TV settings do not have, in a side panel drawn like theirs
 * (in Aura's colours): show over other apps, modify system settings, usage access and
 * notification access. Answers those android.settings actions (see the manifest), so apps that
 * ask for one land here instead of the full-screen phone settings.
 *
 * The first three are app ops, changed with the hidden AppOpsManager.setMode (UPDATE_APP_OPS_STATS,
 * granted to Aura as a privileged system app); notification access is the secure setting
 * enabled_notification_listeners (WRITE_SECURE_SETTINGS). If the box refuses either, the request
 * goes to the phone settings, so the permission can always be granted somewhere.
 */
public final class PermissionsPanel extends Activity {
    private static final String TAG = "AuraPermisos";
    private static final String PHONE = "com.android.settings";
    static final String NOTIF_LISTENERS = "enabled_notification_listeners";

    private enum Kind {
        OVERLAY("android:system_alert_window", "android.permission.SYSTEM_ALERT_WINDOW", R.string.perm_overlay, AppOpsManager.MODE_ERRORED),
        WRITE("android:write_settings", "android.permission.WRITE_SETTINGS", R.string.perm_write, AppOpsManager.MODE_ERRORED),
        USAGE("android:get_usage_stats", "android.permission.PACKAGE_USAGE_STATS", R.string.perm_usage, AppOpsManager.MODE_IGNORED),
        NOTIF(null, null, R.string.perm_notif, 0);

        final String op, permission;
        final int title, offMode;

        Kind(String op, String permission, int title, int offMode) {
            this.op = op;
            this.permission = permission;
            this.title = title;
            this.offMode = offMode;
        }

        static Kind of(String action) {
            if (action == null) return null;
            switch (action) {
                case "android.settings.action.MANAGE_OVERLAY_PERMISSION": return OVERLAY;
                case "android.settings.action.MANAGE_WRITE_SETTINGS": return WRITE;
                case "android.settings.USAGE_ACCESS_SETTINGS": return USAGE;
                case "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS": return NOTIF;
                default: return null;
            }
        }
    }

    /** One switch: an app (app ops) or one notification-listener service of an app. */
    private static final class Row {
        String pkg, label;
        int uid;
        Drawable icon;
        ComponentName listener;
    }

    private Kind kind;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        kind = Kind.of(getIntent().getAction());
        if (kind == null) {
            finish();
            return;
        }
        Uri d = getIntent().getData();
        String only = d != null && "package".equals(d.getScheme()) ? d.getSchemeSpecificPart() : null;
        SidePanel panel = SidePanel.show(this, getString(kind.title));
        List<Row> rows = load(only);
        if (rows.isEmpty()) {
            TextView t = Ui.text(this, 10f, Ui.regular, 0.6f);
            t.setSingleLine(false);
            t.setText(R.string.perm_none);
            t.setPadding(Ui.u(18), Ui.u(12), Ui.u(18), 0);
            panel.list.addView(t);
        }
        for (Row r : rows) {
            SidePanel.Pill pill = new SidePanel.Pill(this);
            SidePanel.Row[] view = new SidePanel.Row[1];
            Runnable show = () -> {
                boolean on = isOn(r);
                pill.setOn(on);
                view[0].setSub(getString(on ? R.string.perm_on : R.string.perm_off));
            };
            view[0] = panel.row(this, r.icon, r.label, null, pill, v -> {
                if (!set(r, !isOn(r))) {
                    toPhoneSettings();
                    return;
                }
                show.run();
            });
            show.run();
        }
        if (panel.list.getChildCount() > 0) panel.list.getChildAt(0).requestFocus();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    // ------------------------------------------------------------ data

    private List<Row> load(String only) {
        PackageManager pm = getPackageManager();
        List<Row> rows = new ArrayList<>();
        if (kind == Kind.NOTIF) {
            Intent q = new Intent("android.service.notification.NotificationListenerService");
            for (ResolveInfo ri : pm.queryIntentServices(q, 0)) {
                if (!"android.permission.BIND_NOTIFICATION_LISTENER_SERVICE".equals(ri.serviceInfo.permission)) continue;
                if (only != null && !only.equals(ri.serviceInfo.packageName)) continue;
                Row r = row(pm, ri.serviceInfo.applicationInfo);
                r.listener = new ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name);
                rows.add(r);
            }
        } else {
            // Apps the person can open (plus the one that asked), not the dozens of system services.
            Set<String> visible = new HashSet<>();
            for (String cat : new String[] {Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER}) {
                for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                    visible.add(ri.activityInfo.packageName);
                }
            }
            for (PackageInfo p : pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
                if (p.requestedPermissions == null || p.packageName.equals(getPackageName())) continue;
                if (only != null ? !only.equals(p.packageName) : !visible.contains(p.packageName)) continue;
                if (!Arrays.asList(p.requestedPermissions).contains(kind.permission)) continue;
                rows.add(row(pm, p.applicationInfo));
            }
        }
        Collections.sort(rows, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return rows;
    }

    private static Row row(PackageManager pm, ApplicationInfo ai) {
        Row r = new Row();
        r.pkg = ai.packageName;
        r.uid = ai.uid;
        r.label = String.valueOf(ai.loadLabel(pm));
        r.icon = ai.loadIcon(pm);
        return r;
    }

    private boolean isOn(Row r) {
        if (kind == Kind.NOTIF) {
            String cur = Settings.Secure.getString(getContentResolver(), NOTIF_LISTENERS);
            return cur != null && Arrays.asList(cur.split(":")).contains(r.listener.flattenToString());
        }
        AppOpsManager ops = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        int mode = ops.checkOpNoThrow(kind.op, r.uid, r.pkg);
        if (mode == AppOpsManager.MODE_DEFAULT) {
            return getPackageManager().checkPermission(kind.permission, r.pkg) == PackageManager.PERMISSION_GRANTED;
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /** False when this box does not let Aura change it (then the phone settings take over). */
    private boolean set(Row r, boolean on) {
        try {
            if (kind == Kind.NOTIF) {
                String cur = Settings.Secure.getString(getContentResolver(), NOTIF_LISTENERS);
                List<String> l = new ArrayList<>();
                if (cur != null && !cur.isEmpty()) l.addAll(Arrays.asList(cur.split(":")));
                String me = r.listener.flattenToString();
                l.remove(me);
                if (on) l.add(me);
                Settings.Secure.putString(getContentResolver(), NOTIF_LISTENERS, android.text.TextUtils.join(":", l));
            } else {
                AppOpsManager ops = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
                Method toOp = AppOpsManager.class.getMethod("strOpToOp", String.class);
                int code = (Integer) toOp.invoke(null, kind.op);
                Method setMode = AppOpsManager.class.getMethod("setMode", int.class, int.class, String.class, int.class);
                setMode.invoke(ops, code, r.uid, r.pkg, on ? AppOpsManager.MODE_ALLOWED : kind.offMode);
            }
            return isOn(r) == on;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "cannot change " + kind + " for " + r.pkg, e);
            return false;
        }
    }

    private void toPhoneSettings() {
        try {
            startActivity(new Intent(getIntent()).setComponent(null).setPackage(PHONE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Log.w(TAG, "no phone settings either", e);
        }
        finish();
    }
}
