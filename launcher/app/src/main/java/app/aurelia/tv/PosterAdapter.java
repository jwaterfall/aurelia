package app.aurelia.tv;

import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
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
 * A library's posters (item_poster.xml). It holds what has been fetched so far, and asks for more as focus nears
 * the end of it. Until the first page arrives it can show placeholder posters (item_poster_placeholder.xml),
 * which never take focus.
 */
final class PosterAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    interface Listener {
        /** {@code art} is the poster, or null while it is still loading. */
        void onFocused(Catalog.Item item, Drawable art);
        void onClicked(Catalog.Item item);
        /** OK held on a poster. True when it opened something, so the press does not also open the poster. */
        default boolean onLongClicked(Catalog.Item item, View poster) {
            return false;
        }
        /** Focus is within a few rows of the last poster fetched. */
        void onNearEnd();
    }

    /** How close to the end (in posters) more are fetched. */
    private static final int AHEAD = 30;
    /** Posters are fetched this tall, in pixels: a little over their size on a 1080p screen. */
    private static final int POSTER_PX = 330;
    private static final int TYPE_POSTER = 0;
    private static final int TYPE_PLACEHOLDER = 1;
    /** A rebind for new watched or favourite marks only: the artwork stays as it is. */
    private static final Object MARKS = new Object();

    private final Account account;
    private final int columns;
    private final int gutter;
    private final Listener listener;
    private final List<Catalog.Item> items = new ArrayList<>();
    private int placeholders;

    PosterAdapter(Account account, int columns, int gutter, Listener listener) {
        this.account = account;
        this.columns = columns;
        this.gutter = gutter;
        this.listener = listener;
    }

    void set(List<Catalog.Item> fresh) {
        items.clear();
        items.addAll(fresh);
        placeholders = 0;
        notifyDataSetChanged();
    }

    /** Nothing yet, and {@code rows} rows of placeholders in its place. */
    void setLoading(int rows) {
        items.clear();
        placeholders = rows * columns;
        notifyDataSetChanged();
    }

    /** How many posters there are (not counting placeholders). */
    int size() {
        return items.size();
    }

    void append(List<Catalog.Item> more) {
        int at = items.size();
        items.addAll(more);
        notifyItemRangeInserted(at, more.size());
    }

    Catalog.Item get(int position) {
        return items.get(position);
    }

    /** Puts {@code item} in place of the poster with its id, if there is one, redrawing only its marks. */
    void update(Catalog.Item item) {
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).id.equals(item.id)) continue;
            items.set(i, item);
            notifyItemChanged(i, MARKS);
            return;
        }
    }

    @Override
    public int getItemCount() {
        return items.isEmpty() ? placeholders : items.size();
    }

    @Override
    public int getItemViewType(int position) {
        return items.isEmpty() ? TYPE_PLACEHOLDER : TYPE_POSTER;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        boolean poster = viewType == TYPE_POSTER;
        View v = LayoutInflater.from(parent.getContext())
                .inflate(poster ? R.layout.item_poster : R.layout.item_poster_placeholder, parent, false);
        v.getLayoutParams().width = GridSpacing.itemWidth(parent, columns, gutter);
        return poster ? new Holder(v) : new RecyclerView.ViewHolder(v) { };
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (holder instanceof Holder && payloads.contains(MARKS)) bindMarks((Holder) holder, items.get(position));
        else super.onBindViewHolder(holder, position, payloads);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (!(holder instanceof Holder)) return;
        Holder h = (Holder) holder;
        Catalog.Item item = items.get(position);
        h.name.setText(item.name);
        h.name.setVisibility(View.INVISIBLE);
        h.loading.setVisibility(View.VISIBLE);
        h.itemView.setContentDescription(item.name);
        Images.load(h.art, Catalog.poster(account, item, POSTER_PX), Library.auth(account), new Images.Loaded() {
            @Override
            public void onLoaded(Bitmap bmp) {
                h.loading.setVisibility(View.GONE);
                // Arrived while focused: the backdrop can take its colours now.
                if (h.itemView.hasFocus()) listener.onFocused(item, h.art.getDrawable());
            }

            @Override
            public void onFailed() {
                h.loading.setVisibility(View.GONE);
                h.name.setVisibility(View.VISIBLE);
            }
        });
        bindMarks(h, item);
    }

    /** The watched mark or episodes-left count, the progress bar, and what the poster does with this item. */
    private void bindMarks(Holder h, Catalog.Item item) {
        if (item.isSeries() && !item.played && item.unplayed > 0) {
            h.badge.setText(String.valueOf(item.unplayed));
            h.badge.setVisibility(View.VISIBLE);
        } else if (item.played) {
            h.badge.setText(R.string.watched_mark);
            h.badge.setVisibility(View.VISIBLE);
        } else {
            h.badge.setVisibility(View.GONE);
        }
        h.progress.setVisibility(item.inProgress() ? View.VISIBLE : View.GONE);
        h.progress.setProgress(item.progressPercent());
        h.itemView.setOnClickListener(v -> listener.onClicked(item));
        h.itemView.setOnLongClickListener(v -> listener.onLongClicked(item, v));
        h.itemView.setOnFocusChangeListener((v, focused) -> {
            if (!focused) return;
            listener.onFocused(item, h.art.getDrawable());
            if (h.getBindingAdapterPosition() >= items.size() - AHEAD) listener.onNearEnd();
        });
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView art;
        final View loading;
        final TextView name;
        final TextView badge;
        final ProgressBar progress;

        Holder(View v) {
            super(v);
            art = v.findViewById(R.id.posterArt);
            loading = v.findViewById(R.id.posterLoading);
            name = v.findViewById(R.id.posterName);
            badge = v.findViewById(R.id.posterBadge);
            progress = v.findViewById(R.id.posterProgress);
        }
    }
}
