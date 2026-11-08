package com.jajua.aura;

import android.app.Activity;
import android.app.AlertDialog;
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

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<AppsCatalog.Offer> queue = new ArrayList<>();
    private AlertDialog dialog;
    private boolean manual;
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
                List<File> apks = AppsCatalog.fetch(getApplicationContext(), o);
                ui(() -> status(getString(R.string.apps_installing, o.label), false));
                // The "Instalar" pressed in Aura is the confirmation: no second screen when Aura is a system app.
                Installer.install(this, apks, o.pkg, ok -> ui(() -> {
                    AppsCatalog.discard(getApplicationContext(), o);
                    if (ok) installed++;
                    else Toast.makeText(this, getString(R.string.apps_failed, o.label), Toast.LENGTH_LONG).show();
                    next();
                }));
            } catch (IOException | RuntimeException e) {
                ui(() -> {
                    Toast.makeText(this, getString(R.string.apps_failed, o.label), Toast.LENGTH_LONG).show();
                    next();
                });
            }
        });
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
