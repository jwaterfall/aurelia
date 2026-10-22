package app.aurelia.tv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;


import app.aurelia.tv.jellyfin.Account;
import app.aurelia.tv.jellyfin.Api;
import app.aurelia.tv.jellyfin.Catalog;
import app.aurelia.tv.player.PlayerActivity;
import app.aurelia.tv.ui.ScrollEdge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Search across the library and the apps: a field at the top that opens with the keyboard up, and as you type,
 * what matches in sections (films, shows, episodes, apps), each a row of the cards those have elsewhere. Before
 * anything is typed, the last few searches. Choosing a film or show opens its page, an episode plays, an app
 * opens.
 *
 * Focus: Down from the field (or the recent searches) goes into the first section; Up and Down move between
 * sections, landing on the card last focused in each, and a section's top comes up to the top as it does.
 */
public final class SearchActivity extends Activity {

    private static final String PREFS = "search";
    private static final String KEY_RECENT = "recent";
    private static final int RECENT = 6;
    /** How long "Clear all" waits for its second press. */
    private static final long CLEAR_ARMED_MS = 4000;
    /** Typing pauses this long before a search goes out. */
    private static final long TYPING_MS = 300;
    private static final int LIMIT = 24;
    /** Episodes sit four to a row, as on a show's page; apps five, as on Home. */
    private static final int EPISODE_COLUMNS = 4;
    private static final int APP_COLUMNS = 5;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Account account;
    private SharedPreferences prefs;
    private EditText field;
    private View recent;
    private LinearLayout recentRow;
    private TextView message;
    private List<InstalledApps.App> apps = Collections.emptyList();
    private ShelfStack stack;
    private ShelfStack.Section movies, shows, episodes, appsSection;
    private final List<ShelfStack.Section> sections = new ArrayList<>();
    /** Bumped for every search, so the answers to older ones are dropped. */
    private int generation;

    public static void start(Context context) {
        context.startActivity(new Intent(context, SearchActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        account = Account.get(this);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        field = findViewById(R.id.searchField);
        recent = findViewById(R.id.searchRecent);
        recentRow = findViewById(R.id.searchRecentRow);
        message = findViewById(R.id.searchMessage);

        stack = new ShelfStack(this, findViewById(R.id.searchSections));
        int gutter = stack.gutter();
        movies = stack.add(R.string.tab_movies,
                new PosterAdapter(account, LibraryPage.COLUMNS, gutter, posterListener())).counted();
        shows = stack.add(R.string.tab_shows,
                new PosterAdapter(account, LibraryPage.COLUMNS, gutter, posterListener())).counted();
        EpisodeAdapter episodeAdapter = new EpisodeAdapter(account, EPISODE_COLUMNS, gutter,
                new EpisodeAdapter.Listener() {
                    @Override
                    public void onFocused(Catalog.Item episode) {
                    }

                    @Override
                    public void onClicked(Catalog.Item episode) {
                        remember();
                        PlayerActivity.start(SearchActivity.this, episode.id);
                    }
                });
        episodeAdapter.setWithShow(true);
        episodes = stack.add(R.string.search_episodes, episodeAdapter).counted();
        appsSection = stack.add(R.string.tab_apps, new AppTileAdapter(APP_COLUMNS, gutter,
                new AppTileAdapter.Listener() {
                    @Override
                    public void onAppFocused(InstalledApps.App app, View tile) {
                    }

                    @Override
                    public void onAppClicked(InstalledApps.App app) {
                        remember();
                        try {
                            startActivity(app.launchIntent());
                        } catch (ActivityNotFoundException | SecurityException e) {
                            Toast.makeText(SearchActivity.this, R.string.not_available, Toast.LENGTH_SHORT).show();
                        }
                    }
                })).counted();
        sections.addAll(Arrays.asList(movies, shows, episodes, appsSection));

        // The results scroll up under the field as Home's rows do under its tabs.
        View results = findViewById(R.id.searchResults);
        View root = findViewById(android.R.id.content);
        ScrollEdge edge = new ScrollEdge(findViewById(R.id.edgeBand), findViewById(R.id.edgeShadow));
        results.setOnScrollChangeListener((v, x, y, ox, oy) -> edge.setRaised(y > 0, true));
        results.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> edge.setEdge(results, root, true));

        apps = InstalledApps.load(this, Config.load(this).hiddenApps);
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                handler.removeCallbacksAndMessages(null);
                handler.postDelayed(SearchActivity.this::search, TYPING_MS);
            }
        });
        field.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            handler.removeCallbacksAndMessages(null);
            search();
            hideKeyboard();
            if (!stack.focusFirst()) field.requestFocus();
            return true;
        });
        showRecent();
        field.requestFocus();
    }

    private PosterAdapter.Listener posterListener() {
        return new PosterAdapter.Listener() {
            @Override
            public void onFocused(Catalog.Item item, Drawable art) {
            }

            @Override
            public void onClicked(Catalog.Item item) {
                remember();
                if (item.isSeries()) ShowActivity.start(SearchActivity.this, item.id);
                else MovieActivity.start(SearchActivity.this, item.id);
            }

            @Override
            public void onNearEnd() {
            }
        };
    }

    // ---- searching -----------------------------------------------------------------------------------

    private String term() {
        return field.getText().toString().trim();
    }

    private void search() {
        String term = term();
        int mine = ++generation;
        if (term.isEmpty()) {
            for (ShelfStack.Section s : sections) s.view.setVisibility(View.GONE);
            message.setVisibility(View.GONE);
            showRecent();
            return;
        }
        recent.setVisibility(View.GONE);
        List<InstalledApps.App> matchingApps = matchApps(term);
        if (!account.isSignedIn()) {
            show(mine, term, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), matchingApps);
            return;
        }
        // Placeholders where nothing is showing yet, so the first answer does not jump in from nowhere.
        if (!movies.shown() && !shows.shown()) {
            ((PosterAdapter) movies.row.getAdapter()).setLoading(1);
            movies.show(0);
            movies.view.setVisibility(View.VISIBLE);
            message.setVisibility(View.GONE);
        }
        Api.async(() -> Arrays.asList(
                Catalog.search(account, term, "Movie", LIMIT),
                Catalog.search(account, term, "Series", LIMIT),
                Catalog.search(account, term, "Episode", LIMIT)), new Api.Callback<List<List<Catalog.Item>>>() {
            @Override
            public void onResult(List<List<Catalog.Item>> r) {
                show(mine, term, r.get(0), r.get(1), r.get(2), matchingApps);
            }

            @Override
            public void onError(Exception e) {
                // The server out of reach: the apps still match.
                show(mine, term, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                        matchingApps);
            }
        });
    }

    private List<InstalledApps.App> matchApps(String term) {
        String t = term.toLowerCase(Locale.ROOT);
        List<InstalledApps.App> out = new ArrayList<>();
        for (InstalledApps.App a : apps) if (a.label.toLowerCase(Locale.ROOT).contains(t)) out.add(a);
        return out;
    }

    private void show(int mine, String term, List<Catalog.Item> films, List<Catalog.Item> series,
                      List<Catalog.Item> eps, List<InstalledApps.App> matchingApps) {
        if (mine != generation || isFinishing()) return;
        boolean hadFocus = findViewById(R.id.searchResults).hasFocus();
        ((PosterAdapter) movies.row.getAdapter()).set(films);
        ((PosterAdapter) shows.row.getAdapter()).set(series);
        ((EpisodeAdapter) episodes.row.getAdapter()).set(eps);
        ((AppTileAdapter) appsSection.row.getAdapter()).setApps(matchingApps);
        movies.show(films.size());
        shows.show(series.size());
        episodes.show(eps.size());
        appsSection.show(matchingApps.size());
        boolean none = films.isEmpty() && series.isEmpty() && eps.isEmpty() && matchingApps.isEmpty();
        message.setText(getString(R.string.search_nothing, term));
        message.setVisibility(none ? View.VISIBLE : View.GONE);
        findViewById(R.id.searchResults).scrollTo(0, 0);
        if (hadFocus && !stack.focusFirst()) field.requestFocus();
    }

    // ---- recent searches -----------------------------------------------------------------------------

    private List<String> recentSearches() {
        String saved = prefs.getString(KEY_RECENT, "");
        List<String> out = new ArrayList<>();
        for (String s : saved.split("\n")) if (!s.trim().isEmpty()) out.add(s);
        return out;
    }

    /** The search that led somewhere is kept, first, for next time. */
    private void remember() {
        String term = term();
        if (term.isEmpty()) return;
        List<String> list = recentSearches();
        list.removeIf(s -> s.equalsIgnoreCase(term));
        list.add(0, term);
        while (list.size() > RECENT) list.remove(list.size() - 1);
        prefs.edit().putString(KEY_RECENT, String.join("\n", list)).apply();
    }

    private void showRecent() {
        List<String> list = recentSearches();
        recentRow.removeAllViews();
        recent.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (String term : list) {
            TextView chip = (TextView) inflater.inflate(R.layout.item_search_recent, recentRow, false);
            chip.setText(term);
            chip.setOnClickListener(v -> {
                field.setText(term);
                field.setSelection(term.length());
                handler.removeCallbacksAndMessages(null);
                search();
                stack.focusFirst();
            });
            recentRow.addView(chip);
        }
        // Last in the row: forget them all, on a second press (the first asks for it, and stops asking after a
        // moment, or as focus leaves). Focus goes back to the field, as the row goes with them.
        if (list.isEmpty()) return;
        TextView clear = (TextView) inflater.inflate(R.layout.item_search_clear, recentRow, false);
        Runnable disarm = () -> clear.setText(R.string.search_clear_all);
        clear.setOnClickListener(v -> {
            if (!getString(R.string.search_clear_confirm).contentEquals(clear.getText())) {
                clear.setText(R.string.search_clear_confirm);
                clear.removeCallbacks(disarm);
                clear.postDelayed(disarm, CLEAR_ARMED_MS);
                return;
            }
            clear.removeCallbacks(disarm);
            prefs.edit().remove(KEY_RECENT).apply();
            field.requestFocus();
            showRecent();
        });
        clear.setOnFocusChangeListener((v, has) -> {
            if (!has) disarm.run();
        });
        recentRow.addView(clear);
    }

    // ---- focus ---------------------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && route(event.getKeyCode())) return true;
        return super.dispatchKeyEvent(event);
    }

    private boolean route(int key) {
        View focus = getCurrentFocus();
        if (focus == null) return false;
        if (stack.contains(focus)) return stack.route(key, focus, field::requestFocus);
        boolean inField = focus == field;
        boolean inRecent = ShelfStack.isIn(focus, recentRow);
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!inField && !inRecent) return false;
                if (inField && recent.getVisibility() == View.VISIBLE && recentRow.getChildCount() > 0) {
                    hideKeyboard();
                    return recentRow.getChildAt(0).requestFocus();
                }
                if (stack.first() == null) return inRecent;
                hideKeyboard();
                return stack.focusFirst();
            case KeyEvent.KEYCODE_DPAD_UP:
                if (inRecent) return field.requestFocus();
                return inField;
            default:
                return false;
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
