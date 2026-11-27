package com.jajua.aura;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * "Ajustes": Aura's own side panel with the settings people actually use, most common first
 * (network, apps, storage, sound, time, language, restart, factory reset...), each opening the
 * matching TV settings panel. "Todos los ajustes" at the end opens the full TV settings menu.
 * Answers android.settings.SETTINGS through {@link SettingsRedirect} and the Ajustes tile.
 */
public final class SettingsPanel extends Activity {
    private static final String TAG = "AuraAjustes";
    private static final String TV = SettingsRedirect.TV;

    private SidePanel.Row network, storage, time;

    /** True when this device has the TV settings this panel opens. */
    static boolean available(Context c) {
        try {
            c.getPackageManager().getPackageInfo(TV, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        SidePanel p = SidePanel.show(this, getString(R.string.sp_title));
        p.setSubtitle(getString(R.string.sp_subtitle, version()));

        p.section(this, R.string.sp_sec_connection);
        network = p.row(this, icon(R.drawable.ic_network), getString(R.string.sp_network), null, null,
                v -> openTv(".connectivity.NetworkActivity"));

        p.section(this, R.string.sp_sec_apps);
        p.row(this, icon(R.drawable.ic_s_apps), getString(R.string.sp_apps), getString(R.string.sp_apps_sub), null,
                v -> openTv(".device.apps.AppsActivity"));
        storage = p.row(this, icon(R.drawable.ic_s_storage), getString(R.string.sp_storage), null, null,
                v -> openTv(".device.StorageResetActivity"));

        p.section(this, R.string.sp_sec_prefs);
        p.row(this, icon(R.drawable.ic_s_sound), getString(R.string.sp_sound), getString(R.string.sp_sound_sub), null,
                v -> openTv(".device.sound.SoundActivity"));
        time = p.row(this, icon(R.drawable.ic_s_time), getString(R.string.sp_time), null, null,
                v -> openTv(".system.DateTimeActivity"));
        p.row(this, icon(R.drawable.ic_s_language), getString(R.string.sp_language),
                capitalize(Locale.getDefault().getDisplayName()), null, v -> openTv(".system.LanguageActivity"));
        p.row(this, icon(R.drawable.ic_s_keyboard), getString(R.string.sp_keyboard), null, null,
                v -> openTv(".system.KeyboardActivity"));
        p.row(this, icon(R.drawable.ic_s_accessibility), getString(R.string.sp_accessibility),
                getString(R.string.sp_accessibility_sub), null, v -> openTv(".system.AccessibilityActivity"));

        p.section(this, R.string.sp_sec_system);
        p.row(this, icon(R.drawable.ic_s_restart), getString(R.string.sp_restart), getString(R.string.sp_restart_sub), null,
                v -> confirmRestart());
        p.row(this, icon(R.drawable.ic_s_reset), getString(R.string.sp_reset), getString(R.string.sp_reset_sub), null,
                v -> confirmReset());
        p.row(this, icon(R.drawable.ic_update), getString(R.string.sp_update), null, null,
                v -> open(new Intent(this, UpdateActivity.class)));
        p.row(this, icon(R.drawable.ic_settings), getString(R.string.sp_upgrade), getString(R.string.sp_upgrade_sub), null,
                v -> open(new Intent(this, SystemUpgradeActivity.class)));
        p.row(this, icon(R.drawable.ic_info), getString(R.string.sp_about), getString(R.string.sp_about_sub), null,
                v -> openTv(".about.AboutActivity"));
        p.row(this, icon(R.drawable.ic_settings), getString(R.string.sp_all), getString(R.string.sp_all_sub), null,
                v -> openTv(".MainSettings"));

        network.view.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from a TV panel: the network, free space or time may have changed.
        network.setSub(networkState());
        long[] s = SystemInfo.storage();
        storage.setSub(getString(R.string.sp_storage_sub, SystemInfo.size(s[1]), SystemInfo.size(s[0])));
        time.setSub(DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date())
                + "  ·  " + java.util.TimeZone.getDefault().getDisplayName(Locale.getDefault()));
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    // ------------------------------------------------------------ rows

    @SuppressWarnings("deprecation")
    private String networkState() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        if (ni == null || !ni.isConnected()) return getString(R.string.sp_net_none);
        if (ni.getType() == ConnectivityManager.TYPE_ETHERNET) return getString(R.string.sp_net_cable);
        if (ni.getType() == ConnectivityManager.TYPE_WIFI) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
            String ssid = wi == null ? null : wi.getSSID();
            if (ssid != null && ssid.length() > 1 && !ssid.contains("unknown ssid")) {
                return getString(R.string.sp_net_wifi, ssid.replace("\"", ""));
            }
        }
        return ni.getTypeName();
    }

    private void confirmRestart() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.sp_restart_q)
                .setMessage(R.string.sp_restart_msg)
                .setPositiveButton(R.string.sp_restart_yes, (d, w) -> {
                    try {
                        ((PowerManager) getSystemService(POWER_SERVICE)).reboot(null);
                    } catch (SecurityException e) {
                        Log.w(TAG, "no REBOOT permission", e);
                        openTv(".about.AboutActivity"); // "Reiniciar" is there too.
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Factory reset, explained first and confirmed twice. Sends the same broadcast the TV settings'
     * own reset screen sends; without the MASTER_CLEAR permission it opens that screen instead.
     */
    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.sp_reset_q)
                .setMessage(R.string.sp_reset_msg)
                .setPositiveButton(R.string.sp_reset_next, (d, w) -> new AlertDialog.Builder(this)
                        .setTitle(R.string.sp_reset_q2)
                        .setMessage(R.string.sp_reset_msg2)
                        .setPositiveButton(R.string.sp_reset_yes, (d2, w2) -> factoryReset())
                        .setNegativeButton(android.R.string.cancel, null)
                        .show())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void factoryReset() {
        if (checkSelfPermission("android.permission.MASTER_CLEAR") != PackageManager.PERMISSION_GRANTED) {
            openTv(".device.StorageResetActivity");
            return;
        }
        Intent wipe = new Intent("android.intent.action.MASTER_CLEAR")
                .setPackage("android")
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra("android.intent.extra.REASON", "Aura > Ajustes > Formatear");
        sendBroadcast(wipe);
    }

    // ------------------------------------------------------------ helpers

    private void openTv(String cls) {
        open(new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(TV, TV + cls)));
    }

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "cannot open " + i, e);
            startActivity(new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(TV, TV + ".MainSettings")));
        }
    }

    private android.graphics.drawable.Drawable icon(int res) {
        return getDrawable(res);
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.getDefault()) + s.substring(1);
    }
}
