package com.jajua.aura;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Sends every settings screen to the TV settings side panel (com.android.tv.settings), even
 * when the firmware also ships the full-screen phone settings and those would win the intent.
 *
 * Declared with a high priority for the android.settings.* actions in the manifest; Android only
 * honours that priority for a system app, so it works once Aura is in /system. The request is
 * first re-sent to the TV settings as is; if they do not answer that action, it goes to the
 * nearest TV screen (an app's own panel when the request names an app), else the main panel.
 * Permission switches the TV settings do not have (overlay, modify system settings, usage and
 * notification access) are answered by {@link PermissionsPanel} instead.
 */
public final class SettingsRedirect extends Activity {
    static final String TV = "com.android.tv.settings";
    private static final String TAG = "AuraSettings";
    private static final Map<String, String> NEAREST = new HashMap<>();

    static {
        String net = ".connectivity.NetworkActivity";
        for (String a : new String[] {
                "android.settings.WIFI_SETTINGS", "android.settings.WIRELESS_SETTINGS",
                "android.settings.WIFI_IP_SETTINGS", "android.settings.WIFI_IF_SETTINGS",
                "android.settings.AIRPLANE_MODE_SETTINGS", "android.settings.VPN_SETTINGS",
                "android.net.vpn.SETTINGS", "android.net.wifi.PICK_WIFI_NETWORK",
                "android.settings.DATA_ROAMING_SETTINGS", "android.settings.NETWORK_OPERATOR_SETTINGS",
                "android.settings.DATA_USAGE_SETTINGS", "android.settings.CAST_SETTINGS"}) {
            NEAREST.put(a, net);
        }
        NEAREST.put("android.settings.ACCESSIBILITY_SETTINGS", ".system.AccessibilityActivity");
        NEAREST.put("android.settings.ACCESSIBILITY_SETTINGS_FOR_SUW", ".system.AccessibilityActivity");
        NEAREST.put("com.android.settings.ACCESSIBILITY_COLOR_SPACE_SETTINGS", ".system.AccessibilityActivity");
        NEAREST.put("android.settings.CAPTIONING_SETTINGS", ".system.CaptionSetupActivity");
        NEAREST.put("android.settings.DREAM_SETTINGS", ".device.display.daydream.DaydreamActivity");
        NEAREST.put("android.settings.DATE_SETTINGS", ".system.DateTimeActivity");
        NEAREST.put("android.settings.LOCALE_SETTINGS", ".system.LanguageActivity");
        for (String a : new String[] {
                "android.settings.INPUT_METHOD_SETTINGS", "android.settings.INPUT_METHOD_SUBTYPE_SETTINGS",
                "android.settings.HARD_KEYBOARD_SETTINGS", "android.settings.VOICE_INPUT_SETTINGS",
                "android.settings.USER_DICTIONARY_SETTINGS", "com.android.settings.TTS_SETTINGS"}) {
            NEAREST.put(a, ".system.KeyboardActivity");
        }
        NEAREST.put("android.settings.SOUND_SETTINGS", ".device.sound.SoundActivity");
        NEAREST.put("com.android.settings.SOUND_SETTINGS", ".device.sound.SoundActivity");
        NEAREST.put("android.settings.ACTION_OTHER_SOUND_SETTINGS", ".device.sound.SoundActivity");
        NEAREST.put("android.settings.SECURITY_SETTINGS", ".system.SecurityActivity");
        NEAREST.put("android.settings.PRIVACY_SETTINGS", ".device.privacy.PrivacyActivity");
        NEAREST.put("android.settings.BACKUP_AND_RESET_SETTINGS", ".device.StorageResetActivity");
        NEAREST.put("android.settings.INTERNAL_STORAGE_SETTINGS", ".device.StorageResetActivity");
        NEAREST.put("android.settings.MEMORY_CARD_SETTINGS", ".device.StorageResetActivity");
        NEAREST.put("android.settings.STORAGE_MANAGER_SETTINGS", ".device.StorageResetActivity");
        NEAREST.put("android.settings.DEVICE_INFO_SETTINGS", ".about.AboutActivity");
        NEAREST.put("android.settings.LOCATION_SOURCE_SETTINGS", ".system.LocationActivity");
        NEAREST.put("android.settings.APPLICATION_DEVELOPMENT_SETTINGS", ".system.development.DevelopmentActivity");
        NEAREST.put("com.android.settings.APPLICATION_DEVELOPMENT_SETTINGS", ".system.development.DevelopmentActivity");
        NEAREST.put("android.settings.ACCOUNT_SYNC_SETTINGS", ".accounts.AccountSyncActivity");
        for (String a : new String[] {
                "android.settings.APPLICATION_SETTINGS", "android.settings.MANAGE_APPLICATIONS_SETTINGS",
                "android.settings.MANAGE_ALL_APPLICATIONS_SETTINGS", "android.settings.NOTIFICATION_SETTINGS",
                "android.settings.MANAGE_DEFAULT_APPS_SETTINGS", "android.settings.HOME_SETTINGS",
                "android.settings.APP_MEMORY_USAGE", "android.settings.MANAGE_DOMAIN_URLS"}) {
            NEAREST.put(a, ".device.apps.AppsActivity");
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            open(getIntent());
        } catch (RuntimeException e) {
            Log.w(TAG, "could not open the TV settings", e);
        }
        finish();
        overridePendingTransition(0, 0);
    }

    private void open(Intent in) {
        // The general "settings" request opens Aura's Ajustes panel (most used options first).
        String a = in.getAction();
        if (("android.settings.SETTINGS".equals(a) || "com.android.settings.action.SETTINGS".equals(a))
                && SettingsPanel.available(this)) {
            startActivity(new Intent(this, SettingsPanel.class));
            return;
        }
        Intent out = new Intent(in);
        out.setComponent(null);
        out.setPackage(TV);
        String pkg = targetPackage(in);
        if (out.resolveActivity(getPackageManager()) == null) {
            String action = in.getAction();
            if (pkg != null) {
                // Any request about one app (its notifications, battery, etc.): that app's panel.
                out = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", pkg, null)).setPackage(TV);
            } else if (action != null && NEAREST.containsKey(action)) {
                out = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(TV, TV + NEAREST.get(action)));
            } else {
                out = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(TV, TV + ".MainSettings"));
            }
        }
        if (getCallingActivity() != null) out.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
        Log.i(TAG, in.getAction() + " -> " + out);
        try {
            startActivity(out);
        } catch (ActivityNotFoundException | SecurityException e) {
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .setComponent(new ComponentName(TV, TV + ".MainSettings")));
        }
    }

    /** The app a request is about: "package:x" data, or the notification-settings extras. */
    private static String targetPackage(Intent in) {
        Uri d = in.getData();
        if (d != null && "package".equals(d.getScheme()) && d.getSchemeSpecificPart() != null) {
            return d.getSchemeSpecificPart();
        }
        String p = in.getStringExtra("android.provider.extra.APP_PACKAGE");
        return p != null ? p : in.getStringExtra("app_package");
    }
}
