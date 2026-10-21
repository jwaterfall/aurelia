package com.gathin.tvhome;

import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.gathin.tvhome.jellyfin.Account;
import com.gathin.tvhome.jellyfin.Catalog;
import com.gathin.tvhome.jellyfin.Library;
import com.gathin.tvhome.ui.GridSpacing;
import com.gathin.tvhome.ui.Images;

import java.util.ArrayList;
import java.util.List;

/**
 * A library's posters (item_poster.xml). It holds what has been fetched so far, and asks for more as focus nears
 * the end of it.
 */
final class PosterAdapter extends RecyclerView.Adapter<PosterAdapter.Holder> {

    interface Listener {
        /** {@code art} is the poster, or null while it is still loading. */
        void onFocused(Catalog.Item item, Drawable art);
        void onClicked(Catalog.Item item);
        /** Focus is within a few rows of the last poster fetched. */
        void onNearEnd();
    }

    /** How close to the end (in posters) more are fetched. */
    private static final int AHEAD = 30;
    /** Posters are fetched this tall, in pixels: a little over their size on a 1080p screen. */
    private static final int POSTER_PX = 330;

    private final Account account;
    private final int columns;
    private final int gutter;
    private final Listener listener;
    private final List<Catalog.Item> items = new ArrayList<>();

    PosterAdapter(Account account, int columns, int gutter, Listener listener) {
        this.account = account;
        this.columns = columns;
        this.gutter = gutter;
        this.listener = listener;
    }

    void set(List<Catalog.Item> fresh) {
        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
    }

    void append(List<Catalog.Item> more) {
        int at = items.size();
        items.addAll(more);
        notifyItemRangeInserted(at, more.size());
    }

    Catalog.Item get(int position) {
        return items.get(position);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_poster, parent, false);
        v.getLayoutParams().width = GridSpacing.itemWidth(parent, columns, gutter);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Catalog.Item item = items.get(position);
        h.name.setText(item.name);
        h.itemView.setContentDescription(item.name);
        Images.load(h.art, Catalog.poster(account, item, POSTER_PX), Library.auth(account), bmp -> {
            // Arrived while focused: the backdrop can take its colours now.
            if (h.itemView.hasFocus()) listener.onFocused(item, h.art.getDrawable());
        });
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
        h.itemView.setOnFocusChangeListener((v, focused) -> {
            if (!focused) return;
            listener.onFocused(item, h.art.getDrawable());
            if (h.getBindingAdapterPosition() >= items.size() - AHEAD) listener.onNearEnd();
        });
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView art;
        final TextView name;
        final TextView badge;
        final ProgressBar progress;

        Holder(View v) {
            super(v);
            art = v.findViewById(R.id.posterArt);
            name = v.findViewById(R.id.posterName);
            badge = v.findViewById(R.id.posterBadge);
            progress = v.findViewById(R.id.posterProgress);
        }
    }
}
