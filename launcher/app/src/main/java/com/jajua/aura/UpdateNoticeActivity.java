package com.jajua.aura;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The big notice shown when the box is switched on and GitHub has a newer Aura: version, what is new and
 * "Actualizar ahora" / "Más tarde". Once per boot. After "Más tarde" the small amber "↑ Aura x.y" button on the home
 * screen header stays until the box is updated. "Actualizar ahora" goes straight to the download (UpdateActivity
 * still checks the package, version and signature before installing).
 */
public final class UpdateNoticeActivity extends Activity {
    static final String EXTRA_VERSION = "version";
    private static final String PREFS = "update_notice";
    private static final String SHOWN_BOOT = "shown_boot";

    private ScrollView scroll;
    /** Notes that do not fit roll slowly down, wait, go back up and start again (credits style). */
    private android.animation.ValueAnimator roll;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable rollDown = this::rollDown;
    private final Runnable rollUp = this::rollUp;
    /** Pixels per second of the automatic scroll: slow enough to read along. */
    private static final float ROLL_SPEED = 28f;

    /** True once per boot (the caller then shows it). Without a boot counter, once per Aura process. */
    private static boolean shownThisProcess;

    static boolean dueThisBoot(Context c) {
        int boot = -1;
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                boot = Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT);
            } catch (Settings.SettingNotFoundException ignored) {
                // Fall back to once per process.
            }
        }
        if (boot < 0) return !shownThisProcess;
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(SHOWN_BOOT, -1) != boot;
    }

    static void markShown(Context c) {
        shownThisProcess = true;
        if (Build.VERSION.SDK_INT < 24) return;
        try {
            int boot = Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT);
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            p.edit().putInt(SHOWN_BOOT, boot).apply();
        } catch (Settings.SettingNotFoundException ignored) {
            // shownThisProcess covers it.
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        setContentView(build(getIntent().getStringExtra(EXTRA_VERSION)));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && scroll != null) {
                // The user takes over: stop rolling, scroll by hand, roll again after a while without keys.
                stopRoll();
                scroll.smoothScrollBy(0, (code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1) * Math.round(scroll.getHeight() * 0.5f));
                handler.postDelayed(rollDown, 8000);
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onDestroy() {
        stopRoll();
        super.onDestroy();
    }

    private int overflow() {
        if (scroll == null || scroll.getChildCount() == 0) return 0;
        return Math.max(0, scroll.getChildAt(0).getHeight() - scroll.getHeight());
    }

    private void rollDown() {
        int from = scroll.getScrollY(), to = overflow();
        if (to <= 0 || isFinishing()) return;
        animateScroll(from, to, Math.round((to - from) * 1000 / (ROLL_SPEED * Ui.uf(1))), () -> handler.postDelayed(rollUp, 2500));
    }

    private void rollUp() {
        if (isFinishing()) return;
        animateScroll(scroll.getScrollY(), 0, 700, () -> handler.postDelayed(rollDown, 2500));
    }

    private void animateScroll(int from, int to, long ms, Runnable then) {
        if (roll != null) roll.cancel();
        roll = android.animation.ValueAnimator.ofInt(from, to);
        roll.setDuration(Math.max(300, ms));
        roll.setInterpolator(from < to ? new android.view.animation.LinearInterpolator()
                : new android.view.animation.DecelerateInterpolator());
        roll.addUpdateListener(a -> scroll.scrollTo(0, (Integer) a.getAnimatedValue()));
        roll.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (!cancelled) then.run();
            }
        });
        roll.start();
    }

    private void stopRoll() {
        handler.removeCallbacksAndMessages(null);
        if (roll != null) roll.cancel();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private View build(String version) {
        FrameLayout root = new FrameLayout(this);
        AuraView aura = new AuraView(this);
        root.addView(aura, new FrameLayout.LayoutParams(-1, -1));
        aura.setTint(0xFFFFB74D);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(Ui.u(36), Ui.u(30), Ui.u(36), Ui.u(28));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x1AFFFFFF);
        bg.setStroke(Math.max(1, Ui.u(1)), 0x26FFFFFF);
        bg.setCornerRadius(Ui.uf(22));
        card.setBackground(bg);
        root.addView(card, new FrameLayout.LayoutParams(Ui.u(620), -2, Gravity.CENTER));

        TextView kicker = Ui.text(this, 12.5f, Ui.medium, 1f);
        kicker.setText(R.string.notice_kicker);
        kicker.setTextColor(0xFFFFB74D);
        kicker.setLetterSpacing(0.12f);
        card.addView(kicker);

        TextView title = Ui.text(this, 34, Ui.medium, 1f);
        title.setText(getString(R.string.notice_title, version));
        title.setGravity(Gravity.CENTER);
        card.addView(title, lp(-2, 10));

        TextView sub = Ui.text(this, 15, Ui.regular, 0.65f);
        sub.setText(getString(R.string.notice_subtitle, SystemInfo.auraVersion(this)));
        card.addView(sub, lp(-2, 6));

        String notes = AppsCatalog.auraNotes(this);
        if (!notes.isEmpty()) {
            // Never taller than this: long notes scroll (Up/Down) instead of covering the buttons.
            final int max = Ui.u(170);
            scroll = new ScrollView(this) {
                @Override
                protected void onMeasure(int w, int h) {
                    super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
                }
            };
            scroll.setFocusable(false);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setVerticalFadingEdgeEnabled(true);
            scroll.setFadingEdgeLength(Ui.u(24));
            TextView n = Ui.text(this, 15, Ui.regular, 0.88f);
            n.setSingleLine(false);
            n.setEllipsize(null);
            n.setLineSpacing(Ui.uf(3), 1f);
            n.setText(notes);
            n.setPadding(0, 0, 0, Ui.u(14)); // The last line clears the bottom fade when the roll stops.
            scroll.addView(n);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
            slp.topMargin = Ui.u(20);
            card.addView(scroll, slp);
            // Starts after a pause to read the first lines; only when the notes do not fit.
            handler.postDelayed(rollDown, 3000);
        }

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        // Room for the focused button to grow; the card itself must clip (the notes scroll inside it).
        buttons.setClipChildren(false);
        buttons.setClipToPadding(false);
        buttons.setPadding(Ui.u(8), Ui.u(4), Ui.u(8), Ui.u(4));
        TextView later = button(R.string.notice_later, false);
        TextView now = button(R.string.notice_now, true);
        buttons.addView(later);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.leftMargin = Ui.u(16);
        buttons.addView(now, nlp);
        card.addView(buttons, lp(-2, 24));

        later.setOnClickListener(v -> finish());
        now.setOnClickListener(v -> {
            startActivity(new Intent(this, UpdateActivity.class).putExtra(UpdateActivity.EXTRA_INSTALL_NOW, true));
            finish();
        });
        later.setNextFocusLeftId(later.getId());
        now.setNextFocusRightId(now.getId());
        now.post(now::requestFocus);

        // Boxes that get system improvements with the update restart once at the end: say it up front.
        if (SystemUpgrade.FIRMWARE.equals(android.os.Build.DISPLAY)) {
            TextView restart = Ui.text(this, 13f, Ui.medium, 0.9f);
            restart.setSingleLine(false);
            restart.setTextColor(0xFFFFB74D);
            restart.setText(R.string.notice_restart);
            card.addView(restart, lp(-2, 16));
        }
        TextView hint = Ui.text(this, 12.5f, Ui.regular, 0.5f);
        hint.setText(R.string.notice_hint);
        card.addView(hint, lp(-2, 10));
        return root;
    }

    private TextView button(int text, boolean primary) {
        final TextView b = Ui.text(this, 16, Ui.medium, 1f);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setPadding(Ui.u(26), Ui.u(11), Ui.u(26), Ui.u(11));
        final GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(Ui.uf(22));
        b.setBackground(d);
        b.setId(View.generateViewId());
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setClickable(true);
        if (Build.VERSION.SDK_INT >= 26) b.setDefaultFocusHighlightEnabled(false);
        final int rest = primary ? 0x33FFB74D : 0x1FFFFFFF;
        d.setColor(rest);
        b.setOnFocusChangeListener((v, has) -> {
            d.setColor(has ? 0xFFF5F5F7 : rest);
            b.setTextColor(has ? 0xFF07080B : Ui.TEXT);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(150).start();
        });
        return b;
    }

    private static LinearLayout.LayoutParams lp(int w, float top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, -2);
        p.topMargin = Ui.u(top);
        return p;
    }
}
