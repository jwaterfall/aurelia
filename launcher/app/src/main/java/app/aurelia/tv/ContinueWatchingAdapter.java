package app.aurelia.tv;

import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
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

import app.aurelia.tv.jellyfin.Api;
import app.aurelia.tv.jellyfin.Library;
import app.aurelia.tv.ui.GridSpacing;

import java.util.ArrayList;
import java.util.List;

/**
 * The Continue Watching row. It is never empty, so Home always has a first row to move to. It shows one of:
 * - placeholders: empty glass cards the size of real ones, while Jellyfin answers;
 * - items: media cards (item_media_card.xml);
 * - a message: a glass band the width of the row (item_row_message.xml), for signed out, unreachable or nothing
 *   in progress, with one button that does the obvious thing.
 */
final class ContinueWatchingAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    /** What a message says, and its button. */
    static final class Message {
        final int icon;
        final CharSequence title;
        final CharSequence body;
        final CharSequence actionLabel;
        final Runnable action;

        Message(int icon, CharSequence title, CharSequence body, CharSequence actionLabel, Runnable action) {
            this.icon = icon;
            this.title = title;
            this.body = body;
            this.actionLabel = actionLabel;
            this.action = action;
        }
    }

    interface Listener {
        /** {@code art} is the card's artwork, or null while it is still loading. */
        void onItemFocused(Library.MediaItem item, Drawable art);
        void onItemClicked(Library.MediaItem item);
        /** Focus on a placeholder or a message: nothing to show behind it. */
        void onNothingFocused();
    }

    private static final int TYPE_CARD = 0;
    private static final int TYPE_MESSAGE = 1;
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
    private final List<Library.MediaItem> items = new ArrayList<>();
    private Message message;
    private boolean loading = true;
    private String imageAuth;

    ContinueWatchingAdapter(int columns, int gutter, Listener listener) {
        this.columns = columns;
        this.gutter = gutter;
        this.listener = listener;
    }

    /** Back to placeholders, for a new sign-in. */
    void showLoading() {
        replace(() -> {
            loading = true;
            message = null;
            items.clear();
        });
    }

    /** {@code auth} is the header artwork is fetched with. */
    void showItems(List<Library.MediaItem> fresh, String auth) {
        imageAuth = auth;
        replace(() -> {
            loading = false;
            message = null;
            items.clear();
            items.addAll(fresh);
        });
    }

    void showMessage(Message m) {
        replace(() -> {
            loading = false;
            message = m;
            items.clear();
        });
    }

    boolean hasItems() {
        return !loading && message == null && !items.isEmpty();
    }

    /**
     * Swaps the contents. Positions that keep their kind of card are rebound in place rather than rebuilt, so the
     * card that has focus (a placeholder, while the first answer is on its way) keeps it.
     */
    private void replace(Runnable change) {
        int before = getItemCount();
        int typeBefore = before > 0 ? getItemViewType(0) : -1;
        change.run();
        int after = getItemCount();
        if (after == 0 || getItemViewType(0) != typeBefore) {
            notifyDataSetChanged();
            return;
        }
        int kept = Math.min(before, after);
        notifyItemRangeChanged(0, kept);
        if (after > kept) notifyItemRangeInserted(kept, after - kept);
        else if (before > kept) notifyItemRangeRemoved(kept, before - kept);
    }

    @Override
    public int getItemCount() {
        if (loading) return PLACEHOLDERS;
        return message != null ? 1 : items.size();
    }

    @Override
    public int getItemViewType(int position) {
        return message != null ? TYPE_MESSAGE : TYPE_CARD;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        int width = GridSpacing.itemWidth(parent, columns, gutter);
        if (viewType == TYPE_MESSAGE) {
            View v = inflater.inflate(R.layout.item_row_message, parent, false);
            v.getLayoutParams().width = width * columns + gutter * (columns - 1);
            v.getLayoutParams().height = Math.round(width * 9f / 16f);
            return new MessageHolder(v);
        }
        View v = inflater.inflate(R.layout.item_media_card, parent, false);
        v.getLayoutParams().width = width;
        return new CardHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof MessageHolder) {
            bindMessage((MessageHolder) holder);
        } else if (loading) {
            bindPlaceholder((CardHolder) holder);
        } else {
            bindItem((CardHolder) holder, items.get(position));
        }
    }

    private void bindMessage(MessageHolder h) {
        Message m = message;
        h.empty.show(m.icon, m.title, m.body, m.actionLabel, m.action);
        h.empty.action.setOnFocusChangeListener((v, focused) -> {
            if (focused) listener.onNothingFocused();
        });
    }

    private void bindPlaceholder(CardHolder h) {
        h.reset();
        h.loading.setVisibility(View.VISIBLE);
        h.text.setVisibility(View.INVISIBLE);
        h.scrim.setVisibility(View.INVISIBLE);
        h.itemView.setBackgroundResource(R.drawable.glass_card);
        h.itemView.setOutlineProvider(null);
        h.itemView.setOnClickListener(null);
        h.itemView.setOnFocusChangeListener((v, focused) -> {
            if (focused) listener.onNothingFocused();
        });
    }

    private void bindItem(CardHolder h, Library.MediaItem item) {
        h.reset();
        h.loading.setVisibility(View.GONE);
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

    private void loadImage(CardHolder h, Library.MediaItem item) {
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
        String auth = imageAuth;
        Api.async(() -> Api.getImage(url, auth), new Api.Callback<Bitmap>() {
            @Override
            public void onResult(Bitmap bmp) {
                if (bmp == null) return;
                cache.put(url, bmp);
                if (!url.equals(h.backdrop.getTag())) return;   // recycled for another item meanwhile
                h.backdrop.setImageBitmap(bmp);
                h.backdrop.animate().alpha(1f).setDuration(FADE_MS).start();
                // Artwork that arrives while its card has focus still reaches the ambient backdrop.
                if (h.itemView.hasFocus()) listener.onItemFocused(item, h.backdrop.getDrawable());
            }

            @Override
            public void onError(Exception e) {
            }
        });
    }

    static final class CardHolder extends RecyclerView.ViewHolder {
        final ImageView backdrop;
        final View loading;
        final View scrim;
        final View text;
        final TextView tag;
        final TextView title;
        final TextView subtitle;
        final ProgressBar progress;

        CardHolder(View v) {
            super(v);
            backdrop = v.findViewById(R.id.backdrop);
            loading = v.findViewById(R.id.loading);
            scrim = v.findViewById(R.id.scrim);
            text = v.findViewById(R.id.text);
            tag = v.findViewById(R.id.tag);
            title = v.findViewById(R.id.title);
            subtitle = v.findViewById(R.id.subtitle);
            progress = v.findViewById(R.id.progress);
        }

        void reset() {
            backdrop.animate().cancel();
            backdrop.setImageDrawable(null);
            backdrop.setTag(null);
        }
    }

    static final class MessageHolder extends RecyclerView.ViewHolder {
        final EmptyState empty;

        MessageHolder(View v) {
            super(v);
            empty = new EmptyState(v);
        }
    }
}
