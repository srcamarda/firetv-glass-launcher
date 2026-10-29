package com.ivantrj.glasslauncher;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Apple TV style home screen.
 *
 * Focus is handled here, not by Android's focus search, so D-pad movement is
 * deterministic: three zones (top bar, dock, grid), Up/Down move exactly one
 * row, and the ends of a row never fall through to another zone.
 */
public class HomeActivity extends Activity {

    private static final int TOP = 0, DOCK = 1, GRID = 2;
    private static final int PILL_INPUTS = 0, PILL_WIFI = 1, PILL_SETTINGS = 2;
    private static final int NEUTRAL_GLOW = 0xFF8E8E9E;

    private View root, page, band, glow, pillRow;
    private ScrollView menuPanel;
    private View[] pills;
    private GlassHighlight highlight;
    private TextView greeting, dockTitle, wifiName;
    private LinearLayout dockRow, inputsMenu;
    private RecyclerView grid;
    private ImageView wallpaper;
    private int columns = 4;
    private GridLayoutManager gridLayout;
    private float gridCellW, gridCellH, gridTileW, gridTileH; // px, set by applyGridSettings()
    private View dateView;
    private SharedPreferences settings;
    private long lastBingAttempt;
    private GridAdapter adapter;

    private final List<AppInfo> dockApps = new ArrayList<>();
    private List<AppInfo> gridApps = new ArrayList<>();

    private int zone = DOCK, topIdx = 0, dockIdx = 0, gridIdx = 0;
    private String restoreGridPkg;
    private int gridScrollTarget, gridScrollActual;
    private SpringValue pageSpring;

    private GradientDrawable glowDrawable;
    private int glowColor = NEUTRAL_GLOW;
    private ValueAnimator glowAnimator;

    /** A row in the pop-up menu. A null action makes it a non-selectable header / info row. */
    private static final class MenuItem {
        final String label;
        final Runnable action;
        final boolean keepOpen; // toggles: the action rebuilds the open menu in place
        MenuItem(String label, Runnable action) { this(label, action, false); }
        MenuItem(String label, Runnable action, boolean keepOpen) {
            this.label = label;
            this.action = action;
            this.keepOpen = keepOpen;
        }
    }

    private boolean menuOpen;
    private int menuIdx;
    private List<MenuItem> menuItems = new ArrayList<>();

    private boolean stopped = true;
    private long wallpaperStamp = -1;
    private long loadedConfigStamp;
    private float density;
    private SharedPreferences prefs;
    private WifiManager wifiManager;
    private ConnectivityManager connectivity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final DecelerateInterpolator ease = new DecelerateInterpolator(1.8f);

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        density = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("focus", MODE_PRIVATE);
        wifiManager = getApplicationContext().getSystemService(WifiManager.class);
        connectivity = getSystemService(ConnectivityManager.class);

        root = findViewById(R.id.root);
        page = findViewById(R.id.page);
        band = findViewById(R.id.band);
        glow = findViewById(R.id.glow);
        highlight = findViewById(R.id.highlight);
        greeting = findViewById(R.id.greeting);
        dockTitle = findViewById(R.id.dockTitle);
        wifiName = findViewById(R.id.wifiName);
        dockRow = findViewById(R.id.dockRow);
        grid = findViewById(R.id.grid);
        inputsMenu = findViewById(R.id.inputsMenu);
        menuPanel = findViewById(R.id.menuPanel);
        wallpaper = findViewById(R.id.wallpaper);
        settings = GlassPrefs.get(this);
        pillRow = findViewById(R.id.pills);
        pills = new View[] {findViewById(R.id.pillInputs), findViewById(R.id.pillWifi), findViewById(R.id.pillSettings)};

        greeting.setTypeface(Fonts.outfit(this, 300));
        ((TextClock) findViewById(R.id.clock)).setTypeface(Fonts.outfit(this, 120));
        dateView = findViewById(R.id.date);
        ((TextClock) dateView).setTypeface(Fonts.outfit(this, 300));
        dockTitle.setTypeface(Fonts.outfit(this, 250));
        for (View pill : pills) {
            sharpenIcon((ImageView) ((LinearLayout) pill).getChildAt(0));
            ((TextView) ((LinearLayout) pill).getChildAt(1)).setTypeface(Fonts.outfit(this, 400));
        }

        glowDrawable = new GradientDrawable();
        glowDrawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowDrawable.setGradientRadius(480 * density);
        glowDrawable.setGradientCenter(0.5f, 0.5f);
        setGlowColors(glowColor);
        glow.setBackground(glowDrawable);
        glow.setScaleY(0.42f); // squash the circle into a wide ellipse behind the dock

        pageSpring = new SpringValue(240f, 0.92f, v -> page.setTranslationY(-v));

        adapter = new GridAdapter(grid);
        gridLayout = new GridLayoutManager(this, columns);
        grid.setLayoutManager(gridLayout);
        grid.setItemAnimator(null);
        grid.setHasFixedSize(true);
        grid.setAdapter(adapter);
        applyGridSettings();
        applyHeaderSettings();
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) { gridScrollActual += dy; }
        });

        // The Wi-Fi pill changes width with the network name: keep the highlight on it
        pillRow.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (zone == TOP) moveHighlight();
        });

        zone = prefs.getInt("zone", DOCK) == GRID ? GRID : DOCK;
        dockIdx = prefs.getInt("dockIdx", 0);
        topIdx = prefs.getInt("topIdx", 0);
        restoreGridPkg = prefs.getString("gridPkg", null);

        IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pkgFilter.addDataScheme("package");
        registerReceiver(packageReceiver, pkgFilter);

        loadApps();
    }

    @Override
    protected void onStart() {
        super.onStart();
        stopped = false;
        IntentFilter timeFilter = new IntentFilter(Intent.ACTION_TIME_TICK);
        timeFilter.addAction(Intent.ACTION_TIME_CHANGED);
        timeFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, timeFilter);
        connectivity.registerDefaultNetworkCallback(networkCallback);
        updateGreeting();
        updateWifi();
        loadWallpaper();
        refreshBing(false);
        if (configStamp() != loadedConfigStamp) loadApps(); // dock.txt / hidden.txt edited
    }

    @Override
    protected void onResume() {
        super.onResume();
        android.util.Log.i("GlassDiag", "Glass visible (onResume)");
        HomeButtonService.onGlassVisible();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopped = true;
        unregisterReceiver(timeReceiver);
        connectivity.unregisterNetworkCallback(networkCallback);
    }

    @Override
    protected void onPause() {
        super.onPause();
        HomeButtonService.onGlassPaused();
        SharedPreferences.Editor e = prefs.edit()
                .putInt("zone", zone)
                .putInt("dockIdx", dockIdx)
                .putInt("topIdx", topIdx);
        if (gridIdx < gridApps.size()) e.putString("gridPkg", gridApps.get(gridIdx).packageName);
        e.apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packageReceiver);
        io.shutdownNow();
    }

    /** Home pressed. From an app: keep the remembered focus. Already home: go back to the dock. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("reload", false)) { // sent by configure.sh
            loadApps();
            wallpaperStamp = -1;
            loadWallpaper();
            return;
        }
        boolean wasVisible = !stopped;
        closeMenu();
        if (wasVisible && !dockApps.isEmpty()) {
            zone = DOCK;
            applyFocus(true);
        }
    }

    // ---------------------------------------------------------------- apps

    private void loadApps() {
        loadedConfigStamp = configStamp();
        Context app = getApplicationContext();
        io.execute(() -> {
            AppLoader.Result result = AppLoader.load(app);
            main.post(() -> onAppsLoaded(result));
        });
    }

    private void onAppsLoaded(AppLoader.Result result) {
        String gridPkg = restoreGridPkg;
        if (gridPkg == null && gridIdx < gridApps.size()) gridPkg = gridApps.get(gridIdx).packageName;
        restoreGridPkg = null;

        dockApps.clear();
        dockApps.addAll(result.dock);
        dockRow.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (AppInfo app : dockApps) {
            View cell = inflater.inflate(R.layout.item_tile, dockRow, false);
            Tiles.bind(cell, app);
            cell.findViewById(R.id.label).setVisibility(View.GONE); // the big dock title already names it
            ((FrameLayout) cell.findViewById(R.id.tile)).setForeground(getDrawable(R.drawable.tile_border));
            dockRow.addView(cell);
        }
        // Band hugs the favourites: one 204dp cell each plus 12dp on both sides; hidden when empty
        ViewGroup.LayoutParams bandLp = band.getLayoutParams();
        bandLp.width = Math.round(dp(dockApps.size() * 204 + 24));
        band.setLayoutParams(bandLp);
        band.setVisibility(dockApps.isEmpty() ? View.INVISIBLE : View.VISIBLE);

        gridApps = result.grid;
        adapter.setApps(gridApps);

        dockIdx = clamp(dockIdx, 0, dockApps.size() - 1);
        int found = -1;
        for (int i = 0; gridPkg != null && i < gridApps.size(); i++) {
            if (gridApps.get(i).packageName.equals(gridPkg)) found = i;
        }
        gridIdx = clamp(found >= 0 ? found : gridIdx, 0, gridApps.size() - 1);
        if (zone == GRID && gridApps.isEmpty()) zone = DOCK;
        if (zone == DOCK && dockApps.isEmpty()) zone = TOP;

        // Place the highlight only once the new tiles have real positions
        afterLayout(() -> applyFocus(false));
    }

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { loadApps(); }
    };

    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (!handles(key)) return super.dispatchKeyEvent(event);
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (menuOpen) {
            menuKey(key);
            return true;
        }
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT: left(); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: right(); break;
            case KeyEvent.KEYCODE_DPAD_UP: up(); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: down(); break;
            case KeyEvent.KEYCODE_BACK: back(); break;
            case KeyEvent.KEYCODE_MENU: openOptions(); break;
            default: if (event.getRepeatCount() == 0) activate(); break;
        }
        return true;
    }

    private static boolean handles(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MENU:
                return true;
            default:
                return false;
        }
    }

    private void left() {
        if (zone == TOP && topIdx > 0) topIdx--;
        else if (zone == DOCK && dockIdx > 0) dockIdx--;
        else if (zone == GRID && gridIdx % columns > 0) gridIdx--;
        else return;
        applyFocus(true);
    }

    private void right() {
        if (zone == TOP && topIdx < pills.length - 1) topIdx++;
        else if (zone == DOCK && dockIdx < dockApps.size() - 1) dockIdx++;
        else if (zone == GRID && gridIdx % columns < columns - 1 && gridIdx + 1 < gridApps.size()) gridIdx++;
        else return;
        applyFocus(true);
    }

    private void up() {
        if (zone == TOP) return;
        if (zone == DOCK) {
            zone = TOP;
        } else if (gridIdx >= columns) {
            gridIdx -= columns;
        } else if (!dockApps.isEmpty()) {
            zone = DOCK;
            dockIdx = Math.min(gridIdx % columns, dockApps.size() - 1);
        } else {
            zone = TOP;
        }
        applyFocus(true);
    }

    private void down() {
        if (zone == TOP && !dockApps.isEmpty()) {
            zone = DOCK;
        } else if ((zone == TOP || zone == DOCK) && !gridApps.isEmpty()) {
            gridIdx = Math.min(zone == DOCK ? dockIdx : 0, gridApps.size() - 1);
            zone = GRID;
        } else if (zone == GRID && gridIdx / columns < (gridApps.size() - 1) / columns) {
            // One row down; a short last row catches the focus on its last tile
            gridIdx = Math.min(gridIdx + columns, gridApps.size() - 1);
        } else {
            return;
        }
        applyFocus(true);
    }

    private void back() {
        if (zone == DOCK || dockApps.isEmpty()) return;
        zone = DOCK;
        applyFocus(true);
    }

    private void activate() {
        if (zone == TOP) {
            if (topIdx == PILL_INPUTS) openMenu();
            else if (topIdx == PILL_WIFI) launch(new Intent(Settings.ACTION_WIFI_SETTINGS));
            else openSettingsMenu();
        } else {
            AppInfo app = focusedApp();
            if (app != null) launch(app.launchIntent);
        }
    }

    /** Settings pill: Fire TV's own settings, or Glass's. */
    private void openSettingsMenu() {
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem(getString(R.string.settings_firetv), this::openSettings));
        items.add(new MenuItem(getString(R.string.settings_glass), () -> openGlassSettings(-1)));
        showMenu(items, pills[PILL_SETTINGS]);
    }

    private String onOff(boolean on) {
        return getString(on ? R.string.set_on : R.string.set_off);
    }

    /** Glass settings. Toggles keep the menu open and rebuild it with the new values. */
    private void openGlassSettings(int select) {
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem(getString(R.string.settings_glass), null));

        boolean bing = settings.getBoolean(GlassPrefs.BING, false);
        int iBing = items.size();
        items.add(new MenuItem(getString(R.string.set_bing, onOff(bing)), () -> {
            settings.edit().putBoolean(GlassPrefs.BING, !bing).apply();
            wallpaperStamp = -1;
            loadWallpaper();
            if (!bing) refreshBing(true);
            openGlassSettings(iBing);
        }, true));

        if (bing) {
            int day = settings.getInt(GlassPrefs.BING_IDX, 0);
            String when = day == 0 ? getString(R.string.bing_today)
                    : getResources().getQuantityString(R.plurals.bing_days_ago, day, day);
            int iDay = items.size();
            items.add(new MenuItem(getString(R.string.set_bing_pick, when), () -> {
                settings.edit().putInt(GlassPrefs.BING_IDX, (day + 1) % GlassPrefs.BING_DAYS).apply();
                refreshBing(true);
                openGlassSettings(iDay);
            }, true));
        }

        int dim = clamp(settings.getInt(GlassPrefs.DIM, GlassPrefs.DIM_DEFAULT), 0, GlassPrefs.DIM_ALPHA.length - 1);
        int[] dimNames = {R.string.dim_light, R.string.dim_medium, R.string.dim_strong};
        int iDim = items.size();
        items.add(new MenuItem(getString(R.string.set_dim, getString(dimNames[dim])), () -> {
            settings.edit().putInt(GlassPrefs.DIM, (dim + 1) % GlassPrefs.DIM_ALPHA.length).apply();
            applyWallpaperDim();
            openGlassSettings(iDim);
        }, true));

        int iCols = items.size();
        items.add(new MenuItem(getString(R.string.set_columns, columns), () -> {
            int next = columns >= GlassPrefs.GRID_COLS_MAX ? GlassPrefs.GRID_COLS_MIN : columns + 1;
            settings.edit().putInt(GlassPrefs.GRID_COLS, next).apply();
            applyGridSettings();
            openGlassSettings(iCols);
        }, true));

        boolean labels = settings.getBoolean(GlassPrefs.GRID_LABELS_ALWAYS, false);
        int iLabels = items.size();
        items.add(new MenuItem(getString(R.string.set_grid_labels,
                getString(labels ? R.string.labels_always : R.string.labels_focused)), () -> {
            settings.edit().putBoolean(GlassPrefs.GRID_LABELS_ALWAYS, !labels).apply();
            applyGridSettings();
            openGlassSettings(iLabels);
        }, true));

        boolean greet = settings.getBoolean(GlassPrefs.SHOW_GREETING, true);
        int iGreet = items.size();
        items.add(new MenuItem(getString(R.string.set_greeting, onOff(greet)), () -> {
            settings.edit().putBoolean(GlassPrefs.SHOW_GREETING, !greet).apply();
            applyHeaderSettings();
            openGlassSettings(iGreet);
        }, true));

        boolean back = settings.getBoolean(GlassPrefs.BACK_LONG_PRESS, true);
        int iBack = items.size();
        items.add(new MenuItem(getString(R.string.set_back_long_press, onOff(back)), () -> {
            settings.edit().putBoolean(GlassPrefs.BACK_LONG_PRESS, !back).apply();
            openGlassSettings(iBack);
        }, true));

        int hidden = AppLoader.readList(this, AppLoader.HIDDEN_FILE).size();
        items.add(hidden > 0
                ? new MenuItem(getString(R.string.menu_hidden_apps, hidden), this::showHiddenMenu)
                : new MenuItem(getString(R.string.set_no_hidden), null));

        items.add(new MenuItem(getString(R.string.set_reset), this::confirmReset));

        String version = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        items.add(new MenuItem(getString(R.string.set_version, version), null));
        showMenu(items, null, select);
    }

    /** Grid columns and label mode. At 5 columns the tiles shrink to fit the same 816dp width. */
    private void applyGridSettings() {
        columns = clamp(settings.getInt(GlassPrefs.GRID_COLS, GlassPrefs.GRID_COLS_MIN),
                GlassPrefs.GRID_COLS_MIN, GlassPrefs.GRID_COLS_MAX);
        int cellW = (int) Math.floor(dp(816) / columns);
        int tileW = cellW - Math.round(dp(24));
        int tileH = Math.round(tileW * 101f / 180f);   // 16:9 like the XML tile (180x101dp)
        int cellH = tileH + Math.round(dp(43));        // the XML cell is 144dp for a 101dp tile
        int iconSize = Math.round(dp(54) * tileW / dp(180));
        int labelTop = Math.round(dp(10)) + tileH + Math.round(dp(13));
        gridCellW = dp(816) / columns; // exact span width, matches GridLayoutManager's placement
        gridCellH = cellH;
        gridTileW = tileW;
        gridTileH = tileH;
        gridLayout.setSpanCount(columns);
        adapter.setLayout(cellW, cellH, tileW, tileH, iconSize, labelTop,
                settings.getBoolean(GlassPrefs.GRID_LABELS_ALWAYS, false));
        // Start the grid from the top with the new geometry
        grid.scrollToPosition(0);
        gridScrollActual = 0;
        gridScrollTarget = 0;
        gridIdx = clamp(gridIdx, 0, gridApps.size() - 1);
        if (zone == GRID) afterLayout(() -> applyFocus(false));
    }

    /** Greeting and date above the clock (invisible rather than gone, so the clock stays put). */
    private void applyHeaderSettings() {
        int v = settings.getBoolean(GlassPrefs.SHOW_GREETING, true) ? View.VISIBLE : View.INVISIBLE;
        greeting.setVisibility(v);
        dateView.setVisibility(v);
    }

    private void confirmReset() {
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem(getString(R.string.reset_header), null));
        items.add(new MenuItem(getString(R.string.reset_no), () -> openGlassSettings(-1)));
        items.add(new MenuItem(getString(R.string.reset_yes), this::resetAll));
        showMenu(items, null, 1); // Cancel focused first
    }

    /** Favourites, hidden apps and Glass settings back to defaults. */
    private void resetAll() {
        new File(getFilesDir(), AppLoader.DOCK_FILE).delete();
        new File(getFilesDir(), AppLoader.HIDDEN_FILE).delete();
        settings.edit().clear().commit();
        applyGridSettings();
        applyHeaderSettings();
        wallpaperStamp = -1;
        loadWallpaper();
        loadApps();
        Toast.makeText(this, R.string.reset_done, Toast.LENGTH_SHORT).show();
    }

    /**
     * On Fire OS 7, ACTION_SETTINGS goes through a stub that reopens the Amazon home screen
     * to show Settings there, which the Home redirect then sends back to Glass. The launcher's
     * MainSettingsActivity opens the same Settings screen directly, so use it when present.
     */
    private void openSettings() {
        Intent fireSettings = new Intent().setClassName("com.amazon.tv.launcher",
                "com.amazon.tv.launcher.ui.MainSettingsActivity");
        if (fireSettings.resolveActivity(getPackageManager()) != null) {
            try {
                startActivity(fireSettings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (RuntimeException ignored) {
                // fall back to the standard intent
            }
        }
        launch(new Intent(Settings.ACTION_SETTINGS));
    }

    /** Menu button on a tile opens that app's system info page (force stop, clear cache...). */
    private void openAppInfo() {
        AppInfo app = focusedApp();
        if (app == null) return;
        launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", app.packageName, null)));
    }

    private AppInfo focusedApp() {
        if (zone == DOCK && dockIdx < dockApps.size()) return dockApps.get(dockIdx);
        if (zone == GRID && gridIdx < gridApps.size()) return gridApps.get(gridIdx);
        return null;
    }

    private void launch(Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.cant_open, Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- focus visuals

    private void applyFocus(boolean animate) {
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            Tiles.setFocused(dockRow.getChildAt(i), zone == DOCK && i == dockIdx, animate);
        }
        adapter.setFocused(zone == GRID ? gridIdx : RecyclerView.NO_POSITION);
        for (int i = 0; i < pills.length; i++) {
            boolean on = zone == TOP && i == topIdx;
            float s = on ? 1.06f : 1f;
            pills[i].animate().scaleX(s).scaleY(s).setDuration(animate ? 200 : 0).setInterpolator(ease).start();
        }

        // Down from the dock slides the whole page up; deeper rows scroll the grid itself
        float pageTarget = 0;
        int scrollTarget = 0;
        if (zone == GRID) {
            int row = gridIdx / columns;
            if (row == 0) {
                pageTarget = band.getTop() - dp(30);
            } else {
                pageTarget = grid.getTop() - dp(20);
                scrollTarget = clamp((row - 1) * rowHeight(), 0, maxGridScroll());
            }
        }
        if (animate) pageSpring.animateTo(pageTarget); else pageSpring.snapTo(pageTarget);
        gridScrollTarget = scrollTarget;
        int dy = scrollTarget - gridScrollActual;
        if (dy != 0) {
            if (animate) grid.smoothScrollBy(0, dy, ease, 420);
            else grid.scrollBy(0, dy);
        }

        updateDockTitle(animate);
        moveHighlight();
    }

    private void moveHighlight() {
        float l, t, w, h;
        if (zone == TOP) {
            View p = pills[topIdx];
            float s = 1.06f, pad = dp(3);
            w = p.getWidth() * s + 2 * pad;
            h = p.getHeight() * s + 2 * pad;
            l = pillRow.getLeft() + p.getLeft() + p.getWidth() / 2f - w / 2f;
            t = pillRow.getTop() + p.getTop() + p.getHeight() / 2f - h / 2f;
            highlight.moveTo(l, t, w, h, h / 2f);
            return;
        }
        float tileW = dp(180), tileH = dp(101); // dock tiles keep the XML size
        if (zone == DOCK) {
            if (dockIdx >= dockRow.getChildCount()) return;
            View cell = dockRow.getChildAt(dockIdx);
            View tile = cell.findViewById(R.id.tile);
            l = dockRow.getLeft() + cell.getLeft() + tile.getLeft();
            t = dockRow.getTop() + cell.getTop() + tile.getTop();
        } else {
            // Computed from the grid geometry and the scroll target, so the highlight
            // heads straight for the final spot while the grid is still scrolling
            int col = gridIdx % columns, row = gridIdx / columns;
            tileW = gridTileW;
            tileH = gridTileH;
            l = grid.getLeft() + grid.getPaddingLeft() + col * gridCellW + dp(12);
            t = grid.getTop() + grid.getPaddingTop() + row * rowHeight() - gridScrollTarget + dp(10);
        }
        float s = Tiles.FOCUS_SCALE, pad = dp(6);
        w = tileW * s + 2 * pad;
        h = tileH * s + 2 * pad;
        highlight.moveTo(l + tileW / 2f - w / 2f, t + tileH / 2f - h / 2f, w, h, dp(14) * s + pad);
    }

    private void updateDockTitle(boolean animate) {
        // Only while a favourite is focused
        float alpha = zone == DOCK ? 1f : 0f;
        glow.animate().alpha(zone == DOCK ? 1f : 0.5f).setDuration(animate ? 300 : 0).start();
        if (dockApps.isEmpty()) {
            dockTitle.setText("");
            return;
        }
        AppInfo app = dockApps.get(dockIdx);
        dockTitle.animate().cancel();
        if (!app.label.contentEquals(dockTitle.getText())) {
            dockTitle.setText(app.label);
            if (animate) {
                dockTitle.setAlpha(0f);
                dockTitle.setTranslationX(dp(10));
            }
            animateGlow(app.glowColor, animate);
        }
        if (animate) {
            dockTitle.animate().alpha(alpha).translationX(0).setDuration(260).setInterpolator(ease).start();
        } else {
            dockTitle.setAlpha(alpha);
            dockTitle.setTranslationX(0);
        }
    }

    private void animateGlow(int color, boolean animate) {
        if (glowAnimator != null) glowAnimator.cancel();
        if (!animate) {
            glowColor = color;
            setGlowColors(color);
            return;
        }
        glowAnimator = ValueAnimator.ofArgb(glowColor, color);
        glowAnimator.setDuration(500);
        glowAnimator.addUpdateListener(a -> {
            glowColor = (int) a.getAnimatedValue();
            setGlowColors(glowColor);
        });
        glowAnimator.start();
    }

    private void setGlowColors(int c) {
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        glowDrawable.setColors(new int[] {Color.argb(120, r, g, b), Color.argb(46, r, g, b), Color.argb(0, r, g, b)});
    }

    // ---------------------------------------------------------------- pop-up menu

    private void openMenu() {
        List<MenuItem> items = new ArrayList<>();
        for (InputsMenu.Entry e : InputsMenu.load(this)) {
            Intent intent = e.intent;
            items.add(new MenuItem(e.label, intent == null ? null : () -> launch(intent)));
        }
        showMenu(items, pills[PILL_INPUTS]);
    }

    /** Shows the menu under {@code anchor}, or centred on screen when it's null. */
    private void showMenu(List<MenuItem> items, View anchor) {
        showMenu(items, anchor, -1);
    }

    /** {@code select}: row to focus (when selectable). An open menu is rebuilt without animation. */
    private void showMenu(List<MenuItem> items, View anchor, int select) {
        boolean refresh = menuOpen;
        menuItems = items;
        inputsMenu.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        menuIdx = -1;
        for (int i = 0; i < items.size(); i++) {
            MenuItem item = items.get(i);
            TextView row = (TextView) inflater.inflate(R.layout.item_input, inputsMenu, false);
            row.setText(item.label);
            row.setTypeface(Fonts.outfit(this, item.action == null ? 300 : 400));
            if (item.action == null) row.setAlpha(0.55f);
            else if (menuIdx < 0) menuIdx = i;
            inputsMenu.addView(row);
        }
        if (select >= 0 && select < items.size() && items.get(select).action != null) menuIdx = select;

        // Fit on screen: long lists (hidden apps) scroll inside the panel
        menuPanel.animate().cancel();
        if (!refresh) menuPanel.scrollTo(0, 0);
        ViewGroup.LayoutParams lp = menuPanel.getLayoutParams();
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        menuPanel.setLayoutParams(lp);
        menuPanel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int w = menuPanel.getMeasuredWidth();
        int maxH = Math.round(root.getHeight() - dp(anchor == null ? 60 : 120));
        int h = Math.min(menuPanel.getMeasuredHeight(), maxH);
        if (h < menuPanel.getMeasuredHeight()) {
            lp.height = h;
            menuPanel.setLayoutParams(lp);
        }

        float x, y;
        if (anchor != null) {
            x = Math.min(pillRow.getLeft() + anchor.getLeft(), root.getWidth() - w - dp(24));
            y = pillRow.getTop() + anchor.getBottom() + dp(12);
        } else {
            x = (root.getWidth() - w) / 2f;
            y = (root.getHeight() - h) / 2f;
        }
        menuPanel.setX(x);
        menuPanel.setVisibility(View.VISIBLE);
        if (refresh) {
            menuPanel.setY(y);
            menuPanel.setAlpha(1f);
        } else {
            menuPanel.setY(y - dp(8));
            menuPanel.setAlpha(0f);
            menuPanel.animate().alpha(1f).y(y).setDuration(180).setInterpolator(ease).start();
        }
        menuOpen = true;
        updateMenuSelection();
    }

    private void closeMenu() {
        if (!menuOpen) return;
        menuOpen = false;
        menuPanel.animate().alpha(0f).setDuration(120)
                .withEndAction(() -> menuPanel.setVisibility(View.GONE)).start();
    }

    private void menuKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
                for (int i = menuIdx - 1; i >= 0; i--) if (menuItems.get(i).action != null) { menuIdx = i; break; }
                updateMenuSelection();
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                for (int i = menuIdx + 1; i < menuItems.size(); i++) if (menuItems.get(i).action != null) { menuIdx = i; break; }
                updateMenuSelection();
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (menuIdx >= 0) {
                    MenuItem item = menuItems.get(menuIdx);
                    if (!item.keepOpen) closeMenu();
                    item.action.run(); // may open another menu, or rebuild this one
                }
                break;
            default: // Back, Left, Right, Menu close it
                closeMenu();
        }
    }

    private void updateMenuSelection() {
        for (int i = 0; i < inputsMenu.getChildCount(); i++) {
            inputsMenu.getChildAt(i).setBackgroundResource(i == menuIdx ? R.drawable.menu_row_selected : 0);
        }
        if (menuIdx < 0) return;
        menuPanel.post(() -> { // keep the selected row inside the visible part of the panel
            if (menuIdx < 0 || menuIdx >= inputsMenu.getChildCount()) return;
            View row = inputsMenu.getChildAt(menuIdx);
            int top = inputsMenu.getTop() + row.getTop(), bottom = inputsMenu.getTop() + row.getBottom();
            int y = menuPanel.getScrollY();
            int visTop = y + menuPanel.getPaddingTop();
            int visBottom = y + menuPanel.getHeight() - menuPanel.getPaddingBottom();
            if (top < visTop) menuPanel.smoothScrollTo(0, top - menuPanel.getPaddingTop());
            else if (bottom > visBottom) menuPanel.smoothScrollTo(0, bottom - menuPanel.getHeight() + menuPanel.getPaddingBottom());
        });
    }

    // ---------------------------------------------------------------- app options (Menu button)

    /** Menu (☰): favourites, hide and app info for the focused app. Hidden apps live in Glass settings. */
    private void openOptions() {
        AppInfo app = focusedApp();
        List<MenuItem> items = new ArrayList<>();
        if (app != null) {
            items.add(new MenuItem(app.label, null));
            if (zone == DOCK) {
                int i = dockIdx;
                items.add(new MenuItem(getString(R.string.menu_remove_favorite), () -> removeFavorite(app)));
                if (i > 0) items.add(new MenuItem(getString(R.string.menu_move_left), () -> moveFavorite(i, -1)));
                if (i < dockApps.size() - 1) items.add(new MenuItem(getString(R.string.menu_move_right), () -> moveFavorite(i, 1)));
            } else if (dockApps.size() < AppLoader.DOCK_MAX) {
                items.add(new MenuItem(getString(R.string.menu_add_favorite), () -> addFavorite(app)));
            } else {
                items.add(new MenuItem(getString(R.string.menu_favorites_full, AppLoader.DOCK_MAX), null));
            }
            items.add(new MenuItem(getString(R.string.menu_hide), () -> hideApp(app)));
            items.add(new MenuItem(getString(R.string.menu_app_info), this::openAppInfo));
        }
        if (!items.isEmpty()) showMenu(items, null);
    }

    private List<String> dockPackages() {
        List<String> pkgs = new ArrayList<>();
        for (AppInfo a : dockApps) pkgs.add(a.packageName);
        return pkgs;
    }

    private void addFavorite(AppInfo app) {
        List<String> pkgs = dockPackages();
        if (pkgs.contains(app.packageName) || pkgs.size() >= AppLoader.DOCK_MAX) return;
        pkgs.add(app.packageName);
        AppLoader.writeList(this, AppLoader.DOCK_FILE, pkgs);
        loadApps();
    }

    private void removeFavorite(AppInfo app) {
        List<String> pkgs = dockPackages();
        pkgs.remove(app.packageName);
        AppLoader.writeList(this, AppLoader.DOCK_FILE, pkgs);
        loadApps();
    }

    private void moveFavorite(int from, int delta) {
        List<String> pkgs = dockPackages();
        int to = from + delta;
        if (from < 0 || to < 0 || from >= pkgs.size() || to >= pkgs.size()) return;
        pkgs.add(to, pkgs.remove(from));
        dockIdx = to; // focus follows the moved app
        AppLoader.writeList(this, AppLoader.DOCK_FILE, pkgs);
        loadApps();
    }

    private void hideApp(AppInfo app) {
        List<String> hidden = AppLoader.readList(this, AppLoader.HIDDEN_FILE);
        if (!hidden.contains(app.packageName)) hidden.add(app.packageName);
        AppLoader.writeList(this, AppLoader.HIDDEN_FILE, hidden);
        List<String> dock = dockPackages();
        if (dock.remove(app.packageName)) AppLoader.writeList(this, AppLoader.DOCK_FILE, dock);
        loadApps();
        Toast.makeText(this, getString(R.string.toast_hidden, app.label), Toast.LENGTH_LONG).show();
    }

    private void showHiddenMenu() {
        List<String> hidden = AppLoader.readList(this, AppLoader.HIDDEN_FILE);
        PackageManager pm = getPackageManager();
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem(getString(R.string.hidden_header), null));
        List<String> uninstalled = new ArrayList<>();
        for (String pkg : hidden) {
            String label;
            try {
                label = String.valueOf(pm.getApplicationInfo(pkg, 0).loadLabel(pm));
            } catch (PackageManager.NameNotFoundException e) {
                uninstalled.add(pkg);
                continue;
            }
            items.add(new MenuItem(label, () -> unhide(pkg)));
        }
        if (!uninstalled.isEmpty()) { // forget apps that were uninstalled meanwhile
            hidden.removeAll(uninstalled);
            AppLoader.writeList(this, AppLoader.HIDDEN_FILE, hidden);
        }
        if (hidden.isEmpty()) return;
        if (hidden.size() > 1) {
            items.add(new MenuItem(getString(R.string.menu_show_all), () -> {
                AppLoader.writeList(this, AppLoader.HIDDEN_FILE, new ArrayList<>());
                loadApps();
            }));
        }
        showMenu(items, null);
    }

    private void unhide(String pkg) {
        List<String> hidden = AppLoader.readList(this, AppLoader.HIDDEN_FILE);
        hidden.remove(pkg);
        AppLoader.writeList(this, AppLoader.HIDDEN_FILE, hidden);
        loadApps();
    }

    // ---------------------------------------------------------------- clock, greeting, wifi, wallpaper

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateGreeting(); refreshBing(false); }
    };

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int text = hour < 5 ? R.string.greeting_night : hour < 12 ? R.string.greeting_morning
                : hour < 18 ? R.string.greeting_afternoon : hour < 22 ? R.string.greeting_evening
                : R.string.greeting_night;
        greeting.setText(text);
    }

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) { main.post(HomeActivity.this::updateWifi); }
        @Override public void onLost(Network n) { main.post(HomeActivity.this::updateWifi); }
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { main.post(HomeActivity.this::updateWifi); }
    };

    private void updateWifi() {
        Network network = connectivity.getActiveNetwork();
        NetworkCapabilities caps = network == null ? null : connectivity.getNetworkCapabilities(network);
        String text;
        if (caps == null) {
            text = getString(R.string.net_offline);
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            text = getString(R.string.net_ethernet);
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            WifiInfo info = wifiManager.getConnectionInfo();
            String ssid = info == null ? null : info.getSSID();
            if (ssid == null || ssid.equals(WifiManager.UNKNOWN_SSID) || ssid.isEmpty()) {
                text = getString(R.string.net_wifi); // name hidden: location permission or location mode is off
            } else {
                text = ssid.startsWith("\"") && ssid.endsWith("\"") ? ssid.substring(1, ssid.length() - 1) : ssid;
            }
        } else {
            text = getString(R.string.net_connected);
        }
        if (!text.contentEquals(wifiName.getText())) wifiName.setText(text);
    }

    /**
     * Wallpaper: Bing's image of the day when enabled in Glass settings, otherwise the optional
     * wallpaper.jpg / wallpaper.png in the app's files dir (see configure.sh). Shown dimmed.
     */
    private void loadWallpaper() {
        File file = null;
        if (settings.getBoolean(GlassPrefs.BING, false)) {
            File b = new File(getFilesDir(), BingWallpaper.FILE);
            if (b.isFile()) file = b;
        }
        for (String name : new String[] {"wallpaper.jpg", "wallpaper.png"}) {
            if (file != null) break;
            File f = new File(getFilesDir(), name);
            if (f.isFile()) file = f;
        }
        applyWallpaperDim();

        long stamp = file == null ? 0 : file.getName().hashCode() * 31L + file.lastModified() + file.length();
        if (stamp == wallpaperStamp) return;
        wallpaperStamp = stamp;
        if (file == null) {
            wallpaper.setImageDrawable(null);
            wallpaper.setVisibility(View.GONE);
            return;
        }
        String path = file.getAbsolutePath();
        int screenW = getResources().getDisplayMetrics().widthPixels;
        io.execute(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= screenW) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.RGB_565; // half the memory, fine for a dimmed photo
            Bitmap bmp = BitmapFactory.decodeFile(path, o);
            main.post(() -> {
                wallpaper.setImageBitmap(bmp);
                wallpaper.setVisibility(bmp == null ? View.GONE : View.VISIBLE);
            });
        });
    }

    private void applyWallpaperDim() {
        int dim = clamp(settings.getInt(GlassPrefs.DIM, GlassPrefs.DIM_DEFAULT), 0, GlassPrefs.DIM_ALPHA.length - 1);
        wallpaper.setColorFilter(Color.argb(GlassPrefs.DIM_ALPHA[dim], 0, 0, 0), PorterDuff.Mode.SRC_ATOP);
    }

    /**
     * Downloads a new Bing image when the saved one is stale (new day, other day offset, missing).
     * Without {@code force}, failed attempts are retried at most every 30 minutes.
     */
    private void refreshBing(boolean force) {
        if (!settings.getBoolean(GlassPrefs.BING, false)) return;
        long now = System.currentTimeMillis();
        if (!force && (!BingWallpaper.isStale(this) || now - lastBingAttempt < 30 * 60_000L)) return;
        lastBingAttempt = now;
        Context app = getApplicationContext();
        io.execute(() -> {
            boolean ok = BingWallpaper.fetch(app);
            main.post(() -> {
                if (ok) {
                    wallpaperStamp = -1;
                    loadWallpaper();
                } else if (force) {
                    Toast.makeText(this, R.string.bing_failed, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    // ---------------------------------------------------------------- helpers

    /** Changes whenever dock.txt or hidden.txt is added, edited or removed. */
    private long configStamp() {
        long stamp = 0;
        for (String name : new String[] {"dock.txt", "hidden.txt"}) {
            File f = new File(getFilesDir(), name);
            if (f.isFile()) stamp = stamp * 31 + f.lastModified() + f.length();
        }
        return stamp;
    }

    /**
     * Vector icons are rasterised once at their view size; the pills' 1.06 focus zoom then
     * upscales that small bitmap and blurs it. Pre-rendering at 2x means the zoom only ever
     * downsamples, so the icon stays sharp at both sizes.
     */
    private void sharpenIcon(ImageView v) {
        Drawable d = v.getDrawable();
        if (d == null || d.getIntrinsicWidth() <= 0 || d.getIntrinsicHeight() <= 0) return;
        int w = d.getIntrinsicWidth() * 2, h = d.getIntrinsicHeight() * 2;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(b));
        BitmapDrawable sharp = new BitmapDrawable(getResources(), b);
        sharp.setFilterBitmap(true);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setImageDrawable(sharp);
    }

    private void afterLayout(Runnable r) {
        root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                r.run();
            }
        });
        root.requestLayout();
    }

    private int rowHeight() { return Math.round(gridCellH); }

    private int maxGridScroll() {
        int rows = (gridApps.size() + columns - 1) / columns;
        return Math.max(0, rows * rowHeight() + grid.getPaddingBottom() - grid.getHeight());
    }

    private float dp(float v) { return v * density; }

    private static int clamp(int v, int min, int max) { return max < min ? min : Math.max(min, Math.min(max, v)); }
}
