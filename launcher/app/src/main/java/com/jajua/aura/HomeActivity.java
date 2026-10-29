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
    private final Runnable reloadTask = this::reload;
    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            main.postDelayed(this, 60_000);
        }
    };

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
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        f.addAction(Intent.ACTION_DATE_CHANGED);
        f.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        register(clockReceiver, f);
        updateDate();
        main.post(statusTick);
    }

    @Override
    protected void onStop() {
        unregisterReceiver(clockReceiver);
        main.removeCallbacks(statusTick);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
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
        header.setPadding(Ui.u(Ui.SAFE_X), Ui.u(Ui.SAFE_Y), Ui.u(Ui.SAFE_X), Ui.u(4));
        date = Ui.text(this, 15, Ui.regular, 0.72f);
        header.addView(date, new LinearLayout.LayoutParams(0, -2, 1f));
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
        addIfResolvable(tiles, "tile:settings", R.string.tile_settings, R.string.tile_settings_desc,
                Settings.ACTION_SETTINGS);
        addIfResolvable(tiles, "tile:network", R.string.tile_network, R.string.tile_network_desc,
                Settings.ACTION_WIFI_SETTINGS);
        // Handled inside Aura, so they never depend on the (possibly patched) system screens.
        tiles.add(new Entry("tile:keys", null, getString(R.string.tile_keys),
                getString(R.string.tile_keys_desc), null, false));
        tiles.add(new Entry("tile:info", null, getString(R.string.tile_info),
                getString(R.string.tile_info_desc), null, false));
        return tiles;
    }

    private void addIfResolvable(List<Entry> out, String key, int label, int desc, String action) {
        Intent i = new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
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
        else launch(card.entry.intent, card.entry.label, card.face);
    }

    // ---------------------------------------------------------------- remote buttons

    private void showKeyMap() {
        final List<CharSequence> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        labels.add(getString(R.string.keys_assign));
        actions.add(this::captureKey);
        for (Map.Entry<Integer, String> m : KeyMap.all(this).entrySet()) {
            final int code = m.getKey();
            labels.add(getString(R.string.keys_entry, KeyMap.keyName(code), appLabel(m.getValue())));
            actions.add(() -> confirmRemoveKey(code));
        }
        if (!KeyMap.serviceEnabled(this)) {
            labels.add(getString(R.string.keys_enable));
            actions.add(() -> launch(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), getString(R.string.keys_enable), null));
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(R.string.keys_title)
                .setItems(labels.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run());
        if (!KeyMap.serviceEnabled(this)) b.setMessage(R.string.keys_disabled_note);
        dialog = b.show();
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

        LinearLayout list = vertical();
        int pad = Ui.u(20);
        list.setPadding(pad, Ui.u(8), pad, 0);
        String claimedNote = android.startsWith("Android " + claimed + " ") ? null
                : getString(R.string.info_claimed, claimed);
        infoRow(list, R.string.info_android, android, claimedNote);
        infoRow(list, R.string.info_chip, SystemInfo.chip(), null);
        infoRow(list, R.string.info_cpu, SystemInfo.cpu(), null);
        infoRow(list, R.string.info_ram, getString(R.string.info_of,
                SystemInfo.size(mem[1]), SystemInfo.size(mem[0])), null);
        infoRow(list, R.string.info_storage, getString(R.string.info_of,
                SystemInfo.size(disk[1]), SystemInfo.size(disk[0])), null);
        infoRow(list, R.string.info_display, SystemInfo.display(this), null);
        infoRow(list, R.string.info_ip, SystemInfo.ipAddress(this), null);

        dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.info_title)
                .setView(list)
                .setPositiveButton(R.string.info_close, null)
                .show();
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
        actions.add(() -> launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", e.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
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
        Locale loc = Locale.getDefault();
        String pattern = android.text.format.DateFormat.getBestDateTimePattern(loc, "EEEEdMMMM");
        String s = new SimpleDateFormat(pattern, loc).format(new Date());
        date.setText(s.isEmpty() ? s : s.substring(0, 1).toUpperCase(loc) + s.substring(1));
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
        long free = SystemInfo.storage()[1];
        status.setText(net + "  ·  " + getString(R.string.storage_free, SystemInfo.size(free)));
    }

    private void register(BroadcastReceiver r, IntentFilter f) {
        // Only system broadcasts are received here, so the receiver stays private.
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(r, f);
    }
}
