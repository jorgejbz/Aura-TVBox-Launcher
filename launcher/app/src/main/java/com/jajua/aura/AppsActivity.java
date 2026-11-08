package com.jajua.aura;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Nuevas apps disponibles": lists what the online app folder has for this box and installs the
 * chosen apps one by one with the system installer (Android shows its own confirmation for each).
 * Opened by Aura after its daily check, or by hand from Aura's update dialog.
 */
public final class AppsActivity extends Activity {
    static final String EXTRA_MANUAL = "manual";
    private static final int REQUEST_INSTALL = 1;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<AppsCatalog.Offer> queue = new ArrayList<>();
    private AlertDialog dialog;
    private boolean manual;
    private File installing;
    private int installed;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        manual = getIntent().getBooleanExtra(EXTRA_MANUAL, false);
        List<AppsCatalog.Offer> known = AppsCatalog.last;
        if (!manual && known != null && !known.isEmpty()) {
            offer(known);
            return;
        }
        if (manual) status(getString(R.string.apps_checking), false);
        worker.execute(this::check);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        if (dialog != null) dialog.dismiss();
        super.onDestroy();
    }

    private void check() {
        try {
            List<AppsCatalog.Offer> offers = AppsCatalog.check(getApplicationContext());
            ui(() -> {
                if (!offers.isEmpty()) offer(offers);
                else if (manual) status(getString(R.string.apps_none), true);
                else finish();
            });
        } catch (IOException | JSONException | RuntimeException e) {
            ui(() -> {
                if (manual) status(getString(R.string.apps_error), true);
                else finish();
            });
        }
    }

    private void offer(List<AppsCatalog.Offer> offers) {
        AppsCatalog.markSeen(this, offers);
        StringBuilder text = new StringBuilder();
        for (AppsCatalog.Offer o : offers) {
            text.append("•  ").append(o.label);
            if (!o.versionName.isEmpty()) text.append(' ').append(o.versionName);
            text.append("  (").append(getString(o.update ? R.string.apps_kind_update : R.string.apps_kind_new))
                    .append(")\n");
        }
        text.append('\n').append(getString(R.string.apps_question));
        close();
        dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.apps_title)
                .setMessage(text.toString())
                .setPositiveButton(R.string.apps_install, (d, w) -> {
                    queue.clear();
                    queue.addAll(offers);
                    next();
                })
                .setNegativeButton(R.string.update_later, (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
    }

    /** Downloads (if needed) and installs the next app; the installer's answer brings us back here. */
    private void next() {
        if (queue.isEmpty()) {
            if (installed > 0) {
                Toast.makeText(this, getString(R.string.apps_done, installed), Toast.LENGTH_LONG).show();
            }
            finish();
            return;
        }
        AppsCatalog.Offer o = queue.remove(0);
        status(getString(R.string.apps_downloading, o.label), false);
        worker.execute(() -> {
            try {
                File apk = AppsCatalog.fetch(getApplicationContext(), o);
                ui(() -> install(apk, o));
            } catch (IOException | RuntimeException e) {
                ui(() -> {
                    Toast.makeText(this, getString(R.string.apps_failed, o.label), Toast.LENGTH_LONG).show();
                    next();
                });
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void install(File apk, AppsCatalog.Offer o) {
        close();
        Uri uri;
        if (Build.VERSION.SDK_INT >= 24) {
            uri = InstallProvider.uri(this, apk.getName());
        } else {
            // Before Android 7 the system installer only reads world-readable file:// paths.
            apk.setReadable(true, false);
            apk.getParentFile().setExecutable(true, false);
            uri = Uri.fromFile(apk);
        }
        installing = apk;
        try {
            startActivityForResult(new Intent(Intent.ACTION_INSTALL_PACKAGE)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                    // Honoured only for a system app like Aura: the box's owner chose these apps.
                    .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), REQUEST_INSTALL);
        } catch (RuntimeException e) {
            Toast.makeText(this, getString(R.string.apps_failed, o.label), Toast.LENGTH_LONG).show();
            next();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQUEST_INSTALL) return;
        if (result == RESULT_OK) installed++;
        if (installing != null) installing.delete();
        installing = null;
        next();
    }

    private void status(String message, boolean closable) {
        close();
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(R.string.apps_title).setMessage(message);
        if (closable) b.setPositiveButton(R.string.info_close, (d, w) -> finish());
        b.setOnCancelListener(d -> finish());
        dialog = b.show();
    }

    private void close() {
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }

    private void ui(Runnable r) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) r.run();
        });
    }
}
