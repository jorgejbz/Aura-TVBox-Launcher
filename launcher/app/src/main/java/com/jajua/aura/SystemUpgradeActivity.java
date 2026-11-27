package com.jajua.aura;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Mejoras del sistema" side panel: says what this box is missing ({@link SystemUpgrade}), and
 * installs it with "Instalar ahora" (the box reboots once). Offered once after an Aura update and
 * always reachable from Ajustes > Sistema.
 */
public final class SystemUpgradeActivity extends Activity {
    /** From the floating notice's "Instalar ahora": no second question, straight to installing. */
    static final String EXTRA_NOW = "now";
    /** Started by itself right after an Aura update: the second half of that same "Actualizar". */
    static final String EXTRA_AFTER_UPDATE = "after_update";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private SidePanel panel;
    private TextView note;
    private boolean busy;

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
        note.setText(R.string.up_checking);
        panel.list.addView(note);
        worker.execute(() -> {
            SystemUpgrade.Manifest m = SystemUpgrade.manifest();
            List<String> missing = m == null ? null : SystemUpgrade.missing(this, m);
            main.post(() -> show(m, missing));
        });
    }

    private void show(SystemUpgrade.Manifest m, List<String> missing) {
        if (isFinishing()) return;
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

    private void install(SystemUpgrade.Manifest m) {
        if (busy) return;
        busy = true;
        // Only the progress text stays: nothing else can be pressed while the system is being changed.
        for (int i = panel.list.getChildCount() - 1; i >= 0; i--) {
            if (panel.list.getChildAt(i) != note) panel.list.removeViewAt(i);
        }
        note.setFocusable(true);
        note.requestFocus();
        note.setText(getIntent().getBooleanExtra(EXTRA_AFTER_UPDATE, false) ? R.string.up_starting_update : R.string.up_starting);
        worker.execute(() -> {
            String err = SystemUpgrade.apply(getApplicationContext(), m, text -> main.post(() -> note.setText(text)));
            main.post(() -> {
                busy = false;
                if (err == null) return; // Rebooting.
                note.setText(err);
                panel.row(this, null, getString(R.string.up_close), null, null, v -> finish()).view.requestFocus();
            });
        });
    }

    @Override
    public void onBackPressed() {
        if (!busy) super.onBackPressed();
    }

    @Override
    public void finish() {
        if (busy) return;
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }
}
