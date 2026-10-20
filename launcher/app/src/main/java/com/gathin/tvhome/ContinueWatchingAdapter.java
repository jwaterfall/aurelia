package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.gathin.tvhome.ui.GridSpacing;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Media cards (item_media_card.xml) for the Continue Watching row. Until Jellyfin answers, the row shows empty
 * glass cards of the same size, so nothing jumps when the real ones arrive.
 */
final class ContinueWatchingAdapter extends RecyclerView.Adapter<ContinueWatchingAdapter.Holder> {

    static final class Item {
        final String id;
        final String title;
        final String subtitle;
        final String tag;
        final String imageUrl;
        /** Percent watched; 0 hides the bar. */
        final int progress;

        Item(String id, String title, String subtitle, String tag, String imageUrl, int progress) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.tag = tag;
            this.imageUrl = imageUrl;
            this.progress = progress;
        }
    }

    interface Listener {
        /** {@code art} is the card's artwork, or null while it is still loading. */
        void onItemFocused(Item item, Drawable art);
        void onItemClicked(Item item);
        void onPlaceholderFocused();
    }

    private static final int PLACEHOLDERS = 3;
    private static final long FADE_MS = 280;
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(
            (int) (Runtime.getRuntime().maxMemory() / 1024 / 8)) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount() / 1024;
        }
    };

    private final int columns;
    private final int gutter;
    private final Listener listener;
    private final List<Item> items = new ArrayList<>();
    private final ExecutorService loader = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean loaded;

    ContinueWatchingAdapter(int columns, int gutter, Listener listener) {
        this.columns = columns;
        this.gutter = gutter;
        this.listener = listener;
    }

    /**
     * Replaces the cards. Positions that exist before and after are rebound in place rather than rebuilt, so the
     * card that has focus (a placeholder, while the first answer is on its way) keeps it.
     */
    void setItems(List<Item> fresh) {
        int before = getItemCount();
        items.clear();
        items.addAll(fresh);
        loaded = true;
        int after = getItemCount();
        int kept = Math.min(before, after);
        notifyItemRangeChanged(0, kept);
        if (after > kept) notifyItemRangeInserted(kept, after - kept);
        else if (before > kept) notifyItemRangeRemoved(kept, before - kept);
    }

    /** Real cards are showing, not placeholders. */
    boolean hasItems() {
        return loaded && !items.isEmpty();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_media_card, parent, false);
        v.getLayoutParams().width = GridSpacing.itemWidth(parent, columns, gutter);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        h.backdrop.animate().cancel();
        h.backdrop.setImageDrawable(null);
        h.backdrop.setTag(null);
        if (!loaded) {
            h.text.setVisibility(View.INVISIBLE);
            h.scrim.setVisibility(View.INVISIBLE);
            h.itemView.setBackgroundResource(R.drawable.glass_card);
            // No shadow under see-through glass: it would show through as a smudge when the card lifts.
            h.itemView.setOutlineProvider(null);
            h.itemView.setOnFocusChangeListener((v, focused) -> {
                if (focused) listener.onPlaceholderFocused();
            });
            h.itemView.setOnClickListener(null);
            return;
        }
        Item item = items.get(position);
        h.itemView.setBackgroundResource(R.drawable.card_surface);
        h.itemView.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        h.text.setVisibility(View.VISIBLE);
        h.scrim.setVisibility(View.VISIBLE);
        h.tag.setText(item.tag);
        h.title.setText(item.title);
        h.subtitle.setText(item.subtitle);
        h.progress.setVisibility(item.progress > 0 ? View.VISIBLE : View.GONE);
        h.progress.setProgress(item.progress);
        h.itemView.setContentDescription(item.title);
        h.itemView.setOnFocusChangeListener((v, focused) -> {
            if (focused) listener.onItemFocused(item, h.backdrop.getDrawable());
        });
        h.itemView.setOnClickListener(v -> listener.onItemClicked(item));
        loadImage(h, item);
        // Rebound under the focus (a placeholder turning into this card): no focus change will report it.
        if (h.itemView.isFocused()) listener.onItemFocused(item, h.backdrop.getDrawable());
    }

    private void loadImage(Holder h, Item item) {
        String url = item.imageUrl;
        if (url == null || url.isEmpty()) return;
        Bitmap cached = cache.get(url);
        if (cached != null) {
            h.backdrop.setImageBitmap(cached);
            h.backdrop.setAlpha(1f);
            return;
        }
        h.backdrop.setTag(url);
        h.backdrop.setAlpha(0f);
        loader.execute(() -> {
            Bitmap bmp = download(url);
            if (bmp == null) return;
            cache.put(url, bmp);
            main.post(() -> {
                if (!url.equals(h.backdrop.getTag())) return;   // recycled for another item meanwhile
                h.backdrop.setImageBitmap(bmp);
                h.backdrop.animate().alpha(1f).setDuration(FADE_MS).start();
                // Artwork that arrives while its card has focus still reaches the ambient backdrop.
                if (h.itemView.hasFocus()) listener.onItemFocused(item, h.backdrop.getDrawable());
            });
        });
    }

    private static Bitmap download(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestProperty("Authorization", "MediaBrowser Token=\"" + JellyfinManager.API_KEY + "\"");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            if (conn.getResponseCode() != 200) return null;
            try (InputStream in = conn.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @Override
    public int getItemCount() {
        return loaded ? items.size() : PLACEHOLDERS;
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView backdrop;
        final View scrim;
        final View text;
        final TextView tag;
        final TextView title;
        final TextView subtitle;
        final ProgressBar progress;

        Holder(View v) {
            super(v);
            backdrop = v.findViewById(R.id.backdrop);
            scrim = v.findViewById(R.id.scrim);
            text = v.findViewById(R.id.text);
            tag = v.findViewById(R.id.tag);
            title = v.findViewById(R.id.title);
            subtitle = v.findViewById(R.id.subtitle);
            progress = v.findViewById(R.id.progress);
        }
    }
}
