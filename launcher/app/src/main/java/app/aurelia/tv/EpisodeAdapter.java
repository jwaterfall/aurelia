package app.aurelia.tv;

import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import app.aurelia.tv.jellyfin.Account;
import app.aurelia.tv.jellyfin.Catalog;
import app.aurelia.tv.jellyfin.Library;
import app.aurelia.tv.ui.GridSpacing;
import app.aurelia.tv.ui.Images;

import java.util.ArrayList;
import java.util.List;

/**
 * One season's episodes in a row (item_episode.xml), on a show's page; while a season loads, placeholders in
 * their places (item_episode_placeholder.xml), which never take focus. Episodes from anywhere (search) name
 * their show instead, with which episode it is and its own name under it.
 */
final class EpisodeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    interface Listener {
        void onFocused(Catalog.Item episode);
        void onClicked(Catalog.Item episode);
    }

    private static final int STILL_PX = 480;
    private static final int TYPE_EPISODE = 0;
    private static final int TYPE_PLACEHOLDER = 1;

    private final Account account;
    private final int columns;
    private final int gutter;
    private final Listener listener;
    private final List<Catalog.Item> items = new ArrayList<>();
    private boolean loading;
    private boolean withShow;

    EpisodeAdapter(Account account, int columns, int gutter, Listener listener) {
        this.account = account;
        this.columns = columns;
        this.gutter = gutter;
        this.listener = listener;
    }

    void set(List<Catalog.Item> episodes) {
        items.clear();
        items.addAll(episodes);
        loading = false;
        notifyDataSetChanged();
    }

    /** Episodes of different shows: each names its show. */
    void setWithShow(boolean on) {
        withShow = on;
    }

    /** A row of placeholders until {@link #set}. */
    void setLoading() {
        items.clear();
        loading = true;
        notifyDataSetChanged();
    }

    List<Catalog.Item> items() {
        return items;
    }

    @Override
    public int getItemCount() {
        return loading ? columns : items.size();
    }

    @Override
    public int getItemViewType(int position) {
        return loading ? TYPE_PLACEHOLDER : TYPE_EPISODE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        boolean episode = viewType == TYPE_EPISODE;
        View v = LayoutInflater.from(parent.getContext())
                .inflate(episode ? R.layout.item_episode : R.layout.item_episode_placeholder, parent, false);
        v.getLayoutParams().width = GridSpacing.itemWidth(parent, columns, gutter);
        return episode ? new Holder(v) : new RecyclerView.ViewHolder(v) { };
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (!(holder instanceof Holder)) return;
        Holder h = (Holder) holder;
        Catalog.Item e = items.get(position);
        if (withShow) {
            h.name.setText(e.seriesName);
            h.length.setText(h.itemView.getContext().getString(R.string.episode_code, e.parentIndex, e.index));
            h.overview.setText(e.name);
        } else {
            h.name.setText(h.itemView.getContext().getString(R.string.details_episode, e.index, e.name));
            h.length.setText(e.runtimeMs >= 60_000 ? Library.length(e.runtimeMs * 10_000L) : null);
            h.overview.setText(e.overview);
        }
        h.itemView.setContentDescription(e.name);
        h.loading.setVisibility(View.VISIBLE);
        Images.load(h.still, Catalog.still(account, e, STILL_PX), Library.auth(account), new Images.Loaded() {
            @Override
            public void onLoaded(Bitmap bitmap) {
                h.loading.setVisibility(View.GONE);
            }

            @Override
            public void onFailed() {
                h.loading.setVisibility(View.GONE);
            }
        });
        h.badge.setVisibility(e.played ? View.VISIBLE : View.GONE);
        h.progress.setVisibility(e.inProgress() ? View.VISIBLE : View.GONE);
        h.progress.setProgress(e.progressPercent());
        h.itemView.setOnClickListener(v -> listener.onClicked(e));
        h.itemView.setOnFocusChangeListener((v, focused) -> {
            if (focused) listener.onFocused(e);
        });
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView still;
        final View loading;
        final TextView name;
        final TextView length;
        final TextView overview;
        final TextView badge;
        final ProgressBar progress;

        Holder(View v) {
            super(v);
            still = v.findViewById(R.id.episodeStill);
            loading = v.findViewById(R.id.episodeLoading);
            name = v.findViewById(R.id.episodeName);
            length = v.findViewById(R.id.episodeLength);
            overview = v.findViewById(R.id.episodeOverview);
            badge = v.findViewById(R.id.episodeBadge);
            progress = v.findViewById(R.id.episodeProgress);
        }
    }
}
