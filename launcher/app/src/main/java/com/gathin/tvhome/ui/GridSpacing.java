package com.gathin.tvhome.ui;

import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * The launcher's column grid. Every row and grid of cards divides the same content width (the screen between
 * the margins) into a number of columns with the same gutter between them, so a five-column row on Home and
 * the five-column Apps grid have identical tiles, and every row starts and ends on the same margin line.
 *
 * Add it as the RecyclerView's item decoration, and size items with {@link #itemWidth}.
 */
public class GridSpacing extends RecyclerView.ItemDecoration {

    private final int columns;
    private final int gutter;

    public GridSpacing(int columns, int gutter) {
        this.columns = columns;
        this.gutter = gutter;
    }

    /** Width of one column of {@code list}, inside its padding. */
    public static int itemWidth(@NonNull View list, int columns, int gutter) {
        int content = list.getMeasuredWidth() - list.getPaddingLeft() - list.getPaddingRight();
        return Math.max(0, (content - (columns - 1) * gutter) / columns);
    }

    @Override
    public void getItemOffsets(@NonNull Rect out, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos == RecyclerView.NO_POSITION) return;
        if (parent.getLayoutManager() instanceof GridLayoutManager) {
            // Each span is content / columns wide; these offsets leave every item exactly itemWidth.
            int col = pos % columns;
            out.left = col * gutter / columns;
            out.right = gutter - (col + 1) * gutter / columns;
            out.top = pos >= columns ? gutter : 0;
        } else {
            out.right = pos == state.getItemCount() - 1 ? 0 : gutter;
        }
    }
}
