package app.aurelia.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;

import app.aurelia.tv.jellyfin.Account;
import app.aurelia.tv.jellyfin.Api;
import app.aurelia.tv.jellyfin.Catalog;
import app.aurelia.tv.player.PlayerActivity;
import app.aurelia.tv.ui.GridSpacing;
import app.aurelia.tv.ui.Placeholder;
import app.aurelia.tv.ui.SegmentedControl;
import app.aurelia.tv.ui.ShelfLayoutManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A show's page, everything on one screen: what it is, with its main button (resume the episode in progress, or
 * play the next one); its seasons as tabs; and the season's episodes in a row, each with its name and a couple
 * of lines about it. Moving across the tabs changes the row. Choosing an episode plays it. Placeholders stand in
 * while the show, or a season, loads. Down from the episodes scrolls to the rows under the page (DetailsMore):
 * the cast and crew, and more like it.
 *
 * Focus moves down from the buttons to the tabs to the episodes and back up the same way; entering the row lands
 * on the episode to watch next, or the one last focused.
 */
public final class ShowActivity extends Activity {

    private static final String EXTRA_ITEM = "item";
    private static final int COLUMNS = 4;
    /** A run across the tabs loads the season it stops on. */
    private static final long SEASON_SETTLE_MS = 180;
    /** A season that takes longer than this to arrive shows placeholders meanwhile. */
    private static final long SEASON_PLACEHOLDER_MS = 150;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Account account;
    private String seriesId;
    private DetailsHeader header;
    private ImageView backdrop;
    private View headerView;
    private View placeholder;
    private View seasonScroll;
    private View buttons;
    private SegmentedControl seasonTabs;
    private RecyclerView row;
    private EpisodeAdapter episodes;
    private DetailsMore more;

    private List<Catalog.Item> seasons = Collections.emptyList();
    private Catalog.Item next;
    private int season = -1;
    private int episodeFocus;
    private int generation;
    private boolean focused;

    public static void start(Context context, String seriesId) {
        context.startActivity(new Intent(context, ShowActivity.class).putExtra(EXTRA_ITEM, seriesId));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_show);
        account = Account.get(this);
        seriesId = getIntent().getStringExtra(EXTRA_ITEM);
        backdrop = findViewById(R.id.detailsBackdrop);
        placeholder = findViewById(R.id.detailsPlaceholder);
        seasonScroll = findViewById(R.id.seasonScroll);
        headerView = findViewById(R.id.detailsHeader);
        // Hidden until it is filled (android:alpha on an <include> is not applied), the placeholder in its place.
        headerView.setAlpha(0f);
        header = new DetailsHeader(this, headerView);
        // The seasons and episodes have to fit under it; its placeholder takes the same shape.
        int logoHeight = getResources().getDimensionPixelSize(R.dimen.show_logo_height);
        header.setCompact(logoHeight);
        placeholder.findViewById(R.id.placeholderMark).getLayoutParams().height = logoHeight;
        placeholder.findViewById(R.id.placeholderLastLine).setVisibility(View.GONE);
        header.setOnWatchedChanged(this::load);
        buttons = headerView.findViewById(R.id.detailsButtons);
        header.play.setOnClickListener(v -> {
            if (next != null) PlayerActivity.start(this, next.id);
        });

        seasonTabs = findViewById(R.id.seasons);
        seasonTabs.setOnTabSelectedListener(index -> {
            handler.removeCallbacksAndMessages(null);
            handler.postDelayed(() -> showSeason(index), SEASON_SETTLE_MS);
        });
        row = findViewById(R.id.episodes);
        int gutter = getResources().getDimensionPixelSize(R.dimen.gutter);
        episodes = new EpisodeAdapter(account, COLUMNS, gutter, new EpisodeAdapter.Listener() {
            @Override
            public void onFocused(Catalog.Item episode) {
                episodeFocus = Math.max(0, episodes.items().indexOf(episode));
            }

            @Override
            public void onClicked(Catalog.Item episode) {
                PlayerActivity.start(ShowActivity.this, episode.id);
            }
        });
        row.setLayoutManager(new ShelfLayoutManager(this));
        row.addItemDecoration(new GridSpacing(1, gutter));
        row.setItemAnimator(null);
        row.setAdapter(episodes);
        episodes.setLoading();

        more = new DetailsMore(this, findViewById(R.id.detailsColumn));
        DetailsMore.fitHero(findViewById(R.id.detailsScroll), findViewById(R.id.showContent),
                findViewById(R.id.detailsDim));
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    // ---- loading -------------------------------------------------------------------------------------

    /** The show, its seasons and what to watch next; then the season that is in. */
    private void load() {
        int mine = ++generation;
        Api.async(() -> {
            Catalog.Item show = Catalog.item(account, seriesId);
            List<Catalog.Item> s = Catalog.seasons(account, seriesId);
            Catalog.Item up = Catalog.nextUp(account, seriesId);
            if (up == null && !s.isEmpty()) {
                // Nothing in progress or next: the first episode of the first real season.
                Catalog.Item first = firstSeason(s);
                List<Catalog.Item> eps = Catalog.episodes(account, seriesId, first.id);
                up = eps.isEmpty() ? null : eps.get(0);
            }
            return new Object[] {show, s, up};
        }, new Api.Callback<Object[]>() {
            @Override
            @SuppressWarnings("unchecked")
            public void onResult(Object[] r) {
                if (mine != generation || isFinishing()) return;
                Catalog.Item show = (Catalog.Item) r[0];
                List<Catalog.Item> s = (List<Catalog.Item>) r[1];
                next = (Catalog.Item) r[2];
                int realSeasons = 0;
                for (Catalog.Item i : s) if (i.index > 0) realSeasons++;
                header.bind(show, realSeasons > 0
                        ? getResources().getQuantityString(R.plurals.seasons, realSeasons, realSeasons) : null, backdrop);
                more.bind(show);
                updatePlay();
                boolean firstTime = seasons.isEmpty();
                seasons = s;
                if (firstTime) {
                    List<String> labels = new ArrayList<>();
                    for (Catalog.Item i : s) {
                        labels.add(i.index == 0 ? getString(R.string.details_specials)
                                : getString(R.string.details_season, i.index));
                    }
                    int start = seasonOf(next);
                    seasonTabs.setTabs(labels, start);
                    showSeason(start);
                } else {
                    showSeason(Math.max(0, season));
                }
                Placeholder.reveal(placeholder, headerView, seasonScroll);
                if (!focused) {
                    focused = true;
                    header.play.requestFocus();
                }
            }

            @Override
            public void onError(Exception e) {
                if (isFinishing() || headerView.getAlpha() > 0f) return;
                String server = account.serverName().isEmpty() ? account.server() : account.serverName();
                Toast.makeText(ShowActivity.this, getString(R.string.details_failed, server), Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }

    private void updatePlay() {
        if (next == null) {
            header.play.setVisibility(View.GONE);
            return;
        }
        header.play.setVisibility(View.VISIBLE);
        String which = getString(R.string.episode_code, next.parentIndex, next.index);
        header.play.setText(getString(next.inProgress() ? R.string.details_resume_episode : R.string.details_play_episode,
                which));
    }

    private static Catalog.Item firstSeason(List<Catalog.Item> s) {
        for (Catalog.Item i : s) if (i.index > 0) return i;
        return s.get(0);
    }

    /** The tab of the season {@code episode} is in; else the first real season's. */
    private int seasonOf(Catalog.Item episode) {
        for (int i = 0; episode != null && i < seasons.size(); i++) {
            if (seasons.get(i).id.equals(episode.seasonId)) return i;
        }
        return Math.max(0, seasons.indexOf(firstSeason(seasons)));
    }

    /**
     * Shows season {@code index}'s episodes. Changing season, the row starts at the next episode when it is in
     * this season, else at the first; the same season again (back from the player) keeps its place.
     */
    private void showSeason(int index) {
        if (index < 0 || index >= seasons.size()) return;
        boolean changed = index != season;
        season = index;
        String seasonId = seasons.get(index).id;
        int mine = generation;
        Runnable showLoading = () -> {
            if (!row.hasFocus()) episodes.setLoading();
        };
        if (changed) handler.postDelayed(showLoading, SEASON_PLACEHOLDER_MS);
        Api.async(() -> Catalog.episodes(account, seriesId, seasonId), new Api.Callback<List<Catalog.Item>>() {
            @Override
            public void onResult(List<Catalog.Item> list) {
                handler.removeCallbacks(showLoading);
                if (mine != generation || season != index || isFinishing()) return;
                boolean rowHadFocus = row.hasFocus();
                episodes.set(list);
                if (changed) {
                    episodeFocus = 0;
                    for (int i = 0; next != null && i < list.size(); i++) {
                        if (list.get(i).id.equals(next.id)) episodeFocus = i;
                    }
                    row.scrollToPosition(episodeFocus);
                }
                if (rowHadFocus) focusEpisode(episodeFocus);
            }

            @Override
            public void onError(Exception e) {
            }
        });
    }

    // ---- focus ---------------------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && route(event.getKeyCode())) return true;
        return super.dispatchKeyEvent(event);
    }

    /** Buttons, tabs and the row are three bands; Up and Down move between them, the row a card at a time. */
    private boolean route(int key) {
        View focus = getCurrentFocus();
        if (focus == null) return false;
        if (more.contains(focus)) return more.route(key, focus, () -> focusEpisode(episodeFocus));
        boolean inButtons = isIn(focus, buttons);
        boolean inTabs = isIn(focus, seasonTabs);
        boolean inRow = isIn(focus, row);
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (inButtons) return seasonTabs.getChildCount() > 0 && seasonTabs.requestFocus();
                if (inTabs) return focusEpisode(episodeFocus);
                if (inRow) more.focusFirst();
                return inRow;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (inRow) return seasonTabs.requestFocus();
                if (inTabs) return header.play.getVisibility() == View.VISIBLE ? header.play.requestFocus()
                        : buttons.requestFocus();
                return inButtons;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!inRow) return false;
                View item = row.findContainingItemView(focus);
                int pos = item == null ? -1 : row.getChildAdapterPosition(item);
                int to = pos + (key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                if (to >= 0 && to < episodes.getItemCount()) focusEpisode(to);
                return true;
            default:
                return false;
        }
    }

    private boolean focusEpisode(int position) {
        if (episodes.getItemCount() == 0) return true;
        int pos = Math.max(0, Math.min(position, episodes.getItemCount() - 1));
        RecyclerView.ViewHolder h = row.findViewHolderForAdapterPosition(pos);
        if (h != null) {
            h.itemView.requestFocus();
            return true;
        }
        row.scrollToPosition(pos);
        row.post(() -> {
            RecyclerView.ViewHolder later = row.findViewHolderForAdapterPosition(pos);
            if (later != null) later.itemView.requestFocus();
        });
        return true;
    }

    private static boolean isIn(View view, View ancestor) {
        for (View v = view; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v == ancestor) return true;
        }
        return false;
    }
}
