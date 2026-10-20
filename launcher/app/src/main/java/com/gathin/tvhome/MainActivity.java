package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;

import com.gathin.tvhome.ui.AmbientBackdrop;
import com.gathin.tvhome.ui.GridShelfLayoutManager;
import com.gathin.tvhome.ui.GridSpacing;
import com.gathin.tvhome.ui.SegmentedControl;
import com.gathin.tvhome.ui.ShelfLayoutManager;

import java.util.Collections;
import java.util.List;

/**
 * The launcher: a top bar (profile, Settings, Search, the Home / Apps tabs, the clock) over two pages.
 * Home has the Jellyfin Continue Watching row and the pinned apps; Apps has every app on the same columns.
 *
 * Focus moves the platform's way inside a row or grid; this class only routes it between the top bar and the
 * pages (see {@link #routeKey}), and remembers where it was in each row so it returns there.
 */
public class MainActivity extends Activity {

    /** Sent by QuickSettingsActivity: open Launcher settings from here so Home rebuilds when they close. */
    static final String ACTION_LAUNCHER_SETTINGS = "com.gathin.tvhome.action.LAUNCHER_SETTINGS";
    private static final int LAUNCHER_SETTINGS_REQUEST = 42;

    private static final int PAGE_HOME = 0;
    private static final int PAGE_APPS = 1;
    /** The launcher grid: Home's media row has three columns, app tiles five. */
    private static final int MEDIA_COLUMNS = 3;
    private static final int APP_COLUMNS = 5;
    private static final long PAGE_IN_MS = 320;
    private static final long PAGE_OUT_MS = 160;

    private Config cfg;
    private boolean animations;
    private WallpaperPlayer wallpaperPlayer;
    private AmbientBackdrop ambient;
    private View topBar;
    private SegmentedControl tabs;
    private View pageHome, pageApps;
    private View cwSection;
    private RecyclerView cwRow, pinnedRow, appsGrid;
    private TextView cwCaption, appsCaption;
    private ContinueWatchingAdapter cwAdapter;
    private AppTileAdapter pinnedAdapter, appsAdapter;
    private final PathInterpolator ease = new PathInterpolator(0.2f, 0f, 0f, 1f);

    /** Where focus was in each row, so coming back down from the top bar lands there again. */
    private int cwFocus, appsFocus;

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // An update sends REMOVED then ADDED; keep the tile until the new APK is in.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            loadApps();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // config.xml, overlaid by config.json from a pushed theme (see Config and tools/theme.sh).
        cfg = Config.load(this);
        animations = cfg.animations && !cfg.reducedMotion;

        ViewGroup root = findViewById(R.id.root);
        wallpaperPlayer = new WallpaperPlayer(this, (android.widget.FrameLayout) root, cfg);
        // A theme's own wallpaper goes in under the bundled one; let it show.
        if (cfg.wallpaperEnabled && !cfg.wallpapers.isEmpty()) findViewById(R.id.wallpaper).setVisibility(View.GONE);
        ambient = findViewById(R.id.ambient);
        ambient.setAnimationsEnabled(animations);

        setUpTopBar();
        setUpHome();
        setUpApps();
        loadApps();
        showPage(PAGE_HOME, false);
        cwRow.post(this::focusHome);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, filter);
        }
        // Not after recreate(): the launch intent survives it and would open the settings again.
        if (savedInstanceState == null) openLauncherSettingsIfAsked(getIntent());
    }

    private void setUpTopBar() {
        topBar = findViewById(R.id.topBar);
        String name = BuildConfig.PROFILE_NAME;
        View profile = findViewById(R.id.btnProfile);
        profile.setVisibility(name.isEmpty() ? View.GONE : View.VISIBLE);
        ((TextView) findViewById(R.id.profileName)).setText(name);
        ((TextView) findViewById(R.id.avatar)).setText(name.isEmpty() ? "" : name.substring(0, 1).toUpperCase());
        profile.setOnClickListener(v -> toast(name));

        View settings = findViewById(R.id.btnSettings);
        settings.setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));
        settings.setOnLongClickListener(v -> {
            startActivityForResult(new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST);
            return true;
        });
        // Only offered when something can search: on TVs with Google's search app disabled nothing answers.
        View search = findViewById(R.id.btnSearch);
        Intent searchIntent = searchIntent();
        search.setVisibility(searchIntent != null ? View.VISIBLE : View.GONE);
        search.setOnClickListener(v -> open(searchIntent()));

        tabs = findViewById(R.id.tabs);
        tabs.setAnimationsEnabled(animations);
        tabs.setOnTabSelectedListener(index -> showPage(index, animations));
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> {
            if (now == null) return;
            // Focus in the top bar: the backdrop goes back to the wallpaper.
            if (isIn(now, topBar)) ambient.clear();
            remember(now);
        });
    }

    private void setUpHome() {
        int gutter = getResources().getDimensionPixelSize(R.dimen.gutter);
        pageHome = findViewById(R.id.pageHome);

        cwSection = findViewById(R.id.cwSection);
        View cwHeader = findViewById(R.id.cwHeader);
        ((TextView) cwHeader.findViewById(R.id.sectionTitle)).setText(R.string.continue_watching);
        cwCaption = cwHeader.findViewById(R.id.sectionCaption);
        cwAdapter = new ContinueWatchingAdapter(MEDIA_COLUMNS, gutter, new ContinueWatchingAdapter.Listener() {
            @Override
            public void onItemFocused(ContinueWatchingAdapter.Item item, Drawable art) {
                ambient.show(item.id, art);
            }

            @Override
            public void onItemClicked(ContinueWatchingAdapter.Item item) {
                JellyfinManager.launchItem(MainActivity.this, item.id);
            }

            @Override
            public void onPlaceholderFocused() {
                ambient.clear();
            }
        });
        setUpRow(cwRow = findViewById(R.id.cwRow), cwAdapter, gutter);

        View pinnedHeader = findViewById(R.id.pinnedHeader);
        ((TextView) pinnedHeader.findViewById(R.id.sectionTitle)).setText(R.string.pinned_apps);
        pinnedAdapter = new AppTileAdapter(APP_COLUMNS, gutter, appListener);
        setUpRow(pinnedRow = findViewById(R.id.pinnedRow), pinnedAdapter, gutter);
    }

    private void setUpRow(RecyclerView row, RecyclerView.Adapter<?> adapter, int gutter) {
        ShelfLayoutManager layout = new ShelfLayoutManager(this);
        layout.setAnimationsEnabled(animations);
        row.setLayoutManager(layout);
        row.addItemDecoration(new GridSpacing(1, gutter));
        row.setItemAnimator(null);
        row.setAdapter(adapter);
    }

    private void setUpApps() {
        int gutter = getResources().getDimensionPixelSize(R.dimen.gutter);
        pageApps = findViewById(R.id.pageApps);
        View header = findViewById(R.id.appsHeader);
        ((TextView) header.findViewById(R.id.sectionTitle)).setText(R.string.all_apps);
        appsCaption = header.findViewById(R.id.sectionCaption);
        appsAdapter = new AppTileAdapter(APP_COLUMNS, gutter, appListener);
        appsGrid = findViewById(R.id.appsGrid);
        GridShelfLayoutManager layout = new GridShelfLayoutManager(this, APP_COLUMNS);
        layout.setAnimationsEnabled(animations);
        appsGrid.setLayoutManager(layout);
        appsGrid.addItemDecoration(new GridSpacing(APP_COLUMNS, gutter));
        appsGrid.setItemAnimator(null);
        appsGrid.setAdapter(appsAdapter);
    }

    private final AppTileAdapter.Listener appListener = new AppTileAdapter.Listener() {
        @Override
        public void onAppFocused(InstalledApps.App app, View tile) {
            ambient.show(app.pkg, app.banner != null ? app.banner : app.icon);
        }

        @Override
        public void onAppClicked(InstalledApps.App app) {
            open(app.launchIntent(), app.label);
        }
    };

    private void loadApps() {
        List<InstalledApps.App> apps = InstalledApps.load(this, cfg.hiddenApps);
        pinnedAdapter.setApps(InstalledApps.pinned(apps, cfg.dockApps));
        appsAdapter.setApps(InstalledApps.pinnedFirst(apps, cfg.dockApps));
        appsCaption.setText(getString(R.string.app_count, apps.size()));
    }

    private void refreshJellyfin() {
        JellyfinManager.fetchContinueWatching(new JellyfinManager.Callback<List<ContinueWatchingAdapter.Item>>() {
            @Override
            public void onSuccess(List<ContinueWatchingAdapter.Item> result) {
                cwAdapter.setItems(result);
                cwCaption.setText(getString(R.string.jellyfin_items, result.size()));
                showContinueWatching(!result.isEmpty());
            }

            @Override
            public void onError(Exception e) {
                // Cards from an earlier answer stay up; the caption says they may be out of date.
                if (cwAdapter.hasItems()) {
                    cwCaption.setText(R.string.jellyfin_unavailable);
                    return;
                }
                cwAdapter.setItems(Collections.emptyList());
                showContinueWatching(false);
            }
        });
    }

    /**
     * Shows or hides the Continue Watching section. Hidden, the pinned apps are the first row: focus that was in it
     * moves there, and Up and Down route around it.
     */
    private void showContinueWatching(boolean show) {
        if ((cwSection.getVisibility() == View.VISIBLE) == show) return;
        boolean hadFocus = cwSection.hasFocus();
        cwSection.animate().cancel();
        if (show) {
            cwSection.setVisibility(View.VISIBLE);
            cwSection.setAlpha(0f);
            cwSection.animate().alpha(1f).setDuration(animations ? PAGE_IN_MS : 0).setInterpolator(ease).start();
        } else {
            cwSection.setVisibility(View.GONE);
            if (hadFocus) pinnedRow.post(() -> focusRow(pinnedRow, 0));
        }
    }

    // ---- pages ---------------------------------------------------------------------------------------

    /** Shows one page, sliding it up into place as the other fades away. */
    private void showPage(int page, boolean animate) {
        View in = page == PAGE_HOME ? pageHome : pageApps;
        View out = page == PAGE_HOME ? pageApps : pageHome;
        if (tabs.getSelectedIndex() != page) tabs.setSelectedIndex(page, false);
        out.animate().cancel();
        in.animate().cancel();
        if (!animate) {
            out.setVisibility(View.GONE);
            in.setVisibility(View.VISIBLE);
            in.setAlpha(1f);
            in.setTranslationY(0f);
            return;
        }
        if (out.getVisibility() == View.VISIBLE) {
            out.animate().alpha(0f).setDuration(PAGE_OUT_MS).setInterpolator(ease)
                    .withEndAction(() -> out.setVisibility(View.GONE)).start();
        }
        if (in.getVisibility() != View.VISIBLE || in.getAlpha() < 1f) {
            in.setVisibility(View.VISIBLE);
            in.setAlpha(0f);
            in.setTranslationY(getResources().getDimension(R.dimen.space_md));
            in.animate().alpha(1f).translationY(0f).setDuration(PAGE_IN_MS).setStartDelay(PAGE_OUT_MS / 2)
                    .setInterpolator(ease).start();
        }
    }

    private int currentPage() {
        return tabs.getSelectedIndex();
    }

    // ---- focus ---------------------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (handleSettingsKey(event)) return true;
        if (event.getAction() == KeyEvent.ACTION_DOWN && routeKey(event.getKeyCode())) return true;
        return super.dispatchKeyEvent(event);
    }

    /**
     * Moves focus between the top bar and the page where the platform's focus search would guess: Up from the
     * first row goes to the tabs (not to whichever top-bar button is nearest), Down from the top bar goes back
     * to the card focus left. Nothing is above the top bar or below the last row, so those keys stop there.
     */
    private boolean routeKey(int keyCode) {
        View focus = getCurrentFocus();
        if (focus == null) return false;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (isIn(focus, topBar)) return true;
            if (isIn(focus, firstHomeRow()) || (isIn(focus, appsGrid) && positionIn(appsGrid, focus) < APP_COLUMNS)) {
                tabs.requestFocus();
                return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (isIn(focus, topBar)) {
                if (currentPage() == PAGE_HOME) focusHome();
                else focusRow(appsGrid, appsFocus);
                return true;
            }
            if (isIn(focus, pinnedRow)) return true;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return atRowEnd(focus, keyCode == KeyEvent.KEYCODE_DPAD_RIGHT);
        }
        return false;
    }

    /**
     * Left on the first card of a row, or Right on the last, stops there. Otherwise the focus search finds the
     * nearest card in a neighbouring row, and focus jumps rows sideways.
     */
    private boolean atRowEnd(View focus, boolean right) {
        for (RecyclerView row : new RecyclerView[]{cwRow, pinnedRow, appsGrid}) {
            if (!isIn(focus, row) || row.getAdapter() == null) continue;
            int pos = positionIn(row, focus);
            if (pos == RecyclerView.NO_POSITION) return false;
            int last = row.getAdapter().getItemCount() - 1;
            int columns = row == appsGrid ? APP_COLUMNS : Integer.MAX_VALUE;
            int col = row == appsGrid ? pos % APP_COLUMNS : pos;
            return right ? pos == last || col == columns - 1 : col == 0;
        }
        return false;
    }

    private void remember(View focus) {
        if (isIn(focus, cwRow)) cwFocus = Math.max(0, positionIn(cwRow, focus));
        else if (isIn(focus, appsGrid)) appsFocus = Math.max(0, positionIn(appsGrid, focus));
    }

    /** Continue Watching, or the pinned apps while it is hidden. */
    private RecyclerView firstHomeRow() {
        return cwSection.getVisibility() == View.VISIBLE ? cwRow : pinnedRow;
    }

    /** Focus onto Home's first row, where it was last. */
    private void focusHome() {
        RecyclerView row = firstHomeRow();
        focusRow(row, row == cwRow ? cwFocus : 0);
    }

    private static boolean isIn(View view, View ancestor) {
        for (View v = view; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v == ancestor) return true;
        }
        return false;
    }

    private static int positionIn(RecyclerView list, View focus) {
        View item = list.findContainingItemView(focus);
        return item == null ? RecyclerView.NO_POSITION : list.getChildAdapterPosition(item);
    }

    /** Focuses item {@code position} of {@code list}, scrolling it on screen first if needed. */
    private void focusRow(RecyclerView list, int position) {
        RecyclerView.Adapter<?> adapter = list.getAdapter();
        if (adapter == null || adapter.getItemCount() == 0) return;
        int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(pos);
        if (holder != null) {
            holder.itemView.requestFocus();
            return;
        }
        list.scrollToPosition(pos);
        list.post(() -> {
            RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(pos);
            if (h != null) h.itemView.requestFocus();
        });
    }

    /** Home and Back both lead back to the start: the Home page, its first card, rows scrolled to the start. */
    private void goHome() {
        showPage(PAGE_HOME, animations);
        cwFocus = 0;
        cwRow.scrollToPosition(0);
        pinnedRow.scrollToPosition(0);
        cwRow.post(this::focusHome);
    }

    @Override
    public void onBackPressed() {
        // A launcher never closes; Back only returns to the start.
        goHome();
    }

    // ---- lifecycle -----------------------------------------------------------------------------------

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (openLauncherSettingsIfAsked(intent)) return;
        // Home pressed while Home is in front. Back from an app does not come this way, so focus stays put then.
        if (intent != null && intent.hasCategory(Intent.CATEGORY_HOME)) goHome();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshJellyfin();
        wallpaperPlayer.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        wallpaperPlayer.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packageReceiver);
        wallpaperPlayer.close();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == LAUNCHER_SETTINGS_REQUEST && resultCode == RESULT_OK) recreate();
    }

    private boolean openLauncherSettingsIfAsked(Intent intent) {
        if (intent == null || !ACTION_LAUNCHER_SETTINGS.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        startActivityForResult(new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST);
        return true;
    }

    /**
     * A Settings button sent as a keycode (settings_key_keycodes, e.g. 83 on TCL Google TV remotes) opens the same
     * thing HomeKeyService would, for when that service is not running.
     */
    private boolean handleSettingsKey(KeyEvent event) {
        for (int code : cfg.settingsKeycodes) {
            if (event.getKeyCode() != code) continue;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                open(cfg.settingsKeyPanel ? new Intent(this, QuickSettingsActivity.class)
                        : new Intent(Settings.ACTION_SETTINGS));
            }
            return true;
        }
        return false;
    }

    // ---- opening things ------------------------------------------------------------------------------

    /** Google TV's search, else the assistant (voice search on Android TV); null when neither is available. */
    private Intent searchIntent() {
        for (String action : new String[]{"android.search.action.GLOBAL_SEARCH", Intent.ACTION_ASSIST}) {
            Intent i = new Intent(action);
            if (i.resolveActivity(getPackageManager()) != null) return i;
        }
        return null;
    }

    private void open(Intent intent) {
        open(intent, null);
    }

    private void open(Intent intent, String label) {
        if (intent == null) return;
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            if (label != null) toast(getString(R.string.cannot_open, label));
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
