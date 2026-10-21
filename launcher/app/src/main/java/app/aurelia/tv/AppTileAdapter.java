package app.aurelia.tv;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import app.aurelia.tv.ui.GridSpacing;

import java.util.ArrayList;
import java.util.List;

/** App tiles (item_app_tile.xml) for a row or a grid, one column of the launcher grid wide. */
final class AppTileAdapter extends RecyclerView.Adapter<AppTileAdapter.Holder> {

    interface Listener {
        void onAppFocused(InstalledApps.App app, View tile);
        void onAppClicked(InstalledApps.App app);

        /** OK held on a tile. True when it opened something, so the press does not also open the app. */
        default boolean onAppLongClicked(InstalledApps.App app, View tile) {
            return false;
        }
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

    /**
     * Shows {@code fresh}, as moves, insertions and removals rather than a rebuild: a tile pinned, unpinned or moved
     * from its menu keeps its view, and with it the focus.
     */
    void setApps(List<InstalledApps.App> fresh) {
        List<InstalledApps.App> old = new ArrayList<>(apps);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return old.size();
            }

            @Override
            public int getNewListSize() {
                return fresh.size();
            }

            @Override
            public boolean areItemsTheSame(int o, int n) {
                return old.get(o).component.equals(fresh.get(n).component);
            }

            @Override
            public boolean areContentsTheSame(int o, int n) {
                return old.get(o) == fresh.get(n);
            }
        });
        apps.clear();
        apps.addAll(fresh);
        diff.dispatchUpdatesTo(this);
    }

    List<InstalledApps.App> apps() {
        return apps;
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
        h.itemView.setOnLongClickListener(v -> listener.onAppLongClicked(app, v));
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
