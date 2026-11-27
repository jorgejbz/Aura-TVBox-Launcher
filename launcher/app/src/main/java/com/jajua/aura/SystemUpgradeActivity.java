package com.jajua.aura;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * "Mejoras del sistema" side panel: says what this box is missing ({@link SystemUpgrade}), and
 * installs it with "Instalar ahora" (the box reboots once). Offered after an Aura update and always
 * reachable from Ajustes > Sistema.
 *
 * The work runs on one app-wide thread, not tied to this screen: if Android closes or recreates the
 * screen (1.4.3 crashed when a keyboard-state change recreated it mid-check), the install still
 * finishes and the box still restarts; a new copy of the screen just shows the progress again.
 */
public final class SystemUpgradeActivity extends Activity {
    /** From the floating notice's "Instalar ahora": no second question, straight to installing. */
    static final String EXTRA_NOW = "now";
    /** Started by itself right after an Aura update: the second half of that same "Actualizar". */
    static final String EXTRA_AFTER_UPDATE = "after_update";

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    /** One install at a time, whichever screen asked. */
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    /** The last progress text, for a screen that opens while the install runs. */
    private static final AtomicReference<String> STEP = new AtomicReference<>();
    /** The screen showing the progress now (any copy of it), or null. */
    private static volatile SystemUpgradeActivity shown;

    /** The system improvements are being installed right now (they copy Aura to /system themselves). */
    static boolean running() {
        return RUNNING.get();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private SidePanel panel;
    private TextView note;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean afterUpdate = getIntent().getBooleanExtra(EXTRA_AFTER_UPDATE, false);
        panel = SidePanel.show(this, getString(afterUpdate ? R.string.up_title_update : R.string.up_title));
        if (afterUpdate) panel.setSubtitle(getString(R.string.up_sub_update));
        note = Ui.text(this, 9.5f, Ui.regular, 0.85f);
        note.setSingleLine(false);
        note.setEllipsize(null);
        note.setLineSpacing(Ui.uf(3), 1f);
        note.setPadding(Ui.u(18), Ui.u(12), Ui.u(18), Ui.u(8));
        panel.list.addView(note);
        shown = this;
        if (RUNNING.get()) {
            showProgress(STEP.get() != null ? STEP.get() : getString(R.string.up_starting));
            return;
        }
        note.setText(R.string.up_checking);
        final Context app = getApplicationContext();
        WORKER.execute(() -> {
            SystemUpgrade.Manifest m = SystemUpgrade.manifest();
            List<String> missing = m == null ? null : SystemUpgrade.missing(app, m);
            main.post(() -> show(m, missing));
        });
    }

    private boolean gone() {
        return isFinishing() || isDestroyed();
    }

    private void show(SystemUpgrade.Manifest m, List<String> missing) {
        if (gone()) return;
        if (m == null || missing.isEmpty()) {
            note.setText(m == null ? R.string.up_none_model : R.string.up_none);
            panel.row(this, null, getString(R.string.up_close), null, null, v -> finish()).view.requestFocus();
            return;
        }
        if (getIntent().getBooleanExtra(EXTRA_NOW, false)) {
            install(m);
            return;
        }
        note.setText(R.string.up_intro);
        panel.section(this, R.string.up_sec_items);
        for (String id : missing) {
            panel.row(this, null, SystemUpgrade.label(this, id), null, null, v -> { }).view.setFocusable(false);
        }
        panel.section(this, R.string.up_sec_go);
        SidePanel.Row go = panel.row(this, getDrawable(R.drawable.ic_update), getString(R.string.up_install),
                getString(R.string.up_install_sub), null, v -> install(m));
        panel.row(this, null, getString(R.string.up_later), null, null, v -> finish());
        go.view.requestFocus();
    }

    /** Only the progress text stays: nothing else can be pressed while the system is being changed. */
    private void showProgress(String text) {
        for (int i = panel.list.getChildCount() - 1; i >= 0; i--) {
            if (panel.list.getChildAt(i) != note) panel.list.removeViewAt(i);
        }
        note.setFocusable(true);
        note.requestFocus();
        note.setText(text);
    }

    private void install(SystemUpgrade.Manifest m) {
        if (!RUNNING.compareAndSet(false, true)) return;
        String first = getString(getIntent().getBooleanExtra(EXTRA_AFTER_UPDATE, false)
                ? R.string.up_starting_update : R.string.up_starting);
        STEP.set(first);
        showProgress(first);
        final Context app = getApplicationContext();
        final Handler ui = main;
        WORKER.execute(() -> {
            String err;
            try {
                err = SystemUpgrade.apply(app, m, text -> {
                    STEP.set(text);
                    ui.post(() -> {
                        SystemUpgradeActivity s = shown;
                        if (s != null && !s.gone()) s.note.setText(text);
                    });
                });
            } catch (RuntimeException e) {
                err = app.getString(R.string.up_err_root);
            }
            final String message = err;
            RUNNING.set(false);
            STEP.set(null);
            ui.post(() -> {
                if (message == null) return; // Rebooting.
                SystemUpgradeActivity s = shown;
                if (s == null || s.gone()) return;
                s.note.setText(message);
                s.panel.row(s, null, s.getString(R.string.up_close), null, null, v -> s.finish()).view.requestFocus();
            });
        });
    }

    @Override
    public void onBackPressed() {
        if (!RUNNING.get()) super.onBackPressed();
    }

    @Override
    public void finish() {
        if (RUNNING.get()) return;
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        if (shown == this) shown = null;
        super.onDestroy();
    }
}
