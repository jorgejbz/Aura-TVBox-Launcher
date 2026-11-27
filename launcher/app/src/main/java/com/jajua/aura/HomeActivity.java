package com.jajua.aura;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HomeActivity extends Activity implements Card.Listener {
    /** File managers in order of preference; the first one installed backs the "Archivos" tile. */
    private static final String[] FILE_MANAGERS = {
            "ru.zdevs.zarchiver", "com.lonelycatgames.Xplore",
            "com.ghisler.android.TotalCommander", "com.alphainventor.filemanager"};

    // Card sizes on the 960 x 540 design canvas.
    private static final float FAV_W = 196, FAV_H = 110, FAV_GAP = 20;
    private static final float CELL_W = 128, CELL_H = 72, CELL_GAP = 16;
    private static final float ROW_PAD = 20;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** Separate from {@link #worker}: downloading APKs must not hold up the app list. */
    private final ExecutorService catalog = Executors.newSingleThreadExecutor();
    private final Runnable reloadTask = this::reload;
    /** "¿Eres nuevo?" bubble under "Guía de uso": 5 s shown, 5 s hidden, while the home screen is visible. */
    private static final long HINT_SHOWN_MS = 5000, HINT_HIDDEN_MS = 5000;
    private static final float DATE_ALPHA = 0.72f;
    private View hint;
    private View guidePill;
    private android.graphics.drawable.GradientDrawable guideBg;
    private View helpPill;
    /** Small amber "↑ Aura x.y" in the header while a newer Aura is published (after "Más tarde"). */
    private TextView updatePill;
    /** The start-up notice waits until the home screen is in front (never over another app or the guide). */
    private boolean noticePending;
    /** Start-up warnings shown one at a time once the home screen is in front (after the update notice). */
    private boolean heatPending;
    /** Switched on in the evening or at night (7 p.m. - 8 a.m.; Xuper goes down at 10): say what to use instead. */
    private boolean nightPending;
    /** What "Mejoras del sistema" would install; the floating notice is due while this is set. */
    private List<String> upgradeMissing;
    /** Set when the improvements started by themselves after an Aura update (no separate notice then). */
    private volatile boolean upgradeAfterUpdate;
    private static final int DOWN_FROM = 19, DOWN_TO = 8;
    private static long lastNightShown = -120_000;
    private static long lastUpdateShown = -120_000;
    /** The update notice was asked for by a return from an app (not the start-up check). */
    private boolean noticeOnReturn;
    /** The last screen opened from here was Aura's own (true) or another app (false). */
    private boolean ownScreen;
    private boolean firstStart = true;
    private XuperApps.Target xuperChanged;
    private String xuperChangedVersion;
    private int bootChecks;
    private final Runnable bootUpdateCheck = new Runnable() {
        @Override
        public void run() {
            if (isDestroyed() || !UpdateNoticeActivity.dueThisBoot(HomeActivity.this)) return;
            if (!online()) {
                // The network can take a while after switching on: try for about five minutes.
                if (++bootChecks < 10) main.postDelayed(this, 30_000);
                return;
            }
            final Context app = getApplicationContext();
            catalog.execute(() -> {
                try {
                    AppsCatalog.checkAura(app);
                } catch (Exception e) {
                    if (++bootChecks < 10) main.postDelayed(bootUpdateCheck, 30_000);
                    return;
                }
                main.post(() -> {
                    refreshBadge();
                    if (AppsCatalog.auraNewerVersion(app) != null) {
                        noticePending = true;
                        showNoticeIfHome();
                    }
                });
            });
        }
    };
    private android.graphics.drawable.GradientDrawable helpBg;
    /**
     * At night the hint alternates with "¿Xuper no carga?": from 7 p.m. (just before Crystal Clear and Magis TV go
     * down at 10) until 8 a.m., when they come back.
     */
    private static final int NIGHT_FROM = 19, NIGHT_TO = 8;
    private boolean hintIsHelp;
    private int hintCount;
    private final Runnable hintShow = new Runnable() {
        @Override
        public void run() {
            if (hint == null) return;
            int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            boolean night = hour >= NIGHT_FROM || hour < NIGHT_TO;
            hintIsHelp = night && (hintCount++ % 2 == 1);
            // One line when it fits; otherwise the same words in two lines ("¿Eres nuevo?" / "Consulta aquí →"),
            // e.g. when the "↑ Aura" button takes room on a 720p TV. The text is never shortened.
            TextView h = (TextView) hint;
            String full = getString(hintIsHelp ? R.string.hint_evening : R.string.hint_new_user);
            float room = ((View) h.getParent()).getWidth() - h.getPaddingRight();
            boolean fits = h.getPaint().measureText(full) <= room;
            h.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.uf(fits ? 14 : 12.5f));
            h.setText(fits ? full : full.replaceFirst("\\? ", "?\n"));
            date.animate().alpha(0f).setDuration(350).start();
            hint.animate().alpha(1f).setStartDelay(200).setDuration(450).start();
            hintGlow(true);
            main.postDelayed(hintHide, HINT_SHOWN_MS);
        }
    };
    private final Runnable hintHide = new Runnable() {
        @Override
        public void run() {
            if (hint == null) return;
            hint.animate().alpha(0f).setStartDelay(0).setDuration(350).start();
            date.animate().alpha(DATE_ALPHA).setStartDelay(200).setDuration(450).start();
            hintGlow(false);
            main.postDelayed(hintShow, HINT_HIDDEN_MS);
        }
    };
    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            checkInternet();
            main.postDelayed(this, 60_000);
        }
    };
    /** Connected to the modem but the internet does not answer (checked once a minute while home is visible). */
    private volatile boolean internetDown;

    /** Off the main thread: a tiny HTTPS request to Google's connectivity check (no data about the box). */
    private void checkInternet() {
        final ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        new Thread(() -> {
            boolean down = false;
            @SuppressWarnings("deprecation")
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            if (ni != null && ni.isConnected()) {
                down = !BoxCare.internetReachable();
                if (down) down = !BoxCare.internetReachable(); // A second try before saying so.
            }
            final boolean result = down;
            main.post(() -> {
                if (result != internetDown && !isDestroyed()) {
                    internetDown = result;
                    updateStatus();
                }
            });
        }, "aura-internet").start();
    }

    private AuraView aura;
    private TextView date;
    private TextView status;
    private TextView heroTitle;
    private TextView heroSubtitle;
    private TextView favHint;
    private TextView allLabel;
    private Viewport page;
    private Viewport favViewport;
    private LinearLayout favRow;
    private LinearLayout tileRow;
    private LinearLayout grid;
    private AlertDialog dialog;

    private List<Entry> apps = Collections.emptyList();
    private String focusedKey;
    private Entry pendingHero;
    private int loadGeneration;
    private boolean rendering;

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            main.removeCallbacks(reloadTask);
            main.postDelayed(reloadTask, 400);
        }
    };

    private final BroadcastReceiver clockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(i.getAction())) updateStatus();
            else updateDate();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        KeyMap.importDefaultsOnce(this);
        KeyMap.enableServiceOnce(this);
        // Aura's settings shortcuts only where they help; elsewhere Android would ask "Abrir con…" (1.4.5 on HiSilicon).
        SettingsShortcuts.sync(this);
        AutoUpdateJob.schedule(this);
        setContentView(buildLayout());

        IntentFilter pkg = new IntentFilter();
        pkg.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkg.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkg.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkg.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pkg.addDataScheme("package");
        register(packageReceiver, pkg);

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goToTop);
        }
        reload();
        // Once per boot, until the user ticks "No volver a mostrar" (RecommendationsActivity decides).
        if (RecommendationsActivity.dueAtStart(this)) main.postDelayed(() -> openRecommendations(false, false), 700);
        // Once per boot, a minute in (after the start-up rush): frees space left by abandoned root/adb installs.
        final Context app = getApplicationContext();
        main.postDelayed(() -> new Thread(() -> StaleSessions.run(app), "aura-limpieza").start(), 60_000);
        // "Mejoras del sistema": close ADB if an upgrade opened it before rebooting; then, once per Aura
        // version, offer what an earlier-prepared box is missing (1080p, settings and keyboard style...).
        new Thread(() -> {
            SystemUpgrade.afterBoot(app);
            continueAfterUpdate(app);
        }, "aura-mejoras-update").start();
        // Two minutes in (after the start-up rush and the cleanup): XuperAmigOnlyTV if the box never had it.
        main.postDelayed(() -> new Thread(() -> {
            EssentialApps.ensure(app);
            main.post(this::refreshBadge);
        }, "aura-esenciales").start(), 120_000);
        // The owner's "¿No funciona?" news (GitHub), so the button can show its dot without opening it.
        main.postDelayed(() -> new Thread(() -> {
            if (NoticeBoard.refreshIfStale(app, 0)) main.post(this::refreshBadge);
        }, "aura-avisos").start(), 15_000);
        main.postDelayed(() -> new Thread(this::offerSystemUpgrade, "aura-mejoras").start(), 30_000);
        // Once per boot: a newer Aura on GitHub gets the big notice (and the small header button).
        main.postDelayed(bootUpdateCheck, 8_000);
        // Once per boot: a Xuper that moved away from the recommended version, and restarts that look like heat.
        main.postDelayed(() -> new Thread(() -> checkXupers(app), "aura-xupers").start(), 20_000);
        new Thread(() -> {
            if (BoxCare.waitForClockAndCheckDownHours(app, DOWN_FROM, DOWN_TO)) main.post(() -> {
                nightPending = true;
                showNoticeIfHome();
            });
        }, "aura-horario").start();
        new Thread(() -> {
            if (BoxCare.recordBootAndCheckHeat(app)) main.post(() -> {
                heatPending = true;
                showNoticeIfHome();
            });
        }, "aura-reinicios").start();
    }

    /**
     * {@code manual}: opened with a button, so only "Continuar" (no "No volver a mostrar").
     * {@code care}: the "Recomendaciones" page (care of the box) instead of the usage guide.
     */
    private void openRecommendations(boolean manual, boolean care) {
        if (isFinishing() || isDestroyed()) return;
        startActivity(new Intent(this, RecommendationsActivity.class)
                .putExtra(RecommendationsActivity.EXTRA_MANUAL, manual)
                .putExtra(RecommendationsActivity.EXTRA_CARE, care));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // The remote's buttons: on again if a force-stop (Ajustes or a tool) switched their service off.
        KeyMap.ensureService(this);
        // Coming back from another app (not from one of Aura's own screens, nor the very first start).
        if (!firstStart && !ownScreen) checkNightOnReturn();
        // Best HDMI mode for this TV (a different TV may have been plugged in); only acts when it is not already.
        final Context hdmiApp = getApplicationContext();
        main.postDelayed(() -> new Thread(() -> BoxCare.ensureBestHdmi(hdmiApp), "aura-hdmi").start(),
                firstStart ? 25_000 : 2_000);
        firstStart = false;
        // Apps opened with the remote's buttons (KeyMapService) never pass through here: assume "another app".
        ownScreen = false;
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        f.addAction(Intent.ACTION_DATE_CHANGED);
        f.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        register(clockReceiver, f);
        updateDate();
        main.post(statusTick);
        main.postDelayed(hintShow, 1500);
        checkNewApps();
    }

    /**
     * Once a day, quietly: asks GitHub for a newer Aura and the app folder, and shows what is pending as a
     * number on the "Actualizar" shortcut. Nothing pops up over what the user is doing.
     */
    @SuppressWarnings("deprecation")
    private void checkNewApps() {
        final Context app = getApplicationContext();
        refreshBadge();
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        if (ni == null || !ni.isConnected() || !AppsCatalog.due(app)) return;
        catalog.execute(() -> {
            try {
                AppsCatalog.checkAura(app);
            } catch (Exception ignored) {
                // GitHub unavailable: the apps below may still answer.
            }
            try {
                AppsCatalog.check(app);
            } catch (Exception ignored) {
                // No network or GitHub unavailable: try again tomorrow, or by hand from "Actualizar".
            }
            refreshBadge();
        });
    }

    /** Counts what is pending off the main thread (it asks the package manager) and shows it. */
    private void refreshBadge() {
        final Context app = getApplicationContext();
        catalog.execute(() -> {
            final int[] n = AppsCatalog.pendingCounts(app);
            final String newer = AppsCatalog.auraNewerVersion(app);
            // At most hourly while the box stays on; a change shows the dot right away (one more pass).
            if (NoticeBoard.refreshIfStale(app, 60 * 60 * 1000L)) main.post(this::refreshBadge);
            final boolean news = NoticeBoard.unseenNews(app);
            main.post(() -> {
                if (isDestroyed()) return;
                if (helpPill instanceof TextView) {
                    TextView helpText = (TextView) helpPill;
                    // An amber dot while the owner's latest news in "¿No funciona?" has not been opened.
                    String label = getString(R.string.sched_button);
                    if (news) {
                        android.text.SpannableString s = new android.text.SpannableString("●  " + label);
                        s.setSpan(new android.text.style.ForegroundColorSpan(0xFFFFB74D), 0, 1, 0);
                        helpText.setText(s);
                    } else {
                        helpText.setText(label);
                    }
                }
                if (updatePill != null) {
                    updatePill.setVisibility(newer == null ? View.GONE : View.VISIBLE);
                    if (newer != null) updatePill.setText(getString(R.string.update_pill, newer));
                    // The header changed width: re-fit the date after the next layout.
                    date.post(this::updateDate);
                }
                for (int i = 0; i < tileRow.getChildCount(); i++) {
                    View v = tileRow.getChildAt(i);
                    if (!(v instanceof Card) || !"tile:update".equals(((Card) v).entry.key)) continue;
                    Card card = (Card) v;
                    // An update (amber number) is not the same as an app you could add (lavender "+n",
                    // and the tile says "Apps nuevas"), so nobody takes a new app for an update.
                    if (n[0] > 0) {
                        card.setBadge(n[0]);
                        card.setLabel(getString(R.string.tile_update));
                    } else if (n[1] > 0) {
                        card.setBadge("+" + (n[1] > 9 ? "9" : n[1]), Ui.ACCENT);
                        card.setLabel(getString(R.string.tile_new_apps));
                    } else {
                        card.setBadge(0);
                        card.setLabel(getString(R.string.tile_update));
                    }
                }
            });
        });
    }

    @Override
    protected void onStop() {
        unregisterReceiver(clockReceiver);
        main.removeCallbacks(statusTick);
        main.removeCallbacks(hintShow);
        main.removeCallbacks(hintHide);
        if (hint != null) {
            hint.animate().cancel();
            hint.setAlpha(0f);
            date.animate().cancel();
            date.setAlpha(DATE_ALPHA);
            hintGlow(false);
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        catalog.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: dismiss menus and go back to the top.
        // Coming back from another app keeps the last focused card instead.
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        else if (hasWindowFocus()) goToTop();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // A home screen never closes; Back just returns to the top of the page.
        goToTop();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f instanceof Card) onCardOptions((Card) f);
            return true;
        }
        // Assigned buttons still work on the home screen if the service is turned off.
        String pkg = KeyMap.isReserved(keyCode) ? null : KeyMap.get(this, keyCode);
        if (pkg != null) {
            for (Entry e : apps) {
                if (e.packageName.equals(pkg)) launch(e.intent, e.label, null);
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        // A new TV or HDMI mode changes the resolution: rescale and rebuild in place.
        if (dialog != null) dialog.dismiss();
        Ui.init(this);
        setContentView(buildLayout());
        updateDate();
        updateStatus();
        render(apps, null);
    }

    // ---------------------------------------------------------------- layout

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);
        aura = new AuraView(this);
        root.addView(aura, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setClipChildren(false); // The focused pill grows a little.
        header.setPadding(Ui.u(Ui.SAFE_X), Ui.u(Ui.SAFE_Y), Ui.u(Ui.SAFE_X), Ui.u(4));
        date = Ui.text(this, 15, Ui.regular, DATE_ALPHA);
        // The date shares its place with "¿Eres nuevo? Consulta la Guía de uso →", which points at the pill.
        FrameLayout dateBox = new FrameLayout(this);
        dateBox.addView(date, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL | Gravity.START));
        hint = newUserHint();
        dateBox.addView(hint, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL | Gravity.END));
        header.addView(dateBox, new LinearLayout.LayoutParams(0, -2, 1f));
        header.addView(headerButtons(), margins(0, 0, 12, 0));
        status = Ui.text(this, 13, Ui.regular, 0.55f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.rightMargin = Ui.u(18);
        header.addView(status, slp);
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("H:mm");
        clock.setFormat12Hour("h:mm");
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.uf(24));
        clock.setTypeface(Ui.medium);
        clock.setTextColor(Ui.TEXT);
        clock.setIncludeFontPadding(false);
        header.addView(clock);
        column.addView(header, new LinearLayout.LayoutParams(-1, -2));

        page = new Viewport(this, true);
        column.addView(page, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout content = vertical();
        content.setPadding(Ui.u(Ui.SAFE_X), Ui.u(14), Ui.u(Ui.SAFE_X), Ui.u(48));
        page.addView(content);

        heroTitle = Ui.text(this, 38, Ui.medium, 1f);
        content.addView(heroTitle);
        heroSubtitle = Ui.text(this, 14, Ui.regular, 0.6f);
        content.addView(heroSubtitle, margins(0, 8, 0, 0));

        content.addView(sectionLabel(R.string.section_favorites), margins(0, 26, 0, 12));
        int pad = Ui.u(ROW_PAD);
        favViewport = new Viewport(this, false);
        favRow = new LinearLayout(this);
        favRow.setClipChildren(false);
        favRow.setClipToPadding(false);
        favRow.setPadding(pad, pad, pad, pad);
        favViewport.addView(favRow);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
        flp.setMargins(-pad, -pad, -pad, -pad);
        content.addView(favViewport, flp);
        favHint = Ui.text(this, 14, Ui.regular, 0.55f);
        favHint.setText(R.string.hint_favorites_empty);
        favHint.setVisibility(View.GONE);
        content.addView(favHint);

        tileRow = new LinearLayout(this);
        tileRow.setClipChildren(false);
        content.addView(tileRow, margins(0, 22, 0, 0));

        allLabel = sectionLabel(R.string.section_all);
        content.addView(allLabel, margins(0, 34, 0, 12));
        grid = vertical();
        content.addView(grid);
        return root;
    }

    /**
     * "Guía de uso" and "Recomendaciones": two small pills next to the network status (Up from the first row
     * reaches them). They light up like the cards and open their screens.
     */
    private View headerButtons() {
        LinearLayout pills = new LinearLayout(this);
        pills.setGravity(Gravity.CENTER_VERTICAL);
        pills.setClipChildren(false);
        final TextView guide = pill(R.string.recs_button, R.string.recs_button_desc,
                () -> openRecommendations(true, false));
        TextView care = pill(R.string.care_button, R.string.care_button_desc,
                () -> openRecommendations(true, true));
        TextView hours = pill(R.string.sched_button, R.string.sched_button_desc, () -> {
            if (isFinishing() || isDestroyed()) return;
            startActivity(new Intent(this, RecommendationsActivity.class)
                    .putExtra(RecommendationsActivity.EXTRA_SCHEDULE, true));
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        });
        guidePill = guide;
        guideBg = (android.graphics.drawable.GradientDrawable) guide.getBackground();
        helpPill = hours;
        helpBg = (android.graphics.drawable.GradientDrawable) hours.getBackground();
        updatePill = pill(R.string.update_pill_desc_title, R.string.update_pill_desc,
                () -> startActivity(new Intent(this, UpdateActivity.class)));
        updatePill.setTextColor(0xFFFFB74D);
        android.graphics.drawable.GradientDrawable ub =
                (android.graphics.drawable.GradientDrawable) updatePill.getBackground();
        ub.setStroke(Math.max(1, Ui.u(1)), 0x99FFB74D);
        updatePill.setOnFocusChangeListener(wrapAmber(updatePill, ub, updatePill.getOnFocusChangeListener()));
        updatePill.setVisibility(View.GONE);
        TextView[] all = {guide, care, hours, updatePill};
        for (int i = 0; i < all.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) lp.leftMargin = Ui.u(10);
            pills.addView(all[i], lp);
            // Left/right move along the pills and stop at the ends; nothing above them.
            all[i].setNextFocusLeftId(all[Math.max(0, i - 1)].getId());
            all[i].setNextFocusRightId(all[Math.min(all.length - 1, i + 1)].getId());
        }
        return pills;
    }

    /** Shows the start-up notice only when the home screen itself is in front, once per boot. */
    private void showNoticeIfHome() {
        if (!hasWindowFocus() || isFinishing() || isDestroyed()) return;
        if (dialog != null && dialog.isShowing()) return;
        if (!noticePending) {
            if (upgradeMissing != null) showUpgradeNotice();
            else if (heatPending) showHeatWarning();
            else if (nightPending) showNightNotice();
            else if (xuperChanged != null) showXuperChanged();
            return;
        }
        String newer = AppsCatalog.auraNewerVersion(this);
        noticePending = false;
        boolean again = noticeOnReturn;
        noticeOnReturn = false;
        long now = android.os.SystemClock.elapsedRealtime();
        // Once per boot at start-up; again each time the user comes back from an app (never twice within a minute).
        boolean due = again ? now - lastUpdateShown >= 60_000 : UpdateNoticeActivity.dueThisBoot(this);
        if (newer == null || !due) {
            main.post(this::showNoticeIfHome); // The other notices (night…) still get their turn.
            return;
        }
        lastUpdateShown = now;
        UpdateNoticeActivity.markShown(this);
        startActivity(new Intent(this, UpdateNoticeActivity.class).putExtra(UpdateNoticeActivity.EXTRA_VERSION, newer));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    /** Worker thread: remembers the first Xuper whose version is not the recommended one (once per version per boot). */
    private void checkXupers(Context app) {
        android.content.SharedPreferences p = app.getSharedPreferences("xuper_guard", MODE_PRIVATE);
        int boot = BoxCare.bootCount(app);
        for (XuperApps.Target t : XuperApps.TARGETS) {
            String have = XuperApps.installedVersion(app, t);
            if (have == null || have.equals(t.recommended)) continue;
            String key = t.pkg + ":" + have;
            if (p.getString("shown", "").equals(boot + "|" + key)) continue;
            p.edit().putString("shown", boot + "|" + key).apply();
            main.post(() -> {
                xuperChanged = t;
                xuperChangedVersion = have;
                showNoticeIfHome();
            });
            return;
        }
    }

    private void showXuperChanged() {
        final XuperApps.Target t = xuperChanged;
        final String have = xuperChangedVersion;
        xuperChanged = null;
        String name = getString(t.label);
        dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.guard_title, name))
                .setMessage(getString(R.string.guard_text, name, have, t.recommended))
                .setPositiveButton(getString(R.string.guard_fix, t.recommended), (d, w) -> startActivity(
                        new Intent(this, ReinstallActivity.class).putExtra(ReinstallActivity.EXTRA_PKG, t.pkg)))
                .setNegativeButton(R.string.update_later, null)
                .setOnDismissListener(d -> main.post(this::showNoticeIfHome))
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(Ui.u(600), -2);
    }

    /** The floating start-up notice for the hours when Xuper does not work. */
    private void showNightNotice() {
        nightPending = false;
        if (BoxCare.nightHidden(this)) return;
        // Never twice in a row (start-up check and return from an app can meet).
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastNightShown < 60_000) return;
        lastNightShown = now;

        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.u(24);
        v.setPadding(pad, Ui.u(8), pad, 0);
        TextView msg = Ui.text(this, 14.5f, Ui.regular, 0.9f);
        msg.setSingleLine(false);
        msg.setEllipsize(null);
        msg.setLineSpacing(Ui.uf(2), 1f);
        msg.setText(R.string.night_text);
        v.addView(msg);
        final android.widget.CheckBox hide = new android.widget.CheckBox(this);
        hide.setText(R.string.night_hide);
        hide.setTextColor(Ui.TEXT);
        hide.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.uf(14));
        hide.setButtonTintList(android.content.res.ColorStateList.valueOf(0xFFF5F5F7));
        final android.graphics.drawable.GradientDrawable hb = new android.graphics.drawable.GradientDrawable();
        hb.setCornerRadius(Ui.uf(10));
        hb.setColor(0x00000000);
        hide.setBackground(hb);
        hide.setPadding(Ui.u(4), Ui.u(6), Ui.u(12), Ui.u(6));
        hide.setOnFocusChangeListener((x, has) -> hb.setColor(has ? 0x33FFFFFF : 0x00000000));
        v.addView(hide, margins(0, 12, 0, 0));

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(R.string.night_title)
                .setView(v)
                .setPositiveButton(R.string.heat_ok, null)
                .setNeutralButton(R.string.night_more, (d, w) -> startActivity(
                        new Intent(this, RecommendationsActivity.class)
                                .putExtra(RecommendationsActivity.EXTRA_SCHEDULE, true)))
                .setOnDismissListener(d -> {
                    if (hide.isChecked()) BoxCare.setNightHidden(this);
                    main.post(this::showNoticeIfHome);
                });
        dialog = b.show();
        final AlertDialog night = dialog;
        // "¿Tienes dudas?" right after "Ver recomendaciones", in the dialog's own button style.
        android.widget.Button more = night.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (more != null && more.getParent() instanceof ViewGroup) {
            ViewGroup bar = (ViewGroup) more.getParent();
            android.widget.Button ask = new android.widget.Button(night.getContext(), null,
                    android.R.attr.buttonBarButtonStyle);
            ask.setText(R.string.night_ask);
            ask.setTextColor(more.getTextColors());
            ask.setTextSize(TypedValue.COMPLEX_UNIT_PX, more.getTextSize());
            ask.setTypeface(more.getTypeface());
            ask.setOnClickListener(x -> {
                night.dismiss();
                main.post(this::showSupportQr);
            });
            bar.addView(ask, bar.indexOfChild(more) + 1, new LinearLayout.LayoutParams(-2, -2));
        }
        android.widget.Button ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (ok != null) ok.requestFocus();
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(Ui.u(660), -2);
    }

    /** Floating "¿Tienes dudas?": the support QR (Telegram with the message already written). */
    private void showSupportQr() {
        if (isFinishing() || isDestroyed()) return;
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.u(24);
        row.setPadding(pad, Ui.u(8), pad, Ui.u(4));
        QrCode qr = QrCode.encode(BoxCare.telegramLink(BoxCare.MSG_SUPPORT));
        if (qr != null) {
            android.widget.ImageView img = new android.widget.ImageView(this);
            android.graphics.drawable.BitmapDrawable bd =
                    new android.graphics.drawable.BitmapDrawable(getResources(), qr.toBitmap(4, 3));
            bd.setFilterBitmap(false);
            img.setImageDrawable(bd);
            int side = Ui.u(170);
            row.addView(img, new LinearLayout.LayoutParams(side, side));
        }
        TextView t = Ui.text(this, 15, Ui.regular, 0.9f);
        t.setSingleLine(false);
        t.setEllipsize(null);
        t.setLineSpacing(Ui.uf(3), 1f);
        t.setText(R.string.ask_text);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.leftMargin = Ui.u(20);
        row.addView(t, tlp);
        dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.night_ask)
                .setView(row)
                .setPositiveButton(R.string.info_close, null)
                .setOnDismissListener(d -> main.post(this::showNoticeIfHome))
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(Ui.u(600), -2);
    }

    /** Back on the home screen from another app in the evening or at night: the notice again (unless turned off). */
    private void checkNightOnReturn() {
        // A newer Aura first (big notice), then the night notice.
        if (AppsCatalog.auraNewerVersion(this) != null) {
            noticePending = true;
            noticeOnReturn = true;
        }
        if (BoxCare.inHours(DOWN_FROM, DOWN_TO) && !BoxCare.nightHidden(this)) nightPending = true;
        showNoticeIfHome();
        // A new Aura published while the box stayed on: ask GitHub again (at most every 30 minutes),
        // and if there is one, the big notice ("Actualizar ahora" / "Más tarde") shows right away.
        if (noticePending || !online()) return;
        final Context app = getApplicationContext();
        catalog.execute(() -> {
            try {
                AppsCatalog.checkAuraIfStale(app, 30 * 60 * 1000L);
            } catch (Exception e) {
                return; // GitHub unavailable: the next return tries again.
            }
            if (AppsCatalog.auraNewerVersion(app) == null) return;
            main.post(() -> {
                refreshBadge();
                noticePending = true;
                noticeOnReturn = true;
                showNoticeIfHome();
            });
        });
    }

    /** Aura's own screens (guide, Sistema, updates…) are not "leaving to an app". */
    @Override
    public void startActivity(Intent intent, Bundle options) {
        android.content.ComponentName cn = intent.getComponent();
        ownScreen = cn != null && getPackageName().equals(cn.getPackageName());
        super.startActivity(intent, options);
    }

    private Entry findApp(String pkg) {
        for (Entry e : apps) if (pkg.equals(e.packageName)) return e;
        return null;
    }

    private void showHeatWarning() {
        heatPending = false;
        dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.heat_title)
                .setMessage(R.string.heat_text)
                .setPositiveButton(R.string.heat_ok, null)
                .setNeutralButton(R.string.care_button, (d, w) -> openRecommendations(true, true))
                .setOnDismissListener(d -> main.post(this::showNoticeIfHome))
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(Ui.u(600), -2);
    }

    /** "Liberar espacio" in Sistema: clears the apps' caches and says how much was freed. */
    private View freeSpaceButton() {
        final TextView b = Ui.text(this, 14, Ui.medium, 1f);
        b.setText(R.string.free_space);
        b.setPadding(Ui.u(12), Ui.u(6), Ui.u(12), Ui.u(6));
        final android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(Ui.uf(10));
        bg.setColor(0x1AFFFFFF);
        b.setBackground(bg);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setClickable(true);
        b.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xFFF5F5F7 : 0x1AFFFFFF);
            b.setTextColor(has ? 0xFF07080B : Ui.TEXT);
        });
        b.setOnClickListener(v -> {
            b.setEnabled(false);
            b.setText(R.string.free_space_working);
            final Context app = getApplicationContext();
            new Thread(() -> {
                long freed = BoxCare.freeSpace(app);
                main.post(() -> {
                    if (isDestroyed()) return;
                    Toast.makeText(this, freed > 1024 * 1024 ? getString(R.string.free_space_done, SystemInfo.size(freed))
                            : getString(R.string.free_space_none), Toast.LENGTH_LONG).show();
                    updateStatus();
                    if (dialog != null && dialog.isShowing()) {
                        dialog.dismiss();
                        showSystemInfo();
                    }
                });
            }, "aura-espacio").start();
        });
        return b;
    }

    @SuppressWarnings("deprecation")
    private boolean online() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    /** The update pill keeps its amber look when it loses the focus (the shared pill style would make it grey). */
    private static View.OnFocusChangeListener wrapAmber(final TextView pill,
            final android.graphics.drawable.GradientDrawable bg, final View.OnFocusChangeListener base) {
        return (v, has) -> {
            if (base != null) base.onFocusChange(v, has);
            if (!has) pill.setTextColor(0xFFFFB74D);
        };
    }

    /** "¿Eres nuevo? Consulta la Guía de uso →": never takes focus, only fades in and out. */
    private View newUserHint() {
        TextView t = Ui.text(this, 14, Ui.medium, 1f);
        t.setText(R.string.hint_new_user);
        t.setTextColor(0xE6F5F5F7);
        t.setAlpha(0f);
        t.setFocusable(false);
        t.setPadding(0, 0, Ui.u(10), 0);
        t.setSingleLine(false);
        t.setMaxLines(2);
        t.setEllipsize(null);
        t.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        t.setLineSpacing(0f, 0.92f);
        return t;
    }

    /**
     * While a hint shows, the border of the pill it points at brightens a little ("Guía de uso", or "¿No funciona?"
     * for the evening one). Off clears both. A focused pill keeps its own look.
     */
    private void hintGlow(boolean on) {
        glow(guidePill, guideBg, on && !hintIsHelp);
        glow(helpPill, helpBg, on && hintIsHelp);
    }

    private static void glow(View pill, android.graphics.drawable.GradientDrawable bg, boolean on) {
        if (bg == null || pill == null || pill.hasFocus()) return;
        bg.setStroke(Math.max(1, Ui.u(on ? 1.5f : 1f)), on ? 0xB3FFFFFF : 0x33FFFFFF);
    }

    private TextView pill(int label, int desc, Runnable open) {
        final TextView b = Ui.text(this, 12.5f, Ui.medium, 1f);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setPadding(Ui.u(12), Ui.u(5), Ui.u(12), Ui.u(5));
        final android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(Ui.uf(14));
        bg.setColor(0x14FFFFFF);
        bg.setStroke(Math.max(1, Ui.u(1)), 0x33FFFFFF);
        b.setBackground(bg);
        b.setTextColor(0xB3F5F5F7);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setClickable(true);
        if (Build.VERSION.SDK_INT >= 26) b.setDefaultFocusHighlightEnabled(false);
        b.setId(View.generateViewId());
        b.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xFFF5F5F7 : 0x14FFFFFF);
            b.setTextColor(has ? 0xFF07080B : 0xB3F5F5F7);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(150).start();
            if (has) {
                aura.setTint(Ui.ACCENT);
                showHero(new Entry("btn:" + label, null, getString(label), getString(desc), null, false));
            }
        });
        b.setOnClickListener(v -> open.run());
        b.setNextFocusUpId(b.getId());
        return b;
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setClipChildren(false);
        l.setClipToPadding(false);
        return l;
    }

    private TextView sectionLabel(int res) {
        TextView t = Ui.text(this, 13, Ui.medium, 0.5f);
        t.setText(getString(res).toUpperCase(Locale.getDefault()));
        t.setLetterSpacing(0.08f);
        return t;
    }

    private static LinearLayout.LayoutParams margins(float l, float t, float r, float b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(Ui.u(l), Ui.u(t), Ui.u(r), Ui.u(b));
        return lp;
    }

    // ---------------------------------------------------------------- data

    private void reload() {
        final int gen = ++loadGeneration;
        final Context app = getApplicationContext();
        worker.execute(() -> {
            List<Entry> loaded = AppLoader.load(app);
            main.post(() -> {
                if (gen == loadGeneration && !isDestroyed()) render(loaded, null);
            });
        });
    }

    /** Rebuilds every row. {@code prefer} is the row to restore focus into first. */
    private void render(List<Entry> loaded, ViewGroup prefer) {
        // Removing the focused card makes Android hand focus to some other card; remember the
        // real one and ignore those interim focus changes until the rows are rebuilt.
        final String keep = focusedKey;
        rendering = true;
        apps = loaded;
        Map<String, Entry> byPackage = new LinkedHashMap<>();
        for (Entry e : apps) byPackage.put(e.packageName, e);

        favRow.removeAllViews();
        List<String> favs = Favorites.get(this, byPackage.keySet());
        for (int i = 0; i < favs.size(); i++) {
            Card c = new Card(this, byPackage.get(favs.get(i)), Ui.u(FAV_W), Ui.u(FAV_H), 14, true, null, this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) lp.leftMargin = Ui.u(FAV_GAP);
            favRow.addView(c, lp);
        }
        favViewport.setVisibility(favs.isEmpty() ? View.GONE : View.VISIBLE);
        favHint.setVisibility(favs.isEmpty() && !apps.isEmpty() ? View.VISIBLE : View.GONE);

        tileRow.removeAllViews();
        List<Entry> tiles = buildTiles(byPackage);
        for (int i = 0; i < tiles.size(); i++) {
            Entry t = tiles.get(i);
            int glyph;
            switch (t.key) {
                case "tile:files": glyph = R.drawable.ic_folder; break;
                case "tile:settings": glyph = R.drawable.ic_settings; break;
                case "tile:network": glyph = R.drawable.ic_network; break;
                case "tile:keys": glyph = R.drawable.ic_remote; break;
                case "tile:update": glyph = R.drawable.ic_update; break;
                default: glyph = R.drawable.ic_info; break;
            }
            Card c = new Card(this, t, Ui.u(CELL_W), Ui.u(CELL_H), 12, false, getDrawable(glyph), this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) lp.leftMargin = Ui.u(CELL_GAP);
            tileRow.addView(c, lp);
        }

        // Apps that already have a shortcut tile (Archivos, Ajustes, Red) are left out of the grid.
        java.util.Set<String> tilePkgs = new java.util.HashSet<>();
        for (Entry t : tiles) {
            if (t.packageName != null) tilePkgs.add(t.packageName);
        }
        List<Entry> shown = new ArrayList<>();
        for (Entry e : apps) {
            if (!tilePkgs.contains(e.packageName)) shown.add(e);
        }

        grid.removeAllViews();
        int avail = getResources().getDisplayMetrics().widthPixels - 2 * Ui.u(Ui.SAFE_X);
        int cols = Math.max(3, (avail + Ui.u(CELL_GAP)) / (Ui.u(CELL_W) + Ui.u(CELL_GAP)));
        LinearLayout row = null;
        for (int i = 0; i < shown.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
                if (i > 0) rlp.topMargin = Ui.u(20);
                grid.addView(row, rlp);
            }
            Card c = new Card(this, shown.get(i), Ui.u(CELL_W), Ui.u(CELL_H), 12, false, null, this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i % cols > 0) lp.leftMargin = Ui.u(CELL_GAP);
            row.addView(c, lp);
        }
        allLabel.setVisibility(apps.isEmpty() ? View.GONE : View.VISIBLE);

        lockRowEnds(favRow);
        lockRowEnds(tileRow);
        for (int i = 0; i < grid.getChildCount(); i++) lockRowEnds((ViewGroup) grid.getChildAt(i));

        rendering = false;
        focusedKey = keep;
        restoreFocus(prefer);
        refreshBadge(); // The tiles were rebuilt; an install or removal may also change the count.
    }

    /**
     * Worker thread, once per boot: if this box still lacks system improvements, queue the floating
     * notice ("Instalar ahora" / "Más tarde"). After "Más tarde" it comes back on the next boot; once
     * installed there is nothing missing and it never shows again.
     */
    private void offerSystemUpgrade() {
        Context app = getApplicationContext();
        // One notice at a time: a newer Aura comes first, and updating it installs these too.
        if (upgradeAfterUpdate || AppsCatalog.auraNewerVersion(app) != null) return;
        android.content.SharedPreferences p = app.getSharedPreferences("system_upgrade", MODE_PRIVATE);
        int boot = BoxCare.bootCount(app);
        if (boot >= 0 && p.getInt("notice_boot", -1) == boot) return;
        SystemUpgrade.Manifest m = SystemUpgrade.manifest();
        if (m == null) return;
        List<String> missing = SystemUpgrade.missing(app, m);
        if (missing.isEmpty()) return;
        p.edit().putInt("notice_boot", boot).apply();
        main.post(() -> {
            upgradeMissing = missing;
            showNoticeIfHome();
        });
    }

    /**
     * Worker thread. Right after "Actualizar" installed this Aura, the system improvements follow by
     * themselves: the update notice (and the release notes on older Auras) already said the box would
     * restart once. Nothing happens if nothing is missing.
     */
    private void continueAfterUpdate(Context app) {
        if (!SystemUpgrade.justUpdated(app)) return;
        SystemUpgrade.Manifest m = SystemUpgrade.manifest();
        if (m == null || SystemUpgrade.missing(app, m).isEmpty()) return;
        upgradeAfterUpdate = true;
        main.postDelayed(() -> {
            if (isDestroyed()) return;
            startActivity(new Intent(this, SystemUpgradeActivity.class)
                    .putExtra(SystemUpgradeActivity.EXTRA_NOW, true)
                    .putExtra(SystemUpgradeActivity.EXTRA_AFTER_UPDATE, true));
        }, 3_000);
    }

    /** The floating notice for "Mejoras del sistema", in the same style as the night notice. */
    private void showUpgradeNotice() {
        List<String> missing = upgradeMissing;
        upgradeMissing = null;
        StringBuilder list = new StringBuilder();
        for (String id : missing) list.append("•  ").append(SystemUpgrade.label(this, id)).append('\n');

        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(Ui.u(24), Ui.u(8), Ui.u(24), 0);
        TextView msg = Ui.text(this, 14.5f, Ui.regular, 0.9f);
        msg.setSingleLine(false);
        msg.setEllipsize(null);
        msg.setLineSpacing(Ui.uf(2), 1f);
        msg.setText(getString(R.string.upn_text, list.toString().trim()));
        v.addView(msg);

        dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.upn_title)
                .setView(v)
                .setPositiveButton(R.string.upn_install, (d, w) -> startActivity(
                        new Intent(this, SystemUpgradeActivity.class).putExtra(SystemUpgradeActivity.EXTRA_NOW, true)))
                .setNegativeButton(R.string.upn_later, null)
                .setOnDismissListener(d -> main.post(this::showNoticeIfHome))
                .show();
        android.widget.Button ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (ok != null) ok.requestFocus();
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(Ui.u(600), -2);
    }

    /**
     * Left on the first card and right on the last one stay put, as on Apple TV. Without this
     * Android searches "anything to the right" and jumps into a different row.
     */
    private static void lockRowEnds(ViewGroup row) {
        int n = row.getChildCount();
        if (n == 0) return;
        View first = row.getChildAt(0), last = row.getChildAt(n - 1);
        first.setNextFocusLeftId(first.getId());
        last.setNextFocusRightId(last.getId());
    }

    /** System shortcuts, only when something on the device can actually handle them. */
    private List<Entry> buildTiles(Map<String, Entry> byPackage) {
        List<Entry> tiles = new ArrayList<>();
        for (String pkg : FILE_MANAGERS) {
            Entry fm = byPackage.get(pkg);
            if (fm != null) {
                tiles.add(new Entry("tile:files", pkg, getString(R.string.tile_files),
                        getString(R.string.tile_files_desc), fm.intent, false));
                break;
            }
        }
        // Some firmwares ship both the phone and the TV settings: use the TV one, and only show it once.
        Entry tvSettings = byPackage.get("com.android.tv.settings");
        if (tvSettings != null) {
            // Aura's Ajustes panel (most used settings first), which opens the TV settings' panels.
            tiles.add(new Entry("tile:settings", tvSettings.packageName, getString(R.string.tile_settings),
                    getString(R.string.tile_settings_desc), new Intent(this, SettingsPanel.class), false));
        } else {
            addIfResolvable(tiles, "tile:settings", R.string.tile_settings, R.string.tile_settings_desc,
                    new Intent(Settings.ACTION_SETTINGS));
        }
        // The TV settings' network side panel if the device has it, not the full-screen phone page.
        Intent network = new Intent(Settings.ACTION_WIFI_SETTINGS)
                .addCategory("android.intent.category.LEANBACK_SETTINGS");
        if (network.resolveActivity(getPackageManager()) != null) {
            addIfResolvable(tiles, "tile:network", R.string.tile_network, R.string.tile_network_desc, network);
        } else {
            addIfResolvable(tiles, "tile:network", R.string.tile_network, R.string.tile_network_desc,
                    new Intent(Settings.ACTION_WIFI_SETTINGS));
        }
        // Handled inside Aura, so they never depend on the (possibly patched) system screens.
        tiles.add(new Entry("tile:keys", null, getString(R.string.tile_keys),
                getString(R.string.tile_keys_desc), null, false));
        tiles.add(new Entry("tile:info", null, getString(R.string.tile_info),
                getString(R.string.tile_info_desc), null, false));
        tiles.add(new Entry("tile:update", null, getString(R.string.tile_update),
                getString(R.string.tile_update_desc), null, false));
        return tiles;
    }

    private void addIfResolvable(List<Entry> out, String key, int label, int desc, Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        android.content.ComponentName target = i.resolveActivity(getPackageManager());
        if (target == null) return;
        // Remember which app handles it, so that app is not repeated in "Todas las apps".
        out.add(new Entry(key, target.getPackageName(), getString(label), getString(desc), i, false));
    }

    // ---------------------------------------------------------------- focus

    private void restoreFocus(ViewGroup prefer) {
        Card target = null;
        if (focusedKey != null) {
            if (prefer != null) target = findCard(prefer, focusedKey);
            if (target == null) target = findCard(favRow, focusedKey);
            if (target == null) target = findCard(tileRow, focusedKey);
            if (target == null) target = findCard(grid, focusedKey);
        }
        if (target == null) target = firstCard();
        if (target == null) return;
        target.requestFocus();
        final Card t = target;
        // Wait for layout so the viewports can measure where the card is.
        page.post(() -> {
            if (isInside(t, favRow)) favViewport.reveal(t, false);
            page.reveal(t, false);
        });
    }

    private Card firstCard() {
        Card c = findCard(favRow, null);
        if (c == null) c = findCard(tileRow, null);
        if (c == null) c = findCard(grid, null);
        return c;
    }

    /** Depth-first search for a card with {@code key}, or the first card when key is null. */
    private static Card findCard(ViewGroup group, String key) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View v = group.getChildAt(i);
            if (v instanceof Card) {
                if (key == null || ((Card) v).entry.key.equals(key)) return (Card) v;
            } else if (v instanceof ViewGroup) {
                Card c = findCard((ViewGroup) v, key);
                if (c != null) return c;
            }
        }
        return null;
    }

    private static boolean isInside(View v, ViewGroup ancestor) {
        for (Object p = v.getParent(); p instanceof View; p = ((View) p).getParent()) {
            if (p == ancestor) return true;
        }
        return false;
    }

    private void goToTop() {
        focusedKey = null;
        page.reset();
        favViewport.reset();
        Card c = firstCard();
        if (c != null) c.requestFocus();
    }

    @Override
    public void onCardFocused(Card card) {
        if (rendering) return;
        focusedKey = card.entry.key;
        if (isInside(card, favRow)) favViewport.reveal(card, true);
        page.reveal(card, true);
        aura.setTint(card.entry.tint);
        showHero(card.entry);
    }

    private void showHero(Entry e) {
        pendingHero = e;
        heroTitle.animate().cancel();
        heroTitle.animate().alpha(0f).setDuration(80).withEndAction(() -> {
            Entry h = pendingHero;
            heroTitle.setText(h.label);
            heroSubtitle.setText(h.subtitle != null ? h.subtitle : getString(R.string.hint_open));
            heroTitle.animate().alpha(1f).setDuration(160).start();
        }).start();
    }

    // ---------------------------------------------------------------- actions

    @Override
    public void onCardClicked(Card card) {
        if (card.entry.key.equals("tile:info")) showSystemInfo();
        else if (card.entry.key.equals("tile:keys")) showKeyMap();
        else if (card.entry.key.equals("tile:update")) startActivity(new Intent(this, UpdateActivity.class));
        else launch(card.entry.intent, card.entry.label, card.face);
    }

    // ---------------------------------------------------------------- remote buttons

    private void showKeyMap() {
        final List<CharSequence> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        labels.add(getString(R.string.keys_tutorial));
        actions.add(this::showKeysTutorial);
        labels.add(getString(R.string.keys_assign));
        actions.add(this::captureKey);
        for (Map.Entry<Integer, String> m : KeyMap.all(this).entrySet()) {
            final int code = m.getKey();
            labels.add(getString(R.string.keys_entry, KeyMap.keyName(code), appLabel(m.getValue())));
            actions.add(() -> confirmRemoveKey(code));
        }
        if (!KeyMap.serviceEnabled(this)) {
            labels.add(getString(R.string.keys_enable));
            actions.add(() -> launch(SettingsShortcuts.route(this, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), getString(R.string.keys_enable), null));
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(R.string.keys_title)
                .setItems(labels.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run());
        if (!KeyMap.serviceEnabled(this)) b.setMessage(R.string.keys_disabled_note);
        dialog = b.show();
    }

    /**
     * "¿Cómo funciona?": the box's remote (photo) with the coloured buttons outlined, and the steps to give each one
     * an app. Back to the Botones list when closed.
     */
    private void showKeysTutorial() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.u(22);
        row.setPadding(pad, Ui.u(6), pad, 0);

        // The photo, with an amber outline over the YTB / PVD / GOG / DIN row (measured on remote_photo.jpg).
        final int photoH = Ui.u(300), photoW = Math.round(photoH * 408f / 1160f);
        FrameLayout photo = new FrameLayout(this);
        android.widget.ImageView img = new android.widget.ImageView(this);
        img.setImageResource(R.drawable.remote_photo);
        img.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        photo.addView(img, new FrameLayout.LayoutParams(photoW, photoH));
        View ring = new View(this);
        android.graphics.drawable.GradientDrawable rd = new android.graphics.drawable.GradientDrawable();
        rd.setCornerRadius(Ui.uf(8));
        rd.setStroke(Math.max(2, Ui.u(2.5f)), 0xFFFFB74D);
        ring.setBackground(rd);
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(Math.round(photoW * 0.80f),
                Math.round(photoH * 0.072f));
        rlp.leftMargin = Math.round(photoW * 0.10f);
        rlp.topMargin = Math.round(photoH * 0.110f);
        photo.addView(ring, rlp);
        // A slow pulse draws the eye to those buttons without getting in the way.
        android.animation.ObjectAnimator pulse = android.animation.ObjectAnimator.ofFloat(ring, "alpha", 1f, 0.25f);
        pulse.setDuration(900);
        pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        pulse.start();
        row.addView(photo);

        TextView steps = Ui.text(this, 14.5f, Ui.regular, 0.92f);
        steps.setSingleLine(false);
        steps.setEllipsize(null);
        steps.setLineSpacing(Ui.uf(3), 1f);
        steps.setText(R.string.keys_tutorial_text);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, -2, 1f);
        slp.leftMargin = Ui.u(22);
        row.addView(steps, slp);

        dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.keys_tutorial_title)
                .setView(row)
                .setPositiveButton(R.string.keys_tutorial_go, (d, w) -> captureKey())
                .setNegativeButton(R.string.info_close, (d, w) -> showKeyMap())
                .setOnDismissListener(d -> pulse.cancel())
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(Ui.u(720), -2);
    }

    /** Waits for one remote button. The service sees it first (even keys the firmware grabs). */
    private void captureKey() {
        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(R.string.keys_title)
                .setMessage(R.string.keys_press)
                .create();
        final KeyMapService.CaptureListener l = code -> {
            if (d.isShowing()) {
                d.dismiss();
                pickAppForKey(code);
            }
        };
        // Fallback when the service is off: the dialog itself receives the key.
        d.setOnKeyListener((di, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_UP) return code != KeyEvent.KEYCODE_BACK;
            if (code == KeyEvent.KEYCODE_BACK) {
                di.dismiss();
            } else if (KeyMap.isReserved(code)) {
                Toast.makeText(this, R.string.keys_reserved, Toast.LENGTH_SHORT).show();
            } else {
                l.onKeyCaptured(code);
            }
            return true;
        });
        d.setOnDismissListener(di -> KeyMapService.capture = null);
        KeyMapService.capture = l;
        dialog = d;
        d.show();
    }

    private void pickAppForKey(final int code) {
        final List<Entry> choices = new ArrayList<>(apps);
        CharSequence[] names = new CharSequence[choices.size()];
        for (int i = 0; i < names.length; i++) names[i] = choices.get(i).label;
        dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.keys_pick_app, KeyMap.keyName(code)))
                .setItems(names, (d, which) -> {
                    KeyMap.set(this, code, choices.get(which).packageName);
                    showKeyMap();
                })
                .show();
    }

    private void confirmRemoveKey(final int code) {
        dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.keys_entry, KeyMap.keyName(code), appLabel(KeyMap.get(this, code))))
                .setItems(new CharSequence[] {getString(R.string.keys_remove)}, (d, which) -> {
                    KeyMap.set(this, code, null);
                    showKeyMap();
                })
                .show();
    }

    private CharSequence appLabel(String pkg) {
        for (Entry e : apps) {
            if (e.packageName.equals(pkg)) return e.label;
        }
        return pkg;
    }

    private void showSystemInfo() {
        long[] mem = SystemInfo.memory();
        long[] disk = SystemInfo.storage();
        String android = SystemInfo.androidVersion();
        String claimed = SystemInfo.claimedRelease();

        // Two columns, so every row fits on a 720p TV; scrollable in case a firmware uses big fonts.
        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        int pad = Ui.u(20);
        cols.setPadding(pad, Ui.u(4), pad, Ui.u(8));
        LinearLayout list = vertical(), right = vertical();
        cols.addView(list, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1f);
        rp.leftMargin = Ui.u(24);
        cols.addView(right, rp);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(cols);
        // "7.1.2" is a truthful claim for "Android 7.1 (API 25)"; only flag a different version.
        String real = android.substring("Android ".length(), android.indexOf(" ("));
        String claimedNote = claimed.equals(real) || claimed.startsWith(real + ".") ? null
                : getString(R.string.info_claimed, claimed);
        infoRow(list, R.string.info_android, android, claimedNote);
        infoRow(list, R.string.info_chip, SystemInfo.chip(), null);
        infoRow(list, R.string.info_cpu, SystemInfo.cpu(), null);
        infoRow(list, R.string.info_ram, getString(R.string.info_of,
                SystemInfo.size(mem[1]), SystemInfo.size(mem[0])), null);
        infoRow(list, R.string.info_storage, getString(R.string.info_of,
                SystemInfo.size(disk[1]), SystemInfo.size(disk[0])), null);
        final View freeBtn = freeSpaceButton();
        freeBtn.setId(View.generateViewId());
        list.addView(freeBtn, margins(0, 8, 0, 0));
        infoRow(right, R.string.info_display, SystemInfo.display(this), null);
        infoRow(right, R.string.info_ip, SystemInfo.ipAddress(this), null);
        // Which boxes still need Actualizar-Aura-TVBox.bat: the /system copy decides the install permission.
        String mine = SystemInfo.auraVersion(this), sys = SystemInfo.auraSystemVersion(this);
        infoRow(right, R.string.info_aura, sys == null || sys.equals(mine) ? mine
                : getString(R.string.info_aura_system, mine, sys), null);
        // The one choice in this window: only meaningful when Aura can install without Android's screen.
        // "Texto más grande": rebuilds the home screen at once (Aura's texts grow 15 %).
        View big = settingSwitch(R.string.info_big_text, Ui.bigText(this), on -> {
            Ui.setBigText(this, on);
            main.postDelayed(() -> {
                if (dialog != null) dialog.dismiss();
                Ui.init(this);
                setContentView(buildLayout());
                updateDate();
                updateStatus();
                render(apps, null);
                showSystemInfo();
            }, 250);
        });
        big.setId(View.generateViewId());
        // Left/right go straight between the controls of this window.
        big.setNextFocusLeftId(freeBtn.getId());
        freeBtn.setNextFocusRightId(big.getId());
        if (Installer.quiet(this)) {
            View sw = autoUpdateSwitch();
            sw.setId(View.generateViewId());
            sw.setNextFocusLeftId(freeBtn.getId());
            right.addView(sw, switchParams(12));
        }
        right.addView(big, switchParams(4));
        final int adbPort = SystemInfo.adbNetworkPort();
        infoRow(right, R.string.info_adb, adbPort > 0 ? getString(R.string.adb_open, adbPort)
                : getString(R.string.adb_closed), adbPort > 0 ? getString(R.string.adb_open_note) : null);

        dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.info_title)
                .setView(scroll)
                .setPositiveButton(R.string.info_close, null)
                .setNegativeButton(adbPort > 0 ? R.string.adb_turn_off : R.string.adb_turn_on,
                        (d, w) -> toggleAdb(adbPort == 0))
                .setNeutralButton(R.string.update_check, (d, w) -> startActivity(
                        new Intent(this, UpdateActivity.class)))
                .show();
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(Ui.u(760), -2);
        addReinstallButton(dialog);
    }

    /**
     * "Reinstalar Xuper's", right after "Buscar actualizaciones" in the dialog's own button bar (an alert
     * dialog has only three buttons), with the very same button style.
     */
    private void addReinstallButton(AlertDialog d) {
        android.widget.Button neutral = d.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (neutral == null || !(neutral.getParent() instanceof ViewGroup)) return;
        ViewGroup bar = (ViewGroup) neutral.getParent();
        android.widget.Button b = new android.widget.Button(d.getContext(), null,
                android.R.attr.buttonBarButtonStyle);
        b.setText(R.string.xr_button);
        b.setTextColor(neutral.getTextColors());
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, neutral.getTextSize());
        b.setTypeface(neutral.getTypeface());
        b.setOnClickListener(v -> {
            d.dismiss();
            startActivity(new Intent(this, ReinstallActivity.class));
        });
        bar.addView(b, bar.indexOfChild(neutral) + 1,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Shows the port's real state afterwards: a firmware can refuse the change. */
    private void toggleAdb(boolean on) {
        SystemInfo.setAdbNetwork(this, on);
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (on == (SystemInfo.adbNetworkPort() == 0)) {
                Toast.makeText(this, R.string.adb_refused, Toast.LENGTH_LONG).show();
            }
            updateStatus();
            showSystemInfo();
        }, 2500);
        // adbd can take a few more seconds to listen again: the home status line must not wait a minute.
        main.postDelayed(this::updateStatus, 8000);
    }

    /** Back from a dialog or another screen: refresh the status line (ADB, network, space) at once. */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && status != null) updateStatus();
        if (hasFocus) showNoticeIfHome();
    }

    /** "Actualizar apps de madrugada": reachable with the arrows, OK switches it, saved at once. */
    private View autoUpdateSwitch() {
        return settingSwitch(R.string.info_auto_update, AutoUpdateJob.enabled(this),
                on -> AutoUpdateJob.setEnabled(this, on));
    }

    /** Full column width, so the toggles of the Sistema window line up. */
    private static LinearLayout.LayoutParams switchParams(float top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.u(top);
        return lp;
    }

    interface OnSwitch {
        void changed(boolean on);
    }

    /** A switch row of the Sistema window: reachable with the arrows, OK switches it, saved at once. */
    private android.widget.Switch settingSwitch(int text, boolean checked, OnSwitch onChange) {
        android.widget.Switch s = new android.widget.Switch(this);
        s.setText(text);
        s.setTextColor(Ui.TEXT);
        s.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.textPx(15));
        s.setTypeface(Ui.medium);
        s.setChecked(checked);
        s.setFocusable(true);
        int p = Ui.u(8);
        s.setPadding(p, p, p, p);
        android.graphics.drawable.GradientDrawable focus = new android.graphics.drawable.GradientDrawable();
        focus.setCornerRadius(Ui.uf(8));
        focus.setColor(0x00000000);
        s.setBackground(focus);
        s.setOnFocusChangeListener((v, has) -> focus.setColor(has ? 0x33FFFFFF : 0x00000000));
        s.setOnCheckedChangeListener((b, on) -> onChange.changed(on));
        return s;
    }

    private void infoRow(LinearLayout list, int label, String value, String note) {
        TextView l = Ui.text(this, 12, Ui.regular, 0.55f);
        l.setText(label);
        list.addView(l, margins(0, 12, 0, 2));
        TextView v = Ui.text(this, 17, Ui.medium, 1f);
        v.setText(value);
        list.addView(v);
        if (note != null) {
            TextView n = Ui.text(this, 12, Ui.regular, 0.55f);
            n.setSingleLine(false); // Notes explain; they may take two lines in a column.
            n.setText(note);
            list.addView(n, margins(0, 2, 0, 0));
        }
    }

    private void launch(Intent intent, CharSequence label, View from) {
        try {
            Bundle opts = from == null ? null : ActivityOptions.makeScaleUpAnimation(
                    from, 0, 0, from.getWidth(), from.getHeight()).toBundle();
            startActivity(intent, opts);
        } catch (ActivityNotFoundException | SecurityException ex) {
            Toast.makeText(this, getString(R.string.err_launch, label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onCardOptions(Card card) {
        final Entry e = card.entry;
        if (!e.isApp()) return;
        final ViewGroup area = isInside(card, favRow) ? favRow : grid;

        Map<String, Entry> byPackage = new LinkedHashMap<>();
        for (Entry a : apps) byPackage.put(a.packageName, a);
        final List<String> favs = new ArrayList<>(Favorites.get(this, byPackage.keySet()));
        final int idx = favs.indexOf(e.packageName);

        List<CharSequence> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (idx < 0) {
            labels.add(getString(R.string.opt_add_fav));
            actions.add(() -> saveFavorites(favs, e.packageName, -1, area));
        } else {
            if (idx > 0) {
                labels.add(getString(R.string.opt_move_left));
                actions.add(() -> saveFavorites(favs, e.packageName, idx - 1, area));
            }
            if (idx < favs.size() - 1) {
                labels.add(getString(R.string.opt_move_right));
                actions.add(() -> saveFavorites(favs, e.packageName, idx + 1, area));
            }
            labels.add(getString(R.string.opt_remove_fav));
            actions.add(() -> {
                favs.remove(e.packageName);
                Favorites.set(this, favs);
                render(apps, grid);
            });
        }
        labels.add(getString(R.string.opt_info));
        actions.add(() -> launch(SettingsShortcuts.route(this, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", e.packageName, null)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                e.label, null));
        if (e.removable) {
            labels.add(getString(R.string.opt_uninstall));
            actions.add(() -> launch(new Intent(Intent.ACTION_DELETE,
                    Uri.fromParts("package", e.packageName, null)), e.label, null));
        }

        dialog = new AlertDialog.Builder(this)
                .setTitle(e.label)
                .setItems(labels.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    /** Adds {@code pkg} at the end (position -1) or moves it to {@code position}. */
    private void saveFavorites(List<String> favs, String pkg, int position, ViewGroup area) {
        favs.remove(pkg);
        if (position < 0) favs.add(pkg);
        else favs.add(position, pkg);
        Favorites.set(this, favs);
        render(apps, area);
    }

    // ---------------------------------------------------------------- header

    private void updateDate() {
        String full = formatDate("EEEEdMMMM");
        View box = (View) date.getParent();
        // "Jueves, 26 de noviembre" when it fits; "Jue, 26 nov" when the header is crowded ("↑ Aura" on 720p).
        date.setText(box.getWidth() == 0 || date.getPaint().measureText(full) <= box.getWidth() ? full
                : formatDate("EEEdMMM"));
        if (box.getWidth() == 0) box.post(this::updateDate);
    }

    private static String formatDate(String skeleton) {
        Locale loc = Locale.getDefault();
        String pattern = android.text.format.DateFormat.getBestDateTimePattern(loc, skeleton);
        String s = new SimpleDateFormat(pattern, loc).format(new Date()).replace(".", "");
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(loc) + s.substring(1);
    }

    @SuppressWarnings("deprecation")
    private void updateStatus() {
        String net = getString(R.string.net_none);
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        if (ni != null && ni.isConnected()) {
            if (ni.getType() == ConnectivityManager.TYPE_ETHERNET) net = getString(R.string.net_ethernet);
            else if (ni.getType() == ConnectivityManager.TYPE_WIFI) net = getString(R.string.net_wifi);
            else net = ni.getTypeName();
        }
        boolean linked = ni != null && ni.isConnected();
        if (!linked || internetDown) {
            // No network, or connected to the modem but with no internet: say it clearly, in red.
            // Say what to check: nothing connected (choose a Wi-Fi in Red), a Wi-Fi without internet, or the cable.
            int msg = !linked ? R.string.status_no_connection
                    : ni.getType() == ConnectivityManager.TYPE_WIFI ? R.string.status_no_internet_wifi
                    : R.string.status_no_internet;
            android.text.SpannableString s = new android.text.SpannableString(getString(msg));
            s.setSpan(new android.text.style.ForegroundColorSpan(0xFFFF6E5A), 0, s.length(), 0);
            s.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, s.length(), 0);
            status.setText(s);
            status.setAlpha(1f);
            return;
        }
        status.setAlpha(0.55f);
        long free = SystemInfo.storage()[1];
        String text = net + "  ·  " + getString(R.string.storage_free, SystemInfo.size(free));
        // Open ADB lets anyone on the network control the box: say so on the home screen, not only in Sistema.
        if (SystemInfo.adbNetworkPort() > 0) {
            android.text.SpannableString s = new android.text.SpannableString(
                    text + "  ·  " + getString(R.string.status_adb_open));
            s.setSpan(new android.text.style.ForegroundColorSpan(0xFFFFB74D), text.length(), s.length(), 0);
            status.setText(s);
        } else {
            status.setText(text);
        }
    }

    private void register(BroadcastReceiver r, IntentFilter f) {
        // Only system broadcasts are received here, so the receiver stays private.
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(r, f);
    }
}
