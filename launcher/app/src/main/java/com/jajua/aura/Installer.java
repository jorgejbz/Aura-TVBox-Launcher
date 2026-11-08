package com.jajua.aura;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Installs an APK with a PackageInstaller session. As a privileged system app Aura holds
 * INSTALL_PACKAGES, so the user's "Instalar" in Aura is the only confirmation; elsewhere (Aura in
 * /data, or a firmware that refuses the permission) Android shows its usual confirmation screen.
 */
final class Installer {
    interface Callback {
        void done(boolean ok);
    }

    private static final String ACTION = "com.jajua.aura.INSTALL_RESULT";
    private static int sequence;

    private Installer() {}

    /**
     * Call from a worker thread (it copies the APK). {@code cb} gets one answer per APK, on any thread
     * (unless Aura replaces itself, which ends this process).
     */
    static void install(Activity a, List<File> apks, String pkg, Callback cb) {
        final String action = ACTION + "." + (++sequence);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        try {
                            a.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                            return; // The final answer arrives here again.
                        } catch (RuntimeException ignored) {
                            // Fall through: report the failure.
                        }
                    }
                }
                try {
                    a.unregisterReceiver(this);
                } catch (IllegalArgumentException ignored) {
                    // Already gone with the activity.
                }
                cb.done(status == PackageInstaller.STATUS_SUCCESS);
            }
        };
        IntentFilter f = new IntentFilter(action);
        if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else a.registerReceiver(receiver, f);

        PackageInstaller pi = a.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(pkg);
        long size = 0;
        for (File apk : apks) size += apk.length();
        params.setSize(size);
        PackageInstaller.Session session = null;
        try {
            int id = pi.createSession(params);
            session = pi.openSession(id);
            // A split app goes in as several APKs of one session (base first); names only need to be unique.
            for (int i = 0; i < apks.size(); i++) {
                File apk = apks.get(i);
                try (InputStream in = new FileInputStream(apk);
                     OutputStream out = session.openWrite(i + ".apk", 0, apk.length())) {
                    byte[] buffer = new byte[65536];
                    int n;
                    while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                    session.fsync(out);
                }
            }
            // The system fills in the result extras, so the PendingIntent must be mutable on Android 12+.
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent result = PendingIntent.getBroadcast(a, sequence,
                    new Intent(action).setPackage(a.getPackageName()), flags);
            session.commit(result.getIntentSender());
            session.close();
        } catch (IOException | RuntimeException e) {
            if (session != null) session.abandon();
            try {
                a.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
                // Not registered.
            }
            cb.done(false);
        }
    }
}
