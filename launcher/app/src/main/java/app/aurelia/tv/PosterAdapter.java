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
import app.aurelia.tv.jellyfin.Api;
import app.aurelia.tv.jellyfin.Catalog;
import app.aurelia.tv.jellyfin.Library;
import app.aurelia.tv.ui.GridSpacing;
import app.aurelia.tv.ui.ImageDisk;
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
        /** Marked from its hold menu (watched, a favourite): {@code item} as it now is, already on its poster. */
        default void onChanged(Catalog.Item item) {
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
    /** A poster's width for posters made before the grid is measured ({@link #prewarm}); 0 until then. */
    private int itemWidth;

    PosterAdapter(Account account, int columns, int gutter, Listener listener) {
        this.account = account;
        this.columns = columns;
        this.gutter = gutter;
        this.listener = listener;
        setHasStableIds(true);
    }

    void set(List<Catalog.Item> fresh) {
        items.clear();
        items.addAll(fresh);
        placeholders = 0;
        notifyDataSetChanged();
    }

    /**
     * Each poster's own id (placeholders their place), so that asked for again (coming back to a screen) the same
     * film keeps its poster, and the poster with focus keeps it.
     */
    @Override
    public long getItemId(int position) {
        return items.isEmpty() ? -1 - position : items.get(position).id.hashCode();
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
        int width = GridSpacing.itemWidth(parent, columns, gutter);
        v.getLayoutParams().width = width > 0 ? width : itemWidth;
        return poster ? new Holder(v) : new RecyclerView.ViewHolder(v) { };
    }

    /**
     * Makes {@code count} posters {@code width} wide for {@code grid} ahead of time, while it is hidden, one per
     * message: frames come first, so making them never holds one up, and showing the grid later only fills them
     * in. Stops early once the grid shows (it makes its own then).
     */
    void prewarm(RecyclerView grid, int width, int count) {
        itemWidth = width;
        RecyclerView.RecycledViewPool pool = grid.getRecycledViewPool();
        pool.setMaxRecycledViews(TYPE_POSTER, count);
        grid.post(new Runnable() {
            @Override
            public void run() {
                if (grid.isShown() || pool.getRecycledViewCount(TYPE_POSTER) >= count) return;
                pool.putRecycledView(createViewHolder(grid, TYPE_POSTER));
                grid.post(this);
            }
        });
    }

    /** The first {@code count} posters' art onto the disk, undecoded, so they show at once when the grid does. */
    void prefetchArt(int count) {
        String auth = Library.auth(account);
        for (int i = 0; i < Math.min(count, items.size()); i++) {
            String url = Catalog.poster(account, items.get(i), POSTER_PX);
            if (url.isEmpty()) continue;
            Api.image(() -> {
                ImageDisk.prefetch(url, auth);
                return null;
            }, new Api.Callback<Void>() {
                @Override
                public void onResult(Void done) {
                }

                @Override
                public void onError(Exception e) {
                }
            });
        }
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
        // Gone, not invisible: laid out for every poster, its four lines of text cost more than the poster.
        h.name.setVisibility(View.GONE);
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
        // Every poster, wherever it is, has the same menu on a held OK.
        h.itemView.setOnLongClickListener(v -> {
            ItemOptions.show(account, item, v, changed -> {
                update(changed);
                listener.onChanged(changed);
            });
            return true;
        });
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
