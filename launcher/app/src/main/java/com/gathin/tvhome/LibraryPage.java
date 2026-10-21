package com.gathin.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.gathin.tvhome.jellyfin.Account;
import com.gathin.tvhome.jellyfin.Api;
import com.gathin.tvhome.jellyfin.Catalog;
import com.gathin.tvhome.jellyfin.Library;
import com.gathin.tvhome.ui.GlassMenu;
import com.gathin.tvhome.ui.GridShelfLayoutManager;
import com.gathin.tvhome.ui.GridSpacing;
import com.gathin.tvhome.ui.Images;

import java.util.Collections;
import java.util.List;

/**
 * A whole library as a page of the launcher (Movies, TV Shows; view_library_page.xml): every poster on the
 * launcher's columns, sorted and filtered as Jellyfin's apps offer, under a preview of whichever has focus.
 * Choosing one opens its page. The sort and filters are remembered for each library.
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
    }

    static final int COLUMNS = 7;
    private static final String PREFS = "library";
    /** A fast run across the posters updates the preview once it settles. */
    private static final long PREVIEW_SETTLE_MS = 120;
    private static final int LOGO_PX = 104;

    private final Activity activity;
    private final Account account;
    private final Catalog.Kind kind;
    private final Host host;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Catalog.Query query = new Catalog.Query();

    private final View root;
    private final View toolbar;
    private final RecyclerView grid;
    private final PosterAdapter adapter;
    private final ImageView logo;
    private final TextView title;
    private final TextView facts;
    private final TextView overview;
    private final TextView sortPill;
    private final TextView filterPill;
    private final TextView genrePill;
    private final TextView count;
    private final View message;
    private final View spinner;
    private final TextView messageText;
    private final TextView messageAction;

    private String libraryId;
    private List<String> genres = Collections.emptyList();
    private int total;
    private boolean loading;
    /** Bumped for every new list asked for, so pages of an older one are dropped. */
    private int generation;
    private int focus;
    private Catalog.Item previewing;
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
        grid = root.findViewById(R.id.libraryGrid);
        logo = root.findViewById(R.id.previewLogo);
        title = root.findViewById(R.id.previewTitle);
        facts = root.findViewById(R.id.previewFacts);
        overview = root.findViewById(R.id.previewOverview);
        sortPill = root.findViewById(R.id.librarySort);
        filterPill = root.findViewById(R.id.libraryFilter);
        genrePill = root.findViewById(R.id.libraryGenre);
        count = root.findViewById(R.id.libraryCount);
        message = root.findViewById(R.id.libraryMessage);
        spinner = root.findViewById(R.id.librarySpinner);
        messageText = root.findViewById(R.id.libraryMessageText);
        messageAction = root.findViewById(R.id.libraryMessageAction);

        int gutter = activity.getResources().getDimensionPixelSize(R.dimen.gutter);
        adapter = new PosterAdapter(account, COLUMNS, gutter, new PosterAdapter.Listener() {
            @Override
            public void onFocused(Catalog.Item item, Drawable art) {
                focus = Math.max(0, positionIn(grid.getFocusedChild()));
                host.onItemFocused(item, art);
                preview(item);
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
        grid.setItemAnimator(null);
        grid.setAdapter(adapter);

        readQuery();
        sortPill.setOnClickListener(this::showSort);
        filterPill.setOnClickListener(this::showFilter);
        genrePill.setOnClickListener(this::showGenres);
        for (View pill : new View[] {sortPill, filterPill, genrePill}) {
            pill.setOnFocusChangeListener((v, focused) -> {
                if (focused) host.onNothingFocused();
            });
        }
        updatePills();
        clearPreview();
    }

    View view() {
        return root;
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

    /** Signed in as someone else, or out: forget the library and its genres; it loads again when shown. */
    void reset() {
        generation++;
        libraryId = null;
        genres = Collections.emptyList();
        loadedOnce = false;
        focus = 0;
        adapter.set(Collections.emptyList());
        count.setText(null);
        clearPreview();
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
            count.setText(null);
            showMessage(activity.getString(R.string.library_signed_out),
                    activity.getString(R.string.jellyfin_connect_title), host::onSignIn);
            return;
        }
        if (adapter.getItemCount() == 0) showMessage(null, null, null);
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
                    showMessage(activity.getString(R.string.library_none, server), null, null);
                    return;
                }
                boolean hadFocus = grid.hasFocus();
                total = page.total;
                adapter.set(page.items);
                count.setText(activity.getResources().getQuantityString(
                        kind == Catalog.Kind.Movie ? R.plurals.library_movies : R.plurals.library_shows, total, total));
                if (page.items.isEmpty()) {
                    showMessage(activity.getString(R.string.library_nothing),
                            activity.getString(R.string.library_clear), LibraryPage.this::clearFilters);
                    return;
                }
                message.setVisibility(View.GONE);
                grid.setVisibility(View.VISIBLE);
                if (focus >= page.items.size()) focus = 0;
                if (hadFocus) focusPoster(focus);
            }

            @Override
            public void onError(Exception e) {
                if (mine != generation) return;
                loading = false;
                if (adapter.getItemCount() > 0) return;   // keep what is showing
                String server = account.serverName().isEmpty() ? account.server() : account.serverName();
                showMessage(activity.getString(R.string.cw_offline_title, server),
                        activity.getString(R.string.jellyfin_try_again), LibraryPage.this::reload);
            }
        });
    }

    private void loadMore() {
        if (loading || adapter.getItemCount() >= total) return;
        int mine = generation;
        int start = adapter.getItemCount();
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

    /** {@code text} with a button, or a spinner alone when {@code text} is null. */
    private void showMessage(String text, String action, Runnable onAction) {
        boolean hadFocus = root.hasFocus();
        grid.setVisibility(text == null && adapter.getItemCount() > 0 ? View.VISIBLE : View.INVISIBLE);
        message.setVisibility(View.VISIBLE);
        spinner.setVisibility(text == null ? View.VISIBLE : View.GONE);
        messageText.setVisibility(text == null ? View.GONE : View.VISIBLE);
        messageText.setText(text);
        messageAction.setVisibility(action == null ? View.GONE : View.VISIBLE);
        messageAction.setText(action);
        messageAction.setOnClickListener(onAction == null ? null : v -> onAction.run());
        if (hadFocus && !root.hasFocus()) focusIn();
        clearPreview();
    }

    // ---- the preview ---------------------------------------------------------------------------------

    private void preview(Catalog.Item item) {
        previewing = item;
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(() -> {
            if (previewing != item) return;
            String seasons = item.isSeries() && item.childCount > 0
                    ? activity.getResources().getQuantityString(R.plurals.seasons, item.childCount, item.childCount)
                    : null;
            facts.setText(Catalog.facts(item, seasons));
            overview.setText(item.overview);
            String url = Catalog.logo(account, item, LOGO_PX);
            title.setText(item.name);
            title.setVisibility(url.isEmpty() ? View.VISIBLE : View.INVISIBLE);
            Images.load(logo, url, Library.auth(account), bmp -> title.setVisibility(View.INVISIBLE));
        }, PREVIEW_SETTLE_MS);
    }

    private void clearPreview() {
        previewing = null;
        handler.removeCallbacksAndMessages(null);
        Images.load(logo, "", null);
        title.setText(activity.getString(kind == Catalog.Kind.Movie ? R.string.tab_movies : R.string.tab_shows));
        title.setVisibility(View.VISIBLE);
        facts.setText(null);
        overview.setText(null);
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
        clearPreview();
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

    // ---- focus ---------------------------------------------------------------------------------------

    /** Coming down into the page: the poster that had focus, or the pills when there are none. */
    void focusIn() {
        if (grid.getVisibility() == View.VISIBLE && adapter.getItemCount() > 0) focusPoster(focus);
        else if (messageAction.getVisibility() == View.VISIBLE && message.getVisibility() == View.VISIBLE) {
            messageAction.requestFocus();
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
        int last = adapter.getItemCount() - 1;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (inGrid) {
                    int pos = positionIn(focused);
                    if (pos < COLUMNS) sortPill.requestFocus();
                    else focusPoster(pos - COLUMNS);
                    return true;
                }
                if (inMessage) {
                    sortPill.requestFocus();
                    return true;
                }
                host.onExitUp();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (inToolbar) {
                    if (grid.getVisibility() == View.VISIBLE && last >= 0) focusPoster(focus);
                    else if (messageAction.getVisibility() == View.VISIBLE) messageAction.requestFocus();
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
        if (adapter.getItemCount() == 0) return;
        int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
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
