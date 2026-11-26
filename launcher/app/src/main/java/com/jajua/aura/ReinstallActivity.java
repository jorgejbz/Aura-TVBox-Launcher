package com.jajua.aura;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "Reinstalar Xuper's" (Aura > Sistema): puts back the recommended Xuper or Xuper Crystal Clear from
 * {@link XuperApps}. One app at a time, the user picks which. Nothing is removed before the new file is
 * downloaded and fully checked; the app's data stays unless the user agrees to remove it (only needed
 * when the installed app was signed by someone else, or Android refuses to go back to an older version).
 */
public final class ReinstallActivity extends Activity {
    /** One reinstall at a time across the whole app (a second window just says so). */
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final int REQ_UNINSTALL = 7;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Future<?> job;
    private AlertDialog dialog;
    private XuperApps.Target target;
    private XuperApps.Check ready;
    private boolean owner;
    private boolean installing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!BUSY.compareAndSet(false, true)) {
            Toast.makeText(this, R.string.xr_busy, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        owner = true;
        XuperApps.cleanup(this);
        choose();
    }

    @Override
    protected void onDestroy() {
        if (job != null) job.cancel(true);
        worker.shutdownNow();
        if (dialog != null) dialog.dismiss();
        if (owner) {
            // An install already handed to Android finishes on its own; the files are not needed after it.
            if (!installing) XuperApps.cleanup(getApplicationContext());
            BUSY.set(false);
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------ steps

    /** Step 1: which app. Each line says which version the box has now. */
    private void choose() {
        // The real version can mean reading the installed APK (Crystal Clear): not on the main thread.
        final android.content.Context app = getApplicationContext();
        worker.execute(() -> {
            final String[] have = new String[XuperApps.TARGETS.length];
            for (int i = 0; i < have.length; i++) have[i] = XuperApps.installedVersion(app, XuperApps.TARGETS[i]);
            ui(() -> {
                CharSequence[] items = new CharSequence[have.length];
                for (int i = 0; i < items.length; i++) {
                    XuperApps.Target t = XuperApps.TARGETS[i];
                    items[i] = getString(R.string.xr_item, getString(t.label), have[i] == null
                            ? getString(R.string.xr_not_installed) : getString(R.string.xr_have, have[i]),
                            t.recommended);
                }
                show(new AlertDialog.Builder(this)
                        .setTitle(R.string.xr_title)
                        .setItems(items, (d, which) -> confirm(XuperApps.TARGETS[which], have[which]))
                        .setNegativeButton(R.string.xr_cancel, (d, w) -> finish()));
            });
        });
    }

    /** Step 2: say what will happen. */
    private void confirm(XuperApps.Target t, String have) {
        target = t;
        String text = getString(have == null ? R.string.xr_confirm_new : R.string.xr_confirm, getString(t.label), have,
                t.recommended);
        show(new AlertDialog.Builder(this)
                .setTitle(getString(R.string.xr_confirm_title, getString(t.label)))
                .setMessage(text)
                .setPositiveButton(R.string.xr_reinstall, (d, w) -> startDownload())
                .setNegativeButton(R.string.xr_cancel, (d, w) -> finish()));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
    }

    /** Step 3: download and check, with progress and "Cancelar". */
    private void startDownload() {
        if (!XuperApps.enoughSpace(this)) {
            fail(getString(R.string.xr_err_storage), null);
            return;
        }
        final String label = getString(target.label);
        show(new AlertDialog.Builder(this)
                .setTitle(getString(R.string.xr_confirm_title, label))
                .setMessage(getString(R.string.xr_downloading, label))
                .setNegativeButton(R.string.xr_cancel, (d, w) -> cancel()));
        final XuperApps.Target t = target;
        job = worker.submit(() -> {
            try {
                XuperApps.Check check = XuperApps.download(getApplicationContext(), t, pct -> ui(() -> {
                    if (dialog != null && dialog.isShowing()) {
                        dialog.setMessage(getString(R.string.xr_downloading, label)
                                + (pct >= 0 ? "  " + pct + "%" : ""));
                    }
                }));
                ui(() -> checked(check));
            } catch (XuperApps.Problem p) {
                if (p.message == R.string.xr_cancelled) return;
                ui(() -> fail(getString(p.message), p.getMessage()));
            }
        });
    }

    private void cancel() {
        if (job != null) job.cancel(true);
        XuperApps.cleanup(getApplicationContext());
        Toast.makeText(this, R.string.xr_cancelled, Toast.LENGTH_SHORT).show();
        finish();
    }

    /** Step 4: the file is verified. Decide how to install it without surprising the user. */
    private void checked(XuperApps.Check c) {
        ready = c;
        String label = getString(target.label);
        if (c.installed() && !c.sameSigner) {
            // Android would refuse it. Say why and let the user decide; never remove anything on our own.
            show(new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.xr_confirm_title, label))
                    .setMessage(getString(R.string.xr_signer, label, c.installedName, c.versionName))
                    .setPositiveButton(R.string.xr_uninstall_first, (d, w) -> uninstallFirst())
                    .setNegativeButton(R.string.xr_cancel, (d, w) -> finish()));
            return;
        }
        if (c.downgrade()) {
            show(new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.xr_confirm_title, label))
                    .setMessage(getString(R.string.xr_downgrade, label, c.installedName, c.versionName))
                    .setPositiveButton(R.string.xr_install, (d, w) -> install(true))
                    .setNegativeButton(R.string.xr_cancel, (d, w) -> finish()));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
            return;
        }
        install(false);
    }

    /** Step 5: Android installs it over the current app (its data stays). */
    private void install(boolean downgrade) {
        final String label = getString(target.label);
        installing = true;
        show(new AlertDialog.Builder(this)
                .setTitle(getString(R.string.xr_confirm_title, label))
                .setMessage(getString(R.string.xr_installing, label, ready.versionName)));
        dialog.setCancelable(false);
        final XuperApps.Check c = ready;
        worker.execute(() -> Installer.install(this, Collections.singletonList(c.apk), target.pkg, true,
                downgrade, (status, message) -> ui(() -> installed(status, message, downgrade))));
    }

    /** Step 6: Android's answer, checked against what is really installed now. */
    private void installed(int status, String message, boolean downgrade) {
        android.util.Log.i("AuraReinstall", "result " + target.pkg + " " + status + " " + message);
        installing = false;
        String label = getString(target.label);
        if (status == PackageInstaller.STATUS_SUCCESS) {
            XuperApps.cleanup(getApplicationContext());
            long now = installedCode();
            if (now == ready.versionCode) {
                show(new AlertDialog.Builder(this)
                        .setTitle(R.string.xr_title)
                        .setMessage(getString(R.string.xr_done, label, ready.versionName))
                        .setPositiveButton(R.string.info_close, (d, w) -> finish()));
            } else {
                fail(getString(R.string.xr_err_verify, label), "installed " + now + " expected " + ready.versionCode);
            }
            return;
        }
        String m = message == null ? "" : message;
        if (m.contains("DOWNGRADE") || (downgrade && status == PackageInstaller.STATUS_FAILURE)) {
            // This firmware does not allow going back while keeping data: only a clean install can.
            show(new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.xr_confirm_title, label))
                    .setMessage(getString(R.string.xr_downgrade_refused, label))
                    .setPositiveButton(R.string.xr_uninstall_first, (d, w) -> uninstallFirst())
                    .setNegativeButton(R.string.xr_cancel, (d, w) -> finish()));
            return;
        }
        int reason;
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_ABORTED: reason = R.string.xr_err_aborted; break;
            case PackageInstaller.STATUS_FAILURE_STORAGE: reason = R.string.xr_err_storage; break;
            case PackageInstaller.STATUS_FAILURE_CONFLICT: reason = R.string.xr_err_conflict; break;
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE: reason = R.string.xr_err_device; break;
            case PackageInstaller.STATUS_FAILURE_INVALID: reason = R.string.xr_err_corrupt; break;
            case PackageInstaller.STATUS_FAILURE_BLOCKED: reason = R.string.xr_err_blocked; break;
            default: reason = R.string.xr_err_install; break;
        }
        XuperApps.cleanup(getApplicationContext());
        fail(getString(reason), status + " " + m);
    }

    /** Opens Android's own uninstall screen; the verified file waits in the cache meanwhile. */
    @SuppressWarnings("deprecation")
    private void uninstallFirst() {
        Intent i = new Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.fromParts("package", target.pkg, null))
                .putExtra(Intent.EXTRA_RETURN_RESULT, true);
        try {
            startActivityForResult(i, REQ_UNINSTALL);
        } catch (RuntimeException e) {
            fail(getString(R.string.xr_err_install), String.valueOf(e));
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQ_UNINSTALL) return;
        if (installedCode() < 0) {
            install(false); // Gone: a clean install of the recommended version.
        } else {
            show(new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.xr_confirm_title, getString(target.label)))
                    .setMessage(R.string.xr_not_uninstalled)
                    .setPositiveButton(R.string.info_close, (d, w) -> finish()));
        }
    }

    // ------------------------------------------------------------ helpers

    private long installedCode() {
        try {
            return XuperApps.versionCode(getPackageManager().getPackageInfo(target.pkg, 0));
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    private void fail(String message, String detail) {
        if (detail != null) android.util.Log.w("AuraReinstall", detail);
        XuperApps.cleanup(getApplicationContext());
        show(new AlertDialog.Builder(this)
                .setTitle(R.string.xr_title)
                .setMessage(message)
                .setPositiveButton(R.string.info_close, (d, w) -> finish()));
    }

    private void show(AlertDialog.Builder b) {
        if (dialog != null) {
            dialog.setOnDismissListener(null);
            dialog.dismiss();
        }
        b.setOnCancelListener(d -> {
            if (installing) return;
            if (job != null) job.cancel(true);
            finish();
        });
        dialog = b.show();
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(Ui.u(600), -2);
    }

    private void ui(Runnable r) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) r.run();
        });
    }
}
