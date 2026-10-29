package com.jajua.aura;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Opens the app the user assigned to a remote button, from anywhere. Uses the standard
 * accessibility key filter; it only sees key presses and never reads screen content.
 */
public final class KeyMapService extends AccessibilityService {
    interface CaptureListener {
        void onKeyCaptured(int keyCode);
    }

    /** Set while Aura's "press a button" dialog is open; the next key goes there instead. */
    static volatile CaptureListener capture;

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (KeyMap.isReserved(code)) return false;

        CaptureListener l = capture;
        if (l != null) {
            if (event.getAction() == KeyEvent.ACTION_UP) main.post(() -> l.onKeyCaptured(code));
            return true;
        }

        String pkg = KeyMap.get(this, code);
        if (pkg == null) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) launch(pkg);
        return true;
    }

    private void launch(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // App was removed after being assigned; nothing to open.
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Key filtering only.
    }

    @Override
    public void onInterrupt() {
        // Nothing to interrupt.
    }
}
