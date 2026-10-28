package app.aurelia.tv;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.graphics.drawable.Drawable;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import app.aurelia.tv.jellyfin.Account;
import app.aurelia.tv.jellyfin.Api;
import app.aurelia.tv.jellyfin.Catalog;
import app.aurelia.tv.ui.GlassMenu;
import app.aurelia.tv.ui.GridShelfLayoutManager;
import app.aurelia.tv.ui.GridSpacing;
import app.aurelia.tv.ui.PageTop;

import java.util.Collections;
import java.util.List;

/**
 * A whole library as a page of the launcher (Movies, TV Shows; view_library_page.xml): every poster on the
 * launcher's columns, sorted and filtered as Jellyfin's apps offer, under a bar with the sort and filters and
 * the name of whichever has focus. Choosing one opens its page. The sort and filters are remembered for each
 * library.
 *
 * Focus: Up from the first row of posters goes to the pills, and from the pills to the tabs ({@link Host#onExitUp}).
 */
final class LibraryPage {

    interface Host {
        void onItemFocused(Catalog.Item item, Drawable art);
        void onNothingFocused();
        void onOpen(Catalog.Item item);
        /** Up from the top of the page. */
        void onExitUp();
        /** The signed-out message's button. */
        void onSignIn();
        GlassMenu menu();
        /** What the menus block focus to while open. */
        ViewGroup background();
        /** The posters have scrolled: the top bar over the page moves with them. */
        void onScrolled();
    }

    static final int COLUMNS = 7;
    private static final String PREFS = "library";
    /** Placeholder rows while the first page loads: enough to fill the screen. */
    private static final int LOADING_ROWS = 3;
    /** Posters made and their art fetched ahead of time: the rows a grid first lays out, a screen and a half. */
    private static final int WARM_POSTERS = 7 * COLUMNS;
    /** A page got ready longer ago than this is fetched again when it first shows. */
    private static final long STALE_MS = 5 * 60 * 1000L;

    private final Activity activity;
    private final Account account;
    private final Catalog.Kind kind;
    private final Host host;
    private final SharedPreferences prefs;
    private final Catalog.Query query = new Catalog.Query();

    private final View root;
    private final View toolbar;
    private final View countGhost;
    private final RecyclerView grid;
    private final PosterAdapter adapter;
    private final TextView focusName;
    private final TextView focusFacts;
    private final TextView sortPill;
    private final TextView filterPill;
    private final TextView genrePill;
    private TextView clearPill;
    private final View message;
    private final EmptyState empty;

    private String libraryId;
    private List<String> genres = Collections.emptyList();
    private int total;
    /** "77 films", shown while no poster has focus. */
    private String count;
    private boolean loading;
    /** Bumped for every new list asked for, so pages of an older one are dropped. */
    private int generation;
    private int focus;
    private boolean loadedOnce;
    /** When the posters showing were fetched (uptime), to tell a page got ready long before it showed. */
    private long loadedAt;
    /** What the page sits in, whose width the grid will have. */
    private final ViewGroup container;

    /** {@code barSpace}: the room at the page's top for Home's top bar over it, which the buttons go below. */
    LibraryPage(Activity activity, ViewGroup container, Catalog.Kind kind, int barSpace, Host host) {
        this.activity = activity;
        this.account = Account.get(activity);
        this.kind = kind;
        this.host = host;
        prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        this.container = container;
        root = LayoutInflater.from(activity).inflate(R.layout.view_library_page, container, false);
        container.addView(root);
        toolbar = root.findViewById(R.id.libraryToolbar);
        ((ViewGroup.MarginLayoutParams) toolbar.getLayoutParams()).topMargin += barSpace;
        countGhost = root.findViewById(R.id.libraryCountGhost);
        grid = root.findViewById(R.id.libraryGrid);
        focusName = root.findViewById(R.id.libraryFocusName);
        focusFacts = root.findViewById(R.id.libraryFocusFacts);
        sortPill = root.findViewById(R.id.librarySort);
        filterPill = root.findViewById(R.id.libraryFilter);
        genrePill = root.findViewById(R.id.libraryGenre);
        clearPill = root.findViewById(R.id.libraryClear);
        clearPill.setOnClickListener(v -> clearFilters());
        message = root.findViewById(R.id.libraryMessage);
        empty = new EmptyState(message);

        int gutter = activity.getResources().getDimensionPixelSize(R.dimen.gutter);
        adapter = new PosterAdapter(account, COLUMNS, gutter, new PosterAdapter.Listener() {
            @Override
            public void onFocused(Catalog.Item item, Drawable art) {
                focus = Math.max(0, positionIn(grid.getFocusedChild()));
                host.onItemFocused(item, art);
                describe(item);
            }

            @Override
            public void onClicked(Catalog.Item item) {
                host.onOpen(item);
            }

            @Override
            public void onNearEnd() {
                loadMore();
            }
        });
        GridShelfLayoutManager layout = new GridShelfLayoutManager(activity, COLUMNS);
        grid.setLayoutManager(layout);
        grid.addItemDecoration(new GridSpacing(COLUMNS, gutter));
        // The first row starts below the buttons (and the bar above them), and the message is centred under them.
        PageTop top = new PageTop(COLUMNS, 0);
        grid.addItemDecoration(top);
        toolbar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            top.setRoom(grid, b);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) message.getLayoutParams();
            if (lp.topMargin != b) {
                lp.topMargin = b;
                message.setLayoutParams(lp);
            }
        });
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView list, int dx, int dy) {
                placeToolbar();
            }
        });
        grid.setItemAnimator(null);
        grid.setAdapter(adapter);

        readQuery();
        sortPill.setOnClickListener(this::showSort);
        filterPill.setOnClickListener(this::showFilter);
        genrePill.setOnClickListener(this::showGenres);
        for (View pill : new View[] {sortPill, filterPill, genrePill}) {
            pill.setOnFocusChangeListener((v, focused) -> {
                if (!focused) return;
                host.onNothingFocused();
                describe(null);
                // The buttons are part of the page's top: focus in them brings it back, as the bar does.
                scrollToTop();
            });
        }
        updatePills();
        describe(null);
    }

    View view() {
        return root;
    }

    /** How far the posters have scrolled from the top of the page; 0 while there are none to scroll. */
    int scrolled() {
        return grid.getVisibility() == View.VISIBLE ? PageTop.scrolled(grid) : 0;
    }

    /** The buttons moved up with the posters as they scroll, out of the way once past them, and the bar with them. */
    private void placeToolbar() {
        toolbar.setTranslationY(-Math.min(scrolled(), toolbar.getBottom()));
        host.onScrolled();
    }

    /**
     * The page is coming into view: the first time, the library loads; got ready long ago ({@link #prewarm}), it
     * is fetched again, the posters it has staying up meanwhile.
     */
    void showing() {
        if (!loadedOnce) {
            loadedOnce = true;
            reload();
        } else if (loadedAt > 0 && SystemClock.uptimeMillis() - loadedAt > STALE_MS) {
            reload();
        }
    }

    /**
     * Gets the page ready while it is hidden and Home has nothing to do, so the first switch to it builds and
     * fetches nothing: its first page of the library, the first posters' art on disk, and views for a screen of
     * posters, made one at a time. Once; signed in only.
     */
    void prewarm() {
        if (loadedOnce || root.isShown() || !account.isSignedIn() || container.getWidth() == 0) return;
        showing();
        int content = container.getWidth() - grid.getPaddingLeft() - grid.getPaddingRight();
        int gutter = activity.getResources().getDimensionPixelSize(R.dimen.gutter);
        adapter.prewarm(grid, GridSpacing.itemWidth(content, COLUMNS, gutter), WARM_POSTERS);
    }

    /** Fetches again, keeping the place, if it has loaded before (back from a film's page, say). */
    void refresh() {
        if (loadedOnce) reload();
    }

    /** Scrolled back to the top, the poster to return to kept (focus going up to the tabs). */
    void scrollToTop() {
        grid.smoothScrollToPosition(0);
    }

    /** Back to the first poster, scrolled to the top (switching to this page from another). */
    void toTop() {
        focus = 0;
        grid.scrollToPosition(0);
        describe(null);
    }

    /** Signed in as someone else, or out: forget the library and its genres; it loads again when shown. */
    void reset() {
        generation++;
        libraryId = null;
        genres = Collections.emptyList();
        loadedOnce = false;
        loadedAt = 0;
        focus = 0;
        adapter.set(Collections.emptyList());
        count = null;
        describe(null);
    }

    void setAnimationsEnabled(boolean enabled) {
        ((GridShelfLayoutManager) grid.getLayoutManager()).setAnimationsEnabled(enabled);
    }

    // ---- loading -------------------------------------------------------------------------------------

    /** Fetches the library again from the start (first showing, a new sign-in, a changed sort or filter). */
    void reload() {
        int mine = ++generation;
        loading = false;
        if (!account.isSignedIn()) {
            adapter.set(Collections.emptyList());
            count = null;
            showMessage(R.drawable.ic_link, activity.getString(R.string.library_connect_title),
                    activity.getString(R.string.library_signed_out),
                    activity.getString(R.string.jellyfin_connect_title), host::onSignIn, false);
            return;
        }
        if (adapter.size() == 0) showLoading();
        loading = true;
        if (!grid.hasFocus()) describe(null);
        Api.async(() -> {
            if (libraryId == null) libraryId = Catalog.library(account, kind);
            return libraryId.isEmpty() ? null : Catalog.items(account, libraryId, kind, query, 0, Catalog.PAGE);
        }, new Api.Callback<Catalog.Page>() {
            @Override
            public void onResult(Catalog.Page page) {
                if (mine != generation) return;
                loading = false;
                loadGenres();
                if (page == null) {
                    String server = account.serverName().isEmpty() ? account.server() : account.serverName();
                    showMessage(R.drawable.ic_search, activity.getString(R.string.library_empty_title),
                            activity.getString(R.string.library_none, server), null, null, false);
                    return;
                }
                boolean hadFocus = grid.hasFocus();
                total = page.total;
                loadedAt = SystemClock.uptimeMillis();
                adapter.set(page.items);
                // Fetched ahead of showing: the art too, so the posters are there the moment the page is.
                if (!root.isShown()) adapter.prefetchArt(WARM_POSTERS);
                count = activity.getResources().getQuantityString(
                        kind == Catalog.Kind.Movie ? R.plurals.library_movies : R.plurals.library_shows, total, total);
                if (!grid.hasFocus()) describe(null);
                if (page.items.isEmpty()) {
                    // The filters stay: they are what to change.
                    showMessage(R.drawable.ic_filter, activity.getString(R.string.library_nothing), null,
                            activity.getString(R.string.library_clear), LibraryPage.this::clearFilters, true);
                    return;
                }
                message.setVisibility(View.GONE);
                toolbar.setVisibility(View.VISIBLE);
                grid.setVisibility(View.VISIBLE);
                if (focus >= page.items.size()) focus = 0;
                if (hadFocus) focusPoster(focus);
            }

            @Override
            public void onError(Exception e) {
                if (mine != generation) return;
                loading = false;
                if (adapter.size() > 0) return;   // keep what is showing
                String server = account.serverName().isEmpty() ? account.server() : account.serverName();
                showMessage(R.drawable.ic_offline, activity.getString(R.string.cw_offline_title, server),
                        activity.getString(R.string.cw_offline_body), activity.getString(R.string.jellyfin_try_again),
                        LibraryPage.this::reload, false);
            }
        });
    }

    /** The genres for the genre menu, once: after the posters, which need them not and should not wait. */
    private void loadGenres() {
        if (!genres.isEmpty() || libraryId == null || libraryId.isEmpty()) return;
        String id = libraryId;
        Api.async(() -> Catalog.genres(account, id, kind), new Api.Callback<List<String>>() {
            @Override
            public void onResult(List<String> found) {
                if (id.equals(libraryId)) genres = found;
            }

            @Override
            public void onError(Exception e) {
            }
        });
    }

    private void loadMore() {
        if (loading || adapter.size() >= total) return;
        int mine = generation;
        int start = adapter.size();
        loading = true;
        Api.async(() -> Catalog.items(account, libraryId, kind, query, start, Catalog.PAGE),
                new Api.Callback<Catalog.Page>() {
                    @Override
                    public void onResult(Catalog.Page page) {
                        if (mine != generation) return;
                        loading = false;
                        total = page.total;
                        adapter.append(page.items);
                    }

                    @Override
                    public void onError(Exception e) {
                        loading = false;
                    }
                });
    }

    /** Placeholder posters until the first page arrives. */
    private void showLoading() {
        boolean hadFocus = root.hasFocus();
        message.setVisibility(View.GONE);
        grid.setVisibility(View.VISIBLE);
        adapter.setLoading(LOADING_ROWS);
        if (hadFocus && !root.hasFocus()) focusIn();
        describe(null);
    }

    /**
     * The empty state instead of the posters. The sort and filter buttons show only with {@code filters}: with
     * nothing to sort (signed out, unreachable) they would do nothing.
     */
    private void showMessage(int icon, String title, String body, String action, Runnable onAction,
                             boolean filters) {
        boolean hadFocus = root.hasFocus();
        grid.setVisibility(View.INVISIBLE);
        toolbar.setVisibility(filters ? View.VISIBLE : View.INVISIBLE);
        message.setVisibility(View.VISIBLE);
        placeToolbar();
        empty.show(icon, title, body, action, onAction);
        if (hadFocus && !root.hasFocus()) focusIn();
        describe(null);
    }

    // ---- what has focus -------------------------------------------------------------------------------

    /** At the bar's far end: {@code item}'s name and facts, or how many there are when null. */
    private void describe(Catalog.Item item) {
        // Loading with no count yet: its placeholder instead, which gives way to it once.
        boolean waiting = item == null && count == null && loading;
        countGhost.setVisibility(waiting ? View.VISIBLE : View.GONE);
        if (item == null) {
            focusName.setText(null);
            focusFacts.setText(count);
            focusFacts.setTextColor(activity.getColor(R.color.text_tertiary));
            return;
        }
        String seasons = item.isSeries() && item.childCount > 0
                ? activity.getResources().getQuantityString(R.plurals.seasons, item.childCount, item.childCount)
                : null;
        focusName.setText(item.name);
        focusFacts.setText(Catalog.facts(item, seasons));
        focusFacts.setTextColor(activity.getColor(R.color.text_secondary));
    }

    // ---- sort and filter -----------------------------------------------------------------------------

    private String key(String name) {
        return kind.name() + "." + name;
    }

    private void readQuery() {
        try {
            query.sort = Catalog.Sort.valueOf(prefs.getString(key("sort"), Catalog.Sort.DateAdded.name()));
            query.watched = Catalog.Watched.valueOf(prefs.getString(key("watched"), Catalog.Watched.Any.name()));
        } catch (IllegalArgumentException e) {
            query.sort = Catalog.Sort.DateAdded;
            query.watched = Catalog.Watched.Any;
        }
        query.ascending = prefs.getBoolean(key("ascending"), query.sort.ascendingFirst);
        query.favourites = prefs.getBoolean(key("favourites"), false);
        query.genre = prefs.getString(key("genre"), "");
    }

    private void apply() {
        prefs.edit()
                .putString(key("sort"), query.sort.name())
                .putBoolean(key("ascending"), query.ascending)
                .putString(key("watched"), query.watched.name())
                .putBoolean(key("favourites"), query.favourites)
                .putString(key("genre"), query.genre)
                .apply();
        updatePills();
        focus = 0;
        grid.scrollToPosition(0);
        reload();
    }

    private void clearFilters() {
        query.watched = Catalog.Watched.Any;
        query.favourites = false;
        query.genre = "";
        apply();
        sortPill.requestFocus();
    }

    private void updatePills() {
        sortPill.setText(sortName(query.sort));
        String filter = activity.getString(query.watched == Catalog.Watched.Unwatched ? R.string.filter_unwatched
                : query.watched == Catalog.Watched.Watched ? R.string.filter_watched : R.string.filter_all);
        if (query.favourites) {
            filter = query.watched == Catalog.Watched.Any ? activity.getString(R.string.filter_favourites)
                    : filter + " · " + activity.getString(R.string.filter_favourites);
        }
        filterPill.setText(filter);
        genrePill.setText(query.genre.isEmpty() ? activity.getString(R.string.genre_all) : query.genre);
        clearPill.setVisibility(filtered() ? View.VISIBLE : View.GONE);
    }

    private boolean filtered() {
        return query.watched != Catalog.Watched.Any || query.favourites || !query.genre.isEmpty();
    }

    /**
     * Back away long enough that the filters were for another time: they go, the sort (a liking) stays. Quietly,
     * without moving focus; the page loads again the next time it shows.
     */
    void forgetFilters() {
        if (!filtered()) return;
        query.watched = Catalog.Watched.Any;
        query.favourites = false;
        query.genre = "";
        prefs.edit()
                .putString(key("watched"), query.watched.name())
                .putBoolean(key("favourites"), false)
                .putString(key("genre"), "")
                .apply();
        updatePills();
        focus = 0;
        grid.scrollToPosition(0);
        if (loadedOnce) reload();
    }

    private String sortName(Catalog.Sort sort) {
        switch (sort) {
            case Name: return activity.getString(R.string.sort_name);
            case Released: return activity.getString(R.string.sort_released);
            case Rating: return activity.getString(R.string.sort_rating);
            case Runtime: return activity.getString(R.string.sort_runtime);
            case Random: return activity.getString(R.string.sort_random);
            default: return activity.getString(R.string.sort_date_added);
        }
    }

    /** The two orders for {@code sort}, ascending first, in its own words (A to Z, Newest first). */
    private String[] orders(Catalog.Sort sort) {
        switch (sort) {
            case Name: return new String[] {get(R.string.order_az), get(R.string.order_za)};
            case Rating: return new String[] {get(R.string.order_lowest), get(R.string.order_highest)};
            case Runtime: return new String[] {get(R.string.order_shortest), get(R.string.order_longest)};
            default: return new String[] {get(R.string.order_oldest), get(R.string.order_newest)};
        }
    }

    private String get(int res) {
        return activity.getString(res);
    }

    private void showSort(View from) {
        GlassMenu.Builder m = new GlassMenu.Builder().add(GlassMenu.Item.heading(get(R.string.library_sort_by)));
        for (Catalog.Sort s : Catalog.Sort.values()) {
            m.add(GlassMenu.Item.option(sortName(s), null, s == query.sort, () -> {
                if (s == query.sort && s != Catalog.Sort.Random) return;
                query.sort = s;
                query.ascending = s.ascendingFirst;
                apply();
            }));
        }
        if (query.sort != Catalog.Sort.Random) {
            String[] o = orders(query.sort);
            // The natural order first: A to Z, but Newest first.
            boolean[] ascending = query.sort.ascendingFirst ? new boolean[] {true, false} : new boolean[] {false, true};
            m.add(GlassMenu.Item.heading(get(R.string.library_order)));
            for (boolean asc : ascending) {
                m.add(GlassMenu.Item.option(asc ? o[0] : o[1], null, asc == query.ascending, () -> {
                    if (asc == query.ascending) return;
                    query.ascending = asc;
                    apply();
                }));
            }
        }
        host.menu().show(from, m, host.background());
    }

    private void showFilter(View from) {
        GlassMenu.Builder m = new GlassMenu.Builder().add(GlassMenu.Item.heading(get(R.string.library_show)));
        Catalog.Watched[] all = Catalog.Watched.values();
        int[] names = {R.string.filter_all, R.string.filter_unwatched, R.string.filter_watched};
        for (int i = 0; i < all.length; i++) {
            Catalog.Watched w = all[i];
            m.add(GlassMenu.Item.option(get(names[i]), null, w == query.watched, () -> {
                if (w == query.watched) return;
                query.watched = w;
                apply();
            }));
        }
        m.add(GlassMenu.Item.divider())
                .add(GlassMenu.Item.option(get(R.string.filter_favourites), null, query.favourites, () -> {
                    query.favourites = !query.favourites;
                    apply();
                }));
        host.menu().show(from, m, host.background());
    }

    private void showGenres(View from) {
        GlassMenu.Builder m = new GlassMenu.Builder()
                .add(GlassMenu.Item.heading(get(R.string.library_genre)))
                .add(GlassMenu.Item.option(get(R.string.genre_all), null, query.genre.isEmpty(), () -> {
                    if (query.genre.isEmpty()) return;
                    query.genre = "";
                    apply();
                }));
        for (String g : genres) {
            m.add(GlassMenu.Item.option(g, null, g.equals(query.genre), () -> {
                if (g.equals(query.genre)) return;
                query.genre = g;
                apply();
            }));
        }
        host.menu().show(from, m, host.background());
    }

    // ---- a poster's quick options ----------------------------------------------------------------------

    /** OK held on a poster: watched and favourite, as they are now. */
    // ---- focus ---------------------------------------------------------------------------------------

    /** Coming down into the page: the poster that had focus, or the pills when there are none. */
    void focusIn() {
        if (grid.getVisibility() == View.VISIBLE && adapter.size() > 0) focusPoster(focus);
        else if (empty.hasAction() && message.getVisibility() == View.VISIBLE) {
            empty.action.requestFocus();
        } else sortPill.requestFocus();
    }

    /**
     * Moves focus for a key pressed inside the page, a poster at a time (as Home's rows do, so a held key never
     * outruns the scrolling). False when the key is not the page's to handle.
     */
    boolean routeKey(int keyCode, View focused) {
        boolean inGrid = isIn(focused, grid);
        boolean inToolbar = isIn(focused, toolbar);
        boolean inMessage = isIn(focused, message);
        if (!inGrid && !inToolbar && !inMessage) return false;
        int last = adapter.size() - 1;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (inGrid) {
                    int pos = positionIn(focused);
                    if (pos < COLUMNS) sortPill.requestFocus();
                    else focusPoster(pos - COLUMNS);
                    return true;
                }
                if (inMessage) {
                    if (toolbar.getVisibility() == View.VISIBLE) sortPill.requestFocus();
                    else host.onExitUp();
                    return true;
                }
                host.onExitUp();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (inToolbar) {
                    if (grid.getVisibility() == View.VISIBLE && last >= 0) focusPoster(focus);
                    else if (empty.hasAction()) empty.action.requestFocus();
                    return true;
                }
                if (inGrid) {
                    int pos = positionIn(focused);
                    if (pos / COLUMNS != last / COLUMNS) focusPoster(Math.min(pos + COLUMNS, last));
                    return true;
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!inGrid) return false;
                boolean right = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
                int pos = positionIn(focused);
                int col = pos % COLUMNS;
                boolean end = right ? col == COLUMNS - 1 || pos == last : col == 0;
                if (!end) focusPoster(pos + (right ? 1 : -1));
                return true;
            default:
                return false;
        }
    }

    private void focusPoster(int position) {
        if (adapter.size() == 0) return;
        int pos = Math.max(0, Math.min(position, adapter.size() - 1));
        RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(pos);
        if (h != null) {
            h.itemView.requestFocus();
            return;
        }
        grid.scrollToPosition(pos);
        grid.post(() -> {
            RecyclerView.ViewHolder later = grid.findViewHolderForAdapterPosition(pos);
            if (later != null) later.itemView.requestFocus();
        });
    }

    private int positionIn(View focused) {
        if (focused == null) return RecyclerView.NO_POSITION;
        View item = grid.findContainingItemView(focused);
        return item == null ? RecyclerView.NO_POSITION : grid.getChildAdapterPosition(item);
    }

    private static boolean isIn(View view, View ancestor) {
        for (View v = view; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v == ancestor) return true;
        }
        return false;
    }
}
