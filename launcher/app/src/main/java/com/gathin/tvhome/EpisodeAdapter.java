package com.gathin.tvhome;

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

/** One season's episodes in a row (item_episode.xml), on a show's page. */
final class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.Holder> {

    interface Listener {
        void onFocused(Catalog.Item episode);
        void onClicked(Catalog.Item episode);
    }

    private static final int STILL_PX = 480;

    private final Account account;
    private final int columns;
    private final int gutter;
    private final Listener listener;
    private final List<Catalog.Item> items = new ArrayList<>();

    EpisodeAdapter(Account account, int columns, int gutter, Listener listener) {
        this.account = account;
        this.columns = columns;
        this.gutter = gutter;
        this.listener = listener;
    }

    void set(List<Catalog.Item> episodes) {
        items.clear();
        items.addAll(episodes);
        notifyDataSetChanged();
    }

    List<Catalog.Item> items() {
        return items;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_episode, parent, false);
        v.getLayoutParams().width = GridSpacing.itemWidth(parent, columns, gutter);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Catalog.Item e = items.get(position);
        h.number.setText(h.itemView.getContext().getString(R.string.details_episode, e.index));
        h.itemView.setContentDescription(e.name);
        Images.load(h.still, Catalog.still(account, e, STILL_PX), Library.auth(account));
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
        final TextView number;
        final TextView badge;
        final ProgressBar progress;

        Holder(View v) {
            super(v);
            still = v.findViewById(R.id.episodeStill);
            number = v.findViewById(R.id.episodeNumber);
            badge = v.findViewById(R.id.episodeBadge);
            progress = v.findViewById(R.id.episodeProgress);
        }
    }
}
