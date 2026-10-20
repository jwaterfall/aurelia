package com.gathin.tvhome.ui;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * A grid that scrolls by whole rows, the vertical counterpart of {@link ShelfLayoutManager}: a row focused off
 * the top or bottom scrolls in until it sits on the grid's padding line, not a gutter short of it.
 */
public class GridShelfLayoutManager extends GridLayoutManager {

    private final PathInterpolator ease = new PathInterpolator(0.25f, 0f, 0f, 1f);
    private boolean animate = true;

    public GridShelfLayoutManager(Context context, int columns) {
        super(context, columns);
    }

    public void setAnimationsEnabled(boolean enabled) {
        animate = enabled;
    }

    @Override
    public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                 @NonNull Rect rect, boolean immediate, boolean focusedChildVisible) {
        int top = getPaddingTop();
        int bottom = getHeight() - getPaddingBottom();
        int dy = 0;
        if (child.getTop() < top) dy = child.getTop() - top;
        else if (child.getBottom() > bottom) dy = child.getBottom() - bottom;
        if (dy == 0) return false;
        if (immediate || !animate) parent.scrollBy(0, dy);
        else parent.smoothScrollBy(0, dy, ease);
        return true;
    }
}
