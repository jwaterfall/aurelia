package app.aurelia.tv;

import android.app.Activity;
import android.content.SharedPreferences;
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

import java.io.IOException;
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
        /** The posters have scrolled away from the top, or back to it. */
        void onScrolled(boolean scrolled);
    }

    static final int COLUMNS = 7;
    private static final String PREFS = "library";
    /** Placeholder rows while the first page loads: enough to fill the screen. */
    private static final int LOADING_ROWS = 3;

    private final Activity activity;
    private final Account account;
    private final Catalog.Kind kind;
    private final Host host;
    private final SharedPreferences prefs;
    private final Catalog.Query query = new Catalog.Query();

    private final View root;
    private final View toolbar;
    private final View content;
    private final RecyclerView grid;
    private final PosterAdapter adapter;
    private final TextView focusName;
    private final TextView focusFacts;
    private final TextView sortPill;
    private final TextView filterPill;
    private final TextView genrePill;
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

    LibraryPage(Activity activity, ViewGroup container, Catalog.Kind kind, Host host) {
        this.activity = activity;
        this.account = Account.get(activity);
        this.kind = kind;
        this.host = host;
        prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        root = LayoutInflater.from(activity).inflate(R.layout.view_library_page, container, false);
        container.addView(root);
        toolbar = root.findViewById(R.id.libraryToolbar);
        content = root.findViewById(R.id.libraryContent);
        grid = root.findViewById(R.id.libraryGrid);
        focusName = root.findViewById(R.id.libraryFocusName);
        focusFacts = root.findViewById(R.id.libraryFocusFacts);
        sortPill = root.findViewById(R.id.librarySort);
        filterPill = root.findViewById(R.id.libraryFilter);
        genrePill = root.findViewById(R.id.libraryGenre);
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
            public boolean onLongClicked(Catalog.Item item, View poster) {
                showOptions(item, poster);
                return true;
            }

            @Override
            public void onNearEnd() {
                loadMore();
            }
        });
        GridShelfLayoutManager layout = new GridShelfLayoutManager(activity, COLUMNS);
        grid.setLayoutManager(layout);
        grid.addItemDecoration(new GridSpacing(COLUMNS, gutter));
        grid.setItemAnimator(null);
        grid.setAdapter(adapter);
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView list, int dx, int dy) {
                host.onScrolled(scrolled());
            }
        });

        readQuery();
        sortPill.setOnClickListener(this::showSort);
        filterPill.setOnClickListener(this::showFilter);
        genrePill.setOnClickListener(this::showGenres);
        for (View pill : new View[] {sortPill, filterPill, genrePill}) {
            pill.setOnFocusChangeListener((v, focused) -> {
                if (!focused) return;
                host.onNothingFocused();
                describe(null);
            });
        }
        updatePills();
        describe(null);
    }

    View view() {
        return root;
    }

    /** Where the posters are cut off as they scroll: the top of their area, under the bar. */
    View content() {
        return content;
    }

    boolean scrolled() {
        return grid.computeVerticalScrollOffset() > 0;
    }

    /** The page is coming into view: the first time, the library loads. */
    void showing() {
        if (!loadedOnce) {
            loadedOnce = true;
            reload();
        }
    }

    /** Fetches again, keeping the place, if it has loaded before (back from a film's page, say). */
    void refresh() {
        if (loadedOnce) reload();
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
        Api.async(() -> {
            if (libraryId == null) libraryId = Catalog.library(account, kind);
            if (genres.isEmpty() && !libraryId.isEmpty()) genres = Catalog.genres(account, libraryId, kind);
            return libraryId.isEmpty() ? null : Catalog.items(account, libraryId, kind, query, 0, Catalog.PAGE);
        }, new Api.Callback<Catalog.Page>() {
            @Override
            public void onResult(Catalog.Page page) {
                if (mine != generation) return;
                loading = false;
                if (page == null) {
                    String server = account.serverName().isEmpty() ? account.server() : account.serverName();
                    showMessage(R.drawable.ic_search, activity.getString(R.string.library_empty_title),
                            activity.getString(R.string.library_none, server), null, null, false);
                    return;
                }
                boolean hadFocus = grid.hasFocus();
                total = page.total;
                adapter.set(page.items);
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
        empty.show(icon, title, body, action, onAction);
        if (hadFocus && !root.hasFocus()) focusIn();
        describe(null);
    }

    // ---- what has focus -------------------------------------------------------------------------------

    /** At the bar's far end: {@code item}'s name and facts, or how many there are when null. */
    private void describe(Catalog.Item item) {
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
    private void showOptions(Catalog.Item item, View poster) {
        GlassMenu.Builder m = new GlassMenu.Builder()
                .add(GlassMenu.Item.heading(item.name))
                .add(GlassMenu.Item.option(get(item.played ? R.string.menu_mark_unwatched : R.string.menu_mark_watched),
                        null, false, () -> mark(item, item.withUserData(!item.played, item.favourite))))
                .add(GlassMenu.Item.option(get(item.favourite ? R.string.menu_remove_favourite : R.string.menu_add_favourite),
                        null, false, () -> mark(item, item.withUserData(item.played, !item.favourite))));
        host.menu().show(poster, m, host.background());
    }

    /**
     * Shows {@code after}'s marks on the poster at once and tells the server. The poster then takes the server's
     * own copy (a show's count of episodes left), or goes back to {@code before} if the server said no.
     */
    private void mark(Catalog.Item before, Catalog.Item after) {
        adapter.update(after);
        boolean played = after.played != before.played;
        Api.async(() -> {
            if (played) Catalog.setPlayed(account, after.id, after.played);
            else Catalog.setFavourite(account, after.id, after.favourite);
            try {
                return Catalog.item(account, after.id);
            } catch (IOException e) {
                return after;   // the mark is made; only the fresh copy is missing
            }
        },new Api.Callback<Catalog.Item>() {
            @Override
            public void onResult(Catalog.Item fresh) {
                adapter.update(fresh);
            }

            @Override
            public void onError(Exception e) {
                adapter.update(before);
            }
        });
    }

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
