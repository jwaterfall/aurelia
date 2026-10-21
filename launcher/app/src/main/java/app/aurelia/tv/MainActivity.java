package app.aurelia.tv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
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
import androidx.annotation.Nullable;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

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
    private TextView cwTitle, cwCaption, nuCaption;
    private ContinueWatchingAdapter cwAdapter, nuAdapter;
    private AppTileAdapter pinnedAdapter, appsAdapter;
    private GlassSheet sheet;
    private Account account;
    /** The framed hero at the top of Home. */
    private HomeHero hero;
    /** Recently added, under the hero; Continue Watching and Next Up are in the hero instead. */
    private View recentSection, cwSection;
    private RecyclerView recentRow;
    private PosterAdapter recentAdapter;
    private int recentFocus;
    private AccountPanel accountPanel;
    private final Account.Listener accountListener = this::onAccountChanged;
    private final PathInterpolator ease = new PathInterpolator(0.2f, 0f, 0f, 1f);

    /** Every app, by name, and the packages pinned to Home in their order (the theme's until changed here). */
    private List<InstalledApps.App> allApps = Collections.emptyList();
    private List<String> pinned;
    /** Continue Watching and Next Up as they show now, for the cards' menus. */
    private Library.HomeRows homeRows;
    /** Counts the requests for Home's rows, so an answer can be told from one asked for before a change. */
    private int homeRequest;
    /** Counts Recently added's questions, so only the latest answer shows. */
    private int recentRequest;
    /**
     * Cards taken off Home from their menu, by id, with the first request for Home's rows whose answer may show
     * them again: none while the server is still being told, then one asked for after it knew.
     */
    private final Map<String, Integer> takenOff = new HashMap<>();

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
        pinned = new ArrayList<>(cfg.dockApps);
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
        cwRow.post(this::focusHomeStart);

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
        // Signed in, a compact menu under the button; signing in keeps the panel, for its code and servers.
        findViewById(R.id.btnProfile).setOnClickListener(v -> {
            if (account.isSignedIn()) showProfileMenu(v);
            else accountPanel.open();
        });

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
            public boolean onItemLongClicked(Library.MediaItem item, View card) {
                showMediaOptions(item, card);
                return true;
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
        hero = new HomeHero(findViewById(R.id.heroSection), account, new HomeHero.Listener() {
            @Override
            public void onShown(String id, Drawable art) {
                ambient.show(id, art);
            }

            @Override
            public void onOpen(Catalog.Item item) {
                if (item.isSeries()) ShowActivity.start(MainActivity.this, item.id);
                else MovieActivity.start(MainActivity.this, item.id);
            }

            @Override
            public void onPlay(Library.MediaItem item) {
                PlayerActivity.start(MainActivity.this, item.id);
            }

            @Override
            public void onOptions(Library.MediaItem item, View card) {
                showMediaOptions(item, card);
            }
        });
        hero.setAnimationsEnabled(animations);
        cwSection = findViewById(R.id.cwSection);
        recentSection = findViewById(R.id.recentSection);
        ((TextView) findViewById(R.id.recentHeader).findViewById(R.id.sectionTitle)).setText(R.string.recently_added);
        recentAdapter = new PosterAdapter(account, LibraryPage.COLUMNS, gutter, new PosterAdapter.Listener() {
            @Override
            public void onFocused(Catalog.Item item, Drawable art) {
                ambient.show(item.id, art);
            }

            @Override
            public void onClicked(Catalog.Item item) {
                if (item.isSeries()) ShowActivity.start(MainActivity.this, item.id);
                else MovieActivity.start(MainActivity.this, item.id);
            }

            @Override
            public void onNearEnd() {
            }
        });
        setUpRow(recentRow = findViewById(R.id.recentRow), recentAdapter, gutter);
        // The rows as they were last time, at once, while the server is asked again: placeholders only when
        // there is nothing kept (the first start, or another account).
        Library.HomeRows kept = Library.keptHomeRows(this, account);
        if (kept != null) showHomeRows(kept);
        ((app.aurelia.tv.ui.SectionScrollView) pageHome).setAlignTop(false);
        ((app.aurelia.tv.ui.SectionScrollView) pageHome).setAnimationsEnabled(animations);
        // The row under the hero peeks in and fades out, so it reads as more below, not cut off.
        ((app.aurelia.tv.ui.SectionScrollView) pageHome).setBottomFadeOnly(
                getResources().getDimensionPixelSize(R.dimen.space_3xl) + getResources().getDimensionPixelSize(R.dimen.space_lg));

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
        menu.setAnimationsEnabled(animations);
        // The hero holds still while a menu is open, so the menu's frost stays true to what is behind it.
        menu.setOnOpenChanged(open -> {
            if (open) hero.stop();
            else hero.start();
        });
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

        @Override
        public boolean onAppLongClicked(InstalledApps.App app, View tile) {
            showAppOptions(app, tile);
            return true;
        }
    };

    private void loadApps() {
        allApps = InstalledApps.load(this, cfg.hiddenApps);
        showApps();
    }

    private void showApps() {
        pinnedAdapter.setApps(InstalledApps.pinned(allApps, pinned));
        appsAdapter.setApps(InstalledApps.pinnedFirst(allApps, pinned));
    }

    // ---- quick options: OK held on a tile or card ----------------------------------------------------------

    /**
     * An app's menu: pin or unpin it, move it along the pinned row (from that row only, where left and right are
     * plain), and Android's own pages for it. Disable and Force stop live in App info: a launcher cannot do them.
     */
    private void showAppOptions(InstalledApps.App app, View tile) {
        List<String> shown = packages(pinnedAdapter.apps());
        boolean isPinned = shown.contains(app.pkg);
        RecyclerView list = isIn(tile, pinnedRow) ? pinnedRow : appsGrid;
        GlassMenu.Builder m = new GlassMenu.Builder()
                .add(GlassMenu.Item.heading(app.label))
                .add(GlassMenu.Item.option(getString(isPinned ? R.string.menu_unpin : R.string.menu_pin), null, false,
                        () -> changePinned(isPinned ? PinnedApps.unpin(pinned, app.pkg) : PinnedApps.pin(pinned, app.pkg),
                                app, list)));
        if (isPinned && list == pinnedRow) {
            // The row mirrors for right-to-left languages: there, left is one place later.
            int left = pinnedRow.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? 1 : -1;
            int at = shown.indexOf(app.pkg);
            if (at + left >= 0 && at + left < shown.size()) {
                m.add(GlassMenu.Item.option(getString(R.string.menu_move_left), null, false,
                        () -> changePinned(PinnedApps.move(pinned, shown, app.pkg, left), app, pinnedRow)));
            }
            if (at - left >= 0 && at - left < shown.size()) {
                m.add(GlassMenu.Item.option(getString(R.string.menu_move_right), null, false,
                        () -> changePinned(PinnedApps.move(pinned, shown, app.pkg, -left), app, pinnedRow)));
            }
        }
        Uri pkg = Uri.fromParts("package", app.pkg, null);
        m.add(GlassMenu.Item.divider())
                .add(GlassMenu.Item.option(getString(R.string.menu_app_info), null, false,
                        () -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg), app.label)));
        // Android asks before it uninstalls; the package receiver then takes the tile away.
        if (app.removable) {
            m.add(GlassMenu.Item.option(getString(R.string.menu_uninstall), null, false,
                    () -> open(new Intent(Intent.ACTION_DELETE, pkg), app.label)));
        }
        menu.show(tile, m, findViewById(R.id.content));
    }

    /**
     * Saves {@code fresh} as the pinned apps and shows it. Focus stays with {@code app} in {@code list} (the tiles
     * move rather than being rebuilt), or, unpinned from Home's row, goes to the tile now in its place.
     */
    private void changePinned(List<String> fresh, InstalledApps.App app, RecyclerView list) {
        int was = positionIn(list, getCurrentFocus());
        pinned = fresh;
        PinnedApps.save(this, fresh);
        // The row would keep the moved tile where it was on screen, which pushes the first tile off the edge when
        // the first one moves; it keeps its scroll by position instead, still on its columns.
        RecyclerView.LayoutManager lm = list.getLayoutManager();
        Parcelable scrolled = lm != null ? lm.onSaveInstanceState() : null;
        showApps();
        if (scrolled != null) lm.onRestoreInstanceState(scrolled);
        AppTileAdapter adapter = (AppTileAdapter) list.getAdapter();
        int now = packages(adapter.apps()).indexOf(app.pkg);
        list.post(() -> {
            if (now >= 0) focusRow(list, now);
            else if (adapter.getItemCount() > 0) focusRow(list, Math.max(0, was));
            else if (nuSection.getVisibility() == View.VISIBLE) focusRow(nuRow, nuFocus);
            else focusRow(cwRow, cwFocus);
        });
    }

    private static List<String> packages(List<InstalledApps.App> apps) {
        List<String> out = new ArrayList<>();
        for (InstalledApps.App a : apps) out.add(a.pkg);
        return out;
    }

    /**
     * A card's menu. In progress: mark it watched, take it off Continue Watching (its place goes back to the start),
     * play it from the start. Next up: mark it watched. Either can open its page: an episode's show, or the film.
     */
    private void showMediaOptions(Library.MediaItem item, View card) {
        boolean resuming = homeRows != null && homeRows.isResuming(item.id);
        GlassMenu.Builder m = new GlassMenu.Builder()
                .add(GlassMenu.Item.heading(item.title))
                .add(GlassMenu.Item.option(getString(R.string.menu_mark_watched), null, false,
                        () -> takeOff(item, () -> {
                            Catalog.setPlayed(account, item.id, true);
                            return null;
                        })));
        if (resuming) {
            m.add(GlassMenu.Item.option(getString(R.string.menu_remove_resume), null, false,
                    () -> takeOff(item, () -> {
                        Library.clearResume(account, item.id);
                        return null;
                    })));
            m.add(GlassMenu.Item.option(getString(R.string.menu_play_from_start), null, false,
                    () -> PlayerActivity.start(this, item.id, true)));
        }
        // Its page, a shortcut past the library: an episode's show (open on the episode's season), or the film's own.
        if (!item.seriesId.isEmpty()) {
            m.add(GlassMenu.Item.option(getString(R.string.menu_go_to_show), null, false,
                    () -> ShowActivity.start(this, item.seriesId)));
        } else {
            m.add(GlassMenu.Item.option(getString(R.string.menu_go_to_movie), null, false,
                    () -> MovieActivity.start(this, item.id)));
        }
        menu.show(card, m, findViewById(R.id.content));
    }

    /**
     * Takes {@code item}'s card off Home at once, and off the rows kept for the next start, while {@code change}
     * tells the server. Answers already on their way leave it off; once the server knows, Home asks again, as
     * marking an episode watched brings on the next one. If the server says no, the card comes back.
     */
    private void takeOff(Library.MediaItem item, Callable<Void> change) {
        if (homeRows == null) return;
        Library.HomeRows before = homeRows;
        takenOff.put(item.id, Integer.MAX_VALUE);
        showHomeRows(homeRows.without(item.id));
        Library.keepHomeRows(getApplicationContext(), account, homeRows);
        Api.async(change, new Api.Callback<Void>() {
            @Override
            public void onResult(Void done) {
                takenOff.put(item.id, homeRequest + 1);
                refreshJellyfin();
            }

            @Override
            public void onError(Exception e) {
                takenOff.remove(item.id);
                showHomeRows(before);
                Library.keepHomeRows(getApplicationContext(), account, before);
            }
        });
    }

    /** {@code rows} without the cards taken off before request {@code request} could know of it. */
    private Library.HomeRows withoutTakenOff(Library.HomeRows rows, int request) {
        for (Map.Entry<String, Integer> e : new ArrayList<>(takenOff.entrySet())) {
            if (request >= e.getValue()) takenOff.remove(e.getKey());
            else rows = rows.without(e.getKey());
        }
        return rows;
    }

    // ---- Jellyfin ------------------------------------------------------------------------------------

    /**
     * The profile button's menu while signed in: who and where (no picture: the button shows it), a waiting update
     * (marked in the icon's blue, and showing its progress in its own row), the Jellyfin app, signing out, and this
     * version.
     */
    private void showProfileMenu(View button) {
        View header = getLayoutInflater().inflate(R.layout.view_profile_header, null, false);
        ((TextView) header.findViewById(R.id.profileName)).setText(account.userName());
        ((TextView) header.findViewById(R.id.profileServer))
                .setText(account.serverName().isEmpty() ? account.server() : account.serverName());
        GlassMenu.Builder m = new GlassMenu.Builder().add(GlassMenu.Item.view(header));
        Updates.Release release = Updates.known(this);
        if (release != null) {
            m.add(GlassMenu.Item.live(getString(R.string.update_to, release.version), null, R.drawable.update_dot,
                    (row, title, detail) -> startUpdate(release, row, title, detail)));
        }
        if (Library.hasApp(this)) {
            m.add(GlassMenu.Item.option(getString(R.string.jellyfin_open_app), null, false, () -> Library.openApp(this)));
        }
        m.add(GlassMenu.Item.option(getString(R.string.jellyfin_sign_out), null, false, accountPanel::signOut));
        m.add(GlassMenu.Item.divider());
        // A footnote, not a heading: the app's icon and version, quiet and in ordinary case.
        View about = getLayoutInflater().inflate(R.layout.view_menu_about, null, false);
        ((TextView) about.findViewById(R.id.aboutVersion)).setText("Aurelia " + BuildConfig.VERSION_NAME);
        View icon = about.findViewById(R.id.aboutIcon);
        // Round, like the pills around it.
        icon.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline outline) {
                outline.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
        icon.setClipToOutline(true);
        m.add(GlassMenu.Item.view(about));
        menu.show(button, m, findViewById(R.id.content));
    }

    /** Downloads and installs {@code release}, saying how far it has got in the menu row that started it. */
    /** An update is downloading. */
    private boolean updating;

    private void startUpdate(Updates.Release release, View row, TextView title, TextView detail) {
        if (!Updates.canInstall(this)) {
            detail.setText(R.string.update_allow);
            detail.setVisibility(View.VISIBLE);
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException e) {
                // Android TV often has no such screen: the hint says how to allow it over adb.
            }
            return;
        }
        // Pressed again (or from the menu opened again) while it downloads: nothing more to do.
        if (updating) return;
        updating = true;
        title.setText(R.string.update_downloading);
        detail.setVisibility(View.GONE);
        Api.async(() -> {
            Updates.install(this, Updates.download(this, release));
            return true;
        }, new Api.Callback<Boolean>() {
            @Override
            public void onResult(Boolean ok) {
                updating = false;
                title.setText(R.string.update_installing);
            }

            @Override
            public void onError(Exception e) {
                updating = false;
                title.setText(getString(R.string.update_to, release.version));
                detail.setText(R.string.update_failed);
                detail.setVisibility(View.VISIBLE);
            }
        });
    }

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
        loadRecent();
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
            homeRows = null;
            hero.setWatching(Collections.emptyList(), Collections.emptyList());
            showNextUp(Collections.emptyList());
            cwTitle.setText(R.string.continue_watching);
            cwCaption.setText(null);
            updateRow(() -> cwAdapter.showMessage(new ContinueWatchingAdapter.Message(R.drawable.ic_link,
                    getString(R.string.cw_connect_title), getString(R.string.cw_connect_body),
                    getString(R.string.jellyfin_connect_title), accountPanel::open)));
            return;
        }
        int request = ++homeRequest;
        Library.homeRows(account, new Api.Callback<Library.HomeRows>() {
            @Override
            public void onResult(Library.HomeRows answer) {
                Library.HomeRows rows = withoutTakenOff(answer, request);
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
        homeRows = rows;
        boolean heroHadFocus = hero.card().hasFocus();
        hero.setWatching(rows.resume, rows.nextUp);
        if (heroHadFocus && !hero.isShowing()) focusHomeStart();
        // One row for everything being watched, in progress first and then next up; the hero
        // only picks from it.
        List<Library.MediaItem> items = new ArrayList<>(rows.resume);
        items.addAll(rows.nextUp);
        showNextUp(Collections.emptyList());
        cwTitle.setText(R.string.continue_watching);
        cwCaption.setText(null);
        if (items.isEmpty()) {
            updateRow(() -> cwAdapter.showMessage(new ContinueWatchingAdapter.Message(R.drawable.ic_check,
                    getString(R.string.cw_empty_title), getString(R.string.cw_empty_body),
                    getString(R.string.cw_browse_movies), () -> {
                        showPage(PAGE_MOVIES, animations);
                        movies.focusIn();
                    })));
            return;
        }
        updateRow(() -> cwAdapter.showItems(items, Library.auth(account)));
    }

    private void loadRecent() {
        int request = ++recentRequest;
        if (!account.isSignedIn()) {
            recentAdapter.set(Collections.emptyList());
            hero.setAdded(Collections.emptyList());
            recentSection.setVisibility(View.GONE);
            return;
        }
        Api.async(() -> Catalog.recentlyAdded(account, 20), new Api.Callback<List<Catalog.Item>>() {
            @Override
            public void onResult(List<Catalog.Item> items) {
                // An answer for an account since signed out of, or overtaken by a newer question, is dropped.
                if (request != recentRequest) return;
                recentAdapter.set(items);
                hero.setAdded(items);
                recentSection.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onError(Exception e) {
            }
        });
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
                // Home is the hero, Continue watching, Next Up (when apart), Recently added, apps.
                if (isIn(focus, topBar)) return true;
                if (focus == hero.card()) return tabs.requestFocus();
                if (isIn(focus, cwRow)) return hero.isShowing() ? hero.card().requestFocus() : tabs.requestFocus();
                if (isIn(focus, nuRow)) return focusRow(cwRow, cwFocus);
                if (isIn(focus, recentRow)) {
                    return nuSection.getVisibility() == View.VISIBLE ? focusRow(nuRow, nuFocus) : focusRow(cwRow, cwFocus);
                }
                if (isIn(focus, pinnedRow)) {
                    if (recentSection.getVisibility() == View.VISIBLE) return focusRow(recentRow, recentFocus);
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
                    if (currentPage() == PAGE_HOME && hero.isShowing()) return hero.card().requestFocus();
                    return currentPage() == PAGE_HOME ? focusRow(cwRow, cwFocus) : focusRow(appsGrid, appsFocus);
                }
                if (focus == hero.card()) return focusRow(cwRow, cwFocus);
                if (isIn(focus, cwRow)) {
                    if (nuSection.getVisibility() == View.VISIBLE) return focusRow(nuRow, nuFocus);
                    return recentSection.getVisibility() == View.VISIBLE ? focusRow(recentRow, recentFocus)
                            : focusRow(pinnedRow, pinnedFocus);
                }
                if (isIn(focus, nuRow)) {
                    return recentSection.getVisibility() == View.VISIBLE ? focusRow(recentRow, recentFocus)
                            : focusRow(pinnedRow, pinnedFocus);
                }
                if (isIn(focus, recentRow)) return focusRow(pinnedRow, pinnedFocus);
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
                if (focus == hero.card()) {
                    hero.step(right ? 1 : -1);
                    return true;
                }
                for (RecyclerView row : new RecyclerView[]{recentRow, cwRow, nuRow, pinnedRow}) {
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

    /** Home's first stop: the hero, or the first card when there is nothing for it. */
    private void focusHomeStart() {
        if (hero.isShowing()) hero.card().requestFocus();
        else focusRow(cwRow, 0);
    }

    private void remember(View focus) {
        if (isIn(focus, recentRow)) recentFocus = Math.max(0, positionIn(recentRow, focus));
        else if (isIn(focus, cwRow)) cwFocus = Math.max(0, positionIn(cwRow, focus));
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
        cwFocus = nuFocus = pinnedFocus = recentFocus = 0;
        recentRow.scrollToPosition(0);
        cwRow.scrollToPosition(0);
        nuRow.scrollToPosition(0);
        pinnedRow.scrollToPosition(0);
        pageHome.scrollTo(0, 0);
        cwRow.post(this::focusHomeStart);
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
        loadRecent();
        checkForUpdate();
        // Back from a film or show: what was watched or favourited there shows on its poster.
        LibraryPage shown = library(currentPage());
        if (shown != null) shown.refresh();
        wallpaperPlayer.start();
        hero.start();
    }

    /** Away this long, and the libraries' filters were for another time: they reset (their sort stays). */
    private static final long FILTERS_LAST_MS = 30 * 60 * 1000L;

    /** True from opening one of Aurelia's own screens (a film, the player) until Home shows again. */
    private boolean inAurelia;

    @Override
    public void startActivity(Intent intent, @Nullable Bundle options) {
        ComponentName to = intent.getComponent();
        inAurelia = to != null && getPackageName().equals(to.getPackageName());
        super.startActivity(intent, options);
    }

    @Override
    protected void onStart() {
        super.onStart();
        inAurelia = false;
        android.content.SharedPreferences prefs = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        long left = prefs.getLong("left_at", 0);
        if (left > 0 && System.currentTimeMillis() - left > FILTERS_LAST_MS) {
            movies.forgetFilters();
            shows.forgetFilters();
        }
        prefs.edit().remove("left_at").apply();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Out of sight: another app, the screensaver, or the TV off, but not a film or the player opened from
        // here. Kept on disk, so a restart counts too.
        if (!inAurelia) {
            getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).edit()
                    .putLong("left_at", System.currentTimeMillis()).apply();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        wallpaperPlayer.stop();
        hero.stop();
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
