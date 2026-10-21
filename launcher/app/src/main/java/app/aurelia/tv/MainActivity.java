package app.aurelia.tv;

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
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import app.aurelia.tv.jellyfin.Account;
import app.aurelia.tv.jellyfin.Api;
import app.aurelia.tv.jellyfin.Catalog;
import app.aurelia.tv.jellyfin.Library;
import app.aurelia.tv.player.PlayerActivity;
import app.aurelia.tv.ui.AmbientBackdrop;
import app.aurelia.tv.ui.GlassMenu;
import app.aurelia.tv.ui.GlassSheet;
import app.aurelia.tv.ui.GridShelfLayoutManager;
import app.aurelia.tv.ui.GridSpacing;
import app.aurelia.tv.ui.ImageDisk;
import app.aurelia.tv.ui.ScrollEdge;
import app.aurelia.tv.ui.SegmentedControl;
import app.aurelia.tv.ui.ShelfLayoutManager;
import app.aurelia.tv.update.Updates;

import java.util.Collections;
import java.util.List;

/**
 * The launcher: a top bar (profile, Settings, Search, the tabs, the clock) over four pages. Home has the Jellyfin
 * Continue Watching row and the pinned apps; Movies and TV Shows are the Jellyfin libraries ({@link LibraryPage});
 * Apps has every app on the same columns.
 *
 * Focus in the top bar moves the platform's way. Between rows and inside them this class moves it itself (see
 * {@link #routeKey}): one card at a time, and back to where it last was in each row.
 */
public class MainActivity extends Activity {

    /** Sent by QuickSettingsActivity: open Launcher settings from here so Home rebuilds when they close. */
    static final String ACTION_LAUNCHER_SETTINGS = "app.aurelia.tv.action.LAUNCHER_SETTINGS";
    private static final int LAUNCHER_SETTINGS_REQUEST = 42;

    private static final int PAGE_HOME = 0;
    private static final int PAGE_MOVIES = 1;
    private static final int PAGE_SHOWS = 2;
    private static final int PAGE_APPS = 3;
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
    private ScrollEdge edge;
    /** Every page, by index: Home, Movies, TV Shows, Apps. */
    private View[] pages;
    private LibraryPage movies, shows;
    private GlassMenu menu;
    private RecyclerView cwRow, nuRow, pinnedRow, appsGrid;
    private View nuSection;
    private TextView cwTitle, cwCaption, nuCaption, appsCaption;
    private ContinueWatchingAdapter cwAdapter, nuAdapter;
    private AppTileAdapter pinnedAdapter, appsAdapter;
    private GlassSheet sheet;
    private Account account;
    private AccountPanel accountPanel;
    private final Account.Listener accountListener = this::onAccountChanged;
    private final PathInterpolator ease = new PathInterpolator(0.2f, 0f, 0f, 1f);

    /** Where focus was in each row, so coming back to a row lands there again. */
    private int cwFocus, nuFocus, pinnedFocus, appsFocus;

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

        account = Account.get(this);
        ImageDisk.init(this);
        sheet = findViewById(R.id.sheet);
        sheet.setAnimationsEnabled(animations);
        accountPanel = new AccountPanel(this, sheet, findViewById(R.id.content));
        setUpTopBar();
        setUpHome();
        setUpLibraries();
        setUpApps();
        pages = new View[] {pageHome, movies.view(), shows.view(), pageApps};
        setUpEdge();
        account.addListener(accountListener);
        updateProfile();
        loadApps();
        showPage(PAGE_HOME, false);
        cwRow.post(() -> focusRow(cwRow, 0));

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
        findViewById(R.id.avatar).setClipToOutline(true);
        findViewById(R.id.btnProfile).setOnClickListener(v -> accountPanel.open());

        View settings = findViewById(R.id.btnSettings);
        settings.setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));
        settings.setOnLongClickListener(v -> {
            startActivityForResult(new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST);
            return true;
        });
        findViewById(R.id.btnSearch).setOnClickListener(v -> SearchActivity.start(this));

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

        View cwHeader = findViewById(R.id.cwHeader);
        ((TextView) cwHeader.findViewById(R.id.sectionTitle)).setText(R.string.continue_watching);
        cwTitle = cwHeader.findViewById(R.id.sectionTitle);
        cwCaption = cwHeader.findViewById(R.id.sectionCaption);
        ContinueWatchingAdapter.Listener mediaListener = new ContinueWatchingAdapter.Listener() {
            @Override
            public void onItemFocused(Library.MediaItem item, Drawable art) {
                ambient.show(item.id, art);
            }

            @Override
            public void onItemClicked(Library.MediaItem item) {
                PlayerActivity.start(MainActivity.this, item.id);
            }

            @Override
            public void onNothingFocused() {
                ambient.clear();
            }
        };
        cwAdapter = new ContinueWatchingAdapter(MEDIA_COLUMNS, gutter, mediaListener);
        setUpRow(cwRow = findViewById(R.id.cwRow), cwAdapter, gutter);

        nuSection = findViewById(R.id.nuSection);
        View nuHeader = findViewById(R.id.nuHeader);
        ((TextView) nuHeader.findViewById(R.id.sectionTitle)).setText(R.string.next_up);
        nuCaption = nuHeader.findViewById(R.id.sectionCaption);
        nuAdapter = new ContinueWatchingAdapter(MEDIA_COLUMNS, gutter, mediaListener);
        setUpRow(nuRow = findViewById(R.id.nuRow), nuAdapter, gutter);
        // The rows as they were last time, at once, while the server is asked again: placeholders only when
        // there is nothing kept (the first start, or another account).
        Library.HomeRows kept = Library.keptHomeRows(this, account);
        if (kept != null) showHomeRows(kept);
        ((app.aurelia.tv.ui.SectionScrollView) pageHome).setAlignTop(false);
        ((app.aurelia.tv.ui.SectionScrollView) pageHome).setAnimationsEnabled(animations);

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

    private void setUpLibraries() {
        menu = findViewById(R.id.menu);
        ViewGroup container = findViewById(R.id.pages);
        LibraryPage.Host host = new LibraryPage.Host() {
            @Override
            public void onItemFocused(Catalog.Item item, Drawable art) {
                ambient.show(item.id, art);
            }

            @Override
            public void onNothingFocused() {
                ambient.clear();
            }

            @Override
            public void onOpen(Catalog.Item item) {
                if (item.isSeries()) ShowActivity.start(MainActivity.this, item.id);
                else MovieActivity.start(MainActivity.this, item.id);
            }

            @Override
            public void onExitUp() {
                tabs.requestFocus();
            }

            @Override
            public void onSignIn() {
                accountPanel.open();
            }

            @Override
            public GlassMenu menu() {
                return menu;
            }

            @Override
            public ViewGroup background() {
                return findViewById(R.id.content);
            }

            @Override
            public void onScrolled(boolean scrolled) {
                updateEdge(true);
            }
        };
        movies = new LibraryPage(this, container, Catalog.Kind.Movie, host);
        shows = new LibraryPage(this, container, Catalog.Kind.Series, host);
        movies.setAnimationsEnabled(animations);
        shows.setAnimationsEnabled(animations);
        // Apps stays last, over the libraries, as it was in the layout.
        pageApps = findViewById(R.id.pageApps);
        pageApps.bringToFront();
    }

    /** The library shown on {@code page}, or null for Home and Apps. */
    private LibraryPage library(int page) {
        return page == PAGE_MOVIES ? movies : page == PAGE_SHOWS ? shows : null;
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

    // ---- Jellyfin ------------------------------------------------------------------------------------

    /** The profile button: the signed-in user's picture, or their initial until it loads; a person icon when signed out. */
    private void updateProfile() {
        View button = findViewById(R.id.btnProfile);
        TextView initial = findViewById(R.id.avatarInitial);
        ImageView picture = findViewById(R.id.avatarImage);
        picture.animate().cancel();
        if (!account.isSignedIn()) {
            button.setContentDescription(getString(R.string.sign_in));
            initial.setText("");
            int pad = getResources().getDimensionPixelSize(R.dimen.space_2xs) + 2;
            picture.setPadding(pad, pad, pad, pad);
            picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
            picture.setImageResource(R.drawable.ic_person);
            picture.setAlpha(1f);
            return;
        }
        String name = account.userName();
        button.setContentDescription(name);
        initial.setText(name.isEmpty() ? "" : name.substring(0, 1).toUpperCase());
        picture.setImageDrawable(null);
        String user = account.userId();
        Api.async(() -> Library.avatar(account), new Api.Callback<android.graphics.Bitmap>() {
            @Override
            public void onResult(android.graphics.Bitmap bmp) {
                if (bmp == null || !user.equals(account.userId())) return;
                picture.setPadding(0, 0, 0, 0);
                picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
                picture.setImageBitmap(bmp);
                picture.setAlpha(0f);
                picture.animate().alpha(1f).setDuration(animations ? PAGE_IN_MS : 0).start();
            }

            @Override
            public void onError(Exception e) {
            }
        });
    }

    private void onAccountChanged() {
        updateProfile();
        updateRow(cwAdapter::showLoading);
        refreshJellyfin();
        movies.reset();
        shows.reset();
        LibraryPage shown = library(currentPage());
        if (shown != null) shown.reload();
    }

    /**
     * Fills Continue Watching and Next Up. The first row always has a card to focus: what is in progress, or
     * with nothing in progress the next episodes (and no second row), or a card that says why there is nothing
     * (signed out, server unreachable, all caught up) and does the obvious thing when selected.
     */
    private void refreshJellyfin() {
        if (!account.isSignedIn()) {
            showNextUp(Collections.emptyList());
            cwTitle.setText(R.string.continue_watching);
            cwCaption.setText(null);
            updateRow(() -> cwAdapter.showMessage(new ContinueWatchingAdapter.Message(R.drawable.ic_link,
                    getString(R.string.cw_connect_title), getString(R.string.cw_connect_body),
                    getString(R.string.jellyfin_connect_title), accountPanel::open)));
            return;
        }
        Library.homeRows(account, new Api.Callback<Library.HomeRows>() {
            @Override
            public void onResult(Library.HomeRows rows) {
                showHomeRows(rows);
                Library.keepHomeRows(getApplicationContext(), account, rows);
            }

            @Override
            public void onError(Exception e) {
                // A token the server no longer accepts has signed the launcher out, which refilled the row.
                if (e instanceof Library.NotSignedIn) return;
                String server = account.serverName().isEmpty() ? account.server() : account.serverName();
                String offline = getString(R.string.cw_offline_title, server);
                // Cards from an earlier answer stay up; the caption says they may be out of date.
                if (cwAdapter.hasItems()) {
                    cwCaption.setText(offline);
                    return;
                }
                cwCaption.setText(null);
                updateRow(() -> cwAdapter.showMessage(new ContinueWatchingAdapter.Message(R.drawable.ic_offline,
                        offline, getString(R.string.cw_offline_body), getString(R.string.jellyfin_try_again), () -> {
                            updateRow(cwAdapter::showLoading);
                            refreshJellyfin();
                        })));
            }
        });
    }

    /** Next Up's row, gone when there is nothing in it; focus in it moves up a row if it goes. */
    /** Continue Watching and Next Up from {@code rows}: fresh from the server, or kept from last time. */
    private void showHomeRows(Library.HomeRows rows) {
        // Nothing in progress: the next episodes take the first row.
        boolean nextFirst = rows.resume.isEmpty() && !rows.nextUp.isEmpty();
        List<Library.MediaItem> items = nextFirst ? rows.nextUp : rows.resume;
        showNextUp(nextFirst ? Collections.emptyList() : rows.nextUp);
        cwTitle.setText(nextFirst ? R.string.next_up : R.string.continue_watching);
        if (items.isEmpty()) {
            cwCaption.setText(null);
            updateRow(() -> cwAdapter.showMessage(new ContinueWatchingAdapter.Message(R.drawable.ic_check,
                    getString(R.string.cw_empty_title), getString(R.string.cw_empty_body),
                    getString(R.string.cw_browse_movies), () -> {
                        showPage(PAGE_MOVIES, animations);
                        movies.focusIn();
                    })));
            return;
        }
        cwCaption.setText(nextFirst
                ? getResources().getQuantityString(R.plurals.next_up_shows, items.size(), items.size())
                : getString(R.string.jellyfin_items, items.size()));
        updateRow(() -> cwAdapter.showItems(items, Library.auth(account)));
    }

    private void showNextUp(List<Library.MediaItem> items) {
        boolean hadFocus = nuRow.hasFocus();
        nuCaption.setText(items.isEmpty() ? null
                : getResources().getQuantityString(R.plurals.next_up_shows, items.size(), items.size()));
        if (items.isEmpty()) {
            nuSection.setVisibility(View.GONE);
            if (hadFocus) focusRow(cwRow, cwFocus);
            return;
        }
        nuAdapter.showItems(items, Library.auth(account));
        nuSection.setVisibility(View.VISIBLE);
        if (hadFocus) nuRow.post(() -> {
            if (!nuRow.hasFocus() && !sheet.isShowing()) focusRow(nuRow, Math.min(nuFocus, items.size() - 1));
        });
    }

    /** Changes Continue Watching; if that rebuilds the card that had focus, focus goes back into the row. */
    private void updateRow(Runnable change) {
        boolean hadFocus = cwRow.hasFocus();
        change.run();
        if (hadFocus) cwRow.post(() -> {
            if (!cwRow.hasFocus() && !sheet.isShowing()) focusRow(cwRow, cwFocus);
        });
    }

    // ---- the scroll edge ------------------------------------------------------------------------------

    /** The band and shadow the pages scroll under (ui.ScrollEdge), kept to the page showing. */
    private void setUpEdge() {
        edge = new ScrollEdge(findViewById(R.id.edgeBand), findViewById(R.id.edgeShadow));
        ((ScrollView) pageHome).setOnScrollChangeListener((v, x, y, ox, oy) -> updateEdge(true));
        appsGrid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView list, int dx, int dy) {
                updateEdge(true);
            }
        });
        View root = findViewById(R.id.root);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateEdge(false));
    }

    /** Page {@code page} back at its top, focus to go to its first card. */
    private void toTop(int page) {
        LibraryPage library = library(page);
        if (library != null) {
            library.toTop();
        } else if (page == PAGE_APPS) {
            appsFocus = 0;
            appsGrid.scrollToPosition(0);
        } else {
            cwFocus = nuFocus = pinnedFocus = 0;
            cwRow.scrollToPosition(0);
            nuRow.scrollToPosition(0);
            pinnedRow.scrollToPosition(0);
            pageHome.scrollTo(0, 0);
        }
    }

    /** Draws the edge where the page showing cuts its content off, raised if that page is scrolled. */
    private void updateEdge(boolean animate) {
        if (edge == null || pages == null) return;
        View root = findViewById(R.id.root);
        int page = currentPage();
        LibraryPage library = library(page);
        boolean scrolled;
        if (library != null) {
            edge.setEdge(library.content(), root, false);
            scrolled = library.scrolled();
        } else if (page == PAGE_APPS) {
            edge.setEdge(appsGrid, root, false);
            scrolled = appsGrid.computeVerticalScrollOffset() > 0;
        } else {
            edge.setEdge(pageHome, root, true);
            scrolled = pageHome.getScrollY() > 0;
        }
        edge.setRaised(scrolled, animate);
    }

    // ---- pages ---------------------------------------------------------------------------------------

    /** Shows one page, sliding it up into place as the others fade away. A library loads the first time it shows. */
    private void showPage(int page, boolean animate) {
        View in = pages[page];
        if (tabs.getSelectedIndex() != page) tabs.setSelectedIndex(page, false);
        LibraryPage library = library(page);
        // A page switched to starts afresh, at its top and its first card, rather than wherever it was left.
        if (in.getVisibility() != View.VISIBLE) toTop(page);
        if (library != null) library.showing();
        edge.setRaised(false, false);
        in.post(() -> updateEdge(animate));
        in.animate().cancel();
        for (View out : pages) {
            if (out == in) continue;
            out.animate().cancel();
            if (!animate) {
                out.setVisibility(View.GONE);
            } else if (out.getVisibility() == View.VISIBLE) {
                out.animate().alpha(0f).setDuration(PAGE_OUT_MS).setInterpolator(ease)
                        .withEndAction(() -> out.setVisibility(View.GONE)).start();
            }
        }
        if (!animate) {
            in.setVisibility(View.VISIBLE);
            in.setAlpha(1f);
            in.setTranslationY(0f);
            return;
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
     * Moves focus where the platform's focus search would guess wrong. Up from Home's first row or the Apps grid's
     * goes to the tabs, not to the nearest top-bar button; Down from the top bar returns to the card focus left.
     * Inside a row or the grid, focus moves one card per key here too: the platform searches only among cards
     * already laid out, so a key held down outruns the scrolling and focus escaped to the top bar.
     */
    private boolean routeKey(int keyCode) {
        View focus = getCurrentFocus();
        if (focus == null || sheet.isShowing() || menu.isShowing()) return false;
        LibraryPage library = library(currentPage());
        if (library != null && !isIn(focus, topBar)) return library.routeKey(keyCode, focus);
        boolean right = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (isIn(focus, topBar)) return true;
                if (isIn(focus, cwRow)) return tabs.requestFocus();
                if (isIn(focus, nuRow)) return focusRow(cwRow, cwFocus);
                if (isIn(focus, pinnedRow)) {
                    return nuSection.getVisibility() == View.VISIBLE ? focusRow(nuRow, nuFocus) : focusRow(cwRow, cwFocus);
                }
                if (isIn(focus, appsGrid)) {
                    int pos = positionIn(appsGrid, focus);
                    return pos < APP_COLUMNS ? tabs.requestFocus() : focusRow(appsGrid, pos - APP_COLUMNS);
                }
                return false;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (isIn(focus, topBar)) {
                    if (library != null) {
                        library.focusIn();
                        return true;
                    }
                    return currentPage() == PAGE_HOME ? focusRow(cwRow, cwFocus) : focusRow(appsGrid, appsFocus);
                }
                if (isIn(focus, cwRow)) {
                    return nuSection.getVisibility() == View.VISIBLE ? focusRow(nuRow, nuFocus)
                            : focusRow(pinnedRow, pinnedFocus);
                }
                if (isIn(focus, nuRow)) return focusRow(pinnedRow, pinnedFocus);
                if (isIn(focus, pinnedRow)) return true;
                if (isIn(focus, appsGrid)) {
                    int pos = positionIn(appsGrid, focus), last = appsAdapter.getItemCount() - 1;
                    // From the last full row, Down goes to the last app when the row below is shorter.
                    if (pos / APP_COLUMNS == last / APP_COLUMNS) return true;
                    return focusRow(appsGrid, Math.min(pos + APP_COLUMNS, last));
                }
                return false;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                for (RecyclerView row : new RecyclerView[]{cwRow, nuRow, pinnedRow}) {
                    if (!isIn(focus, row)) continue;
                    int pos = positionIn(row, focus) + (right ? 1 : -1);
                    if (pos >= 0 && row.getAdapter() != null && pos < row.getAdapter().getItemCount()) focusRow(row, pos);
                    return true;   // the ends of a row stop focus rather than letting it jump rows
                }
                if (isIn(focus, appsGrid)) {
                    int pos = positionIn(appsGrid, focus), col = pos % APP_COLUMNS;
                    boolean end = right ? col == APP_COLUMNS - 1 || pos == appsAdapter.getItemCount() - 1 : col == 0;
                    if (!end) focusRow(appsGrid, pos + (right ? 1 : -1));
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    private void remember(View focus) {
        if (isIn(focus, cwRow)) cwFocus = Math.max(0, positionIn(cwRow, focus));
        else if (isIn(focus, nuRow)) nuFocus = Math.max(0, positionIn(nuRow, focus));
        else if (isIn(focus, pinnedRow)) pinnedFocus = Math.max(0, positionIn(pinnedRow, focus));
        else if (isIn(focus, appsGrid)) appsFocus = Math.max(0, positionIn(appsGrid, focus));
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

    /**
     * Focuses item {@code position} of {@code list}, scrolling it on screen first if needed. Always true: the key
     * that asked for it is handled either way.
     */
    private boolean focusRow(RecyclerView list, int position) {
        RecyclerView.Adapter<?> adapter = list.getAdapter();
        if (adapter == null || adapter.getItemCount() == 0) return true;
        int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(pos);
        if (holder != null) {
            holder.itemView.requestFocus();
            return true;
        }
        list.scrollToPosition(pos);
        list.post(() -> {
            RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(pos);
            if (h != null) h.itemView.requestFocus();
        });
        return true;
    }

    /** Home and Back both lead back to the start: the Home page, its first card, rows scrolled to the start. */
    private void goHome() {
        showPage(PAGE_HOME, animations);
        cwFocus = nuFocus = pinnedFocus = 0;
        cwRow.scrollToPosition(0);
        nuRow.scrollToPosition(0);
        pinnedRow.scrollToPosition(0);
        pageHome.scrollTo(0, 0);
        cwRow.post(() -> focusRow(cwRow, 0));
    }

    @Override
    public void onBackPressed() {
        // A launcher never closes; Back steps back through a panel, and otherwise returns to the start.
        if (accountPanel.isOpen()) accountPanel.back();
        else if (menu.isShowing()) menu.hide();
        else goHome();
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
        checkForUpdate();
        // Back from a film or show: what was watched or favourited there shows on its poster.
        LibraryPage shown = library(currentPage());
        if (shown != null) shown.refresh();
        wallpaperPlayer.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        wallpaperPlayer.stop();
    }

    /** Marks the profile button while a newer version waits, asking GitHub every few hours (update.Updates). */
    private void checkForUpdate() {
        View dot = findViewById(R.id.updateDot);
        dot.setVisibility(Updates.known(this) != null ? View.VISIBLE : View.GONE);
        if (!Updates.due(this)) return;
        Context app = getApplicationContext();
        Api.async(() -> Updates.check(app) != null, new Api.Callback<Boolean>() {
            @Override
            public void onResult(Boolean newer) {
                dot.setVisibility(newer ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onError(Exception e) {
                // Offline, or GitHub busy: next time.
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        account.removeListener(accountListener);
        accountPanel.close();
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
