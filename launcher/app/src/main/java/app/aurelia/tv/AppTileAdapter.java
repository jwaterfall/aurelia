package app.aurelia.tv;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import app.aurelia.tv.ui.GridSpacing;

import java.util.ArrayList;
import java.util.List;

/** App tiles (item_app_tile.xml) for a row or a grid, one column of the launcher grid wide. */
final class AppTileAdapter extends RecyclerView.Adapter<AppTileAdapter.Holder> {

    interface Listener {
        void onAppFocused(InstalledApps.App app, View tile);
        void onAppClicked(InstalledApps.App app);
    }

    private final int columns;
    private final int gutter;
    private final Listener listener;
    private final List<InstalledApps.App> apps = new ArrayList<>();

    AppTileAdapter(int columns, int gutter, Listener listener) {
        this.columns = columns;
        this.gutter = gutter;
        this.listener = listener;
        setHasStableIds(true);
    }

    void setApps(List<InstalledApps.App> fresh) {
        apps.clear();
        apps.addAll(fresh);
        notifyDataSetChanged();
    }

    @Override
    public long getItemId(int position) {
        return apps.get(position).component.hashCode();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app_tile, parent, false);
        v.getLayoutParams().width = GridSpacing.itemWidth(parent, columns, gutter);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        InstalledApps.App app = apps.get(position);
        boolean hasBanner = app.banner != null;
        h.banner.setVisibility(hasBanner ? View.VISIBLE : View.GONE);
        h.fallback.setVisibility(hasBanner ? View.GONE : View.VISIBLE);
        h.banner.setImageDrawable(app.banner);
        h.icon.setImageDrawable(hasBanner ? null : app.icon);
        h.label.setText(app.label);
        h.itemView.setContentDescription(app.label);
        h.itemView.setOnFocusChangeListener((v, focused) -> {
            if (focused) listener.onAppFocused(app, v);
        });
        h.itemView.setOnClickListener(v -> listener.onAppClicked(app));
    }

    @Override
    public int getItemCount() {
        return apps.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView banner;
        final View fallback;
        final ImageView icon;
        final TextView label;

        Holder(View v) {
            super(v);
            banner = v.findViewById(R.id.banner);
            fallback = v.findViewById(R.id.fallback);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
        }
    }
}
